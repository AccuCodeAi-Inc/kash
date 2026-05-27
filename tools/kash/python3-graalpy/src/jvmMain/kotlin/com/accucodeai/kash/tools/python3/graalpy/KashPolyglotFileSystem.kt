package com.accucodeai.kash.tools.python3.graalpy

import com.accucodeai.kash.fs.FileType
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import com.accucodeai.kash.fs.FileSystem as KashFileSystem
import com.accucodeai.kash.fs.Paths as KashPaths
import org.graalvm.polyglot.io.FileSystem as PolyglotFileSystem

/**
 * Adapts kash's [KashFileSystem] to GraalPy's [PolyglotFileSystem] so Python's
 * `open()`, `os.stat`, etc. route through the virtual FS — never the host.
 *
 * Scope: read + write of regular files, basic attributes, directory listing.
 * Operations that require real-FS semantics we don't model — symlinks,
 * permissions enforcement on read, current-working-directory mutation —
 * either fall through to deterministic stubs or raise `UnsupportedOperationException`.
 *
 * Path model: we use `java.nio.file.Paths` as a string-carrier only. Every
 * operation normalizes via `path.toString()` before delegating to the kash
 * FS, which has its own normalization (`Paths.normalize` in kash). The host
 * default-FS path semantics are never invoked.
 */
internal class KashPolyglotFileSystem(
    private val fs: KashFileSystem,
    private val initialCwd: String,
    /**
     * Optional host-FS passthrough region. Paths that match one of these
     * directories (the directory itself OR any descendant) are served from
     * the real host filesystem via `java.nio.file` instead of routing through
     * [fs]. Used to expose GraalPy's stdlib cache (which Truffle's
     * InternalResource machinery extracts to a host directory) without
     * granting blanket host access.
     */
    hostPassthroughPrefixes: List<String> = emptyList(),
) : PolyglotFileSystem {
    private var cwd: String = initialCwd
    private val passthroughDirs: List<String> =
        hostPassthroughPrefixes.map { entry ->
            val p = Paths.get(entry.trimEnd('/'))
            // Canonicalize via toRealPath() if the dir exists, so symlink-escape
            // comparisons later are real-vs-real (e.g. on macOS, /var/folders
            // is itself a symlink to /private/var/folders). If the dir doesn't
            // exist yet, fall back to lexical normalization — it'll get
            // re-resolved once content lands.
            val canonical =
                try {
                    if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) p.toRealPath() else p.toAbsolutePath().normalize()
                } catch (_: java.io.IOException) {
                    p.toAbsolutePath().normalize()
                }
            canonical.toString()
        }

    private fun isHostPassthrough(path: String): Boolean =
        passthroughDirs.any { dir -> path == dir || path.startsWith("$dir/") } ||
            // Also catch paths whose un-canonicalized form would only match
            // after toRealPath resolution (handles the case where the caller
            // passes the symlink-side path and the passthrough dir was
            // canonicalized). Cheap second pass via real-path comparison.
            run {
                val p = Paths.get(path)
                if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return@run false
                val real =
                    try {
                        p.toRealPath().toString()
                    } catch (_: java.io.IOException) {
                        return@run false
                    }
                passthroughDirs.any { dir -> real == dir || real.startsWith("$dir/") }
            }

    /**
     * Defense against symlink trapdoors inside a passthrough directory: any
     * existing symlink (or path whose canonical form escapes the passthrough
     * region) is rejected. Without this, a malicious symlink placed inside
     * the GraalPy cache by another process could redirect a sandboxed file
     * op outside the cache.
     */
    private fun rejectSymlinkEscape(abs: String) {
        val p = Paths.get(abs)
        // Only existing paths can be (or have ancestors that are) symlinks.
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        val real =
            try {
                p.toRealPath()
            } catch (_: java.io.IOException) {
                return
            }
        val realStr = real.toString()
        val safe = passthroughDirs.any { dir -> realStr == dir || realStr.startsWith("$dir/") }
        if (!safe) {
            throw SecurityException("symlink target escapes passthrough region: $abs -> $realStr")
        }
    }

    override fun parsePath(uri: URI): Path = Paths.get(uri.path)

    override fun parsePath(path: String): Path = Paths.get(path)

    override fun toAbsolutePath(path: Path): Path {
        val s = path.toString()
        val absoluteString = if (s.startsWith("/")) s else "$cwd/$s"
        // Lexical normalization — collapse `..` and `.` segments before any
        // routing or prefix check. Without this, `/cache/../../etc/passwd`
        // string-matches the cache passthrough prefix and reaches the kernel
        // where the dots get resolved against the real filesystem (the kernel
        // resolves `..` during the openat syscall, regardless of what the
        // calling code believed about the path).
        return Paths.get(KashPaths.normalize(absoluteString))
    }

    override fun toRealPath(
        path: Path,
        vararg linkOptions: LinkOption,
    ): Path {
        val abs = toAbsolutePath(path)
        // For the host-passthrough region, resolve symlinks via the underlying
        // FS so callers that use realpath() for security checks get an accurate
        // canonical path. For the kash virtual FS, there are no symlinks
        // today, so absolute-normalized IS the real path.
        if (isHostPassthrough(abs.toString())) {
            return try {
                Paths.get(abs.toString()).toRealPath()
            } catch (_: java.io.IOException) {
                abs
            }
        }
        return abs
    }

    override fun setCurrentWorkingDirectory(currentWorkingDirectory: Path) {
        cwd = currentWorkingDirectory.toString()
    }

    override fun checkAccess(
        path: Path,
        modes: Set<AccessMode>,
        vararg linkOptions: LinkOption,
    ) {
        val abs = toAbsolutePath(path).toString()
        if (isHostPassthrough(abs)) {
            rejectSymlinkEscape(abs)
            if (!Files.exists(Paths.get(abs), LinkOption.NOFOLLOW_LINKS)) {
                throw NoSuchFileException(abs)
            }
            return
        }
        if (!fs.exists(abs)) throw NoSuchFileException(abs)
        // We do not model per-mode permissions; existence is sufficient.
    }

    override fun createDirectory(
        dir: Path,
        vararg attrs: FileAttribute<*>,
    ) {
        val abs = toAbsolutePath(dir).toString()
        if (isHostPassthrough(abs)) {
            // The Truffle cache directory is created by Truffle itself; we still
            // allow mkdirs for completeness so first-run extraction can happen.
            rejectSymlinkEscape(abs)
            Files.createDirectories(Paths.get(abs))
            return
        }
        fs.mkdirs(abs)
    }

    override fun delete(path: Path) {
        val abs = toAbsolutePath(path).toString()
        if (isHostPassthrough(abs)) {
            rejectSymlinkEscape(abs)
            Files.delete(Paths.get(abs))
            return
        }
        fs.remove(abs)
    }

    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel {
        val abs = toAbsolutePath(path).toString()
        if (isHostPassthrough(abs)) {
            // Host passthrough: open via java.nio.file. Truffle does write to
            // this region during InternalResource extraction; let it through.
            rejectSymlinkEscape(abs)
            // NOFOLLOW_LINKS makes any future symlink that's planted at this
            // exact path fail rather than silently traversing it.
            val safeOptions = options + LinkOption.NOFOLLOW_LINKS
            return Files.newByteChannel(Paths.get(abs), safeOptions, *attrs)
        }
        val writing =
            options.contains(StandardOpenOption.WRITE) ||
                options.contains(StandardOpenOption.APPEND) ||
                options.contains(StandardOpenOption.CREATE) ||
                options.contains(StandardOpenOption.CREATE_NEW) ||
                options.contains(StandardOpenOption.TRUNCATE_EXISTING)
        val reading = options.contains(StandardOpenOption.READ) || !writing
        val creating =
            options.contains(StandardOpenOption.CREATE) ||
                options.contains(StandardOpenOption.CREATE_NEW)
        if (!creating && !fs.exists(abs)) {
            // POSIX open() with O_RDONLY (no O_CREAT) fails ENOENT on missing
            // paths — without this, a read of a non-existent file would return
            // 0-byte EOF and silently "succeed."
            throw NoSuchFileException(abs)
        }
        // Honor O_NOFOLLOW (LinkOption.NOFOLLOW_LINKS): if the path itself is
        // a symlink, refuse to open it — POSIX returns ELOOP.
        val noFollow = options.any { it == LinkOption.NOFOLLOW_LINKS }
        if (noFollow && fs.exists(abs)) {
            val st =
                try {
                    fs.statLink(abs)
                } catch (_: Throwable) {
                    null
                }
            if (st?.type == FileType.SYMLINK) {
                throw java.nio.file.FileSystemException(abs, null, "Too many levels of symbolic links (O_NOFOLLOW)")
            }
        }
        return InMemoryChannel(
            fs = fs,
            path = abs,
            read = reading,
            write = writing,
            append = options.contains(StandardOpenOption.APPEND),
        )
    }

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>,
    ): DirectoryStream<Path> {
        val abs = toAbsolutePath(dir).toString()
        if (isHostPassthrough(abs)) {
            rejectSymlinkEscape(abs)
            return Files.newDirectoryStream(Paths.get(abs), filter)
        }
        val entries =
            fs.list(abs).map { name ->
                Paths.get(if (abs == "/") "/$name" else "$abs/$name")
            }
        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = entries.toMutableList().iterator()

            override fun close() = Unit
        }
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption,
    ): Map<String, Any> {
        val abs = toAbsolutePath(path).toString()
        if (isHostPassthrough(abs)) {
            rejectSymlinkEscape(abs)
            // Force NOFOLLOW even if caller omitted it — reading attributes
            // *through* a symlink in the cache region could reveal info
            // about files outside the passthrough.
            val safeOptions = (options.toList() + LinkOption.NOFOLLOW_LINKS).toTypedArray()
            return Files.readAttributes(Paths.get(abs), attributes, *safeOptions)
        }
        if (!fs.exists(abs)) throw NoSuchFileException(abs)
        // POSIX lstat semantics — honor caller's NOFOLLOW so Python's
        // `os.lstat(p)` / `pathlib.Path.is_symlink()` report the link itself.
        // Default (no NOFOLLOW): stat follows links.
        val noFollow = options.any { it == LinkOption.NOFOLLOW_LINKS }
        val stat = if (noFollow) fs.statLink(abs) else fs.stat(abs)
        val isDir = stat.type == FileType.DIRECTORY
        val isRegular = stat.type == FileType.REGULAR
        val isSymlink = stat.type == FileType.SYMLINK
        val mtime = FileTime.fromMillis(stat.mtimeEpochSeconds * 1000L)
        // POSIX-style mode for Python: type bits OR'd over kash's stored perm
        // bits. `FileStat.mode` is the lower-12-bit permission word (rwx +
        // setuid/setgid/sticky); GraalPy / CPython callers expect the upper
        // type bits set so `stat.S_ISDIR(mode)` etc. work.
        val permBits = stat.mode and 0xFFF
        val typeBits =
            when {
                isDir -> 0x4000

                // S_IFDIR
                isSymlink -> 0xA000

                // S_IFLNK
                isRegular -> 0x8000

                // S_IFREG
                stat.type == FileType.FIFO -> 0x1000

                stat.type == FileType.CHAR -> 0x2000

                stat.type == FileType.BLOCK -> 0x6000

                stat.type == FileType.SOCKET -> 0xC000

                else -> 0x0000
            }
        val mode = typeBits or permBits
        return mapOf(
            // BasicFileAttributeView keys
            "isRegularFile" to isRegular,
            "isDirectory" to isDir,
            "isSymbolicLink" to isSymlink,
            "isOther" to (!isDir && !isRegular && !isSymlink),
            "size" to stat.size,
            "creationTime" to mtime,
            "lastModifiedTime" to mtime,
            "lastAccessTime" to mtime,
            "fileKey" to abs,
            // PosixFileAttributeView keys
            "permissions" to posixPermsFromBits(permBits),
            "owner" to NamedPrincipal(stat.ownerName),
            "group" to NamedPrincipal(stat.groupName),
            // unix:* keys — Integer/Long values that Truffle unboxes directly.
            // kash's FS doesn't model uid/gid/inode/dev numerically; we
            // synthesize stable, plausible values so Python's stat path works.
            "mode" to mode,
            "ino" to (abs.hashCode().toLong() and 0xFFFFFFFFL),
            "dev" to 0L,
            "nlink" to stat.nlink,
            "uid" to 0,
            "gid" to 0,
            "ctime" to mtime,
            "rdev" to 0L,
        )
    }

    override fun readSymbolicLink(link: Path): Path {
        val abs = toAbsolutePath(link).toString()
        if (isHostPassthrough(abs)) {
            // Cache region: delegate to host, then re-validate the resolved
            // target stays within the passthrough region. Prevents a planted
            // symlink whose target lives outside the cache from leaking the
            // host filesystem to sandboxed code.
            val target = Files.readSymbolicLink(Paths.get(abs))
            val resolved =
                if (target.isAbsolute) {
                    target.toString()
                } else {
                    Paths
                        .get(abs)
                        .parent
                        .resolve(target)
                        .normalize()
                        .toString()
                }
            val safe = passthroughDirs.any { dir -> resolved == dir || resolved.startsWith("$dir/") }
            if (!safe) {
                throw SecurityException("symlink target escapes passthrough region: $abs -> $resolved")
            }
            return target
        }
        return Paths.get(fs.readSymlink(abs))
    }
}

private fun posixPermsFromBits(bits: Int): Set<PosixFilePermission> {
    val out = java.util.EnumSet.noneOf(PosixFilePermission::class.java)
    if (bits and 0b100_000_000 != 0) out.add(PosixFilePermission.OWNER_READ)
    if (bits and 0b010_000_000 != 0) out.add(PosixFilePermission.OWNER_WRITE)
    if (bits and 0b001_000_000 != 0) out.add(PosixFilePermission.OWNER_EXECUTE)
    if (bits and 0b000_100_000 != 0) out.add(PosixFilePermission.GROUP_READ)
    if (bits and 0b000_010_000 != 0) out.add(PosixFilePermission.GROUP_WRITE)
    if (bits and 0b000_001_000 != 0) out.add(PosixFilePermission.GROUP_EXECUTE)
    if (bits and 0b000_000_100 != 0) out.add(PosixFilePermission.OTHERS_READ)
    if (bits and 0b000_000_010 != 0) out.add(PosixFilePermission.OTHERS_WRITE)
    if (bits and 0b000_000_001 != 0) out.add(PosixFilePermission.OTHERS_EXECUTE)
    return out
}

private class NamedPrincipal(
    private val n: String,
) : UserPrincipal,
    GroupPrincipal {
    override fun getName(): String = n
}

/**
 * Minimal `SeekableByteChannel` backed by an in-memory byte buffer that
 * flushes to [fs] on close (write mode) or reads everything up-front (read
 * mode). Channels for streaming I/O in the in-memory FS already exist —
 * this just adapts to the JDK NIO `SeekableByteChannel` contract Python
 * needs.
 */
private class InMemoryChannel(
    private val fs: com.accucodeai.kash.fs.FileSystem,
    private val path: String,
    private val read: Boolean,
    private val write: Boolean,
    private val append: Boolean,
) : SeekableByteChannel {
    // External boundary: GraalPy's SeekableByteChannel API is synchronous.
    // We `runBlocking` to drive our suspending FS — bounded, infrequent
    // python file open/close.
    private val buffer: ByteArray =
        if (read && fs.exists(path)) {
            kotlinx.coroutines.runBlocking {
                val acc = Buffer()
                val src = fs.source(path)
                try {
                    while (true) {
                        val n = src.readAtMostTo(acc, 8 * 1024L)
                        if (n == -1L) break
                    }
                    acc.readByteArray()
                } finally {
                    src.close()
                }
            }
        } else {
            ByteArray(0)
        }
    private var writeAcc: java.io.ByteArrayOutputStream? = if (write) java.io.ByteArrayOutputStream() else null
    private var position: Long = if (append) buffer.size.toLong() else 0L
    private var open = true

    override fun isOpen(): Boolean = open

    override fun close() {
        if (!open) return
        open = false
        writeAcc?.let { acc ->
            kotlinx.coroutines.runBlocking {
                val sink = fs.sink(path, append = false)
                try {
                    val staged = Buffer()
                    staged.write(acc.toByteArray())
                    sink.write(staged, staged.size)
                    sink.flush()
                } finally {
                    sink.close()
                }
            }
        }
    }

    override fun read(dst: ByteBuffer): Int {
        if (!read) throw NonReadableChannelException()
        val remaining = buffer.size - position.toInt()
        if (remaining <= 0) return -1
        val n = minOf(remaining, dst.remaining())
        dst.put(buffer, position.toInt(), n)
        position += n
        return n
    }

    override fun write(src: ByteBuffer): Int {
        val w = writeAcc ?: throw NonWritableChannelException()
        val n = src.remaining()
        val tmp = ByteArray(n)
        src.get(tmp)
        w.write(tmp)
        position += n
        return n
    }

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        position = newPosition
        return this
    }

    override fun size(): Long = buffer.size.toLong()

    override fun truncate(size: Long): SeekableByteChannel {
        if (writeAcc == null) throw NonWritableChannelException()
        // We accumulate writes in writeAcc and overwrite on close, so truncate
        // is a no-op against the staging buffer — Python's open(..., 'w') already
        // sets TRUNCATE_EXISTING and writes the full new content.
        return this
    }
}
