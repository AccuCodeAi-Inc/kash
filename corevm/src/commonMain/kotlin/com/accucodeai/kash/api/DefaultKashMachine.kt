package com.accucodeai.kash.api

import com.accucodeai.kash.api.binfmt.BinfmtNativeReject
import com.accucodeai.kash.api.binfmt.BinfmtRegistry
import com.accucodeai.kash.api.binfmt.BinfmtShellConvention
import com.accucodeai.kash.api.binfmt.DefaultBinfmtRegistry
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.fs.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * Construct a default [KashMachine]. The host terminal (when present) is
 * attached per-call by the interpreter as fd 0/1/2 OFD metadata via
 * [installStdio] — the machine is no longer the owner.
 */
public fun KashMachine(
    fs: FileSystem,
    registry: CommandRegistry = CommandRegistry(emptyList()),
    bootEpochSeconds: Long = 0L,
    nowEpochSeconds: () -> Long = { bootEpochSeconds },
    clock: com.accucodeai.kash.api.clock.ShellClock = com.accucodeai.kash.api.clock.DefaultShellClock,
    networkPolicy: NetworkPolicy = NetworkPolicy.None,
): KashMachine = DefaultKashMachine(fs, registry, bootEpochSeconds, nowEpochSeconds, clock, networkPolicy)

internal class DefaultKashMachine(
    override val fs: FileSystem,
    override val registry: CommandRegistry,
    override val bootEpochSeconds: Long = 0L,
    override val nowEpochSeconds: () -> Long = { bootEpochSeconds },
    override val clock: com.accucodeai.kash.api.clock.ShellClock = com.accucodeai.kash.api.clock.DefaultShellClock,
    override val networkPolicy: NetworkPolicy = NetworkPolicy.None,
) : KashMachine {
    override val binfmt: BinfmtRegistry =
        DefaultBinfmtRegistry().apply {
            // Default chain mirroring Linux's `linux_binfmt` list:
            //  - native-reject (30): claim ELF/Mach-O/PE, refuse with a
            //    friendly message instead of dropping into the shell-script
            //    fallback that would mis-parse the binary's magic bytes.
            //  - shell-convention (1000): the universal terminator —
            //    shebang dispatched by basename through the request's
            //    utility runner, otherwise the bytes run as a kash shell
            //    script via the request's shell runner. NotMine when those
            //    runners are absent (headless / embedded use), so the rest
            //    of the chain still works.
            //
            // [BinfmtScript] (Linux-style full-path reexec) is available
            // as a class but not auto-registered; hosts that want strict
            // reexec opt in.
            register(BinfmtNativeReject())
            register(BinfmtShellConvention())
        }

    override var foregroundSignalReceiver: ((KashSignal) -> Unit)? = null
    override val sessionSignalReceivers: MutableMap<Int, (KashSignal) -> Unit> = mutableMapOf()

    override val processTable: MutableMap<Int, KashProcess> = mutableMapOf()
    override val processGroups: MutableMap<Int, MutableSet<Int>> = mutableMapOf()
    override var foregroundPgrp: Int? = null
    override val sessions: MutableMap<Int, Session> = mutableMapOf()
    override val snapshotSlots: MutableMap<Int, JsonElement> = mutableMapOf()
    override val liveSnapshotProviders: MutableMap<Int, () -> Unit> = mutableMapOf()

    // Starts at 1: first allocation produces pid 1 for init via
    // [ensureInit], and subsequent allocations 2, 3, … for spawned
    // processes. Long-lived sessions can boot init lazily; no out-of-band
    // pid management.
    //
    // Public-read-write via the interface so snapshot restore can seed
    // the allocator past captured pids; otherwise restore would re-mint
    // pid 2 for a brand-new spawn even though pid 2 is already an
    // (auto-reaped, slot-only) reference in the snapshot.
    override var nextPid: Int = 1

    /**
     * Per-spawn exit-completion handles. Lets [wait] suspend until the
     * child completes without polling. Kept off the [KashProcess] type
     * itself so the public interface doesn't carry coroutine machinery —
     * the VM owns lifecycle, the process is just state.
     */
    private val exitDeferreds: MutableMap<Int, CompletableDeferred<ExitStatus>> = mutableMapOf()

    /**
     * Serializes machine-wide mutations: [processTable], [exitDeferreds],
     * [nextPid], and (the parent-side of) `parent.children`. Without this,
     * two concurrent pipeline-stage coroutines each calling `spawn` on
     * their own (different) parent processes still both write into the
     * shared [processTable] / [exitDeferreds] maps and bump [nextPid] —
     * classic shared-state race. [KashProcess] state itself is single-
     * coroutine by construction (per-stage subshell forking; see
     * [com.accucodeai.kash.interpreter.InterpreterPipelines.runPipelineCore]
     * and `dispatchBackground`), so we don't lock at the process level —
     * only at the machine level where state is genuinely shared.
     */
    private val tableMutex = Mutex()

    override fun allocatePid(): Int = nextPid++

    override fun ensureInit(
        cwd: String,
        env: MutableMap<String, String>,
    ): KashProcess {
        processTable[1]?.let { return it }
        // Take pid 1 via the normal allocator. We expect nextPid == 1 here;
        // on a fresh machine that's the first allocation. If something else
        // already burned pid 1 we still want init at 1 (POSIX-faithful), so
        // construct it explicitly and bump nextPid past 1 if needed.
        val pid = if (nextPid <= 1) allocatePid() else 1
        if (nextPid <= 1) nextPid = 2
        val init =
            KashProcess(machine = this, pid = pid, cwd = cwd, env = env, ppid = null).apply {
                commandName = "init"
                argv = listOf("init")
                // Default RLIMIT_NPROC: well above any real shell's
                // high-water mark, well below what would OOM the JVM.
                // Inherited by every descendant via fork()'s rlimit
                // copy. Override with `ulimit -u N` from the shell.
                rlimits[RLimit.NPROC] = RLimitPair(soft = 4096L, hard = Long.MAX_VALUE)
            }
        processTable[1] = init
        return init
    }

    override fun unregisterProcess(pid: Int) {
        val process = processTable.remove(pid) ?: return
        for (entry in process.fdTable.values) entry.ofd.release()
        process.fdTable.clear()
        reparentChildrenToInit(process, dyingPid = pid)
        val parentPid = process.ppid
        if (parentPid != null) {
            // Non-iterating removal: a background pipeline driver running
            // concurrently with the foreground can mutate the parent's
            // children list (new fork add, sibling fork drop) under us.
            // `removeAll { ... }` uses `filterInPlace` which iterates with
            // an index and crashes when the list shrinks under it. A
            // direct `remove(element)` works on a single sweep.
            val parent = processTable[parentPid] ?: return
            val target = parent.children.firstOrNull { it.pid == pid } ?: return
            parent.children.remove(target)
        }
    }

    /**
     * Walk [dying]'s [KashProcess.children]. For each surviving child,
     * rewrite `ppid = 1` and move it onto init's children list. Clears
     * [dying]'s children list. No-op if init is missing or [dying] is init.
     */
    private fun reparentChildrenToInit(
        dying: KashProcess,
        dyingPid: Int,
    ) {
        if (dyingPid == 1) return
        val init = processTable[1] ?: return
        val survivors = dying.children.toList()
        if (survivors.isEmpty()) return
        for (orphan in survivors) {
            orphan.ppid = 1
            init.children += orphan
        }
        dying.children.clear()
    }

    override suspend fun spawn(
        parent: KashProcess,
        block: suspend (child: KashProcess) -> Int,
    ): SpawnResult {
        // [KashProcess.fork] now does pid allocation + processTable
        // insert + children link itself. spawn just records the
        // exit-completion handle for the future wait/reap.
        //
        // RLIMIT_NPROC: if fork throws, surface a failure-shaped
        // SpawnResult — pid=-1, deferred pre-completed with exit 1 —
        // so callers can detect via `sr.pid < 0` and skip the wait.
        val child: KashProcess
        val exitDeferred: CompletableDeferred<ExitStatus>
        try {
            tableMutex.withLock {
                child = parent.fork()
                exitDeferred = CompletableDeferred()
                exitDeferreds[child.pid] = exitDeferred
            }
        } catch (_: ForkException) {
            val failed = CompletableDeferred<ExitStatus>()
            failed.complete(ExitStatus.Exited(1))
            return SpawnResult(pid = -1, exit = failed)
        }

        val status =
            try {
                ExitStatus.Exited(block(child))
            } catch (ce: CancellationException) {
                // Coroutine cancellation: propagate to keep structured
                // concurrency intact. Don't synthesize an exit status here.
                throw ce
            } catch (t: Throwable) {
                // Any uncaught throw becomes a non-zero exit. Later phases
                // distinguish signal-killed (Signaled) here once the VM
                // routes signals through the child's signalChannel.
                // Surface the throwable on the child's stderr so the failure
                // isn't silent — without this, a tool that throws from a
                // constructor (e.g. a wasm "not available" stub) returns
                // exit 1 with no diagnostic, leaving callers blind.
                val sink = child.fdTable[2]?.ofd?.sink
                if (sink != null) {
                    val cls = t::class.simpleName ?: "Throwable"
                    val msg = t.message?.let { ": $it" } ?: ""
                    try {
                        sink.writeUtf8("kash: $cls$msg\n")
                    } catch (_: Throwable) {
                        // stderr write itself failed (closed pipe, etc.) —
                        // nothing left we can do.
                    }
                }
                ExitStatus.Exited(1)
            } finally {
                // Release the fdTable refs the fork retained — child's
                // OFDs go away. (CLOEXEC-or-not doesn't matter for an
                // exited process; everything closes at the kernel boundary.)
                for (entry in child.fdTable.values) entry.ofd.release()
                child.fdTable.clear()
            }

        child.exitStatus = status
        child.state = ProcessState.ZOMBIE
        exitDeferred.complete(status)
        // Init auto-reap: if the child's parent is init (pid 1), reap
        // immediately — that's what a real init does in its SIGCHLD/
        // waitpid loop. Without this, orphaned zombies whose parent died
        // (and got reparented to init) would linger in processTable
        // forever. Direct children of init (i.e. the top-level shell)
        // also auto-reap, which is fine: spawn's deferred has already
        // completed, so callers awaiting `result.exit` see the status
        // before the reap.
        if (child.ppid == 1) {
            tableMutex.withLock { reapInChildOfInit(child.pid) }
        }
        return SpawnResult(pid = child.pid, exit = exitDeferred)
    }

    /**
     * Reap a zombie whose parent is init. Must run under [tableMutex].
     * Mirrors the cleanup in [wait]'s reap block but skips the await
     * (status already complete) and rewrites init's children list.
     */
    private fun reapInChildOfInit(pid: Int) {
        val child = processTable[pid] ?: return
        // Walk the child's own surviving children (rare for a tool, but
        // possible for nested fork-tree shapes) — reparent them to init
        // before dropping the dying process.
        reparentChildrenToInit(child, dyingPid = pid)
        processTable.remove(pid)
        exitDeferreds.remove(pid)
        for (entry in child.fdTable.values) entry.ofd.release()
        child.fdTable.clear()
        processTable[1]?.children?.removeAll { it.pid == pid }
    }

    override suspend fun wait(pid: Int): ExitStatus? {
        // Read the deferred under the lock so a concurrent wait+reap
        // doesn't see it after another path removed it. Then await
        // outside the lock — awaiting under the lock would block every
        // other spawn/wait until this child finishes.
        val deferred = tableMutex.withLock { exitDeferreds[pid] } ?: return null
        val status = deferred.await()
        // Reap: drop from process table + parent's children list. All
        // table writes (and the parent-children mutation) under the lock.
        tableMutex.withLock {
            val child = processTable[pid]
            if (child != null) reparentChildrenToInit(child, dyingPid = pid)
            processTable.remove(pid)
            exitDeferreds.remove(pid)
            val parentPid = child?.ppid
            if (parentPid != null) {
                processTable[parentPid]?.children?.removeAll { it.pid == pid }
            }
        }
        return status
    }
}
