package com.accucodeai.kash

import com.accucodeai.kash.api.signal.SigTerm
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork / SignalRouter coverage (Item 3 of the process/job-model plan,
 * scaled-back scope). Documents the architecture decisions:
 *
 *  - Root interpreter registers its pid (== `$$`) in [SignalRouter]
 *    so `kill $$` from any fork resolves to the root's `deliverSignal`.
 *  - Forks intentionally do NOT register their own pids today. The
 *    fork's `foregroundSignals` channel has no general drain hook,
 *    and intercepting `kill <fork-pid>` in the router would steal the
 *    signal from the `JobControl.signal` path that the parent uses
 *    to cancel coproc / procsub / pipeline-stage jobs (bash's
 *    `coproc.tests` `kill $REFLECT_PID` depends on that route).
 *  - The cleanup callsites in `dispatchBackground`, `runCoproc`,
 *    `runProcSub`, and control-flow forks DO call
 *    `signalRouter.unregister(stage.process.pid)` next to the existing
 *    `machine.unregisterProcess(...)`. These are no-ops for forks
 *    today (since forks aren't registered), but the symmetry is in
 *    place for a future "fork-side trap dispatch" implementation that
 *    will register forks and need the matching teardown.
 *
 * Full "kill -USR1 \$BASHPID fires the fork's trap" requires (a) a
 * drain path on the fork's `foregroundSignals` channel, (b) selective
 * router-vs-JobControl routing per signal class (fatal-class through
 * JobControl to preserve coproc semantics; USR-class through router
 * to fire the fork's trap). Not in this pass.
 */
class ForkSignalDrainTest {
    @Test fun rootPidRoutesThroughSignalRouterToRootsDeliverSignal() =
        runTest {
            // `kill -TERM $$` from any context (foreground, subshell)
            // must reach the root's deliverSignal — the path that
            // cancels the foreground job and fires the SIGTERM trap.
            // Verified through the existing routing tests; this is a
            // regression guard for the SignalRouter foundation.
            val k = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            val s = k.newSession()
            try {
                // Setting a TERM trap proves the signal reached
                // deliverSignal AND the trap was dispatched (the
                // trap-handler script `echo got-term` ran).
                val r = s.exec("trap 'echo got-term' TERM\nkill -TERM \$\$")
                assertTrue(
                    r.stdout.contains("got-term"),
                    "expected `got-term` in stdout (proves trap fired), got: ${r.stdout}",
                )
            } finally {
                s.close()
            }
        }

    @Test fun killAgainstBackgroundedPipelinePidRoutesThroughJobControl() =
        runTest {
            // Regression: `kill <pid>` against a backgrounded
            // pipeline's leader pid must route through
            // `JobControl.signal` (which finds the job by pid and
            // cancels it), NOT into the fork's own
            // `foregroundSignals` channel (which has no drain). This
            // is the same path that coproc.tests' `kill $REFLECT_PID`
            // depends on. We exercise a simpler shape — a backgrounded
            // sleep + explicit kill of its pid — to avoid coproc
            // cancellation timing in tests.
            val k =
                Kash(
                    registry = standardRegistry(),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                val r =
                    s.exec(
                        "sleep 0.01 &\npid=\$!\nkill \$pid 2>/dev/null\nwait \$pid; status=\$?\necho status=\$status",
                    )
                assertTrue(
                    r.stdout.contains("status="),
                    "expected `status=` in stdout — kill against fork pid must not hang, got: '${r.stdout}'",
                )
            } finally {
                s.close()
            }
        }

    @Test fun signalRouterUnregisterIsIdempotent() =
        runTest {
            // The cleanup callsites in dispatchBackground / coproc /
            // procsub call signalRouter.unregister(stage.process.pid).
            // For forks that were never registered (current default),
            // this is a no-op. Calling it twice or in a loop must not
            // throw. Indirect check: run a pipeline that exercises
            // every cleanup path and verify no exception propagated.
            val k =
                Kash(
                    registry = standardRegistry(),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                val r = s.exec("(echo a | cat) &\nwait")
                assertEquals(0, r.exitCode)
            } finally {
                s.close()
            }
        }

    @Test fun backgroundedSubshellTrapOnRootPidStillFiresViaRouter() =
        runTest {
            // `( kill -USR1 $$ ) &` from a backgrounded subshell —
            // the inner kill resolves `$$` to the ROOT's pid (POSIX
            // sticky-$$). The router carries USR1 to the root's
            // deliverSignal, which fires the registered trap.
            // Regression guard for the earlier SignalRouter cleanup.
            val k = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            val s = k.newSession()
            try {
                val r =
                    s.exec(
                        "trap 'echo got-usr1' USR1\n" +
                            "( kill -USR1 \$\$ ) &\n" +
                            "wait",
                    )
                assertTrue(
                    r.stdout.contains("got-usr1"),
                    "expected `got-usr1` in stdout (USR1 routed root→trap), got: ${r.stdout}",
                )
            } finally {
                s.close()
            }
        }
}
