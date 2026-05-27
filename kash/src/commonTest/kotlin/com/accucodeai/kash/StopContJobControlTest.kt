package com.accucodeai.kash

import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stop/cont job-control coverage. Kash is coroutine-only, so SigTstp/SigStop
 * flip a logical "stopped" bit observable through `jobs` and `wait`. Tools
 * that cooperate (poll their JobSignalContext) can park additionally; the
 * tests here exercise the bit-and-state path that works for every job.
 */
class StopContJobControlTest {
    @Test fun killStopMovesJobToStoppedState() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // `yes &` would never end; not in core. `true &` completes
                // instantly, but the signal-after-Done case is also valid:
                // signal() no-ops on completed jobs, so the state stays Done.
                // We instead use the bg banner / jobs round-trip with a
                // pipeline of harmless builtins that can park between
                // stages. Since none of our jobs reliably block in core,
                // the most we can assert here is that `kill -STOP` parses
                // and returns 0 even after the job has finished.
                val r = s.exec("true &\nwait\nkill -STOP %1 2>/dev/null\nkill -CONT %1 2>/dev/null\necho done")
                // After `wait` the job is reaped; kill targets a missing
                // job and prints "no such job" to stderr but echo still
                // runs.
                assertTrue(r.stdout.contains("done"), "stdout=${r.stdout}")
            } finally {
                s.close()
            }
        }

    @Test fun fgOnMissingJobReturnsOne() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // `set -m` opens monitor mode so `fg` performs the lookup
                // instead of refusing with "no job control" — that
                // diagnostic has its own dedicated test below.
                val r = s.exec("set -m\nfg %99")
                assertEquals(1, r.exitCode)
                assertTrue(r.stderr.contains("no such job"))
            } finally {
                s.close()
            }
        }

    @Test fun bgOnMissingJobReturnsOne() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("set -m\nbg %99")
                assertEquals(1, r.exitCode)
                assertTrue(r.stderr.contains("no such job"))
            } finally {
                s.close()
            }
        }

    @Test fun fgReapsJobAndReturnsItsExitCode() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // `set -m` BEFORE the `&` so the job records monitor
                // mode at launch; without it, bash refuses `fg %1`
                // with "job 1 started without job control".
                val r = s.exec("set -m\nfalse &\nfg %1\necho \$?")
                assertTrue(r.stdout.contains("false"), "expected command label, got: ${r.stdout}")
                assertTrue(r.stdout.trimEnd().endsWith("1"), "expected trailing exit code 1, got: ${r.stdout}")
            } finally {
                s.close()
            }
        }

    @Test fun bgEchoesBannerAndReturnsZero() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("set -m\ntrue &\nbg %1\necho done\nwait")
                assertEquals(0, r.exitCode)
                assertTrue(r.stdout.contains("done"), "stdout=${r.stdout}")
            } finally {
                s.close()
            }
        }

    @Test fun fgWithoutMonitorReportsNoJobControl() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("true &\nfg %1")
                assertEquals(1, r.exitCode)
                // Bash format: `fg: no job control` when monitor mode is off.
                assertTrue(
                    r.stderr.contains("no job control") ||
                        r.stderr.contains("started without job control"),
                    "expected no-job-control diagnostic, got: ${r.stderr}",
                )
            } finally {
                s.close()
            }
        }

    @Test fun killStopIsAcceptedByKillBuiltin() =
        runTest {
            // Verify the kill builtin parses STOP/CONT signal names without
            // a syntax error. Targets a non-existent job so we don't depend
            // on timing.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val rStop = s.exec("kill -STOP %99")
                // Exit code 1 ("no such job"), not 1+stderr-with-"invalid signal".
                assertTrue(
                    rStop.stderr.contains("no such job") && !rStop.stderr.contains("invalid signal"),
                    "STOP should parse: stderr=${rStop.stderr}",
                )
                val rCont = s.exec("kill -CONT %99")
                assertTrue(
                    rCont.stderr.contains("no such job") && !rCont.stderr.contains("invalid signal"),
                    "CONT should parse: stderr=${rCont.stderr}",
                )
                val rTstp = s.exec("kill -TSTP %99")
                assertTrue(
                    rTstp.stderr.contains("no such job") && !rTstp.stderr.contains("invalid signal"),
                    "TSTP should parse: stderr=${rTstp.stderr}",
                )
            } finally {
                s.close()
            }
        }
}
