@file:OptIn(ExperimentalCoroutinesApi::class)

package com.accucodeai.kash.jobs

import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.ProcessState
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.api.signal.SigCont
import com.accucodeai.kash.api.signal.SigHup
import com.accucodeai.kash.api.signal.SigInt
import com.accucodeai.kash.api.signal.SigKill
import com.accucodeai.kash.api.signal.SigQuit
import com.accucodeai.kash.api.signal.SigStop
import com.accucodeai.kash.api.signal.SigTerm
import com.accucodeai.kash.api.signal.SigTstp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.io.Buffer

/**
 * Session-scoped job *view* over [KashMachine.processTable].
 *
 * Post-refactor: this class does NOT own a coroutine scope or launch
 * jobs itself. The dispatching sites (`dispatchBackground`, coproc,
 * procsub) launch their own driver coroutines into the session scope
 * and hand the resulting [kotlinx.coroutines.Job] back via
 * [KashJob.driverJob]. Each pipeline's per-stage pids are recorded in
 * [KashJob.memberPids]; signal dispatch fans out across them and reads
 * state from [KashMachine.processTable].
 */
public class JobControl internal constructor(
    private val machine: KashMachine,
) {
    private val byId = linkedMapOf<Int, KashJob>()
    private var lastBgIdValue: Int? = null
    private var prevBgIdValue: Int? = null

    /**
     * Set of job ids whose state has changed since the last
     * [clearChangedJobs] call. Backs `jobs -n` (only-changed view) and
     * the implicit "status notification at synchronization points"
     * future bash compatibility hook. Mutated by [register] (every new
     * job is "changed") and by [signal]/[reap] on state transitions.
     */
    private val changedIds: MutableSet<Int> = mutableSetOf()

    /**
     * Monotonically-increasing counter of "top-level statements" the
     * shell has stepped through. Bumped by [Interpreter.runStreaming]
     * once per outer-shell statement (forks that own their own
     * jobControl bump their own copy; in-place `(...)` and `$()`
     * subshells share the parent's value and do not bump).
     *
     * Used by the `jobs` builtin's "report-and-remove" decision: a
     * DONE entry whose [KashJob.completedAtStatement] equals the
     * current counter completed during *this* statement, hasn't yet
     * crossed a synchronization boundary, and is reaped on the
     * NEXT statement's `jobs` invocation — matching bash's bash's
     * `cleanup_dead_jobs` running at sync points, not at the
     * listing site.
     */
    public var topLevelStatementCounter: Long = 0L

    /**
     * FIFO completion notification stream. Each registered job's
     * `invokeOnCompletion` callback enqueues the job here; `wait -n`
     * receives in arrival order, which is the closest analogue to
     * bash's real-time "first-process-to-die-wins" semantic when
     * the test scheduler has already advanced virtual time past
     * multiple jobs' delays before `wait -n` runs.
     *
     * Bounded with DROP_OLDEST so a long-running shell (or
     * conformance-test pair runner) that backgrounds many jobs
     * without ever calling `wait -n` doesn't accumulate the
     * captured `KashJob` references (each pins a stdoutBuf /
     * stderrBuf / stageCodes / done channel — non-trivial memory).
     * `wait -n` filters on `byId` membership anyway, so a dropped
     * entry just makes wait-n suspend instead of short-circuit —
     * functionally equivalent for any job that's still alive.
     */
    internal val completionStream: kotlinx.coroutines.channels.Channel<KashJob> =
        kotlinx.coroutines.channels.Channel(
            capacity = 256,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

    public fun changedJobs(): Set<Int> = changedIds.toSet()

    public fun clearChangedJobs() {
        changedIds.clear()
    }

    /**
     * Pick the lowest free job id (bash semantics: after `[1] [2] [3]`
     * complete, the next `&` gets `[1]` again).
     */
    private fun allocateJobId(): Int {
        var i = 1
        while (i in byId) i++
        return i
    }

    /** Backs `$!`. Null until the first `&`. */
    public fun lastBgPid(): Int? = lastBgIdValue?.let { byId[it]?.leaderPid }

    /**
     * Job-id of the most recent `&` (the one [lastBgPid] resolves through).
     * Exposed so subshell-aware callers can mask it without iterating the
     * job table: `id in interpreter.subshellJobMask` is the cheap check.
     */
    public fun lastBgId(): Int? = lastBgIdValue

    /** Live snapshot of every still-tracked job. */
    public fun list(): List<KashJob> = byId.values.toList()

    /**
     * Cheap snapshot of the current job ids. Subshell-mask construction in
     * the interpreter wants the ids only, not the values — exposing keys
     * directly skips materializing a list of [KashJob] entries on every
     * `forkSubshell`. Returns a defensive copy so the caller can hold it
     * across map mutations.
     */
    public fun jobIds(): Set<Int> = byId.keys.toSet()

    /**
     * Resolve a jobspec to a [KashJob]:
     *   - `%N` — by id
     *   - `%+` / `%%` — most recent
     *   - `%-` — previous
     *   - bare integer — interpret as a pid. POSIX: this is the leader's
     *     pid; we additionally scan every job's [KashJob.memberPids] so
     *     `wait <tail-stage-pid>` and `kill <middle-stage-pid>` resolve
     *     to the owning job. Matches zsh's per-process job-membership
     *     lookup.
     */
    public fun resolve(spec: String): KashJob? {
        if (spec.isEmpty()) return null
        if (spec == "%+" || spec == "%%") return lastBgIdValue?.let { byId[it] }
        if (spec == "%-") return prevBgIdValue?.let { byId[it] }
        if (spec.startsWith("%")) {
            val rest = spec.substring(1)
            val jobId = rest.toIntOrNull()
            if (jobId != null) return byId[jobId]
            // Bash jobspec string-match: `%STRING` matches the job whose
            // command line begins with STRING; `%?STRING` matches any job
            // whose command line contains STRING. Both are ambiguous if
            // multiple match — we follow bash and return the first match
            // in insertion order (LinkedHashMap iteration).
            return if (rest.startsWith("?")) {
                val needle = rest.substring(1)
                if (needle.isEmpty()) null else byId.values.firstOrNull { needle in it.command }
            } else {
                byId.values.firstOrNull { it.command.startsWith(rest) }
            }
        }
        val pid = spec.toIntOrNull() ?: return null
        return byId.values.firstOrNull { pid in it.memberPids }
    }

    /**
     * Register a new pipeline job. The dispatch site has already pre-
     * forked one [com.accucodeai.kash.api.KashProcess] per stage and
     * holds their pids in [memberPids]; this method allocates a job id,
     * inserts the entry, and bumps the `%+`/`%-` slots. The dispatch
     * site is expected to assign [KashJob.driverJob] right after.
     */
    public fun register(
        pretty: String,
        memberPids: List<Int>,
        startedUnderMonitor: Boolean = false,
    ): KashJob {
        require(memberPids.isNotEmpty()) { "job must have at least one member pid" }
        val id = allocateJobId()
        val job =
            KashJob(
                id = id,
                command = pretty,
                memberPids = memberPids,
                stdoutBuf = Buffer(),
                stderrBuf = Buffer(),
                stageCodes = IntArray(memberPids.size),
                done = CompletableDeferred(),
                startedUnderMonitor = startedUnderMonitor,
            )
        byId[id] = job
        prevBgIdValue = lastBgIdValue
        lastBgIdValue = id
        changedIds += id
        // Notify `wait -n` of the completion in FIFO arrival order. Fires
        // exactly once when the driver completes `job.done`.
        job.done.invokeOnCompletion { completionStream.trySend(job) }
        return job
    }

    /**
     * Build the in-flight foreground statement's [KashJob] **without**
     * inserting it into the job table. The deferred runs under the
     * shell's stop-gate dispatcher; if it parks on TSTP, the runStreaming
     * dispatcher promotes the job into the table via
     * [promoteStoppedForegroundJob] at that point — bash's behavior:
     * a foreground command has no `%N` slot while running; Ctrl-Z gives
     * it one. Returning a non-byId KashJob keeps `fg %1` resolution
     * correct: a backgrounded job dispatched while a foreground is
     * running still gets `%1`, not `%2`.
     *
     * The sentinel id `-1` indicates "not yet promoted"; the `[N]+ Stopped`
     * notification uses the post-promotion id, not this sentinel.
     */
    internal fun makeForegroundJob(
        pretty: String,
        memberPid: Int,
        startedUnderMonitor: Boolean,
    ): KashJob =
        KashJob(
            id = -1,
            command = pretty,
            memberPids = listOf(memberPid),
            stdoutBuf = Buffer(),
            stderrBuf = Buffer(),
            stageCodes = IntArray(1),
            done = CompletableDeferred(),
            startedUnderMonitor = startedUnderMonitor,
            isForeground = true,
        )

    /**
     * Promote a Ctrl-Z'd foreground KashJob into the regular job table.
     * Allocates a fresh job id (with the new id replacing the sentinel
     * via [KashJob.promotedId]), inserts the job, bumps `%+`/`%-` per
     * bash's "stopping a job makes it the current job", marks changed.
     * Returns the assigned job id so the caller can format the
     * `[N]+ Stopped <cmd>` line.
     */
    internal fun promoteStoppedForegroundJob(job: KashJob): Int {
        require(job.isForeground && job.promotedId == null) {
            "foreground job must be unpromoted before promotion"
        }
        val id = allocateJobId()
        job.promotedId = id
        byId[id] = job
        prevBgIdValue = lastBgIdValue
        lastBgIdValue = id
        changedIds += id
        return id
    }

    /**
     * Derive a job's aggregate state from its member processes. Mirrors
     * zsh's job-state aggregation:
     *   - DONE/TERMINATED once [KashJob.done] has completed.
     *   - STOPPED if the logical stop bit is set (a STOP signal has
     *     been delivered and a CONT has not cleared it).
     *   - STOPPED if every member's [com.accucodeai.kash.api
     *     .KashProcess.state] is STOPPED.
     *   - RUNNING otherwise.
     */
    public fun stateOf(job: KashJob): JobState =
        when {
            job.done.isCompleted -> {
                // Distinguish a clean exit from a cancelled-by-signal
                // exit: the latter completed exceptionally via the
                // driver's catch, but we always complete `done` with a
                // value, so peek at the value to tell.
                val v = job.done.getCompleted()
                if (v in 129..255) JobState.TERMINATED else JobState.DONE
            }

            job.stopped -> {
                JobState.STOPPED
            }

            allMembersStopped(job) -> {
                JobState.STOPPED
            }

            else -> {
                JobState.RUNNING
            }
        }

    /**
     * Apply [block] to the process at [rootPid] and every transitive
     * descendant via [com.accucodeai.kash.api.KashProcess.children].
     * Skips missing entries silently. Used by STOP/CONT fanout so a
     * signal targeted at a pipeline-stage subshell reaches the actual
     * tool process the stage is hosting.
     */
    private inline fun walkSubtreeAndApply(
        rootPid: Int,
        block: (com.accucodeai.kash.api.KashProcess) -> Unit,
    ) {
        val root = machine.processTable[rootPid] ?: return
        val stack = ArrayDeque<com.accucodeai.kash.api.KashProcess>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            block(p)
            for (child in p.children) stack.addLast(child)
        }
    }

    private fun allMembersStopped(job: KashJob): Boolean {
        if (job.memberPids.isEmpty()) return false
        for (pid in job.memberPids) {
            val p = machine.processTable[pid] ?: continue
            if (p.state != ProcessState.STOPPED) return false
        }
        return true
    }

    /**
     * Deliver [sig] to every member of [job]:
     *  - **terminate** (INT/TERM/HUP/QUIT): run fatal-signal hooks,
     *    cancel the driver coroutine — structured cancellation
     *    propagates to every stage. The driver's `finally` block
     *    completes `job.done` with `128 + sig.number`.
     *  - **STOP/TSTP**: flip [KashJob.stopped] and write
     *    [ProcessState.STOPPED] onto every member process so
     *    processTable-walking tools (`ps`, `jobs -l`) report the right
     *    state. (Kash coroutines can't actually preempt, but the bit
     *    is observable and cooperating tools may park.)
     *  - **CONT**: clear stop bit, restore member states, wake the
     *    job's `awaitContinue` consumers.
     */
    public fun signal(
        job: KashJob,
        sig: KashSignal,
    ) {
        if (job.done.isCompleted) return
        when {
            sig === SigInt || sig === SigTerm || sig === SigHup || sig === SigQuit || sig === SigKill -> {
                job.runFatalSignalHooks()
                job.driverJob?.cancel(CancellationException("signal $sig"))
                // The driver's finally completes `done`; we don't pre-
                // emptively complete here so the exit code reflects the
                // actual signal (recorded in the catch via `sigToExitCode`).
            }

            sig === SigTstp || sig === SigStop -> {
                job.stopped = true
                // Foreground statement: signal its stoppedSignal so the
                // runStreaming `select { deferred.onAwait; stoppedSignal.onReceive }`
                // can race-return Stopped to the caller. Backgrounded
                // jobs use it too — harmless since no one's selecting.
                job.stoppedSignal.trySend(Unit)
                // Bash: stopping a job promotes it to the current job
                // (`%+`); the previously-current slot shifts to `%-`.
                // Matches bash's "stopping a job makes it the current
                // job" rule. Foreground jobs (which
                // sit outside the %+/%- chain) are exempted — they
                // shouldn't displace whatever was %+ before stopping.
                if (!job.isForeground && lastBgIdValue != job.id) {
                    prevBgIdValue = lastBgIdValue
                    lastBgIdValue = job.id
                }
                changedIds += job.id
                // STOP/CONT fan out across the *full* process subtree of
                // each pipeline member: a stage subshell normally runs
                // its tool body in a grandchild process (machine.spawn
                // inside the stage), so signalling only the subshell's
                // own pid would miss the tool itself. Mirrors how
                // POSIX `kill -<pgid>` reaches every process in the
                // group.
                for (pid in job.memberPids) {
                    walkSubtreeAndApply(pid) { p ->
                        if (p.state == ProcessState.RUNNING) p.state = ProcessState.STOPPED
                    }
                }
            }

            sig === SigCont -> {
                job.stopped = false
                for (pid in job.memberPids) {
                    walkSubtreeAndApply(pid) { p ->
                        if (p.state == ProcessState.STOPPED) p.state = ProcessState.RUNNING
                    }
                }
                job.contChannel.trySend(Unit)
                changedIds += job.id
            }

            else -> {
                // Other signals are recorded but have no in-shell
                // semantics yet. Future: trap handlers; today no-op.
            }
        }
    }

    /**
     * Remove [job] from the table without waiting for it. Backs
     * `disown`: the driver keeps running but `jobs` no longer lists
     * the job. Stage pids stay in `processTable` until the driver's
     * cleanup unregisters them.
     */
    public fun forget(job: KashJob) {
        byId.remove(job.id)
        if (lastBgIdValue == job.id) {
            lastBgIdValue = byId.keys.maxOrNull()
            prevBgIdValue = byId.keys.filter { it != lastBgIdValue }.maxOrNull()
        } else if (prevBgIdValue == job.id) {
            prevBgIdValue = byId.keys.filter { it != lastBgIdValue }.maxOrNull()
        }
    }

    /**
     * Wait for [job] to complete, drain buffers, return the pipeline's
     * last-stage exit code (or `128 + signum` for a signal-killed job).
     * Honors the logical stop bit via [KashJob.awaitContinue] — a
     * stopped job is not reaped until SIGCONT lifts the stop.
     */
    public suspend fun reap(
        job: KashJob,
        out: SuspendSink,
        err: SuspendSink,
    ): Int {
        job.awaitContinue()
        val code = job.done.await()
        job.drainTo(out, err)
        byId.remove(job.id)
        if (lastBgIdValue == job.id) {
            lastBgIdValue = byId.keys.maxOrNull()
            prevBgIdValue = byId.keys.filter { it != lastBgIdValue }.maxOrNull()
        } else if (prevBgIdValue == job.id) {
            prevBgIdValue = byId.keys.filter { it != lastBgIdValue }.maxOrNull()
        }
        return code
    }

    /**
     * Reap *only* if [job] has already completed — non-blocking variant
     * used by the `wait`/`jobs` synchronization-point status reporter
     * that doesn't want to suspend on still-running pipelines. Returns
     * the exit code if reaped, null if the job is still running.
     */
    public fun tryReap(job: KashJob): Int? {
        if (!job.done.isCompleted) return null
        val code = job.done.getCompleted()
        byId.remove(job.id)
        if (lastBgIdValue == job.id) {
            lastBgIdValue = byId.keys.maxOrNull()
            prevBgIdValue = byId.keys.filter { it != lastBgIdValue }.maxOrNull()
        } else if (prevBgIdValue == job.id) {
            prevBgIdValue = byId.keys.filter { it != lastBgIdValue }.maxOrNull()
        }
        return code
    }

    /**
     * Cancel every tracked job's driver. Idempotent. Called at session
     * shutdown; structured cancellation of [com.accucodeai.kash.interpreter
     * .Interpreter.sessionScope] also propagates here, so this is mostly
     * defensive.
     */
    public fun cancelAll() {
        for (j in byId.values) j.driverJob?.cancel()
        byId.clear()
        lastBgIdValue = null
        prevBgIdValue = null
    }
}

/**
 * Translate a fatal signal into the POSIX `128 + signum` exit code.
 * Lives at top-level so the driver coroutine's catch block (in the
 * pipeline dispatch site) doesn't need to import the signal singletons.
 */
public fun fatalSignalExitCode(sig: KashSignal): Int = 128 + sig.number
