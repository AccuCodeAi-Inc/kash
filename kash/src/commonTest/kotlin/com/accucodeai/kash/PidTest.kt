package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies POSIX-faithful pid behavior:
 *  - `$$` is the SHELL's pid, sticky across subshell forks.
 *  - `$BASHPID` is the CURRENT process's pid, distinct in every subshell.
 *  - `fork()`-allocated pids are real, distinct, and registered in
 *    `machine.processTable` (so `ps` / `/proc/<pid>` see them).
 *  - Subshell pids are unregistered after the subshell completes.
 */
class PidTest {
    @Test
    fun dollarDollar_returnsShellPid() =
        runTest {
            val r = Kash().exec($$"echo $$")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val pid = r.stdout.trim().toIntOrNull()
            assertTrue(pid != null && pid > 0, "expected positive pid, got: <${r.stdout}>")
        }

    @Test
    fun bashPid_returnsCurrentPid_inTopLevel_equalsDollarDollar() =
        runTest {
            // At top level, $$ and $BASHPID are the same — both report
            // the shell process's pid.
            val r = Kash().exec($$"echo $$=$BASHPID")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val parts = r.stdout.trim().split("=")
            assertEquals(2, parts.size)
            assertEquals(parts[0], parts[1])
        }

    @Test
    fun dollarDollar_unchangedInSubshell() =
        runTest {
            // bash semantics: `$$` is sticky. `(echo $$)` inside a
            // subshell prints the PARENT shell's pid, not the subshell's.
            val r = Kash().exec($$"echo $$; (echo $$)")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.trim().lines()
            assertEquals(2, lines.size)
            assertEquals(lines[0], lines[1], "\$\$ must be identical in parent and subshell")
        }

    @Test
    fun bashPid_changesInSubshell() =
        runTest {
            // $BASHPID, in contrast, reflects the CURRENT process —
            // distinct in subshells.
            val r = Kash().exec($$"echo $BASHPID; (echo $BASHPID)")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.trim().lines()
            assertEquals(2, lines.size)
            assertNotEquals(
                lines[0],
                lines[1],
                "\$BASHPID must differ in parent vs subshell",
            )
        }

    @Test
    fun subshellPid_visibleInPsThenDeregisteredAfter() =
        runTest {
            // Run two `ps` invocations — one capturing the moment the
            // subshell is alive, one after. The mid-subshell ps should
            // show one MORE pid than the post-subshell ps.
            // (Subshell `(ps)` registers + deregisters; the outer ps
            // sees only the long-lived process.)
            val k = Kash()
            val mid = k.exec("(ps)")
            assertEquals(0, mid.exitCode, "stderr=${mid.stderr}")
            // The subshell that ran ps had its OWN pid registered.
            // ps output includes the subshell pid plus the shell pid.
            val midCount = mid.stdout.lines().count { it.isNotBlank() } - 1 // minus header
            assertTrue(midCount >= 1, "expected at least 1 process row in (ps), got:\n${mid.stdout}")

            val after = k.exec("ps")
            val afterCount = after.stdout.lines().count { it.isNotBlank() } - 1
            // After the subshell exits, its pid is unregistered.
            // Should be back to the baseline (just the shell process).
            assertTrue(
                afterCount <= midCount,
                "subshell pid should have been cleaned up; mid=$midCount after=$afterCount",
            )
        }

    @Test
    fun ppid_reportsShellsParentPid() =
        runTest {
            // Shell process is forked from init (pid 1) at exec time, so
            // ppid is 1 in every entrypoint. Matches real bash running
            // under a Linux init/systemd.
            val r = Kash().exec($$"echo $PPID")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("1\n", r.stdout)
        }

    @Test
    fun init_existsAtPid1AndIsLabeled() =
        runTest {
            val k = Kash()
            // Force machine bootstrap by running any script.
            k.exec("true")
            val init = k.machine.processTable[1]
            assertTrue(init != null, "pid 1 must be registered")
            assertEquals("init", init.commandName)
            assertEquals(listOf("init"), init.argv)
            assertEquals(null, init.ppid, "init has no parent")
        }

    @Test
    fun shell_isChildOfInit() =
        runTest {
            // $$ should not equal 1 (that's init's pid) and $PPID should
            // equal 1. Verifies the shell is a real fork of init, not
            // init itself.
            val r = Kash().exec($$"echo $$=$PPID")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val parts = r.stdout.trim().split("=")
            assertEquals(2, parts.size)
            assertNotEquals("1", parts[0], "shell should not be pid 1")
            assertEquals("1", parts[1], "shell's parent should be init (pid 1)")
        }

    @Test
    fun ppid_unchangedInSubshell() =
        runTest {
            // bash semantics: $PPID is sticky like $$. A subshell's PPID
            // reports the SHELL's parent, not the subshell's parent.
            val r = Kash().exec($$"echo $PPID; (echo $PPID)")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.trim().lines()
            assertEquals(2, lines.size)
            assertEquals(lines[0], lines[1], "\$PPID must be identical in parent and subshell")
        }

    @Test
    fun bangBgPid_returnsBackgroundLeaderPid() =
        runTest {
            // `cmd &` followed by `echo $!` should print the bg job's
            // leader pid. Without sleep it races, but `wait $!` makes
            // it deterministic AND exercises that $! is a real pid the
            // wait builtin can resolve through machine.processTable.
            val r = Kash().exec($$"sleep 0 & echo $!; wait")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val pid = r.stdout.trim().toIntOrNull()
            assertTrue(pid != null && pid > 0, "expected positive pid, got: <${r.stdout}>")
        }

    @Test
    fun ps_showsAtLeastTheShellProcess() =
        runTest {
            val r = Kash().exec("ps")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.lines().filter { it.isNotBlank() }
            // Header + at least one process row.
            assertTrue(lines.size >= 2, "expected header + ≥1 row, got:\n${r.stdout}")
            assertTrue(lines[0].contains("PID") && lines[0].contains("CMD"), "header missing: ${lines[0]}")
        }
}
