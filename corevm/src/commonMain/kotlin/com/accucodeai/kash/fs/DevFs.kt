package com.accucodeai.kash.fs

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.io.EmptySuspendSource
import com.accucodeai.kash.api.io.NullSuspendSink
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.session
import com.accucodeai.kash.api.terminal.ControllingTty
import kotlinx.io.Buffer
import kotlin.random.Random

/**
 * Synthetic, read-/write-routed [FileSystem] backing the POSIX `/dev/`
 * device tree. Mounted at `/dev` by [installSystemBin].
 *
 *  - **`/dev/null`** — bit-bucket; writes discarded, reads EOF.
 *  - **`/dev/tty`** — opener's controlling terminal, resolved per-call
 *    via `opener.session().controllingTty` (Linux
 *    `tty_open_current_tty()` consulting `current->signal->tty`).
 *    Throws [FileNotFound] (kash's `ENXIO`) when no opener, no session,
 *    or no controlling tty. Bypasses fd-0/1/2 redirection so tools can
 *    `read </dev/tty` for keyboard input through a piped stdin — the
 *    `sudo`/`ssh`/`gpg` password-prompt pattern.
 *  - **`/dev/fd/N`** — dups fd `N` from the opener's table. Each
 *    `open()` returns a fresh OFD (refcount 1, `owning=false`) sharing
 *    the slot's source/sink, so `cat /dev/fd/3` reads through fd 3
 *    without taking ownership of the underlying stream.
 *  - **`/dev/stdin`, `/dev/stdout`, `/dev/stderr`** — aliases for
 *    `/dev/fd/0`/`1`/`2`, resolved through the same [dupFromFdTable]
 *    helper.
 *  - **`/dev/random`, `/dev/urandom`** — pseudo-infinite CRNG streams
 *    backed by [kotlin.random.Random], modeled identically (Linux ≥ 5.6
 *    treats both the same). Reads never return EOF; writes are rejected
 *    (real `/dev/random` write is `CAP_SYS_ADMIN`-gated entropy mixing).
 *
 * DevFs takes no construction arguments — per-process state comes off
 * the [KashProcess] passed to [openHandle], which reaches the session
 * table via `process.machine`.
 */
public class DevFs : FileSystem {
    private val staticEntries =
        setOf("/", "/null", "/zero", "/tty", "/fd", "/stdin", "/stdout", "/stderr", "/random", "/urandom")

    override fun exists(path: String): Boolean = exists(path, opener = null)

    override fun exists(
        path: String,
        opener: KashProcess?,
    ): Boolean {
        val p = Paths.normalize(path)
        if (p in staticEntries) return true
        val n = fdSlot(p) ?: return false
        return opener?.fdTable?.containsKey(n) == true
    }

    override fun isDirectory(path: String): Boolean {
        val p = Paths.normalize(path)
        return p == "/" || p == "/fd"
    }

    override fun source(path: String): SuspendSource = source(path, opener = null)

    override fun source(
        path: String,
        opener: KashProcess?,
    ): SuspendSource {
        val p = Paths.normalize(path)
        return when (p) {
            "/null" -> {
                EmptySuspendSource
            }

            "/zero" -> {
                ZeroSuspendSource
            }

            "/random", "/urandom" -> {
                RandomSuspendSource()
            }

            else -> {
                // /tty, /fd/N, /stdin, /stdout, /stderr need per-opener
                // resolution. Delegate to openHandle and unwrap to the
                // source — same code path scripts hit via
                // `cat /dev/fd/N` (which uses fs.source rather than
                // fs.openHandle directly). Error paths report the
                // fully-qualified /dev/X form so messages say
                // "no such file or directory: /dev/fd/3" rather than
                // the mount-relative "/fd/3".
                val ofd =
                    openHandle(path, AccessMode.RDONLY, opener)
                        ?: throw FileNotFound("/dev$p")
                ofd.source ?: throw FileNotFound("/dev$p")
            }
        }
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = sink(path, append, mode, opener = null)

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
        opener: KashProcess?,
    ): SuspendSink {
        val p = Paths.normalize(path)
        return when (p) {
            "/null" -> {
                NullSuspendSink
            }

            else -> {
                // /tty, /fd/N, /stdout, /stderr (write side) — open the
                // OFD and unwrap to the sink. /random and /urandom are
                // RDONLY in openHandle, so writes to them throw
                // FileNotFound there, which is the right answer.
                val ofd =
                    openHandle(path, AccessMode.WRONLY, opener)
                        ?: throw FileNotFound("/dev$p")
                ofd.sink ?: throw FileNotFound("/dev$p")
            }
        }
    }

    override fun mkdirs(
        path: String,
        mode: Int,
    ): Unit = throw ReadOnlyMountException("/dev${Paths.normalize(path)}")

    override fun list(path: String): List<String> = list(path, opener = null)

    override fun list(
        path: String,
        opener: KashProcess?,
    ): List<String> {
        val p = Paths.normalize(path)
        return when (p) {
            "/" -> {
                listOf("null", "zero", "tty", "fd", "stdin", "stdout", "stderr", "random", "urandom")
            }

            "/fd" -> {
                opener
                    ?.fdTable
                    ?.keys
                    ?.sorted()
                    ?.map { it.toString() } ?: emptyList()
            }

            else -> {
                throw NotADirectory("/dev$p")
            }
        }
    }

    override fun remove(path: String): Unit = throw ReadOnlyMountException("/dev${Paths.normalize(path)}")

    override fun stat(path: String): FileStat = stat(path, opener = null)

    override fun stat(
        path: String,
        opener: KashProcess?,
    ): FileStat {
        val p = Paths.normalize(path)
        return when {
            p == "/" || p == "/fd" -> {
                FileStat(
                    path = p,
                    type = FileType.DIRECTORY,
                    size = 0L,
                    mtimeEpochSeconds = 0L,
                    mode = 0b111_101_101,
                )
            }

            p == "/null" || p == "/zero" || p == "/tty" || p == "/random" || p == "/urandom" -> {
                FileStat(
                    path = p,
                    type = FileType.CHAR,
                    size = 0L,
                    mtimeEpochSeconds = 0L,
                    mode = 0b110_110_110,
                    nlink = 1,
                )
            }

            p == "/stdin" || p == "/stdout" || p == "/stderr" || fdSlot(p) != null -> {
                // Linux's `/dev/fd/N` (a symlink to `/proc/self/fd/N`) and
                // `/dev/std{in,out,err}` ARE magic symlinks: `readlink`
                // resolves them to the underlying fd's target. Returning
                // SYMLINK here makes `[ -L /dev/fd/N ]` true (matches Linux)
                // and feeds [readSymlink] for `readlink /dev/fd/N`. open()
                // still works on the path through `openHandle`'s carve-out.
                FileStat(
                    path = p,
                    type = FileType.SYMLINK,
                    size = 0L,
                    mtimeEpochSeconds = 0L,
                    mode = 0b111_111_111,
                    nlink = 1,
                    symlinkTarget = readSymlinkTarget(p, opener),
                )
            }

            else -> {
                throw FileNotFound("/dev$p")
            }
        }
    }

    /**
     * Compute the symlink target for `/dev/fd/N`, `/dev/stdin`, `/dev/stdout`,
     * `/dev/stderr`. Returns the OFD-derived `readlink` target via the shared
     * [fdLinkTargetForOfd] helper, or `anon_inode:[fd-N]` if [opener] is null
     * (no process context to look up). Reused by [stat] (FileStat.symlinkTarget)
     * and [readSymlink].
     */
    private fun readSymlinkTarget(
        normalized: String,
        opener: KashProcess?,
    ): String {
        val fd =
            when (normalized) {
                "/stdin" -> 0
                "/stdout" -> 1
                "/stderr" -> 2
                else -> fdSlot(normalized) ?: return "/dev$normalized"
            }
        val ofd = opener?.fdTable?.get(fd)?.ofd
        return fdLinkTargetForOfd(ofd, fd)
    }

    override fun readSymlink(path: String): String = readSymlink(path, opener = null)

    override fun readSymlink(
        path: String,
        opener: KashProcess?,
    ): String {
        val p = Paths.normalize(path)
        if (p != "/stdin" && p != "/stdout" && p != "/stderr" && fdSlot(p) == null) {
            // Real Linux `readlink` returns EINVAL for non-symlinks. The
            // FS interface uses FileNotFound to signal both "absent" and
            // "wrong type" (matches the contract throughout DevFs).
            throw FileNotFound("/dev$p")
        }
        return readSymlinkTarget(p, opener)
    }

    // For magic-symlink slots ([readSymlink] handles those above), statLink
    // is now the type-preserving SYMLINK shape from [stat]. For non-fd paths
    // DevFs has no symlinks, so statLink == stat. The override exists to
    // thread `opener` through — without it the FileSystem default
    // delegates to statLink(path) → stat(path) → stat(path, null), which
    // strips per-opener context.
    override fun statLink(
        path: String,
        opener: KashProcess?,
    ): FileStat = stat(path, opener)

    override fun sourceNoFollow(
        path: String,
        opener: KashProcess?,
    ): SuspendSource = source(path, opener)

    override fun sinkNoFollow(
        path: String,
        append: Boolean,
        opener: KashProcess?,
    ): SuspendSink = sink(path, append, mode = 0b110_110_110, opener = opener)

    override fun openHandle(
        path: String,
        accessMode: AccessMode,
        opener: KashProcess?,
    ): OpenFileDescription? {
        val p = Paths.normalize(path)
        return when (p) {
            "/null" -> {
                OpenFileDescription(
                    source = if (accessMode != AccessMode.WRONLY) EmptySuspendSource else null,
                    sink = if (accessMode != AccessMode.RDONLY) NullSuspendSink else null,
                    accessMode = accessMode,
                    path = "/dev/null",
                    isTty = false,
                    owning = false,
                )
            }

            "/tty" -> {
                openTty(accessMode, opener) ?: throw FileNotFound("/dev/tty")
            }

            "/stdin" -> {
                openFd(0, accessMode, "/dev/stdin", opener)
            }

            "/stdout" -> {
                openFd(1, accessMode, "/dev/stdout", opener)
            }

            "/stderr" -> {
                openFd(2, accessMode, "/dev/stderr", opener)
            }

            "/zero" -> {
                // Linux /dev/zero accepts writes (silently discards),
                // matching /dev/null. Mirror that: writes route to
                // NullSuspendSink so `> /dev/zero` doesn't error.
                OpenFileDescription(
                    source = if (accessMode != AccessMode.WRONLY) ZeroSuspendSource else null,
                    sink = if (accessMode != AccessMode.RDONLY) NullSuspendSink else null,
                    accessMode = accessMode,
                    path = "/dev/zero",
                    isTty = false,
                    owning = false,
                )
            }

            "/random", "/urandom" -> {
                // Reads pull from CRNG; writes are rejected (real
                // /dev/random write is CAP_SYS_ADMIN-gated entropy
                // mixing — no analog here).
                if (accessMode != AccessMode.RDONLY) throw FileNotFound("/dev$p")
                OpenFileDescription(
                    source = RandomSuspendSource(),
                    accessMode = AccessMode.RDONLY,
                    path = "/dev$p",
                    isTty = false,
                    owning = false,
                )
            }

            else -> {
                val n = fdSlot(p) ?: return null
                openFd(n, accessMode, "/dev/fd/$n", opener)
            }
        }
    }

    /**
     * Resolve the opener's controlling tty through its session, build a
     * fresh OFD pointing at the same underlying device. Returns null —
     * which [openHandle] converts to [FileNotFound] — when:
     *   - no opener supplied
     *   - opener's session not in the machine's session table
     *   - the session has no controlling tty
     */
    private fun openTty(
        accessMode: AccessMode,
        opener: KashProcess?,
    ): OpenFileDescription? {
        val tty: ControllingTty = opener?.session()?.controllingTty ?: return null
        return OpenFileDescription(
            source = if (accessMode != AccessMode.WRONLY) tty.source else null,
            sink = if (accessMode != AccessMode.RDONLY) tty.sink else null,
            accessMode = accessMode,
            path = "/dev/tty",
            isTty = true,
            owning = false,
            terminalControl = tty.terminalControl,
        )
    }

    private fun openFd(
        fd: Int,
        accessMode: AccessMode,
        reportedPath: String,
        opener: KashProcess?,
    ): OpenFileDescription {
        // Report the fully-qualified /dev/... path in errors, not the
        // mount-relative form (the FS-layer sees "/stdin" but the user
        // typed "/dev/stdin"). Without this the error reads
        // `no such file or directory: /stdin` which is confusing.
        val target = opener ?: throw FileNotFound(reportedPath)
        return dupFromFdTable(target, fd, accessMode, reportedPath)
    }

    /** Parse "/fd/<N>" → N, or null if [normalized] isn't that shape. */
    private fun fdSlot(normalized: String): Int? {
        if (!normalized.startsWith("/fd/")) return null
        val rest = normalized.removePrefix("/fd/")
        if (rest.isEmpty() || rest.any { !it.isDigit() }) return null
        return rest.toIntOrNull()
    }
}

/**
 * Pseudo-infinite [SuspendSource] yielding random bytes from
 * [kotlin.random.Random]. Backs `/dev/random` and `/dev/urandom` —
 * modeled identically because Linux ≥ 5.6 treats them the same.
 *
 * `readAtMostTo` honors the caller's `byteCount` request up to
 * [MAX_CHUNK] (4 KiB, matching `getrandom(2)`'s default short-read
 * threshold). Never returns EOF.
 *
 * `kotlin.random.Random.Default` is not cryptographically secure on
 * all platforms — for kash's purposes (script-level "give me some
 * bytes") this is fine; cryptographic use cases should reach for a
 * dedicated CSPRNG anyway.
 */
private class RandomSuspendSource(
    private val random: Random = Random.Default,
) : SuspendSource {
    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (byteCount <= 0L) return 0L
        val n = byteCount.coerceAtMost(MAX_CHUNK).toInt()
        sink.write(random.nextBytes(n))
        return n.toLong()
    }

    override fun close() {}

    companion object {
        const val MAX_CHUNK: Long = 4096L
    }
}

/**
 * Pseudo-infinite [SuspendSource] yielding zero bytes. Backs
 * `/dev/zero`. Same MAX_CHUNK as [RandomSuspendSource] so streaming
 * consumers (`head -c N /dev/zero`, `dd if=/dev/zero`) see the same
 * short-read shape. `ByteArray(n)` is zero-filled by the platform —
 * no separate fill loop needed.
 */
private object ZeroSuspendSource : SuspendSource {
    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (byteCount <= 0L) return 0L
        val n = byteCount.coerceAtMost(MAX_CHUNK).toInt()
        sink.write(ByteArray(n))
        return n.toLong()
    }

    override fun close() {}

    private const val MAX_CHUNK: Long = 4096L
}
