package com.accucodeai.kash.fs

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readAllBytes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.io.Buffer

/**
 * Minimal filesystem abstraction.
 *
 * Streaming primitives [source] / [sink] are the foundation, returning
 * suspending [com.accucodeai.kash.api.io.SuspendSource] /
 * [com.accucodeai.kash.api.io.SuspendSink] handles — read/write parks the
 * *coroutine*, not the dispatcher thread. The [readBytes] / [writeBytes] /
 * [appendBytes] helpers exist for callers that want a one-shot byte buffer
 * (they suspend too — implemented on top of the streaming methods so any
 * [FileSystem] gets them for free).
 *
 * JVM-backed implementations adapt their blocking `RawSink`/`RawSource` once
 * via `.asSuspend()` at the boundary; the unavoidable blocking syscall hops
 * to `Dispatchers.IO` so it parks an elastic IO worker rather than starving
 * the caller's pool.
 */
public interface FileSystem {
    public fun exists(path: String): Boolean

    public fun isDirectory(path: String): Boolean

    public fun source(path: String): SuspendSource

    /**
     * Open [path] for writing. [mode] is the permission bits to apply when
     * the file is *newly created*; pre-existing files keep their mode.
     * Callers are responsible for any umask masking (`mode and ctx.umask.inv()`) —
     * the FS applies [mode] as-is. Default `0o666` matches POSIX `creat(2)`.
     */
    public fun sink(
        path: String,
        append: Boolean = false,
        mode: Int = 0b110_110_110,
    ): SuspendSink

    /**
     * Create [path] (and any missing parents) as a directory. [mode] is the
     * permission bits for newly-created directories; existing dirs along the
     * way are untouched. Callers apply umask masking themselves. Default
     * `0o777` matches POSIX `mkdir(2)`.
     */
    public fun mkdirs(
        path: String,
        mode: Int = 0b111_111_111,
    )

    public fun list(path: String): List<String>

    public fun remove(path: String)

    // -------- Opener-aware overloads --------
    //
    // Every read-surface method gets a sibling overload that carries an
    // optional [opener] — the [com.accucodeai.kash.api.KashProcess]
    // performing the operation. Backends whose resolution depends on
    // per-process state (ProcFs's `/proc/self`, DevFs's `/dev/tty`)
    // override the opener-aware form; everyone else inherits the default
    // delegate, so existing implementations don't have to change.
    //
    // Mirrors Linux's `do_filp_open()` / `vfs_statx()` family carrying
    // `current` into the filesystem layer. The opener-bound facade
    // exposed via `KashProcess.fs` calls these overloads automatically,
    // so callers using `ctx.process.fs.X(path)` get per-process semantics
    // without an explicit opener argument.

    public fun exists(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): Boolean = exists(path)

    public fun isDirectory(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): Boolean = isDirectory(path)

    public fun source(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): SuspendSource = source(path)

    public fun sink(
        path: String,
        append: Boolean,
        mode: Int,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): SuspendSink = sink(path, append, mode)

    public fun list(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): List<String> = list(path)

    public fun stat(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): FileStat = stat(path)

    public fun statLink(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): FileStat = statLink(path)

    public fun readSymlink(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): String = readSymlink(path)

    public fun listStat(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): List<FileStat> = listStat(path)

    public fun sourceNoFollow(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): SuspendSource = sourceNoFollow(path)

    public fun sinkNoFollow(
        path: String,
        append: Boolean,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): SuspendSink = sinkNoFollow(path, append)

    public suspend fun readBytes(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): ByteArray = readBytes(path)

    /**
     * File metadata. Throws [FileNotFound] for missing paths.
     *
     * Default impl is a stub that returns just enough for the [test]/`[`
     * builtin to keep working — concrete filesystems should override.
     */
    public fun stat(path: String): FileStat {
        if (!exists(path)) throw FileNotFound(path)
        val isDir = isDirectory(path)
        return FileStat(
            path = path,
            type = if (isDir) FileType.DIRECTORY else FileType.REGULAR,
            size = 0L, // default stat is stubby; overriders supply real size
            mtimeEpochSeconds = 0L,
            mode = if (isDir) 0b111_101_101 else 0b110_100_100,
        )
    }

    /**
     * Stat every direct entry of [path]. Bulk form so an in-memory FS can
     * avoid re-walking its node table per entry, but the default is
     * `list(path).map { stat(...) }` so any [FileSystem] gets it for free.
     */
    public fun listStat(path: String): List<FileStat> {
        val base = if (path == "/") "/" else "$path/"
        return list(path).map { stat("$base$it") }
    }

    /**
     * Replace the mode bits of [path]. Only the low 12 bits (`rwxrwxrwx`
     * plus setuid / setgid / sticky) are stored.
     *
     * Default impl throws — backends that don't model mode bits must opt in
     * by overriding, so a `chmod` against an FS that can't honor it surfaces
     * a clear error rather than silently no-op'ing.
     */
    public fun chmod(
        path: String,
        mode: Int,
    ): Unit = throw UnsupportedOperationException("chmod is not supported by this FileSystem")

    /**
     * Set the modification time of [path] in epoch seconds.
     *
     * Default impl throws — read-only mounts (`HOST_BORROW`, `ENGINE_CACHE`)
     * inherit this so a `touch -t` against them surfaces a clear error
     * instead of a silent no-op. Backends that model mtime override this.
     */
    public fun setMtime(
        path: String,
        epochSeconds: Long,
    ): Unit = throw UnsupportedOperationException("setMtime is not supported by this FileSystem")

    /**
     * If [path] is a synthetic registry entry (i.e. a [ToolsFs] file at
     * `/usr/bin/<name>`), return its [CommandSpec]. Default null — real files
     * (regular FS entries) return null, and the caller treats them as
     * scripts or external binaries.
     *
     * This is the one extension point [com.accucodeai.kash.fs.ToolsFs] uses
     * to short-circuit PATH resolution back to the in-process command
     * without coupling the interpreter to a specific FS class.
     */
    public fun commandSpec(path: String): CommandSpec? = null

    /**
     * POSIX `open(2)` analog: vend a fully-formed [OpenFileDescription] for
     * [path]. Default returns null — the redirection layer then falls back
     * to wrapping [source]/[sink] in a vanilla OFD.
     *
     * Backends override this when they need to attach metadata that can't
     * round-trip through a bare `SuspendSource`/`SuspendSink`: the canonical case
     * is `DevFs`'s `/dev/tty`, which carries a [com.accucodeai.kash.api
     * .terminal.TerminalControl] handle on the OFD so a tool reading
     * `</dev/tty` gets keyboard access regardless of fd-0 redirection.
     *
     * [opener] is the process performing the open — needed by backends
     * whose resolution depends on per-process state. Today that's just
     * `/dev/tty` (resolves to the opener's session's controlling tty) and
     * `/proc/self/...` (resolves to the opener's pid), but the parameter
     * mirrors how Linux's `do_filp_open()` carries `current` into the
     * filesystem layer. Null is allowed for callers without process
     * context (low-level tools, FS-only tests).
     */
    public fun openHandle(
        path: String,
        accessMode: AccessMode,
        opener: com.accucodeai.kash.api.KashProcess? = null,
    ): OpenFileDescription? = null

    /**
     * POSIX `lstat`: like [stat] but does NOT follow symbolic links. Default
     * impl falls back to [stat] (correct for FSes that don't model symlinks).
     */
    public fun statLink(path: String): FileStat = stat(path)

    /**
     * Return the target string of the symlink at [path] verbatim. Throws
     * [NotASymlink] if [path] is not a symbolic link, or [FileNotFound] if
     * it doesn't exist.
     */
    public fun readSymlink(path: String): String = throw NotASymlink(path)

    /**
     * Create a symbolic link at [linkPath] pointing to [target]. POSIX
     * allows dangling links — [target] is stored verbatim and is not
     * validated. Backends that don't support symlinks throw
     * [UnsupportedOperationException].
     */
    public fun createSymlink(
        linkPath: String,
        target: String,
    ): Unit = throw UnsupportedOperationException("createSymlink is not supported by this FileSystem")

    /**
     * Create a hard link at [linkPath] pointing to the same inode as
     * [target]. POSIX `link(2)`: the two paths become indistinguishable —
     * same contents, same mode, same mtime — and changes through one are
     * visible through the other. `nlink` increments on each link, and the
     * inode persists until every link is removed.
     *
     * Restrictions:
     * - [target] must exist and be a regular file. POSIX `EPERM` on dirs.
     * - Cross-filesystem links are rejected (POSIX `EXDEV`) — for kash that
     *   means cross-mount in [MountedFileSystem].
     *
     * Default impl throws — backends that don't model multi-link inodes
     * surface a clear error rather than silently degrading.
     */
    public fun createHardLink(
        linkPath: String,
        target: String,
    ): Unit = throw UnsupportedOperationException("createHardLink is not supported by this FileSystem")

    /**
     * Open [path] for reading WITHOUT following a terminal symbolic link
     * — POSIX `open(path, O_RDONLY | O_NOFOLLOW)`. If [path] itself is a
     * symlink, implementations should throw a `SecurityException` analog
     * (POSIX returns `ELOOP`). Default impl delegates to [source] — correct
     * for backends that don't model symlinks.
     */
    public fun sourceNoFollow(path: String): SuspendSource = source(path)

    /**
     * Open [path] for writing WITHOUT following a terminal symbolic link.
     * Default impl delegates to [sink].
     */
    public fun sinkNoFollow(
        path: String,
        append: Boolean = false,
    ): SuspendSink = sink(path, append)

    public suspend fun readBytes(path: String): ByteArray = source(path).readAllBytes()

    public suspend fun writeBytes(
        path: String,
        bytes: ByteArray,
        mode: Int = 0b110_110_110,
    ) {
        val s = sink(path, append = false, mode = mode)
        try {
            val buf = Buffer()
            buf.write(bytes)
            s.write(buf, buf.size)
            s.flush()
        } finally {
            s.close()
        }
    }

    public suspend fun appendBytes(
        path: String,
        bytes: ByteArray,
    ) {
        val s = sink(path, append = true)
        try {
            val buf = Buffer()
            buf.write(bytes)
            s.write(buf, buf.size)
            s.flush()
        } finally {
            s.close()
        }
    }

    /**
     * Move/rename [from] to [to]. Default impl is recursive copy-then-delete —
     * O(bytes) and non-atomic across crashes, but correct on any [FileSystem].
     * Backends that can do better (same-mount inode swap) should override.
     *
     * If [to] already exists, it is overwritten for files; directory targets
     * throw — callers must remove the destination first if that's intended.
     */
    public suspend fun rename(
        from: String,
        to: String,
    ) {
        if (!exists(from)) error("rename: source not found: $from")
        if (isDirectory(from)) {
            if (exists(to) && !isDirectory(to)) error("rename: destination is a file: $to")
            mkdirs(to)
            for (name in list(from)) {
                val src = if (from.endsWith("/")) "$from$name" else "$from/$name"
                val dst = if (to.endsWith("/")) "$to$name" else "$to/$name"
                rename(src, dst)
            }
            remove(from)
        } else {
            writeBytes(to, readBytes(from))
            remove(from)
        }
    }

    /**
     * Observe filesystem changes affecting [path]. The returned [Flow] emits
     * [FsEvent]s as long as it's collected; cancelling the collecting
     * coroutine stops the watch.
     *
     * The default implementation is a coarse 100 ms polling loop over
     * `stat(path)` that emits [FsEvent.Modified] on size or mtime change and
     * [FsEvent.Deleted] when the file disappears. It works on every
     * [FileSystem] but is appropriate only for moderate update rates.
     * Backends that can do better should override:
     *
     *  - [InMemoryFs] uses an in-process event bus (immediate, exact).
     *  - [com.accucodeai.kash.fs.HostFs] uses `java.nio.file.WatchService`.
     *
     * Backends that *can't* watch at all (e.g. read-only embedded mounts)
     * may keep the polling default since it merely produces no events when
     * nothing changes.
     */
    public fun watch(path: String): Flow<FsEvent> =
        flow {
            // Polling fallback. Tracks size + mtime; an existence flip
            // (file came/went) emits Created/Deleted.
            var prevExists = exists(path)
            var prevSize: Long = if (prevExists) runCatching { stat(path).size }.getOrDefault(-1L) else -1L
            var prevMtime: Long =
                if (prevExists) runCatching { stat(path).mtimeEpochSeconds }.getOrDefault(0L) else 0L
            while (currentCoroutineContext().isActive) {
                delay(POLL_INTERVAL_MS)
                val nowExists = exists(path)
                if (!nowExists) {
                    if (prevExists) {
                        emit(FsEvent.Deleted(path))
                        prevExists = false
                        prevSize = -1
                        prevMtime = 0
                    }
                    continue
                }
                if (!prevExists) {
                    emit(FsEvent.Created(path))
                    prevExists = true
                }
                val s = runCatching { stat(path) }.getOrNull() ?: continue
                if (s.size != prevSize || s.mtimeEpochSeconds != prevMtime) {
                    emit(FsEvent.Modified(path))
                    prevSize = s.size
                    prevMtime = s.mtimeEpochSeconds
                }
            }
        }

    public companion object {
        /** Default polling interval for the fallback [watch] impl. */
        public const val POLL_INTERVAL_MS: Long = 100L
    }
}

/**
 * One filesystem change observed by [FileSystem.watch]. Path is the watched
 * path itself; we don't yet model directory-watch sub-path events.
 */
public sealed interface FsEvent {
    public val path: String

    public data class Created(
        override val path: String,
    ) : FsEvent

    public data class Modified(
        override val path: String,
    ) : FsEvent

    public data class Deleted(
        override val path: String,
    ) : FsEvent
}

public class FileNotFound(
    path: String,
) : RuntimeException("no such file or directory: $path")

public class NotADirectory(
    path: String,
) : RuntimeException("not a directory: $path")

public class NotASymlink(
    path: String,
) : RuntimeException("not a symbolic link: $path")

/**
 * Thrown when symlink resolution exceeds the POSIX `SYMLOOP_MAX` depth cap
 * — protects [stat] / [source] / [sink] from infinite cycles like
 * `a -> b -> a`.
 */
public class SymlinkLoop(
    path: String,
) : RuntimeException("too many levels of symbolic links: $path")
