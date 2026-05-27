package com.accucodeai.kash.fs

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.nio.file.Paths as NioPaths

/**
 * [FileSystem] backed by the real host filesystem via `java.nio.file`,
 * scoped to a [rootDir]. Paths the caller passes are treated as absolute
 * inside this FS — `/foo` maps to `<rootDir>/foo` on the host. Attempts to
 * escape the root via `..` are rejected with [SecurityException].
 *
 * Used for two mount-table roles today:
 * - `ENGINE_CACHE` mounts that need real-disk persistence across JVM
 *   restarts (e.g. GraalPy's extracted stdlib cache).
 * - Future `HOST_BORROW` mounts that opt-in expose part of the host tree
 *   to scripts.
 *
 * Read-only semantics are NOT a property of this class — they live on the
 * [Mount] that wraps it, so the same `HostFs` instance can be mounted
 * writable at one path and read-only at another (matches Docker's `:ro`
 * bind flag).
 */
public class HostFs(
    public val rootDir: String,
) : FileSystem {
    private val root: Path =
        run {
            val initial = NioPaths.get(rootDir).toAbsolutePath().normalize()
            if (!Files.exists(initial)) Files.createDirectories(initial)
            require(Files.isDirectory(initial)) { "HostFs rootDir is not a directory: $initial" }
            // Resolve symlinks in the rootDir itself so subsequent symlink-escape
            // checks compare real-vs-real (matters on macOS where /tmp and
            // /var/folders are themselves symlinks).
            initial.toRealPath()
        }

    private fun resolve(virtualPath: String): Path {
        val resolved = resolveNoFollow(virtualPath)
        // Symlink check: if the resolved path exists, its canonical form
        // (with all symlinks resolved) must also live under root. This
        // catches trapdoor symlinks planted inside root by another host
        // process — a symlink at <root>/escape -> /etc/passwd would pass
        // the lexical check but its toRealPath() resolves to /etc/passwd.
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            val real =
                try {
                    resolved.toRealPath()
                } catch (_: IOException) {
                    null
                }
            if (real != null && real != root && !real.startsWith(root)) {
                throw SecurityException(
                    "symlink in $virtualPath escapes HostFs root: $resolved -> $real",
                )
            }
        }
        return resolved
    }

    /**
     * Like [resolve] but skips the `toRealPath()` symlink-escape check.
     * Used by [statLink] / [readSymlink] / [createSymlink], which describe
     * or create the link entry itself and must NOT follow it — plus
     * [createSymlink] specifically because the link path doesn't exist yet
     * so `toRealPath()` would throw on it.
     */
    private fun resolveNoFollow(virtualPath: String): Path {
        val rel = Paths.normalize(virtualPath).trimStart('/')
        val resolved =
            if (rel.isEmpty()) {
                root
            } else {
                root.resolve(rel).normalize()
            }
        if (resolved != root && !resolved.startsWith(root)) {
            throw SecurityException("path escapes HostFs root: $virtualPath")
        }
        return resolved
    }

    override fun exists(path: String): Boolean = Files.exists(resolve(path))

    override fun isDirectory(path: String): Boolean = Files.isDirectory(resolve(path))

    override fun source(path: String): SuspendSource {
        val p = resolve(path)
        if (!Files.exists(p)) throw FileNotFound(path)
        if (Paths.endsWithSlash(path) && !Files.isDirectory(p)) throw NotADirectory(path)
        val channel = Files.newInputStream(p, StandardOpenOption.READ)
        val bytes = channel.use { it.readAllBytes() }
        val buf = Buffer()
        buf.write(bytes)
        return buf.asSuspendSource()
    }

    override fun sourceNoFollow(path: String): SuspendSource {
        val p = resolveNoFollow(path)
        if (Files.isSymbolicLink(p)) {
            throw SecurityException("path is a symbolic link (O_NOFOLLOW): $path")
        }
        return source(path)
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink {
        if (Paths.endsWithSlash(path)) throw NotADirectory(path)
        val p = resolve(path)
        Files.createDirectories(p.parent ?: root)
        // Lazy open: defer Files.newOutputStream until the first write /
        // flush / close. A SuspendSink constructed for a redirection that
        // is then cancelled before any byte flows (e.g. expansion error
        // on the same command line) would otherwise leak the fd until GC.
        // The chmod-on-create still has to happen at open time so we
        // remember the "was-created" check now and apply the mode once
        // the stream actually opens.
        return object : SuspendSink {
            private val pending = Buffer()
            private var out: java.io.OutputStream? = null
            private var closed: Boolean = false

            private fun ensureOpen(): java.io.OutputStream {
                out?.let { return it }
                val wasCreated = !Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                val s =
                    if (append) {
                        Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    } else {
                        Files.newOutputStream(
                            p,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                        )
                    }
                if (wasCreated) applyPosixMode(p, mode)
                out = s
                return s
            }

            override suspend fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                source.readAtMostTo(pending, byteCount)
            }

            override suspend fun flush() {
                if (pending.size > 0L) {
                    val bytes = pending.readByteArray()
                    withContext(Dispatchers.IO) {
                        val s = ensureOpen()
                        s.write(bytes)
                        s.flush()
                    }
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                // close is the cheap "drop fd" syscall; no need to bounce to IO.
                // If we never opened, there's nothing to close — that's the
                // cancel-before-write path we set out to make leak-free.
                try {
                    out?.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun sinkNoFollow(
        path: String,
        append: Boolean,
    ): SuspendSink {
        val p = resolveNoFollow(path)
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(p)) {
            throw SecurityException("path is a symbolic link (O_NOFOLLOW): $path")
        }
        return sink(path, append)
    }

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {
        val p = resolve(path)
        // Walk segments so we can chmod each NEWLY-created directory; existing
        // ones along the way keep their host mode.
        val created = mutableListOf<java.nio.file.Path>()
        var cur: java.nio.file.Path = p.root ?: root
        for (seg in p) {
            cur = cur.resolve(seg)
            if (!Files.exists(cur)) {
                Files.createDirectory(cur)
                created.add(cur)
            } else if (!Files.isDirectory(cur)) {
                throw NotADirectory(cur.toString())
            }
        }
        for (c in created) applyPosixMode(c, mode)
    }

    /**
     * Best-effort POSIX-mode application. On non-POSIX filesystems (Windows
     * NTFS, some network mounts) the view is unavailable — swallow the
     * exception rather than fail the create, matching `umask`'s nature as an
     * advisory mask.
     */
    private fun applyPosixMode(
        p: java.nio.file.Path,
        mode: Int,
    ) {
        try {
            val view =
                Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView::class.java)
                    ?: return
            val perms = mutableSetOf<java.nio.file.attribute.PosixFilePermission>()
            if (mode and 0b100_000_000 != 0) perms += java.nio.file.attribute.PosixFilePermission.OWNER_READ
            if (mode and 0b010_000_000 != 0) perms += java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            if (mode and 0b001_000_000 != 0) perms += java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
            if (mode and 0b000_100_000 != 0) perms += java.nio.file.attribute.PosixFilePermission.GROUP_READ
            if (mode and 0b000_010_000 != 0) perms += java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
            if (mode and 0b000_001_000 != 0) perms += java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
            if (mode and 0b000_000_100 != 0) perms += java.nio.file.attribute.PosixFilePermission.OTHERS_READ
            if (mode and 0b000_000_010 != 0) perms += java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
            if (mode and 0b000_000_001 != 0) perms += java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
            view.setPermissions(perms)
        } catch (_: Throwable) {
        }
    }

    override fun list(path: String): List<String> {
        val p = resolve(path)
        if (!Files.exists(p)) throw FileNotFound(path)
        if (!Files.isDirectory(p)) throw NotADirectory(path)
        return Files
            .newDirectoryStream(p)
            .use { stream ->
                stream.map { it.fileName.toString() }
            }.sorted()
    }

    /**
     * Real-host watch via `java.nio.file.WatchService`. WatchService is
     * directory-granular, so we register the parent directory of [path]
     * and filter events for the file's basename. Flow cancellation closes
     * the WatchService and the polling coroutine.
     *
     * Watching a directory directly emits one event per direct child
     * change (no recursion — recursive watching is a host-specific can of
     * worms WatchService doesn't help with on macOS/Linux without polling).
     */
    override fun watch(path: String): Flow<FsEvent> {
        val p = resolveNoFollow(path)
        val isDir = Files.isDirectory(p)
        val watchDir = if (isDir) p else p.parent ?: p
        val targetName: String? = if (isDir) null else p.fileName.toString()
        return channelFlow {
            val service = watchDir.fileSystem.newWatchService()
            val key: WatchKey =
                watchDir.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
            // Poll loop on IO dispatcher — WatchService.poll is blocking.
            val poller =
                launch {
                    while (isActive) {
                        val k = service.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        for (ev in k.pollEvents()) {
                            val ctx = ev.context() as? Path ?: continue
                            if (targetName != null && ctx.toString() != targetName) continue
                            val abs = watchDir.resolve(ctx)
                            val virtualPath = "/" + root.relativize(abs).toString().replace('\\', '/')
                            val out: FsEvent =
                                when (ev.kind()) {
                                    StandardWatchEventKinds.ENTRY_CREATE -> FsEvent.Created(virtualPath)
                                    StandardWatchEventKinds.ENTRY_MODIFY -> FsEvent.Modified(virtualPath)
                                    StandardWatchEventKinds.ENTRY_DELETE -> FsEvent.Deleted(virtualPath)
                                    else -> continue
                                }
                            trySend(out)
                        }
                        if (!k.reset()) break
                    }
                }
            awaitClose {
                poller.cancel()
                try {
                    key.cancel()
                } catch (_: Throwable) {
                }
                try {
                    service.close()
                } catch (_: Throwable) {
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun remove(path: String) {
        // POSIX unlink does NOT follow symlinks — operates on the link entry
        // itself. Use resolveNoFollow so a symlink whose target escapes root
        // can still be unlinked (the link itself lives inside root).
        val p = resolveNoFollow(path)
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(p)) {
            Files.delete(p)
            return
        }
        if (Files.isDirectory(p)) {
            // Recursive delete — `rm -r` analog. For now we only walk one level
            // because most engine-cache use is single-file. If we hit a real
            // multi-level case, swap to Files.walk + reverse-order delete.
            Files
                .walk(p)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.delete(it) }
        } else {
            Files.delete(p)
        }
    }

    override fun stat(path: String): FileStat {
        val p = resolve(path)
        if (!Files.exists(p)) throw FileNotFound(path)
        val attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java)
        val type =
            when {
                attrs.isDirectory -> FileType.DIRECTORY
                attrs.isSymbolicLink -> FileType.SYMLINK
                attrs.isRegularFile -> FileType.REGULAR
                else -> FileType.REGULAR
            }
        val mode = readPosixMode(p) ?: if (type == FileType.DIRECTORY) 0b111_101_101 else 0b110_100_100
        return FileStat(
            path = path,
            type = type,
            size = attrs.size(),
            mtimeEpochSeconds = attrs.lastModifiedTime().toMillis() / 1000L,
            mode = mode,
        )
    }

    override fun statLink(path: String): FileStat {
        val p = resolveNoFollow(path)
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw FileNotFound(path)
        val attrs =
            Files.readAttributes(
                p,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        val type =
            when {
                attrs.isSymbolicLink -> FileType.SYMLINK
                attrs.isDirectory -> FileType.DIRECTORY
                attrs.isRegularFile -> FileType.REGULAR
                else -> FileType.REGULAR
            }
        val target =
            if (type == FileType.SYMLINK) {
                try {
                    Files.readSymbolicLink(p).toString()
                } catch (_: IOException) {
                    null
                }
            } else {
                null
            }
        val mode = readPosixMode(p) ?: if (type == FileType.DIRECTORY) 0b111_101_101 else 0b110_100_100
        return FileStat(
            path = path,
            type = type,
            size = attrs.size(),
            mtimeEpochSeconds = attrs.lastModifiedTime().toMillis() / 1000L,
            mode = mode,
            symlinkTarget = target,
        )
    }

    override fun readSymlink(path: String): String {
        val p = resolveNoFollow(path)
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw FileNotFound(path)
        if (!Files.isSymbolicLink(p)) throw NotASymlink(path)
        return Files.readSymbolicLink(p).toString()
    }

    override fun createSymlink(
        linkPath: String,
        target: String,
    ) {
        val p = resolveNoFollow(linkPath)
        Files.createDirectories(p.parent ?: root)
        // Store the target string verbatim — POSIX allows dangling links.
        // Any later read THROUGH this link still hits the `resolve()`
        // toRealPath check, so a target that escapes root is rejected at
        // follow time, not create time.
        Files.createSymbolicLink(p, NioPaths.get(target))
    }

    override fun createHardLink(
        linkPath: String,
        target: String,
    ) {
        // resolve() rejects targets that escape root (lexical + symlink
        // check), so we can't create a hard link to a file outside the
        // jail — POSIX `link(2)` analog of EXDEV / EACCES.
        val src = resolve(target)
        if (!Files.exists(src)) throw FileNotFound(target)
        if (Files.isDirectory(src)) {
            throw RuntimeException("hard link to directory not permitted: $target")
        }
        val link = resolveNoFollow(linkPath)
        Files.createDirectories(link.parent ?: root)
        Files.createLink(link, src)
    }

    override fun setMtime(
        path: String,
        epochSeconds: Long,
    ) {
        val p = resolve(path)
        if (!Files.exists(p)) throw FileNotFound(path)
        Files.setLastModifiedTime(
            p,
            java.nio.file.attribute.FileTime
                .fromMillis(epochSeconds * 1000L),
        )
    }

    override fun chmod(
        path: String,
        mode: Int,
    ) {
        val p = resolve(path)
        if (!Files.exists(p)) throw FileNotFound(path)
        try {
            val view = Files.getFileAttributeView(p, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            view?.setPermissions(PosixFilePermissions.fromString(modeToRwx(mode)))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX FS (Windows) — store the mode as a best-effort attribute fallback.
            // Skip silently; the file remains accessible per host defaults.
        }
    }

    private fun readPosixMode(p: Path): Int? =
        try {
            val view = Files.getFileAttributeView(p, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            view?.readAttributes()?.permissions()?.let { perms ->
                PosixFilePermissions.toString(perms).let(::rwxToMode)
            }
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }

    private fun rwxToMode(rwx: String): Int {
        // 9-char string like "rwxr-xr--" → 0o754
        require(rwx.length == 9) { "expected 9-char rwx string, got '$rwx'" }
        var mode = 0
        for (i in 0 until 9) {
            if (rwx[i] != '-') mode = mode or (1 shl (8 - i))
        }
        return mode
    }

    private fun modeToRwx(mode: Int): String {
        val sb = StringBuilder(9)
        val chars = charArrayOf('r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x')
        for (i in 0 until 9) {
            sb.append(if ((mode shr (8 - i)) and 1 == 1) chars[i] else '-')
        }
        return sb.toString()
    }

    /** Copy a file using the host FS atomically — used by tests for setup. */
    public fun copyFromHost(
        hostSource: Path,
        virtualPath: String,
    ) {
        val target = resolve(virtualPath)
        Files.createDirectories(target.parent ?: root)
        Files.copy(hostSource, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
