package com.accucodeai.kash.tools.timeout

import com.accucodeai.kash.api.UtilityRunner
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeoutCommandTest {
    private val cmd = TimeoutCommand()

    /** A runner that records that it was invoked and returns [code] immediately. */
    private fun fixedRunner(
        code: Int,
        onRun: () -> Unit = {},
    ) = UtilityRunner { _, _, _, _, _ ->
        onRun()
        code
    }

    /**
     * A runner that writes [before], suspends for [delayMs], then writes
     * [after] and returns 0. Under a tight timeout the [after] write must
     * never happen — that's the cooperative-cancellation assertion.
     */
    private fun slowRunner(
        delayMs: Long,
        before: String,
        after: String,
    ) = UtilityRunner { _, _, _, stdout, _ ->
        stdout.writeUtf8(before)
        delay(delayMs)
        stdout.writeUtf8(after)
        0
    }

    @Test fun returnsChildExitWhenItFinishesInTime() =
        runTest {
            val rc = cmd.run(listOf("10", "fake"), bareCommandContext(utilityRunner = fixedRunner(0)))
            assertEquals(0, rc.exitCode)
        }

    @Test fun passesThroughNonZeroChildExit() =
        runTest {
            val rc = cmd.run(listOf("10", "fake"), bareCommandContext(utilityRunner = fixedRunner(3)))
            assertEquals(3, rc.exitCode)
        }

    @Test fun passesThroughCommandNotFound() =
        runTest {
            // The interpreter's UtilityRunner returns 127 for an unknown name.
            val rc = cmd.run(listOf("10", "nope"), bareCommandContext(utilityRunner = fixedRunner(127)))
            assertEquals(127, rc.exitCode)
        }

    @Test fun timesOutWithExit124AndCancelsChild() =
        runTest {
            val out = Buffer()
            val rc =
                cmd.run(
                    listOf("1", "slow"),
                    bareCommandContext(
                        stdout = out.asSuspendSink(),
                        utilityRunner = slowRunner(delayMs = 10_000, before = "before\n", after = "after\n"),
                    ),
                )
            assertEquals(124, rc.exitCode)
            // The child was cancelled mid-delay: its pre-delay write survives,
            // the post-delay write never ran.
            assertEquals("before\n", out.readString())
        }

    @Test fun durationZeroDisablesTimeout() =
        runTest {
            // A 10s child under a "0" (disabled) timeout runs to completion.
            val out = Buffer()
            val rc =
                cmd.run(
                    listOf("0", "slow"),
                    bareCommandContext(
                        stdout = out.asSuspendSink(),
                        utilityRunner = slowRunner(delayMs = 10_000, before = "a", after = "b"),
                    ),
                )
            assertEquals(0, rc.exitCode)
            assertEquals("ab", out.readString())
        }

    @Test fun durationSuffixIsHonored() =
        runTest {
            // 1m timeout, child finishes after 500ms virtual → completes.
            val rc =
                cmd.run(
                    listOf("1m", "slow"),
                    bareCommandContext(utilityRunner = slowRunner(delayMs = 500, before = "", after = "")),
                )
            assertEquals(0, rc.exitCode)
        }

    @Test fun preserveStatusReturns128PlusSignalOnTimeout() =
        runTest {
            val rc =
                cmd.run(
                    listOf("--preserve-status", "1", "slow"),
                    bareCommandContext(utilityRunner = slowRunner(10_000, "", "")),
                )
            assertEquals(143, rc.exitCode) // 128 + SIGTERM(15)
        }

    @Test fun signalNameShapesPreserveStatusExit() =
        runTest {
            val rc =
                cmd.run(
                    listOf("-s", "KILL", "--preserve-status", "1", "slow"),
                    bareCommandContext(utilityRunner = slowRunner(10_000, "", "")),
                )
            assertEquals(137, rc.exitCode) // 128 + SIGKILL(9)
        }

    @Test fun signalNumberAlsoAccepted() =
        runTest {
            val rc =
                cmd.run(
                    listOf("-s9", "--preserve-status", "1", "slow"),
                    bareCommandContext(utilityRunner = slowRunner(10_000, "", "")),
                )
            assertEquals(137, rc.exitCode)
        }

    @Test fun verboseDiagnosesOnTimeout() =
        runTest {
            val err = Buffer()
            val rc =
                cmd.run(
                    listOf("-v", "1", "myprog"),
                    bareCommandContext(
                        stderr = err.asSuspendSink(),
                        utilityRunner = slowRunner(10_000, "", ""),
                    ),
                )
            assertEquals(124, rc.exitCode)
            assertEquals("timeout: sending signal TERM to command 'myprog'\n", err.readString())
        }

    @Test fun invalidDurationIsUsageError() =
        runTest {
            val err = Buffer()
            val rc =
                cmd.run(
                    listOf("notanumber", "fake"),
                    bareCommandContext(stderr = err.asSuspendSink(), utilityRunner = fixedRunner(0)),
                )
            assertEquals(125, rc.exitCode)
            assertTrue(err.readString().contains("invalid time interval"))
        }

    @Test fun missingDurationIsUsageError() =
        runTest {
            val rc = cmd.run(emptyList(), bareCommandContext(utilityRunner = fixedRunner(0)))
            assertEquals(125, rc.exitCode)
        }

    @Test fun missingCommandIsUsageError() =
        runTest {
            val err = Buffer()
            val rc =
                cmd.run(
                    listOf("5"),
                    bareCommandContext(stderr = err.asSuspendSink(), utilityRunner = fixedRunner(0)),
                )
            assertEquals(125, rc.exitCode)
            assertTrue(err.readString().contains("missing operand"))
        }

    @Test fun unknownOptionIsUsageError() =
        runTest {
            val rc = cmd.run(listOf("-z", "5", "fake"), bareCommandContext(utilityRunner = fixedRunner(0)))
            assertEquals(125, rc.exitCode)
        }

    @Test fun doubleDashStopsOptionParsing() =
        runTest {
            // After `--`, `-v` is the DURATION operand (invalid) → usage error,
            // proving option parsing stopped.
            val rc = cmd.run(listOf("--", "-v", "fake"), bareCommandContext(utilityRunner = fixedRunner(0)))
            assertEquals(125, rc.exitCode)
        }

    @Test fun noInterpreterContextIsUsageError() =
        runTest {
            // utilityRunner defaults to null in a bare context.
            val rc = cmd.run(listOf("5", "fake"), bareCommandContext())
            assertEquals(125, rc.exitCode)
        }
}
