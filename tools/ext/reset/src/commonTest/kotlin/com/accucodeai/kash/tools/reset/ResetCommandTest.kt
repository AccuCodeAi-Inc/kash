package com.accucodeai.kash.tools.reset

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.test.FakeTerminalControl
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun runReset(
    args: List<String>,
    terminal: TerminalControl? = null,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            env = mutableMapOf(),
            cwd = "/",
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            stdinIsTty = terminal != null,
            stdoutIsTty = terminal != null,
            stderrIsTty = terminal != null,
            terminal = terminal,
        )
    val res = ResetCommand().run(args, ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class ResetCommandTest {
    @Test fun emitsFullResetSequence() =
        runTest {
            val (rc, out, err) = runReset(emptyList())
            assertEquals(0, rc)
            assertEquals("", err)
            val expected =
                "\u001Bc" +
                    "\u001B[!p" +
                    "\u001B[?7h" +
                    "\u001B[?25h" +
                    "\u001B[m" +
                    "\u001B[H\u001B[2J\u001B[3J"
            assertEquals(expected, out)
        }

    @Test fun callsExitRawModeOnTerminal() =
        runTest {
            val term = FakeTerminalControl()
            val (rc, _, _) = runReset(emptyList(), term)
            assertEquals(0, rc)
            assertEquals(1, term.exitRawCalls)
        }

    @Test fun bareOperandIsTreatedAsTermAndIgnored() =
        runTest {
            val (rc, _, err) = runReset(listOf("xterm-256color"))
            assertEquals(0, rc)
            assertEquals("", err)
        }

    @Test fun ignoredFlagsDoNotError() =
        runTest {
            val (rc, _, err) = runReset(listOf("-q", "-Q", "-I"))
            assertEquals(0, rc)
            assertEquals("", err)
        }

    @Test fun dashEConsumesNextArg() =
        runTest {
            val (rc, _, err) = runReset(listOf("-e", "^?"))
            assertEquals(0, rc)
            assertEquals("", err)
        }

    @Test fun dashERequiresValue() =
        runTest {
            val (rc, _, err) = runReset(listOf("-e"))
            assertEquals(1, rc)
            assertTrue(err.contains("requires an argument"))
        }

    @Test fun unknownFlagIsError() =
        runTest {
            val (rc, _, err) = runReset(listOf("--bogus"))
            assertEquals(1, rc)
            assertTrue(err.contains("invalid option"))
        }

    @Test fun versionPrints() =
        runTest {
            val (rc, out, _) = runReset(listOf("-V"))
            assertEquals(0, rc)
            assertTrue(out.startsWith("reset (kash)"))
        }

    @Test fun helpPrints() =
        runTest {
            val (rc, out, _) = runReset(listOf("--help"))
            assertEquals(0, rc)
            assertTrue(out.contains("Usage: reset"))
        }
}
