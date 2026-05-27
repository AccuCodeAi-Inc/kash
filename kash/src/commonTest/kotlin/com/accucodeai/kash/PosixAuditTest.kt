package com.accucodeai.kash

import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * POSIX-conformance pins for the builtins we recently touched. These
 * lock in behavior the audit identified as deviations from the spec:
 * `kill -l` should not surface pseudo-signals, `kill -l <exit-code>`
 * should resolve via the +128 offset, `trap -p` should print.
 */
class PosixAuditTest {
    @Test fun killDashLOmitsPseudoSignals() =
        runTest {
            val r = Kash().exec("kill -l")
            assertEquals(0, r.exitCode)
            // Deliverable signals appear...
            assertTrue(r.stdout.contains("SIGINT"), "expected SIGINT in: ${r.stdout}")
            assertTrue(r.stdout.contains("SIGTERM"), "stdout=${r.stdout}")
            assertTrue(r.stdout.contains("SIGTSTP"), "stdout=${r.stdout}")
            assertTrue(r.stdout.contains("SIGCONT"), "stdout=${r.stdout}")
            // ...pseudo-signals do not.
            assertFalse(r.stdout.contains("SIGDEBUG"), "stdout=${r.stdout}")
            assertFalse(r.stdout.contains("SIGRETURN"), "stdout=${r.stdout}")
            assertFalse(r.stdout.contains("SIGERR"), "stdout=${r.stdout}")
            assertFalse(r.stdout.contains("SIGEXIT"), "stdout=${r.stdout}")
            // No negative numbers in the listing.
            assertFalse(r.stdout.contains("-1)"), "stdout=${r.stdout}")
        }

    @Test fun killDashLResolvesExitCode() =
        runTest {
            // POSIX: `kill -l 130` → `INT` (130 = 128 + SIGINT.number=2).
            val r = Kash().exec("kill -l 130")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("INT\n", r.stdout)

            // 143 = 128 + 15 (TERM).
            val r2 = Kash().exec("kill -l 143")
            assertEquals(0, r2.exitCode, "stderr=${r2.stderr}")
            assertEquals("TERM\n", r2.stdout)
        }

    @Test fun killDashLNameYieldsNumber() =
        runTest {
            val r = Kash().exec("kill -l INT")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("2\n", r.stdout)
        }

    @Test fun killDashLBareNumber() =
        runTest {
            // `kill -l 2` (bare signal number, <128) → signal name.
            val r = Kash().exec("kill -l 2")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("INT\n", r.stdout)
        }

    @Test fun killNumericSignalParsesStopContTstp() =
        runTest {
            // Verify numeric forms of the job-control signals route through
            // the existing parser. Target a missing job so we don't rely on
            // bg-job timing — the test is that the parse succeeds, not that
            // delivery does anything observable.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r17 = s.exec("kill -17 %99") // STOP
                assertFalse(r17.stderr.contains("invalid signal"), "stderr=${r17.stderr}")
                val r18 = s.exec("kill -18 %99") // TSTP
                assertFalse(r18.stderr.contains("invalid signal"), "stderr=${r18.stderr}")
                val r19 = s.exec("kill -19 %99") // CONT
                assertFalse(r19.stderr.contains("invalid signal"), "stderr=${r19.stderr}")
            } finally {
                s.close()
            }
        }

    @Test fun trapDashPListsAllWithNoArgs() =
        runTest {
            val r =
                Kash().exec(
                    """
                    trap 'echo a' INT
                    trap 'echo b' TERM
                    trap -p
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // Real bash prepends `SIG` to real signal names on listing —
            // verified against bash 3.2:
            //   $ bash -c "trap 'echo a' INT; trap 'echo b' TERM; trap -p"
            //   trap -- 'echo a' SIGINT
            //   trap -- 'echo b' SIGTERM
            assertTrue(r.stdout.contains("trap -- 'echo a' SIGINT"), "stdout=${r.stdout}")
            assertTrue(r.stdout.contains("trap -- 'echo b' SIGTERM"), "stdout=${r.stdout}")
        }

    @Test fun trapDashPFiltersBySignal() =
        runTest {
            val r =
                Kash().exec(
                    """
                    trap 'echo a' INT
                    trap 'echo b' TERM
                    trap -p TERM
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertTrue(r.stdout.contains("TERM"), "stdout=${r.stdout}")
            assertFalse(r.stdout.contains("INT"), "stdout=${r.stdout}")
        }

    @Test fun trapDashPRejectsInvalidSignal() =
        runTest {
            val r = Kash().exec("trap -p NOPE")
            assertEquals(1, r.exitCode)
            assertTrue(r.stderr.contains("invalid signal"), "stderr=${r.stderr}")
        }
}
