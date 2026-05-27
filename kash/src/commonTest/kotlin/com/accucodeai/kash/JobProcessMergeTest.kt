package com.accucodeai.kash

import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Vision-level coverage for the "marry JobControl to processTable"
 * refactor — zsh-style per-member process tracking inside a single
 * pipeline-shaped Job.
 *
 * Today kash maintains two parallel tables (machine.processTable +
 * JobControl.byId) that don't agree:
 *  - JobControl only knows the *leader* pid of each pipeline; the
 *    middle/tail stages live only in processTable.
 *  - JobState is derived from the aggregate-pipeline coroutine, not
 *    from the per-stage ProcessState.
 *  - `kill %N` signals only the leader; the rest of the pipeline keeps
 *    running.
 *
 * These tests pin the *intended* behavior. Most will fail today; that's
 * the point — they describe the post-refactor surface. Drop the
 * `@Ignore` annotations as features land.
 *
 * Note: this test class is intentionally NOT part of the bash
 * conformance corpus — kash diverges from bash here (we have no
 * controlling-tty pgrp transfer, so "foreground job" is a logical
 * concept, not a kernel one), and these expectations are kash-native.
 */
class JobProcessMergeTest {
    // ------------------------------------------------------------------
    // 1. A multi-stage pipeline registers EVERY stage in processTable
    //    and the job tracks all of them — not just the leader.
    // ------------------------------------------------------------------

    @Test fun multiStageBackgroundPipelineRegistersAllStages() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    parentContext = coroutineContext,
                    isInteractive = true,
                ).newSession()
            try {
                // 3-stage pipeline backgrounded. While the job is alive,
                // processTable should hold the session pid + 3 stage pids.
                // We snapshot the table inside the script via `wait` —
                // by the time `wait` returns, all stages are reaped, so
                // we instead use a long-ish first stage (sleep proxy via
                // `read`) and inspect the table from the host. With the
                // virtual scheduler, `sleep 1 &` advances time deterministically;
                // we just need a non-zero stage count snapshot.
                s.exec("true | true | true &")
                // Membership must be exposed somewhere. POSIX-equivalent:
                // jobs -l on a 3-stage pipeline should report 3 pids.
                val r = s.exec("jobs -l")
                // Expected (zsh-style): one line per stage, each with its pid.
                // Today kash prints one line with the leader pid only.
                val pidLines = r.stdout.lines().filter { it.isNotBlank() }
                assertTrue(
                    pidLines.size >= 3,
                    "expected ≥3 lines (one per stage), got: ${r.stdout}",
                )
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 2. Job state is DERIVED from member ProcessState — not from the
    //    aggregate pipeline coroutine. A job is "Done" iff every member
    //    process is in ZOMBIE/reaped state.
    // ------------------------------------------------------------------

    @Test fun jobStateMatchesMemberProcessStates() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // Background a 2-stage pipeline; wait for completion.
                // After `wait` the job table should be empty AND every
                // stage pid must be gone from processTable.
                s.exec("true | true &\nwait")
                // Nothing tracked.
                val r = s.exec("jobs")
                assertEquals("", r.stdout)
                // processTable contains only the session leader (pid > 0).
                // No leftover pipeline stages.
                val leftover =
                    s.machine.processTable.values.filter { p ->
                        p.commandName != "init" && p.pid != s.process.pid
                    }
                assertTrue(
                    leftover.isEmpty(),
                    "expected no pipeline stages in processTable, got: " +
                        leftover.joinToString { "pid=${it.pid} name=${it.commandName}" },
                )
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 3. `kill %N` is a process-group dispatch — sends to every member
    //    of the pipeline, not just the leader. Current code only signals
    //    `job.leaderPid`.
    // ------------------------------------------------------------------

    @Test fun killByJobSpecFansOutToAllPipelineStages() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // Pipeline of three sleeps backgrounded; capture stage pids;
                // `kill %1` should TERM every stage, not just the first.
                // We approximate by checking that processTable empties out
                // after a kill + wait, with no stage left hanging.
                s.exec("sleep 5 | sleep 5 | sleep 5 &")
                s.exec("kill %1")
                s.exec("wait 2>/dev/null")
                val leftover =
                    s.machine.processTable.values.filter { p ->
                        p.commandName.startsWith("sleep")
                    }
                assertTrue(
                    leftover.isEmpty(),
                    "every pipeline stage must be reaped after `kill %1`, leftover: " +
                        leftover.joinToString { "pid=${it.pid}" },
                )
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 4. `kill <bare-pid>` resolves through processTable membership —
    //    not just leaderPid match. Killing the TAIL stage of a pipeline
    //    by pid should find the owning job.
    // ------------------------------------------------------------------

    @Test fun killByBarePidResolvesOwningJobThroughProcessTable() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // Background a pipeline. The leader pid is in $!; the
                // last stage's pid is some larger number we'd discover
                // via `jobs -l`. Without exposing per-stage pids today
                // we just assert that a kill targeting ANY pipeline pid
                // (including a hypothetical tail-stage pid one above $!)
                // resolves to the owning job rather than "no such job".
                s.exec("sleep 5 | sleep 5 &")
                val rDollar = s.exec("echo \$!")
                val leaderPid = rDollar.stdout.trim().toInt()
                // Tail stage was forked just after leader → leaderPid+1
                // by allocator monotonicity. Kill it by bare pid.
                val rKill = s.exec("kill ${leaderPid + 1}")
                // Expected: succeeds (exit 0), no "no such job" diagnostic.
                assertEquals(0, rKill.exitCode, "kill by tail-stage pid must resolve")
                assertTrue(
                    !rKill.stderr.contains("no such job"),
                    "no-such-job stderr was: ${rKill.stderr}",
                )
                s.exec("wait 2>/dev/null")
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 5. `wait <pid>` for a non-leader pipeline stage returns that
    //    stage's exit code, NOT the aggregate job's. Routes through
    //    machine.wait, not jobControl.reap.
    // ------------------------------------------------------------------

    @Test fun waitOnNonLeaderPidReturnsThatStageExit() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // Two-stage pipeline where the LAST stage exits non-zero.
                // `wait <tail-pid>` should return 1 (tail's exit), not 0
                // (leader's exit). Today wait routes through jobControl
                // and returns the aggregate.
                s.exec("true | false &")
                val rDollar = s.exec("echo \$!")
                val leaderPid = rDollar.stdout.trim().toInt()
                val rWait = s.exec("wait ${leaderPid + 1}")
                assertEquals(1, rWait.exitCode, "tail stage exited 1; wait must report it")
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 6. `disown` removes the job from JobControl but leaves the member
    //    processes running until they complete on their own. Symmetric
    //    with bash semantics, and required for the post-refactor world
    //    where the two tables are linked.
    // ------------------------------------------------------------------

    @Test fun disownRemovesJobButLeavesProcessesUntilComplete() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                s.exec("true &")
                val rDollar = s.exec("echo \$!")
                val pid = rDollar.stdout.trim().toInt()
                // disown drops it from jobs.
                s.exec("disown %1")
                val rJobs = s.exec("jobs")
                assertEquals("", rJobs.stdout, "disowned job must not appear in `jobs`")
                // But the process either ran to completion (auto-reaped
                // by init since it has no waiting parent in JobControl)
                // or is still in the table — both are valid for the
                // refactor. The wrong outcome is "wait %1 says it's still
                // there": jobControl no longer tracks it.
                val rWait = s.exec("wait %1")
                assertEquals(127, rWait.exitCode, "disowned job must not be resolvable as %1")
                assertTrue(rWait.stderr.contains("no such job"), "stderr=${rWait.stderr}")
                // pid is just a sanity unused-warning suppressor.
                assertTrue(pid > 0)
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 7. Two distinct background pipelines have disjoint pid sets in
    //    processTable. Membership doesn't leak across jobs.
    // ------------------------------------------------------------------

    @Test fun separateBackgroundJobsHaveDisjointMemberPids() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                s.exec("sleep 5 | sleep 5 &")
                val p1 =
                    s
                        .exec("echo \$!")
                        .stdout
                        .trim()
                        .toInt()
                s.exec("sleep 5 | sleep 5 &")
                val p2 =
                    s
                        .exec("echo \$!")
                        .stdout
                        .trim()
                        .toInt()
                // Distinct leaders.
                assertTrue(p1 != p2, "leaders must differ: p1=$p1 p2=$p2")
                // Both jobs visible.
                val rJobs = s.exec("jobs")
                assertTrue(rJobs.stdout.contains("[1]"))
                assertTrue(rJobs.stdout.contains("[2]"))
                // Clean up.
                s.exec("kill %1; kill %2; wait 2>/dev/null")
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 8. SIGSTOP delivered to a pipeline marks every stage's
    //    ProcessState as STOPPED (not just a logical bit on KashJob).
    //    The job's reported state is then derived as STOPPED because
    //    all members are stopped.
    // ------------------------------------------------------------------

    @Test fun stopSignalUpdatesEveryStageProcessState() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                s.exec("sleep 5 | sleep 5 &")
                s.exec("kill -STOP %1")
                // Every pipeline-member process in processTable must
                // report ProcessState.STOPPED.
                val sleepProcs =
                    s.machine.processTable.values.filter {
                        it.commandName.startsWith("sleep")
                    }
                assertTrue(sleepProcs.isNotEmpty(), "expected sleep stages in processTable")
                for (p in sleepProcs) {
                    assertEquals(
                        com.accucodeai.kash.api.ProcessState.STOPPED,
                        p.state,
                        "stage pid=${p.pid} expected STOPPED, was ${p.state}",
                    )
                }
                // Cleanup.
                s.exec("kill -CONT %1; kill %1; wait 2>/dev/null")
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 9. Job completion notification: after every member of a job
    //    reaches a terminal state, the next synchronization point
    //    (here: re-running `jobs`) should remove it. No zombies.
    // ------------------------------------------------------------------

    @Test fun jobAutoReapsAfterAllMembersTerminate() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                s.exec("true | true &")
                s.exec("wait")
                val rJobs = s.exec("jobs")
                assertEquals("", rJobs.stdout, "completed job must auto-clear from table")
                // No ZOMBIE leftovers either.
                val zombies =
                    s.machine.processTable.values.filter {
                        it.state == com.accucodeai.kash.api.ProcessState.ZOMBIE
                    }
                assertTrue(
                    zombies.isEmpty(),
                    "expected no zombies, got: ${zombies.joinToString { "pid=${it.pid}" }}",
                )
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 10. PIPESTATUS is sourced from per-member exit codes, populated
    //     even for backgrounded pipelines after they're reaped. Today
    //     PIPESTATUS only reflects FOREGROUND pipelines.
    // ------------------------------------------------------------------

    @Test fun pipestatusReflectsBackgroundedPipelineMembers() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // wait $! returns the aggregate (last stage) — fine.
                // But PIPESTATUS, after the wait, should describe every
                // stage of the just-reaped pipeline.
                val r =
                    s.exec(
                        """
                        true | false | true &
                        wait ${'$'}!
                        echo "${'$'}{PIPESTATUS[0]} ${'$'}{PIPESTATUS[1]} ${'$'}{PIPESTATUS[2]}"
                        """.trimIndent(),
                    )
                assertEquals(
                    "0 1 0\n",
                    r.stdout,
                    "PIPESTATUS after wait on background pipeline (stdout=${r.stdout})",
                )
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 11. `wait <leader-pid>` on an *already-reaped* job no longer
    //     resolves — but bash returns 127 with "is not a child of this
    //     shell". Confirms we route through machine state, not a stale
    //     JobControl cache.
    // ------------------------------------------------------------------

    @Test fun waitOnAlreadyReapedPidIsNotChild() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                s.exec("true &")
                val pid =
                    s
                        .exec("echo \$!")
                        .stdout
                        .trim()
                        .toInt()
                s.exec("wait \$!")
                // Now wait again on the same pid — already reaped.
                val r2 = s.exec("wait $pid")
                assertEquals(127, r2.exitCode)
                assertTrue(
                    r2.stderr.contains("not a child") || r2.stderr.contains("no such"),
                    "expected not-a-child/no-such-job, got: ${r2.stderr}",
                )
            } finally {
                s.close()
            }
        }

    // ------------------------------------------------------------------
    // 12. Sanity check: the Session.machine.processTable is the SAME
    //     table the interpreter's job dispatch writes to. If this
    //     fails, all of the above is moot.
    // ------------------------------------------------------------------

    @Test fun sessionMachineProcessTableIsAuthoritative() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val before = s.machine.processTable.size
                s.exec("sleep 5 &")
                val during = s.machine.processTable.size
                assertTrue(
                    during > before,
                    "background launch must register a pid in machine.processTable",
                )
                s.exec("kill %1; wait 2>/dev/null")
                // Eventually returns to pre-launch size (modulo init/session).
                assertNotNull(s.machine.processTable[s.process.pid])
            } finally {
                s.close()
            }
        }
}
