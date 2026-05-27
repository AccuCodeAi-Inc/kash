package com.accucodeai.kash

import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1 background-job coverage. Each session is constructed with
 * `parentContext = coroutineContext` so the session scope inherits the
 * `runTest` scheduler — backgrounds are children of the test scope and
 * tear down with it, no detached roots. The instant-complete commands
 * (`true`/`false`/`echo`) used below make virtual-time pacing unnecessary.
 */
class JobControlTest {
    @Test fun bgBannerAndWaitReapsZero() =
        runTest {
            // `isInteractive = true` because the `[N]` job banner is an
            // interactive-shell affordance; non-interactive bash (and kash
            // matching it for conformance) suppresses it.
            val s =
                Kash(
                    registry = standardRegistry(),
                    parentContext = coroutineContext,
                    isInteractive = true,
                ).newSession()
            try {
                val r = s.exec("true &\nwait")
                assertEquals(0, r.exitCode)
                // bash-style banner on stderr: `[1]`.
                assertTrue(r.stderr.contains("[1]"), "expected [1] banner, got: ${r.stderr}")
            } finally {
                s.close()
            }
        }

    @Test fun waitOnFalseReturnsExitOne() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // POSIX: `wait $!` waits for the most recent background pid.
                // $! is now a real pid; `wait` routes through machine.wait.
                val r = s.exec("false &\nwait \$!")
                assertEquals(1, r.exitCode)
            } finally {
                s.close()
            }
        }

    @Test fun dollarBangIsRealLeaderPid() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // `$!` returns the actual leader pid (real, allocated via
                // machine.allocatePid()) — NOT the kash job id. POSIX
                // requires this so `wait $!` / `kill $!` route through
                // the process table. Pid is positive and != session pid.
                val sessionPid = s.process.pid
                val r = s.exec("true &\necho \$!\nwait")
                val pid = r.stdout.trim().toIntOrNull()
                assertTrue(pid != null && pid > 0, "expected positive pid, got: <${r.stdout}>")
                assertTrue(pid != sessionPid, "bg pid must differ from session pid")
            } finally {
                s.close()
            }
        }

    @Test fun multipleBackgroundJobsHaveDistinctPids() =
        runTest {
            // Interactive: required for the `[N]` banners this test asserts.
            val s =
                Kash(
                    registry = standardRegistry(),
                    parentContext = coroutineContext,
                    isInteractive = true,
                ).newSession()
            try {
                // Three `&` jobs in sequence — $! tracks the MOST RECENT
                // one's leader pid. Each job allocates a fresh pid via
                // fork(), so the three banners ([1]/[2]/[3]) and the
                // final $! pid are all distinct.
                val r = s.exec("true &\ntrue &\ntrue &\necho \$!\nwait")
                val pid = r.stdout.trim().toIntOrNull()
                assertTrue(pid != null && pid > 0, "expected positive pid, got: <${r.stdout}>")
                assertTrue(r.stderr.contains("[1]"))
                assertTrue(r.stderr.contains("[2]"))
                assertTrue(r.stderr.contains("[3]"))
            } finally {
                s.close()
            }
        }

    @Test fun jobsBuiltinListsRunningOnly() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // Each `true &` completes near-instantly; `wait` reaps them.
                // `jobs` after `wait` shows nothing (all reaped).
                val r = s.exec("true &\nwait\njobs")
                assertEquals("", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test fun waitOnUnknownJobReturns127() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("wait %99")
                assertEquals(127, r.exitCode)
                assertTrue(r.stderr.contains("no such job"))
            } finally {
                s.close()
            }
        }

    @Test fun waitWithNoOperandReturnsZeroEvenWhenJobFails() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("false &\nwait")
                // POSIX: bare `wait` always returns 0.
                assertEquals(0, r.exitCode)
            } finally {
                s.close()
            }
        }

    @Test fun bgItselfReturnsZero() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("false &\necho \$?")
                // `$?` immediately after `cmd &` is 0 regardless of the
                // backgrounded command's eventual status.
                assertEquals("0\n", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test fun jobsListWithDashPPrintsIdsOnly() =
        runTest {
            // POSIX/bash: `jobs -p` prints the PID of each tracked job's
            // process-group leader, one per line — NOT the job-id. The
            // test name and an earlier expectation of "1\n" reflected a
            // misreading; the assertion now matches real bash output
            // shape (a single decimal-integer line, terminated by \n).
            //
            //   $ bash -c "true & jobs -p; wait"
            //   73899
            //
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("true &\njobs -p\nwait")
                assertTrue(
                    r.stdout.matches(Regex("\\d+\n")),
                    "expected single PID line, got: ${r.stdout}",
                )
            } finally {
                s.close()
            }
        }
}
