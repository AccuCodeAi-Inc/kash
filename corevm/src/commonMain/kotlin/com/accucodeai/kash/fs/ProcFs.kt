package com.accucodeai.kash.fs

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.Disposition
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.ProcessState
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.signal.toSigmaskHex
import kotlinx.io.Buffer

/**
 * Synthetic, read-only [FileSystem] backing the Linux `/proc` tree. Mounted
 * at `/proc` by [installSystemBin]. Formats match what real Linux emits
 * (`man 5 proc` + the kernel sources cited per entry) so parsers in the
 * wild — procps, awk one-liners, `[ -p /proc/self/fd/1 ]` — work without
 * modification.
 *
 * Per-process:
 *  - **`/proc/<pid>/cmdline`** — NUL-separated argv with trailing NUL.
 *  - **`/proc/<pid>/environ`** — NUL-separated `KEY=VALUE`, trailing NUL.
 *  - **`/proc/<pid>/cwd`** — symlink to the process's current directory.
 *  - **`/proc/<pid>/exe`** — symlink to `/usr/bin/<commandName>` when the
 *    [com.accucodeai.kash.api.CommandSpec] is registered, else the bare
 *    command name.
 *  - **`/proc/<pid>/fd/`** — directory listing open fd slot numbers.
 *  - **`/proc/<pid>/fd/<N>`** — magic symlink. `readlink` returns the
 *    OFD's recorded `path`, `/dev/tty` for tty OFDs, or `pipe:[N]` for
 *    anonymous streams — matching the Linux shape scripts gate on
 *    (`case $(readlink /proc/self/fd/0) in /dev/tty*) …`). `open()`
 *    duplicates the entry: a fresh OFD (refcount 1, `owning=false`)
 *    sharing the original's source/sink.
 *  - **`/proc/<pid>/status`** — `Label:\t<value>\n` per
 *    `fs/proc/array.c:proc_pid_status()`. Fields populated from
 *    [KashProcess] (pid, ppid, state, uids, signal masks, fd count).
 *    Vm* / context-switch fields are absent — no memory or scheduler
 *    model.
 *  - **`/proc/<pid>/stat`** — single line of 52 space-separated fields
 *    per `man 5 proc`. Field 2 `(comm)` is parenthesized but unescaped
 *    (procps splits on the last `)`).
 *  - **`/proc/<pid>/maps`** — single synthetic `[heap]` line so
 *    `grep '\[heap\]' /proc/self/maps` finds something. No address-
 *    space model.
 *
 * `/proc/self` is the magic symlink resolving per-call to
 * `/proc/<opener.pid>`, like Linux's `tty_open_current_tty()` keying
 * off `current`.
 *
 * Machine-wide:
 *  - **`/proc/meminfo`** — all values 0 kB, kernel format
 *    `%-15s %8lu kB\n`. Scripts sizing on `MemTotal` should treat 0 as
 *    "unknown" and degrade.
 *  - **`/proc/cpuinfo`** — one synthetic processor block.
 *    `grep -c ^processor` reports 1, so `nproc`-via-cpuinfo fan-out
 *    runs one worker (honest: kash is one Kotlin VM).
 *  - **`/proc/stat`** (machine-wide — *not* `/proc/<pid>/stat`) —
 *    aggregate `cpu  0 0 …`, `btime <epoch>`, `processes`,
 *    `procs_running`. `btime` from [KashMachine.bootEpochSeconds].
 *  - **`/proc/uptime`** — two centisecond floats:
 *    `<uptime>.00 <idle>.00\n`. Uptime = `now - bootEpochSeconds`;
 *    idle mirrors uptime.
 *  - **`/proc/loadavg`** — `0.00 0.00 0.00 <running>/<total> <last-pid>\n`.
 *    No EWMA load model — zeros are honest.
 */
public class ProcFs(
    /**
     * Lazy process-table view: `pid → [KashProcess]`. Supplier (not
     * direct reference) because [installSystemBin] builds the FS
     * *before* the table exists — the machine that owns the table
     * wraps this FS, not the other way around. The supplier closes
     * over a `var` (or a reference into `machine.processTable`) the
     * caller populates after the machine is constructed.
     *
     * Narrower than holding the whole [com.accucodeai.kash.api
     * .KashMachine]: ProcFs only needs to enumerate live pids and
     * resolve each to a [KashProcess]. Anything else it needs (e.g.
     * the registry, for `/proc/<pid>/exe`) is reachable via
     * `process.machine.*` once the process is in hand. This mirrors
     * the Plan-9 / Linux model where procfs reads from a per-namespace
     * process table without depending on the kernel struct.
     *
     * Returns an empty map in headless/test setups; lookups then
     * report empty `/proc`.
     */
    private val processes: () -> Map<Int, KashProcess>,
) : FileSystem {
    private val processTable: Map<Int, KashProcess>
        get() = processes()

    override fun exists(path: String): Boolean = exists(path, opener = null)

    override fun exists(
        path: String,
        opener: KashProcess?,
    ): Boolean {
        val p = Paths.normalize(path)
        return when {
            p == "/" -> true
            p == "/self" -> true
            else -> resolveProcPath(p, opener) != null
        }
    }

    override fun isDirectory(path: String): Boolean = isDirectory(path, opener = null)

    override fun isDirectory(
        path: String,
        opener: KashProcess?,
    ): Boolean {
        val p = Paths.normalize(path)
        if (p == "/") return true
        val r = resolveProcPath(p, opener) ?: return false
        return r is ProcEntry.PidDir || r is ProcEntry.FdDir
    }

    override fun source(path: String): SuspendSource = source(path, opener = null)

    override fun source(
        path: String,
        opener: KashProcess?,
    ): SuspendSource {
        val p = Paths.normalize(path)
        val entry = resolveProcPath(p, opener) ?: throw FileNotFound(path)
        // /proc/<pid>/fd/N is a magic symlink — the dup-from-fd-table
        // semantics live on openHandle. Mirror them here for callers
        // (like `cat /proc/self/fd/N`) that hit fs.source directly.
        if (entry is ProcEntry.FdEntry) {
            val target = processTable[entry.pid] ?: throw FileNotFound(path)
            return dupFromFdTable(target, entry.fd, AccessMode.RDONLY, "/proc/${entry.pid}/fd/${entry.fd}")
                .source ?: throw FileNotFound(path)
        }
        return entry.toSource() ?: throw FileNotFound(path)
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = throw ReadOnlyMountException(path)

    override fun mkdirs(
        path: String,
        mode: Int,
    ): Unit = throw ReadOnlyMountException(path)

    override fun remove(path: String): Unit = throw ReadOnlyMountException(path)

    override fun list(path: String): List<String> = list(path, opener = null)

    override fun list(
        path: String,
        opener: KashProcess?,
    ): List<String> {
        val p = Paths.normalize(path)
        if (p == "/") {
            val pids = processTable.keys.sorted().map { it.toString() }
            return (pids + "self" + "meminfo" + "cpuinfo" + "stat" + "uptime" + "loadavg").sorted()
        }
        val r = resolveProcPath(p, opener) ?: throw FileNotFound(path)
        return when (r) {
            is ProcEntry.PidDir -> {
                listOf("cmdline", "environ", "cwd", "exe", "fd", "status", "stat", "maps")
            }

            is ProcEntry.FdDir -> {
                processTable[r.pid]
                    ?.fdTable
                    ?.keys
                    ?.sorted()
                    ?.map { it.toString() }
                    ?: emptyList()
            }

            else -> {
                throw NotADirectory(path)
            }
        }
    }

    override fun stat(path: String): FileStat = stat(path, opener = null)

    override fun stat(
        path: String,
        opener: KashProcess?,
    ): FileStat {
        val p = Paths.normalize(path)
        if (p == "/") {
            return FileStat(
                path = "/",
                type = FileType.DIRECTORY,
                size = 0L,
                mtimeEpochSeconds = 0L,
                mode = 0b101_101_101, // 0o555
            )
        }
        val r = resolveProcPath(p, opener) ?: throw FileNotFound(path)
        return when (r) {
            is ProcEntry.PidDir -> {
                FileStat(
                    path = p,
                    type = FileType.DIRECTORY,
                    size = 0L,
                    mtimeEpochSeconds = 0L,
                    mode = 0b101_101_101,
                )
            }

            is ProcEntry.FdDir -> {
                FileStat(
                    path = p,
                    type = FileType.DIRECTORY,
                    size = 0L,
                    mtimeEpochSeconds = 0L,
                    mode = 0b101_000_000, // r-x for owner; Linux's /proc/<pid>/fd is 0o500
                )
            }

            is ProcEntry.FdEntry -> {
                FileStat(
                    path = p,
                    type = FileType.SYMLINK,
                    size = 0L,
                    mtimeEpochSeconds = 0L,
                    mode = 0b111_000_000, // rwx for owner — Linux convention
                    symlinkTarget = fdLinkTarget(r),
                )
            }

            is ProcEntry.SelfSymlink -> {
                FileStat(
                    path = p,
                    type = FileType.SYMLINK,
                    size = 0L,
                    mtimeEpochSeconds = 0L,
                    mode = 0b111_111_111,
                    // /proc/self resolves per-process: when opener is
                    // provided it points at the calling pid; otherwise we
                    // fall back to the lowest registered pid (typically
                    // init) so symlink-chain resolvers in opener-less
                    // contexts (FS-only tests, stream API) still have a
                    // stable answer.
                    symlinkTarget = resolveSelfTarget(opener),
                )
            }

            is ProcEntry.CwdSymlink -> {
                FileStat(
                    path = p,
                    type = FileType.SYMLINK,
                    size = 0L,
                    mtimeEpochSeconds = 0L,
                    mode = 0b111_111_111,
                    symlinkTarget = r.target,
                )
            }

            is ProcEntry.ExeSymlink -> {
                FileStat(
                    path = p,
                    type = FileType.SYMLINK,
                    size = 0L,
                    mtimeEpochSeconds = 0L,
                    mode = 0b111_111_111,
                    symlinkTarget = r.target,
                )
            }

            is ProcEntry.RegularRead -> {
                FileStat(
                    path = p,
                    type = FileType.REGULAR,
                    size = r.bytes.size.toLong(),
                    mtimeEpochSeconds = 0L,
                    mode = 0b100_100_100, // 0o444 — read-only
                )
            }
        }
    }

    override fun statLink(path: String): FileStat = stat(path)

    override fun statLink(
        path: String,
        opener: KashProcess?,
    ): FileStat = stat(path, opener)

    override fun readSymlink(path: String): String = readSymlink(path, opener = null)

    override fun readSymlink(
        path: String,
        opener: KashProcess?,
    ): String {
        val p = Paths.normalize(path)
        val r = resolveProcPath(p, opener) ?: throw FileNotFound(path)
        return when (r) {
            is ProcEntry.SelfSymlink -> resolveSelfTarget(opener)
            is ProcEntry.CwdSymlink -> r.target
            is ProcEntry.ExeSymlink -> r.target
            is ProcEntry.FdEntry -> fdLinkTarget(r)
            else -> throw NotASymlink(path)
        }
    }

    /**
     * Linux's `readlink /proc/<pid>/fd/N` returns the underlying file's
     * path for regular files, a `pipe:[<inum>]` / `socket:[<inum>]`
     * placeholder for anonymous IPC, or `/dev/pts/N` / `/dev/tty` for
     * terminals. Scripts gate on these — `[ -p /proc/self/fd/1 ]` and
     * `case $(readlink /proc/self/fd/0) in /dev/tty*) …` are common
     * "is my IO a pipe or a terminal?" idioms.
     *
     * kash heuristics:
     *  - OFD's recorded `path` if present → use verbatim.
     *  - `isTty=true` → `/dev/tty` (the path users hit interactively,
     *    matches Linux for fd-on-controlling-tty cases).
     *  - OFD's `pipeInode` set → `pipe:[<inode>]`. Same pipe seen on
     *    different fds (or different dups across fork) reports the same
     *    inode; different pipes get distinct inodes. Matches Linux.
     *  - otherwise → `anon_inode:[fd-<N>]` placeholder (an anonymous OFD
     *    with no path, no tty, and no pipe — likely a capture buffer).
     */
    private fun fdLinkTarget(entry: ProcEntry.FdEntry): String =
        fdLinkTargetForOfd(
            processTable[entry.pid]?.fdTable?.get(entry.fd)?.ofd,
            entry.fd,
        )

    /**
     * `/proc/self` target. When [opener] is non-null, points at the
     * calling process's pid — the POSIX semantic, mirroring kernel
     * `tty_open_current_tty()` reading `current`. When null (e.g.
     * stream-API readers without process context), falls back to the
     * lowest registered pid so symlink-chain resolvers still have a
     * stable answer. Returns "/proc/1" against an empty table.
     */
    private fun resolveSelfTarget(opener: KashProcess?): String {
        val pid = opener?.pid ?: processTable.keys.minOrNull() ?: 1
        return "/proc/$pid"
    }

    override fun openHandle(
        path: String,
        accessMode: AccessMode,
        opener: KashProcess?,
    ): OpenFileDescription? {
        val p = Paths.normalize(path)
        // `/proc/<pid>/fd/N` honors any access mode compatible with the
        // underlying OFD — that's the whole point of the magic-symlink
        // dup semantics. Resolve first; the rest of this method (the
        // RegularRead read-only surface) keys off the entry type.
        val entryEarly = resolveProcPath(p, opener)
        if (entryEarly is ProcEntry.FdEntry) {
            val target = processTable[entryEarly.pid] ?: throw FileNotFound(path)
            val reported = "/proc/${entryEarly.pid}/fd/${entryEarly.fd}"
            return dupFromFdTable(target, entryEarly.fd, accessMode, reported)
        }
        if (accessMode != AccessMode.RDONLY) return null
        // /self/X gets substituted to /<opener.pid>/X inside
        // resolveProcPath when opener is present. Compute the path the
        // returned OFD should report (for `tty(1)`-style introspection)
        // so consumers see the resolved /proc/<pid>/X rather than
        // /proc/self/X.
        val reportedPath =
            when {
                opener != null && (p == "/self" || p.startsWith("/self/")) -> {
                    "/proc/${opener.pid}" + p.removePrefix("/self")
                }

                else -> {
                    "/proc$p"
                }
            }
        val entry = resolveProcPath(p, opener) ?: return null
        return when (entry) {
            is ProcEntry.RegularRead -> {
                OpenFileDescription(
                    source = Buffer().apply { write(entry.bytes) }.asSuspendSource(),
                    accessMode = AccessMode.RDONLY,
                    path = reportedPath,
                    owning = false,
                )
            }

            // Symlinks/dirs aren't read-as-files; let the caller fall
            // back to FS lookup if they want stat/readlink.
            else -> {
                null
            }
        }
    }

    // -------- Internal resolution --------

    private sealed interface ProcEntry {
        data object SelfSymlink : ProcEntry

        data class PidDir(
            val pid: Int,
        ) : ProcEntry

        data class RegularRead(
            val bytes: ByteArray,
        ) : ProcEntry

        data class CwdSymlink(
            val target: String,
        ) : ProcEntry

        data class ExeSymlink(
            val target: String,
        ) : ProcEntry

        data class FdDir(
            val pid: Int,
        ) : ProcEntry

        data class FdEntry(
            val pid: Int,
            val fd: Int,
        ) : ProcEntry
    }

    private fun ProcEntry.toSource(): SuspendSource? =
        when (this) {
            is ProcEntry.RegularRead -> Buffer().apply { write(bytes) }.asSuspendSource()
            else -> null
        }

    /**
     * Resolve [normalizedPath] (already passed through `Paths.normalize`)
     * to a [ProcEntry]. Returns null for missing paths. Does not handle
     * `/self` resolution itself — that requires opener context and lives
     * in [openHandle].
     */
    private fun resolveProcPath(
        normalizedPath: String,
        opener: KashProcess? = null,
    ): ProcEntry? {
        if (normalizedPath == "/self") return ProcEntry.SelfSymlink

        // Split: /<pid>/(cmdline|environ|cwd|exe)? or /<pid>
        val rest = normalizedPath.removePrefix("/")
        val segments = rest.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        // Machine-wide single-segment entries — checked before pid parse
        // so they take precedence over a literal pid named "meminfo"
        // (impossible anyway, but the order matters for clarity).
        if (segments.size == 1) {
            when (segments[0]) {
                "meminfo" -> return ProcEntry.RegularRead(formatMeminfo())
                "cpuinfo" -> return ProcEntry.RegularRead(formatCpuinfo())
                "stat" -> return ProcEntry.RegularRead(formatProcStat())
                "uptime" -> return ProcEntry.RegularRead(formatUptime())
                "loadavg" -> return ProcEntry.RegularRead(formatLoadavg())
            }
        }

        // /self/<entry>: with an opener, substitute the calling pid and
        // resolve normally (so cat /proc/self/cmdline returns the
        // caller's argv, not empty bytes). Without an opener, fall back
        // to empty/placeholder content — matches the FS-level shape so
        // `[ -L /proc/self/cwd ]` works, but content reads are empty.
        if (segments[0] == "self") {
            opener?.let { p ->
                val rewritten = "/${p.pid}/" + segments.drop(1).joinToString("/")
                return resolveProcPath(Paths.normalize(rewritten), opener = null)
            }
            return when (segments.size) {
                1 -> {
                    ProcEntry.SelfSymlink
                }

                2 -> {
                    when (segments[1]) {
                        "cmdline", "environ", "status", "stat", "maps" -> {
                            ProcEntry.RegularRead(ByteArray(0))
                        }

                        "cwd" -> {
                            ProcEntry.CwdSymlink("/")
                        }

                        "exe" -> {
                            ProcEntry.ExeSymlink("")
                        }

                        "fd" -> {
                            null
                        }

                        // opener-less /proc/self/fd has no useful surface
                        else -> {
                            null
                        }
                    }
                }

                else -> {
                    null
                }
            }
        }

        val pid = segments[0].toIntOrNull() ?: return null
        val process = processTable[pid] ?: return null
        return when (segments.size) {
            1 -> {
                ProcEntry.PidDir(pid)
            }

            2 -> {
                when (segments[1]) {
                    "cmdline" -> ProcEntry.RegularRead(formatCmdline(process))
                    "environ" -> ProcEntry.RegularRead(formatEnviron(process))
                    "status" -> ProcEntry.RegularRead(formatStatus(process))
                    "stat" -> ProcEntry.RegularRead(formatStat(process))
                    "maps" -> ProcEntry.RegularRead(formatMaps(process))
                    "cwd" -> ProcEntry.CwdSymlink(process.cwd)
                    "exe" -> ProcEntry.ExeSymlink(formatExe(process))
                    "fd" -> ProcEntry.FdDir(pid)
                    else -> null
                }
            }

            3 -> {
                if (segments[1] != "fd") return null
                val fd = segments[2].toIntOrNull() ?: return null
                if (!process.fdTable.containsKey(fd)) return null
                ProcEntry.FdEntry(pid, fd)
            }

            else -> {
                null
            }
        }
    }

    private fun formatCmdline(p: KashProcess): ByteArray {
        if (p.argv.isEmpty()) return ByteArray(0)
        // Linux convention (`man 5 proc`): NUL-separated argv with a
        // trailing NUL after the last arg. Scripts use
        // `tr '\0' ' ' </proc/$$/cmdline` for a human-readable form.
        return p.argv
            .joinToString(separator = "\u0000", postfix = "\u0000")
            .encodeToByteArray()
    }

    private fun formatEnviron(p: KashProcess): ByteArray {
        if (p.env.isEmpty()) return ByteArray(0)
        // Linux convention: NUL-separated KEY=VALUE pairs, trailing NUL.
        return p.env.entries
            .joinToString(separator = "\u0000", postfix = "\u0000") { (k, v) -> "$k=$v" }
            .encodeToByteArray()
    }

    private fun formatExe(p: KashProcess): String {
        val name = p.commandName.ifEmpty { return "" }
        // Best-effort: if the command is registered on the process's
        // machine, point at its synthetic /usr/bin entry — the closest
        // analog to the real Linux "path of the binary that was exec'd."
        // ProcFs avoids depending on the machine directly; we navigate
        // via the in-hand process instead. Costs nothing because we
        // already needed the process to read its commandName.
        return if (p.machine.registry[name] != null) "/usr/bin/$name" else name
    }

    // -------- /proc/<pid>/status, /proc/<pid>/stat, /proc/<pid>/maps --------

    /** Linux's `TASK_COMM_LEN - 1` — comm field truncates to 15 chars. */
    private fun commName(p: KashProcess): String {
        val raw = p.commandName.ifEmpty { p.argv.firstOrNull() ?: "" }
        return if (raw.length > 15) raw.substring(0, 15) else raw
    }

    private fun stateLetter(s: ProcessState): String =
        when (s) {
            ProcessState.RUNNING -> "R"
            ProcessState.STOPPED -> "T"
            ProcessState.ZOMBIE -> "Z"
        }

    private fun stateLabel(s: ProcessState): String =
        when (s) {
            ProcessState.RUNNING -> "R (running)"
            ProcessState.STOPPED -> "T (stopped)"
            ProcessState.ZOMBIE -> "Z (zombie)"
        }

    /** Octal, zero-padded to 4 digits — matches kernel `%#04o`-ish format. */
    private fun umaskOctal(umask: Int): String = umask.toString(8).padStart(4, '0')

    private fun formatStatus(p: KashProcess): ByteArray {
        val ignored = p.dispositions.filterValues { it is Disposition.Ignore }.keys
        val caught = p.dispositions.filterValues { it is Disposition.Handler }.keys
        // FDSize on Linux is round-up to next multiple of 64. Smallest is 64.
        val fdSize = ((p.fdTable.size / 64) + 1) * 64
        val groups = p.supplementaryGids.sorted().joinToString(" ", postfix = " ")
        val sb = StringBuilder()
        sb.append("Name:\t").append(commName(p)).append('\n')
        sb.append("Umask:\t").append(umaskOctal(p.umask)).append('\n')
        sb.append("State:\t").append(stateLabel(p.state)).append('\n')
        sb.append("Tgid:\t").append(p.pid).append('\n')
        sb.append("Ngid:\t0\n")
        sb.append("Pid:\t").append(p.pid).append('\n')
        sb.append("PPid:\t").append(p.ppid ?: 0).append('\n')
        sb.append("TracerPid:\t0\n")
        sb
            .append("Uid:\t")
            .append(p.realUid)
            .append('\t')
            .append(p.effectiveUid)
            .append('\t')
            .append(p.savedUid)
            .append('\t')
            .append(p.effectiveUid)
            .append('\n')
        sb
            .append("Gid:\t")
            .append(p.realGid)
            .append('\t')
            .append(p.effectiveGid)
            .append('\t')
            .append(p.savedGid)
            .append('\t')
            .append(p.effectiveGid)
            .append('\n')
        sb.append("FDSize:\t").append(fdSize).append('\n')
        sb.append("Groups:\t").append(groups).append('\n')
        sb.append("Threads:\t1\n")
        sb.append("SigQ:\t0/0\n")
        sb.append("SigPnd:\t").append(p.pendingSignals.toSigmaskHex()).append('\n')
        sb.append("ShdPnd:\t0000000000000000\n")
        sb.append("SigBlk:\t").append(p.signalMask.toSigmaskHex()).append('\n')
        sb.append("SigIgn:\t").append(ignored.toSigmaskHex()).append('\n')
        sb.append("SigCgt:\t").append(caught.toSigmaskHex()).append('\n')
        sb.append("voluntary_ctxt_switches:\t0\n")
        sb.append("nonvoluntary_ctxt_switches:\t0\n")
        return sb.toString().encodeToByteArray()
    }

    /**
     * Single space-separated line, 52 fields, trailing `\n`. Field 2
     * `(comm)` is parenthesized but NOT escaped — procps parses by
     * finding the LAST `)` in the line, so this matches the in-the-wild
     * contract.
     *
     * HZ=100 assumed: utime/stime/starttime are clock ticks, so
     * `ticks = micros / 10_000`. starttime emits 0 — kash has no
     * machine-boot reference; non-zero would lie about uptime
     * arithmetic.
     */
    private fun formatStat(p: KashProcess): ByteArray {
        val utime = p.userCpuMicros / 10_000
        val stime = p.sysCpuMicros / 10_000
        val fields =
            listOf(
                p.pid.toString(), // 1
                "(${commName(p)})", // 2
                stateLetter(p.state), // 3
                (p.ppid ?: 0).toString(), // 4
                p.pgid.toString(), // 5
                p.sid.toString(), // 6
                "0", // 7  tty_nr
                "-1", // 8  tpgid
                "0", // 9  flags
                "0",
                "0",
                "0",
                "0", // 10-13 minflt/cminflt/majflt/cmajflt
                utime.toString(), // 14 utime
                stime.toString(), // 15 stime
                "0",
                "0", // 16-17 cutime/cstime
                "20", // 18 priority (Linux default for nice=0)
                p.niceValue.toString(), // 19 nice
                "1", // 20 num_threads
                "0", // 21 itrealvalue
                "0", // 22 starttime (see comment above)
                "0",
                "0", // 23-24 vsize/rss
                Long.MAX_VALUE.toString(), // 25 rsslim
                "0",
                "0",
                "0",
                "0",
                "0",
                "0", // 26-31 mm addresses
                "0",
                "0",
                "0",
                "0",
                "0",
                "0", // 32-37 legacy signal fields
                "0", // 38 processor
                "0", // 39 rt_priority
                "0", // 40 policy
                "0", // 41 delayacct_blkio_ticks
                "0",
                "0", // 42-43 guest_time/cguest_time
                "0",
                "0",
                "0",
                "0",
                "0",
                "0",
                "0",
                "0", // 44-51 mm boundaries
                "0", // 52 exit_code
            )
        return (fields.joinToString(" ") + "\n").encodeToByteArray()
    }

    private fun formatMaps(
        @Suppress("UNUSED_PARAMETER") p: KashProcess,
    ): ByteArray {
        // Single synthetic [heap] line. Kernel format:
        //   %08lx-%08lx %c%c%c%c %08lx %02x:%02x %lu %*s%s\n
        // with the pathname column padded to ~73 via seq_pad. The prefix
        // here is exactly 73 chars including trailing spaces, so the
        // bracketed pathname lands at the conventional column.
        // kash has no address-space model — this exists so
        // `grep '\[heap\]' /proc/self/maps` finds something.
        val prefix = "00000000-00000000 rw-p 00000000 00:00 0"
        val pad = " ".repeat(73 - prefix.length)
        return "$prefix$pad[heap]\n".encodeToByteArray()
    }

    // -------- /proc/meminfo, /proc/cpuinfo --------

    /**
     * Kernel format: `"%-15s %8lu kB\n"` — label left-padded to 15
     * chars, value right-justified in an 8-char field, literal ` kB`
     * (lowercase k, capital B). All zeros: kash has no host-memory
     * model. Scripts probing for sizing should treat MemTotal=0 as
     * "unknown" and degrade rather than allocate based on the value.
     */
    private fun formatMeminfo(): ByteArray {
        val labels =
            listOf(
                "MemTotal:",
                "MemFree:",
                "MemAvailable:",
                "Buffers:",
                "Cached:",
                "SwapCached:",
                "Active:",
                "Inactive:",
                "Active(anon):",
                "Inactive(anon):",
                "Active(file):",
                "Inactive(file):",
                "Unevictable:",
                "Mlocked:",
                "SwapTotal:",
                "SwapFree:",
                "Dirty:",
                "Writeback:",
                "AnonPages:",
                "Mapped:",
                "Shmem:",
                "Slab:",
                "SReclaimable:",
                "SUnreclaim:",
                "KernelStack:",
                "PageTables:",
                "CommitLimit:",
                "Committed_AS:",
                "VmallocTotal:",
                "VmallocUsed:",
            )
        val sb = StringBuilder()
        for (l in labels) {
            sb
                .append(l.padEnd(15))
                .append(' ')
                .append("0".padStart(8))
                .append(" kB\n")
        }
        return sb.toString().encodeToByteArray()
    }

    /**
     * One synthetic processor block. `grep -c ^processor` reports 1.
     * Honest: kash is one Kotlin VM, fan-out by that idiom correctly
     * runs one worker. Kernel emits a blank line after each block
     * (including the last), so we match.
     */
    private fun formatCpuinfo(): ByteArray =
        buildString {
            append("processor\t: 0\n")
            append("vendor_id\t: kash\n")
            append("cpu family\t: 0\n")
            append("model\t\t: 0\n")
            append("model name\t: kash synthetic CPU\n")
            append("stepping\t: 0\n")
            append("cpu MHz\t\t: 0.000\n")
            append("cache size\t: 0 KB\n")
            append("physical id\t: 0\n")
            append("siblings\t: 1\n")
            append("core id\t\t: 0\n")
            append("cpu cores\t: 1\n")
            append("apicid\t\t: 0\n")
            append("initial apicid\t: 0\n")
            append("fpu\t\t: yes\n")
            append("fpu_exception\t: yes\n")
            append("cpuid level\t: 0\n")
            append("wp\t\t: yes\n")
            append("flags\t\t: \n")
            append("bugs\t\t: \n")
            append("bogomips\t: 0.00\n")
            append("clflush size\t: 64\n")
            append("cache_alignment\t: 64\n")
            append("address sizes\t: 64 bits physical, 64 bits virtual\n")
            append("power management:\n")
            append("\n")
        }.encodeToByteArray()

    // -------- /proc/stat, /proc/uptime, /proc/loadavg --------

    /**
     * Reach the [KashMachine] indirectly via any live process — same
     * pattern as [formatExe] dereferencing `process.machine.registry`.
     * Returns null only in headless setups (empty process table) where
     * machine-wide fields degrade to safe defaults.
     */
    private fun machineOrNull(): KashMachine? = processTable.values.firstOrNull()?.machine

    /**
     * `/proc/stat` — machine-wide CPU + boot accounting, per
     * `fs/proc/stat.c`. The double-space after `cpu` and the
     * `cpu0` summary line are kernel-traditional (procps tolerates
     * single space too, but we match the kernel).
     */
    private fun formatProcStat(): ByteArray {
        val machine = machineOrNull()
        val boot = machine?.bootEpochSeconds ?: 0L
        val totalProcs = (machine?.nextPid ?: 1) - 1
        val running = processTable.values.count { it.state == ProcessState.RUNNING }
        return buildString {
            append("cpu  0 0 0 0 0 0 0 0 0 0\n")
            append("cpu0 0 0 0 0 0 0 0 0 0 0\n")
            append("intr 0\n")
            append("ctxt 0\n")
            append("btime ").append(boot).append('\n')
            append("processes ").append(totalProcs).append('\n')
            append("procs_running ").append(running).append('\n')
            append("procs_blocked 0\n")
            append("softirq 0\n")
        }.encodeToByteArray()
    }

    /**
     * `/proc/uptime` — two floats `<uptime> <idle>` in seconds with
     * centisecond precision, per `fs/proc/uptime.c`. kash has no
     * per-CPU idle accounting; emit idle == uptime, the honest answer
     * for a single-CPU never-idle machine.
     */
    private fun formatUptime(): ByteArray {
        val machine = machineOrNull()
        val seconds = ((machine?.nowEpochSeconds?.invoke() ?: 0L) - (machine?.bootEpochSeconds ?: 0L)).coerceAtLeast(0L)
        val f = "$seconds.00"
        return "$f $f\n".encodeToByteArray()
    }

    /**
     * `/proc/loadavg` — five fields per `fs/proc/loadavg.c`:
     * `<1min> <5min> <15min> <runnable>/<total> <last-pid>`. We have
     * no EWMA load model — emit 0.00 across the board, honest.
     * `running`/`total` come from the process table; `last-pid` is
     * `nextPid - 1`, the most recently allocated pid (or 1 if none
     * yet allocated, matching Linux's `current->pid` at boot).
     */
    private fun formatLoadavg(): ByteArray {
        val machine = machineOrNull()
        val running = processTable.values.count { it.state == ProcessState.RUNNING }
        val total = processTable.size.coerceAtLeast(1)
        val lastPid = ((machine?.nextPid ?: 2) - 1).coerceAtLeast(1)
        return "0.00 0.00 0.00 $running/$total $lastPid\n".encodeToByteArray()
    }
}
