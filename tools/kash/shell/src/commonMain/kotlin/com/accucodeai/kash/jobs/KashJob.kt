package com.accucodeai.kash.jobs

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.transferFrom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.Buffer

/**
 * A background pipeline tracked by [JobControl].
 *
 * Pure-data entry as of the "marry JobControl to processTable" refactor.
 * The old design held a `Deferred<Int>` produced by `scope.async` and
 * exposed a derived `state`/`isStopped` over coroutine flags; the new
 * design lets the dispatching coroutine (held in [driverJob]) be
 * fire-and-forget and derives state from the constituent processes'
 * [com.accucodeai.kash.api.ProcessState] via [JobControl].
 *
 *  - [memberPids] is the ordered list of pipeline-stage pids, leader
 *    first. `kill %N` fans out across all of them; `wait <pid>` for any
 *    member returns that stage's exit. POSIX `$!` = `memberPids[0]`.
 *  - [stageCodes] is filled in by the driver as stages complete (same
 *    array `runForkedStages` writes through); after reap it's
 *    published to `PIPESTATUS`.
 *  - [done] is completed by the driver with the last-stage exit code
 *    (or `128+signum` if cancelled by a fatal signal). [JobControl.reap]
 *    awaits this, then drains the per-job stdout/stderr buffers.
 *  - [driverJob] is the parent of every stage coroutine. Cancelling it
 *    (terminate-class signals) tears down the whole pipeline; the
 *    driver's catch translates the cancellation into a signal-exit code.
 *  - [stopped] / [contChannel] gate `wait`/`fg`: when STOP-flagged the
 *    awaiter parks until CONT. We also write [com.accucodeai.kash.api
 *    .ProcessState.STOPPED] onto each member's [com.accucodeai.kash.api
 *    .KashProcess] so `processTable`-walking tools see the right state.
 */
public class KashJob internal constructor(
    public val id: Int,
    public val command: String,
    public val memberPids: List<Int>,
    internal val stdoutBuf: Buffer,
    internal val stderrBuf: Buffer,
    internal val stageCodes: IntArray,
    internal val done: CompletableDeferred<Int>,
    /**
     * Whether monitor mode (`set -m`) was on at the moment this job
     * was dispatched. Bash gates `fg %N` on this: turning on monitor
     * AFTER a job was backgrounded still refuses with "job N started
     * without job control" because the kernel-side job-control state
     * wasn't established at fork time. We don't have a kernel-side
     * state, but we mirror the diagnostic.
     */
    public val startedUnderMonitor: Boolean = false,
    /**
     * True for the *foreground* statement's KashJob — registered by
     * [com.accucodeai.kash.interpreter.Interpreter.runStreaming] under
     * monitor mode so TSTP has a target. Foreground jobs do NOT move
     * the `%+`/`%-` pointers, do NOT appear in `jobs` listings while
     * running (only when stopped), and are reaped immediately when
     * their statement completes normally. The flag exists so the
     * visibility filter can distinguish them from backgrounded jobs.
     */
    public val isForeground: Boolean = false,
) {
    /** POSIX `$!`: the leader (first-stage) pid. */
    public val leaderPid: Int get() = memberPids.first()

    /**
     * Effective job id used in user-facing diagnostics. For backgrounded
     * jobs this is just [id]. For an unpromoted foreground job ([id] is
     * the sentinel `-1`), bash semantics say the job has no job-id slot
     * until it gets Ctrl-Z'd — `effectiveId` reads the post-promotion
     * id when present, sentinel otherwise.
     */
    public val effectiveId: Int get() = promotedId ?: id

    /**
     * Real job id assigned by [JobControl.promoteStoppedForegroundJob]
     * when a foreground job is Ctrl-Z'd. Null for backgrounded jobs
     * (which get a real id at register time) and for foreground jobs
     * that complete normally without stopping.
     */
    internal var promotedId: Int? = null

    /**
     * Snapshot of [JobControl.topLevelStatementCounter] at the moment
     * this job's `done` completed — i.e., which top-level shell
     * statement was executing when the driver finished. The `jobs`
     * builtin uses this to defer reaping: a DONE entry whose
     * `completedAtStatement == topLevelStatementCounter` finished
     * during the *currently-executing* statement and hasn't yet
     * crossed a sync boundary, so listing it twice within the same
     * statement (e.g. `echo $(jobs); echo $(jobs)`) doesn't lose it.
     * Reap fires only on the next statement's listing.
     */
    internal var completedAtStatement: Long? = null

    /**
     * The Kotlin coroutine driving this job's pipeline stages. Set
     * exactly once by the dispatch site right after `register(...)`.
     * Cancelling it tears down every stage's launched coroutine via
     * structured cancellation; the driver's `finally` completes [done].
     */
    internal var driverJob: kotlinx.coroutines.Job? = null

    /**
     * Logical stop bit, flipped by SIGTSTP/SIGSTOP and cleared by
     * SIGCONT. Drives `wait`/`fg` gating. Distinct from
     * `processTable[pid].state == STOPPED` because the latter is
     * per-member and this is per-pipeline; [JobControl.signal] keeps
     * them in lockstep.
     */
    internal var stopped: Boolean = false

    /** Suspending wait used by [awaitContinue] so `wait`/`fg` honor STOP/CONT without busy-loop. */
    internal val contChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /**
     * Signalled when [JobControl.signal] flips [stopped] true (TSTP /
     * STOP delivery). The foreground statement's runStreaming await
     * races this channel against the deferred's completion via
     * `select { … }` — winning here means "return control to the REPL,
     * leave the job in the table, don't reap". CONFLATED so a TSTP
     * arriving while no one is yet receiving still gets observed by
     * the first subsequent receive.
     */
    internal val stoppedSignal =
        kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /**
     * Hooks run synchronously *before* the driver coroutine is cancelled
     * on a terminate-class signal. Coproc registers a hook here that
     * closes the parent→child sink so the body's `read` sees EOF rather
     * than `CancellationException` mid-read. Multiple hooks allowed;
     * exceptions are swallowed so one misbehaving hook can't block the
     * others or the cancellation itself.
     */
    private val fatalSignalHooks: MutableList<() -> Unit> = mutableListOf()

    public fun onFatalSignal(hook: () -> Unit) {
        fatalSignalHooks += hook
    }

    internal fun runFatalSignalHooks() {
        for (h in fatalSignalHooks) {
            try {
                h()
            } catch (_: Throwable) {
            }
        }
    }

    /** Block while [stopped] is set, returning as soon as CONT is delivered or [done] completes. */
    internal suspend fun awaitContinue() {
        while (stopped && !done.isCompleted) {
            contChannel.receive()
        }
    }

    /**
     * Drain the per-job buffers into the caller's sinks. Called by
     * [JobControl.reap] once [done] is complete — at that point the
     * driver has finished writing, so the Buffer is safe to read.
     */
    internal suspend fun drainTo(
        out: SuspendSink,
        err: SuspendSink,
    ) {
        out.transferFrom(stdoutBuf.asSuspendSource())
        err.transferFrom(stderrBuf.asSuspendSource())
    }
}

/**
 * Job state, derived per-call from [KashJob.memberPids] mapped through
 * the machine's process table by [JobControl.stateOf].
 */
public enum class JobState { RUNNING, STOPPED, DONE, TERMINATED }
