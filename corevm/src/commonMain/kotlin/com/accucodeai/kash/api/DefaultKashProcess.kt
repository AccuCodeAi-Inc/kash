package com.accucodeai.kash.api

import com.accucodeai.kash.api.signal.KashSignal

/**
 * Thrown by [KashProcess.fork] when the per-process `RLIMIT_NPROC` (or
 * other forthcoming per-process rlimit) would be exceeded by the new
 * child. Mirrors POSIX `fork(2)` returning -1 with `errno = EAGAIN`.
 *
 * Callers should translate this into bash's
 * `kash: fork: retry: Resource temporarily unavailable\n` stderr line
 * and surface exit status 1 for the offending statement, without
 * crashing the shell.
 */
public class ForkException(
    message: String,
) : RuntimeException(message)

/**
 * Construct a default [KashProcess] on [machine].
 *
 * [pid] is **required** — caller chooses whether to pass `1` (for an init-
 * like process), [KashMachine.allocatePid] (for an embedder-managed
 * process the VM doesn't fork), or any explicit value (tests).
 *
 * `fork()`-created processes do NOT go through this factory; the fork
 * path in [DefaultKashProcess.fork] handles pid allocation itself.
 */
public fun KashProcess(
    machine: KashMachine,
    pid: Int,
    umask: Int = 0b000_010_010,
    cwd: String = "/",
    env: MutableMap<String, String> = mutableMapOf(),
    ppid: Int? = null,
): KashProcess =
    DefaultKashProcess(
        machine = machine,
        umask = umask,
        cwd = cwd,
        env = env,
        pid = pid,
        ppid = ppid,
    )

internal class DefaultKashProcess(
    override val machine: KashMachine,
    override var umask: Int = 0b000_010_010,
    override var cwd: String = "/",
    override var rootDir: String = "/",
    override val env: MutableMap<String, String> = mutableMapOf(),
    override val fdTable: MutableMap<Int, FdTableEntry> = mutableMapOf(),
    override var pid: Int = 1,
    override var ppid: Int? = null,
    override var pgid: Int = 1,
    override var sid: Int = 1,
    override var realUid: Int = 0,
    override var effectiveUid: Int = 0,
    override var savedUid: Int = 0,
    override var realGid: Int = 0,
    override var effectiveGid: Int = 0,
    override var savedGid: Int = 0,
    override val supplementaryGids: MutableSet<Int> = mutableSetOf(),
    override val signalMask: MutableSet<KashSignal> = mutableSetOf(),
    override val pendingSignals: MutableSet<KashSignal> = mutableSetOf(),
    override val dispositions: MutableMap<KashSignal, Disposition> = mutableMapOf(),
    override val rlimits: MutableMap<RLimit, RLimitPair> = mutableMapOf(),
    override var niceValue: Int = 0,
    override var controllingTty: OpenFileDescription? = null,
    override var state: ProcessState = ProcessState.RUNNING,
    override var exitStatus: ExitStatus? = null,
    override var argv: List<String> = emptyList(),
    // Default: empty. Init sets "init", shell-spawn sites set "kash",
    // tool-spawn sites set the tool's name. An unset commandName falling
    // through to `ps` shows as `[unknown]` rather than masquerading as
    // the shell — useful for catching processes that escaped labeling.
    override var commandName: String = "",
    override val startTimeMillis: Long = 0L,
    override var userCpuMicros: Long = 0L,
    override var sysCpuMicros: Long = 0L,
    override val children: MutableList<KashProcess> = mutableListOf(),
) : KashProcess {
    override var attachmentSink: AttachmentSink? = null

    // Per-process FS facade: forwards every read-surface call to
    // [machine.fs] with `this` as the opener, so /proc/self and
    // /dev/tty resolve relative to the calling process. Cached
    // (lazy val) so process.fs returns the same instance each call —
    // identity stability matters for caching and ===-based assertions.
    override val fs: com.accucodeai.kash.fs.FileSystem by lazy {
        OpenerBoundFs(delegate = machine.fs, opener = this)
    }

    override fun fork(): KashProcess {
        // RLIMIT_NPROC enforcement: count machine-wide live processes
        // (real Linux counts per-UID; we have one UID today). If the
        // parent's NPROC soft limit is set and would be exceeded, fail
        // with the POSIX-equivalent EAGAIN. Subshells and tool spawns
        // both flow through here, so this single seam defends both.
        val nproc = rlimits[RLimit.NPROC]
        if (nproc != null && machine.processTable.size >= nproc.soft) {
            throw ForkException("retry: Resource temporarily unavailable")
        }
        // No locking: per-stage pipeline forking ([InterpreterPipelines.runPipelineCore]
        // pre-forks foreground multi-stage pipelines; [dispatchBackground]
        // pre-forks at `&` time) guarantees that no two coroutines write
        // to the same [KashProcess] concurrently, so iteration here can't
        // race a writer.
        val newFdTable: MutableMap<Int, FdTableEntry> = mutableMapOf()
        for ((fd, entry) in fdTable) {
            entry.ofd.retain()
            newFdTable[fd] = FdTableEntry(ofd = entry.ofd, closeOnExec = entry.closeOnExec)
        }
        // Allocate a fresh pid from the machine — POSIX `fork(2)`
        // returns a child with a distinct pid. Previously the comment
        // here said "caller allocates new pid"; in practice only
        // KashMachine.spawn did so, while Interpreter.forkSubshell
        // didn't — leaving subshell processes with pid == parent.pid
        // and breaking $$, $BASHPID, and `/proc/<subshell-pid>`.
        val newPid = machine.allocatePid()
        val child =
            DefaultKashProcess(
                machine = machine,
                umask = umask,
                cwd = cwd,
                rootDir = rootDir,
                env = env.toMutableMap(),
                fdTable = newFdTable,
                pid = newPid,
                ppid = pid,
                pgid = pgid,
                sid = sid,
                realUid = realUid,
                effectiveUid = effectiveUid,
                savedUid = savedUid,
                realGid = realGid,
                effectiveGid = effectiveGid,
                savedGid = savedGid,
                supplementaryGids = supplementaryGids.toMutableSet(),
                signalMask = signalMask.toMutableSet(),
                // pending signals: POSIX clears on fork.
                pendingSignals = mutableSetOf(),
                dispositions = dispositions.toMutableMap(),
                rlimits = rlimits.toMutableMap(),
                niceValue = niceValue,
                controllingTty = controllingTty,
                state = ProcessState.RUNNING,
                exitStatus = null,
                argv = argv.toList(),
                commandName = commandName,
                startTimeMillis = 0L, // caller stamps with now()
                userCpuMicros = 0L,
                sysCpuMicros = 0L,
                children = mutableListOf(),
            )
        // Register the child in the machine's process table — POSIX
        // semantics: a fork'd process is immediately visible to /proc,
        // ps, kill, etc. The lifetime owner (KashMachine.spawn for
        // tools, Interpreter.forkSubshell for subshells) is responsible
        // for unregistering via [KashMachine.unregisterProcess] on
        // teardown.
        machine.processTable[newPid] = child
        children += child
        return child
    }

    override fun execReset() {
        // Drop FD_CLOEXEC entries; release their OFDs.
        val toClose = fdTable.entries.filter { it.value.closeOnExec }.map { it.key }
        for (fd in toClose) {
            fdTable.remove(fd)?.ofd?.release()
        }
        // Handler → Default; Ignore stays Ignore.
        val it = dispositions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value is Disposition.Handler) e.setValue(Disposition.Default)
        }
        // TODO(phase 2+): reset alarms / POSIX timers.
    }
}
