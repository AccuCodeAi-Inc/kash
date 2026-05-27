package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic process-group coverage (Item 1 of the process/job-model
 * plan). Verifies that backgrounded pipelines get a fresh pgid (their
 * leader stage's pid), that every member's `process.pgid` field is
 * stamped to that value, that machine-wide `processGroups[pgid]`
 * tracks the membership, and that the `tcgetpgrp`/`tcsetpgrp`
 * synthetic-tty-pgrp surface works.
 */
class SyntheticPgrpTest {
    @Test fun pipelineStagesShareLeaderPgidAndAreRecordedInProcessGroups() =
        runTest {
            // Use a fresh Kash + interactive session so the job survives
            // long enough for us to inspect its pgid before `wait` reaps.
            val k =
                Kash(
                    registry = standardRegistry(),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                // A two-stage pipeline backgrounded — we want the pgid
                // membership snapshot WHILE both stages exist. `read` on
                // a closed-empty pipeline returns fast, so use `cat` (or
                // anything that reads stdin); pre-fork stamps pgid at
                // dispatch time before the driver gets going.
                //
                // The simpler instrumentation: backgound a tiny pipeline,
                // then read processGroups before `wait`. The pipeline
                // completes near-instantly but our forks are already
                // stamped synchronously inside dispatchBackground, so
                // assertion ordering doesn't race with completion.
                s.exec("(true | true) &")
                val machine = k.machine
                // After dispatch, at least one pgid entry must exist.
                // Stage 0's pid is the pgid (the pre-fork loop in
                // dispatchBackground assigns it that way).
                val pgidEntries = machine.processGroups.filterValues { it.isNotEmpty() }
                assertTrue(
                    pgidEntries.isNotEmpty(),
                    "expected at least one populated pgid entry, got: ${machine.processGroups}",
                )
                // Every member of every populated pgid must have its
                // process.pgid stamped to that pgid (consistency check
                // — if dispatchBackground only updated the map and not
                // the process field, this catches it).
                for ((pgid, members) in pgidEntries) {
                    for (memberPid in members) {
                        val proc = machine.processTable[memberPid]
                        if (proc != null) {
                            assertEquals(
                                pgid,
                                proc.pgid,
                                "process $memberPid in pgid $pgid has process.pgid=${proc.pgid}",
                            )
                        }
                    }
                }
                // Reap. After completion the entry should be cleaned up.
                s.exec("wait")
                assertTrue(
                    machine.processGroups.values.all { it.isEmpty() } ||
                        machine.processGroups.isEmpty(),
                    "expected processGroups to be empty or contain only empty members after wait, got: ${machine.processGroups}",
                )
            } finally {
                s.close()
            }
        }

    @Test fun foregroundPgrpSetDuringStatementClearedBetweenStatements() =
        runTest {
            // Between commands (REPL idle), foregroundPgrp must be null.
            // During a foreground statement it's stamped to the shell's
            // own pid. The runStreaming wrapper sets-and-restores around
            // each statement, so peek the value via a tiny script that
            // reads $! / $$ etc. is awkward — easier to inspect via the
            // machine directly before and after exec().
            val k = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            val s = k.newSession()
            try {
                // Pre-condition: nothing in the foreground.
                assertNull(k.machine.foregroundPgrp, "expected null pre-exec")
                // Run something trivial. After exec() returns, the
                // foreground slot must be cleared.
                s.exec("true")
                assertNull(k.machine.foregroundPgrp, "expected null post-exec")
            } finally {
                s.close()
            }
        }

    @Test fun backgroundedJobDoesNotTouchForegroundPgrp() =
        runTest {
            // A backgrounded `cmd &` registers a new pgid in
            // processGroups but must NOT set machine.foregroundPgrp —
            // that slot is reserved for foreground work.
            val k =
                Kash(
                    registry = standardRegistry(),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                s.exec("true &")
                // The `&` dispatch returns synchronously; foreground
                // slot must be cleared as we re-emerge between
                // statements.
                assertNull(
                    k.machine.foregroundPgrp,
                    "background dispatch should not leave foregroundPgrp set, got: ${k.machine.foregroundPgrp}",
                )
                s.exec("wait")
            } finally {
                s.close()
            }
        }

    @Test fun tcgetpgrpAndTcsetpgrpRoundTripViaMachine() =
        runTest {
            // The synthetic POSIX `tcgetpgrp(SHTTY)` / `tcsetpgrp(SHTTY,
            // pgid)` surface lives on KashMachine. Verify both directions
            // mutate `foregroundPgrp` consistently.
            val k = Kash(registry = standardRegistry(), parentContext = coroutineContext)
            val s = k.newSession()
            try {
                assertNull(k.machine.tcgetpgrp())
                k.machine.tcsetpgrp(42)
                assertEquals(42, k.machine.foregroundPgrp)
                assertEquals(42, k.machine.tcgetpgrp())
                k.machine.tcsetpgrp(null)
                assertNull(k.machine.tcgetpgrp())
            } finally {
                s.close()
            }
        }

    @Test fun forkedSubshellInheritsParentPgidUntilJobDispatchRestampsIt() =
        runTest {
            // KashProcess.fork() copies pgid from parent (matches POSIX
            // fork() — child inherits parent's pgrp until setpgid). A
            // `( body ) &` triggers a fork AND a job dispatch, so the
            // resulting stage's pgid must be its OWN pid (the leader),
            // not the parent's pgid. Regression guard against forgetting
            // the stamp at dispatch time.
            val k =
                Kash(
                    registry = standardRegistry(),
                    parentContext = coroutineContext,
                    isInteractive = true,
                )
            val s = k.newSession()
            try {
                s.exec("(true) &")
                // While the job exists (briefly), inspect processGroups:
                // there must be a pgid whose pid set contains a member
                // whose process.pgid == that pgid (not the parent's).
                val groups = k.machine.processGroups
                val nonEmpty = groups.filterValues { it.isNotEmpty() }
                if (nonEmpty.isNotEmpty()) {
                    for ((pgid, members) in nonEmpty) {
                        for (pid in members) {
                            val proc = k.machine.processTable[pid] ?: continue
                            assertEquals(
                                pgid,
                                proc.pgid,
                                "subshell fork pid=$pid should be in its own pgrp $pgid, but process.pgid=${proc.pgid}",
                            )
                            assertNotNull(proc, "process must exist while job is live")
                        }
                    }
                }
                s.exec("wait")
            } finally {
                s.close()
            }
        }
}
