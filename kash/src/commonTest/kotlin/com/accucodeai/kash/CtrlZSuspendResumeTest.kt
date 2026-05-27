@file:OptIn(ExperimentalCoroutinesApi::class)

package com.accucodeai.kash

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.signal.SigCont
import com.accucodeai.kash.api.signal.SigTstp
import com.accucodeai.kash.jobs.JobState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end Ctrl-Z suspend/resume coverage — the real bash cycle:
 *  - Ctrl-Z (TSTP) parks the foreground deferred at the next
 *    suspension point via the stop-gate dispatcher.
 *  - The runStreaming select races the deferred against the
 *    foreground KashJob's stoppedSignal and returns Stopped.
 *  - The job is promoted into the regular job table (real id,
 *    bumps %+/%-) so `jobs` lists it and `fg %N` finds it.
 *  - `fg %N` resumes the gate and awaits completion.
 *
 * Test orchestration uses a [PauseHere] tool that parks on a Channel
 * — deterministic across the runTest scheduler. Tests advance the
 * scheduler to let the deferred park, then deliver TSTP synthetically.
 */
class CtrlZSuspendResumeTest {
    /**
     * Test-only tool that parks on [ch].receive(). Sends to [ready]
     * just before parking so the test can `await` to know the tool is
     * suspended (deterministic across the runTest scheduler).
     */
    private class PauseHere(
        val ch: Channel<Unit>,
        val ready: Channel<Unit>,
    ) : Command,
        CommandSpec {
        override val name = "pause-here"
        override val kind = CommandKind.TOOL
        override val command: Command get() = this

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
        ): CommandResult {
            ready.trySend(Unit)
            ch.receive()
            return CommandResult(exitCode = 0)
        }
    }

    /**
     * Bring the scheduler to a state where the pause-here body has
     * parked at `ch.receive()`. `ready.receive()` blocks until the
     * tool signals readiness; the subsequent `delay(0)` lets any
     * residual dispatches drain before we deliver TSTP.
     */
    private suspend fun awaitPaused(ready: Channel<Unit>) {
        ready.receive()
        delay(0)
    }

    @Test fun tstpPromotesForegroundJobIntoTableAsStopped() =
        runTest {
            val ch = Channel<Unit>(Channel.CONFLATED)
            val ready = Channel<Unit>(Channel.CONFLATED)
            val k =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(PauseHere(ch, ready)),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                val execJob =
                    async {
                        s.exec("set -m\npause-here")
                    }
                awaitPaused(ready)
                s.deliverSignal(SigTstp)
                // Run all pending scheduler tasks so runStreaming's
                // select resumes on stoppedSignal, the foreground
                // KashJob gets promoted into the table, and the
                // Stopped sync line lands on stderr — before the
                // test asserts. `delay(0)` only yields once; `runCurrent`
                // drains every task scheduled-and-currently-runnable.
                runCurrent()
                // The foreground KashJob must now be in the table,
                // promoted from sentinel id to a real id, marked
                // isForeground, state STOPPED.
                val list = s.interpreter.jobControl.list()
                assertTrue(list.isNotEmpty(), "promoted foreground job must be in the table")
                val fgJob = list.first { it.isForeground }
                // The KashJob.id field stays at the sentinel -1 (it's
                // a `val`); the post-promotion id lives on
                // `effectiveId` (== promotedId when promoted). The
                // byId map uses effectiveId as the key.
                assertTrue(
                    fgJob.effectiveId > 0,
                    "promoted job must have a real effectiveId, was ${fgJob.effectiveId}",
                )
                assertEquals(JobState.STOPPED, s.interpreter.jobControl.stateOf(fgJob))
                // Drain so runTest can finish: send the channel value,
                // then resume the gate. (The channel send + gate resume
                // together let pause-here return, the deferred complete,
                // and the supervisorScope unblock.)
                ch.trySend(Unit)
                s.deliverSignal(SigCont)
                execJob.await()
            } finally {
                s.close()
            }
        }

    @Test fun tstpEmitsStoppedSyncPointOnStderr() =
        runTest {
            val ch = Channel<Unit>(Channel.CONFLATED)
            val ready = Channel<Unit>(Channel.CONFLATED)
            val k =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(PauseHere(ch, ready)),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                val execJob =
                    async {
                        s.exec("set -m\npause-here")
                    }
                awaitPaused(ready)
                s.deliverSignal(SigTstp)
                // Run all pending scheduler tasks so runStreaming's
                // select resumes on stoppedSignal, the foreground
                // KashJob gets promoted into the table, and the
                // Stopped sync line lands on stderr — before the
                // test asserts. `delay(0)` only yields once; `runCurrent`
                // drains every task scheduled-and-currently-runnable.
                runCurrent()
                ch.trySend(Unit)
                s.deliverSignal(SigCont)
                val r = execJob.await()
                assertTrue(
                    r.stderr.contains("Stopped"),
                    "expected `[N]+ Stopped …` in stderr, got: '${r.stderr}'",
                )
            } finally {
                s.close()
            }
        }

    @Test fun monitorOffMakesTstpANoOpAndStatementCompletes() =
        runTest {
            val ch = Channel<Unit>(Channel.CONFLATED)
            val ready = Channel<Unit>(Channel.CONFLATED)
            val k =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(PauseHere(ch, ready)),
                    parentContext = coroutineContext,
                )
            val s = k.newSession()
            try {
                val execJob =
                    async {
                        // No `set -m`: monitor mode stays off. TSTP has
                        // no foreground KashJob target → no stop, no
                        // sync line. The tool runs to completion once
                        // the channel sends.
                        s.exec("pause-here\necho done")
                    }
                awaitPaused(ready)
                s.deliverSignal(SigTstp)
                // Run all pending scheduler tasks so runStreaming's
                // select resumes on stoppedSignal, the foreground
                // KashJob gets promoted into the table, and the
                // Stopped sync line lands on stderr — before the
                // test asserts. `delay(0)` only yields once; `runCurrent`
                // drains every task scheduled-and-currently-runnable.
                runCurrent()
                // No monitor → no gate pause, so trySend lets the
                // tool return immediately without needing SigCont.
                ch.trySend(Unit)
                val r = execJob.await()
                assertFalse(
                    r.stderr.contains("Stopped"),
                    "no-monitor should not emit Stopped, got: '${r.stderr}'",
                )
                assertTrue(r.stdout.contains("done"), "expected 'done', got: '${r.stdout}'")
            } finally {
                s.close()
            }
        }

    @Test fun foregroundJobNotInJobsListingWhileRunning() =
        runTest {
            // Foreground statements are NEVER in the job table while
            // running. `jobs` must show only backgrounded work.
            val k =
                Kash(
                    registry = standardRegistry(),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                val r = s.exec("set -m\ntrue &\nwait\njobs")
                // The `jobs` builtin's own foreground statement (and
                // every other in this script) is NOT listed. After
                // `wait`, the backgrounded `true &` is reaped (gone
                // from jobControl.list per JobControl.reap), so `jobs`
                // output is empty.
                assertFalse(r.stdout.contains("jobs"), "jobs builtin's own foreground statement must not appear")
            } finally {
                s.close()
            }
        }

    @Test fun backgroundedSubshellRunsUnaffectedByForegroundGate() =
        runTest {
            // Backgrounded jobs are launched on sessionScope WITHOUT
            // the stop-gate dispatcher. Pausing the gate must NOT
            // affect them. Verified indirectly: `true &; wait` must
            // complete without ever observing TSTP, because we never
            // deliver one.
            val k =
                Kash(
                    registry = standardRegistry(),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                val r = s.exec("set -m\ntrue &\nwait")
                assertEquals(0, r.exitCode)
            } finally {
                s.close()
            }
        }

    @Test fun stoppedForegroundJobShownInJobsListing() =
        runTest {
            // After TSTP promotes a foreground job, `jobs` (run as
            // the NEXT foreground statement, after stop) must list
            // the stopped job. This is the bash sync-point semantic
            // — `[N]+ Stopped` visible to subsequent `jobs` calls.
            val ch = Channel<Unit>(Channel.CONFLATED)
            val ready = Channel<Unit>(Channel.CONFLATED)
            val k =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(PauseHere(ch, ready)),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                val execJob =
                    async {
                        s.exec("set -m\npause-here")
                    }
                awaitPaused(ready)
                s.deliverSignal(SigTstp)
                // Run all pending scheduler tasks so runStreaming's
                // select resumes on stoppedSignal, the foreground
                // KashJob gets promoted into the table, and the
                // Stopped sync line lands on stderr — before the
                // test asserts. `delay(0)` only yields once; `runCurrent`
                // drains every task scheduled-and-currently-runnable.
                runCurrent()
                // Confirm via the live table — we can't call s.exec
                // mid-flight (it would re-enter the interpreter), but
                // we can inspect jobControl.list() directly through
                // the public accessor on Interpreter.
                val list = s.interpreter.jobControl.list()
                val stopped = list.filter { s.interpreter.jobControl.stateOf(it) == JobState.STOPPED }
                assertTrue(stopped.isNotEmpty(), "expected a STOPPED job, got: $list")
                assertTrue(stopped.any { it.isForeground }, "stopped job must be the foreground one")
                // Drain so runTest can finish.
                ch.trySend(Unit)
                s.deliverSignal(SigCont)
                execJob.await()
            } finally {
                s.close()
            }
        }

    /**
     * REPL prompt-restoration regression: after Ctrl-Z, the
     * interpreter's `exec(...)` call must RETURN so the REPL loop
     * can read the next line. If the stopped foreground deferred
     * is still a child of runStreaming's `supervisorScope`, that
     * scope will refuse to complete until the deferred does — but
     * the deferred is parked on the stop-gate and only resumes on
     * a future `fg`/`bg`/CONT. Net effect in the live shell is a
     * dead prompt after `^Z`: the user types but nothing happens.
     *
     * The test models this directly. We dispatch `set -m\npause-here`
     * as `s.exec(...)` (the same entry point the REPL uses), TSTP
     * the foreground statement, run the scheduler until quiescent,
     * and assert the `exec` deferred has COMPLETED — i.e. `exec`
     * returned to the caller and a REPL would now be drawing the
     * next prompt. Without the fix, the deferred stays Active
     * because the stopped statement's child coroutine pins the
     * supervisorScope.
     */
    @Test fun tstpAllowsExecToReturnSoReplPromptCanRedraw() =
        runTest {
            val ch = Channel<Unit>(Channel.CONFLATED)
            val ready = Channel<Unit>(Channel.CONFLATED)
            val k =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(PauseHere(ch, ready)),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                val execJob =
                    async {
                        s.exec("set -m\npause-here")
                    }
                awaitPaused(ready)
                s.deliverSignal(SigTstp)
                // Run every immediately-runnable task. After this, the
                // foreground statement's `pause-here` is still parked at
                // `ch.receive()`, but `exec` itself MUST be done —
                // otherwise the REPL never gets to draw the next prompt.
                runCurrent()
                assertTrue(
                    execJob.isCompleted,
                    "exec must return after Ctrl-Z so the REPL prompt redraws; " +
                        "if this hangs the user sees a dead prompt and has to ^C twice",
                )
                // Drain so runTest can finish.
                ch.trySend(Unit)
                s.deliverSignal(SigCont)
                execJob.await()
            } finally {
                s.close()
            }
        }

    /**
     * Multi-Ctrl-Z + next-command regression: in the live REPL, a
     * user reported `^Z`'ing a few sleeps and then `agent` doing
     * nothing — the shell appeared dead. Root cause: `^Z` calls
     * `stopGate.pause()` and leaves the gate paused permanently;
     * subsequent statements `async(stopGateDispatcher) { body }`
     * queue their body on the paused gate's invokeOnCompletion,
     * so the body never dispatches. Each new `^Z` still fires
     * `stoppedSignal`, so the prompt appears to redraw, but the
     * statement underneath never ran.
     *
     * The test models this: park-here, ^Z, park-here, ^Z, then
     * dispatch a plain `echo done`. With the gate-leak bug the
     * `echo` body never runs. With the fix the gate gets
     * resumed at the Ctrl-Z sync point and the echo prints.
     */
    @Test fun ctrlZDoesNotStrandLaterStatementsOnPausedGate() =
        runTest {
            val ch1 = Channel<Unit>(Channel.CONFLATED)
            val ready1 = Channel<Unit>(Channel.CONFLATED)
            val ch2 = Channel<Unit>(Channel.CONFLATED)
            val ready2 = Channel<Unit>(Channel.CONFLATED)

            class PauseN(
                val name0: String,
                val ch: Channel<Unit>,
                val ready: Channel<Unit>,
            ) : Command,
                CommandSpec {
                override val name = name0
                override val kind = CommandKind.TOOL
                override val command: Command get() = this

                override suspend fun run(
                    args: List<String>,
                    ctx: CommandContext,
                ): CommandResult {
                    ready.trySend(Unit)
                    ch.receive()
                    return CommandResult(exitCode = 0)
                }
            }
            val k =
                Kash(
                    registry = standardRegistry(),
                    customCommands =
                        listOf(
                            PauseN("pause1", ch1, ready1),
                            PauseN("pause2", ch2, ready2),
                        ),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                // ^Z the first pause.
                val e1 = async { s.exec("set -m\npause1") }
                awaitPaused(ready1)
                s.deliverSignal(SigTstp)
                runCurrent()
                assertTrue(e1.isCompleted, "first exec returns after ^Z")

                // ^Z the second pause. If the gate stayed paused from the
                // first ^Z this body wouldn't even start, but its
                // stoppedSignal still fires — so the "Stopped" line lands
                // and exec returns; the deferred itself was never run.
                val e2 = async { s.exec("pause2") }
                awaitPaused(ready2)
                s.deliverSignal(SigTstp)
                runCurrent()
                assertTrue(e2.isCompleted, "second exec returns after ^Z")

                // Now run a plain `echo done`. With the gate-leak bug
                // this exec hangs because the async body is queued on
                // the paused gate. With the fix it runs to completion
                // and emits "done" on stdout.
                val r3 = s.exec("echo done")
                assertEquals(
                    "done\n",
                    r3.stdout,
                    "next statement after ^Z must execute; stdout was: '${r3.stdout}'",
                )

                // Drain so runTest can finish: release both paused tools.
                ch1.trySend(Unit)
                ch2.trySend(Unit)
                s.deliverSignal(SigCont)
            } finally {
                s.close()
            }
        }

    @Test fun stopGateCloseDoesNotLeakOnSessionShutdown() =
        runTest {
            // Pause the gate via TSTP; close the session before resuming.
            // gate.close() must release pending invokeOnCompletion
            // callbacks; sessionScope.cancel() unwinds. No
            // UncompletedCoroutinesError from runTest.
            val ch = Channel<Unit>(Channel.CONFLATED)
            val ready = Channel<Unit>(Channel.CONFLATED)
            val k =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(PauseHere(ch, ready)),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            val execJob =
                async {
                    try {
                        s.exec("set -m\npause-here")
                    } catch (_: Throwable) {
                        com.accucodeai.kash.api
                            .ExecResult(exitCode = 130, stdout = "", stderr = "")
                    }
                }
            awaitPaused(ready)
            s.deliverSignal(SigTstp)
            runCurrent()
            // Send the channel value FIRST so pause-here's receive can
            // complete once the gate releases. Then close: gate.close()
            // fires the invokeOnCompletion callbacks (with cancellation
            // cause); each callback dispatches into inner, the parked
            // continuation resumes, channel.receive completes (the Unit
            // we sent), pause-here returns, deferred completes,
            // supervisorScope unblocks, exec returns.
            ch.trySend(Unit)
            s.close()
            execJob.await()
        }
}
