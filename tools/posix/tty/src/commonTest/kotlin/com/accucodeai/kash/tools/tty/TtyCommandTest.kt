package com.accucodeai.kash.tools.tty

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.api.terminal.TerminalSize
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeTerm : TerminalControl {
    override suspend fun enterRawMode() = Unit

    override suspend fun exitRawMode() = Unit

    override suspend fun useAlternateScreen(enable: Boolean) = Unit

    override fun size(): TerminalSize = TerminalSize(80, 24)

    override suspend fun readKey(): Key = Key.Named.ENTER

    override suspend fun write(s: String) = Unit

    override suspend fun flush() = Unit
}

private suspend fun runTty(
    args: List<String>,
    stdinIsTty: Boolean = false,
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
            stdinIsTty = stdinIsTty,
            terminal = terminal,
        )
    val res = TtyCommand().run(args, ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class TtyCommandTest {
    @Test fun reportsTtyPathWhenStdinIsTty() =
        runTest {
            val (rc, out, _) = runTty(emptyList(), stdinIsTty = true, terminal = FakeTerm())
            assertEquals(0, rc)
            // installStdio stamps path="/dev/tty" when isTty + terminalControl
            // are both set — that's what `tty` surfaces.
            assertEquals("/dev/tty\n", out)
        }

    @Test fun reportsNotATtyWhenStdinNotTty() =
        runTest {
            val (rc, out, _) = runTty(emptyList(), stdinIsTty = false, terminal = null)
            assertEquals(1, rc)
            assertEquals("not a tty\n", out)
        }

    @Test fun silentFlagSuppressesOutputKeepsExit() =
        runTest {
            val (rcTty, outTty, _) = runTty(listOf("-s"), stdinIsTty = true, terminal = FakeTerm())
            assertEquals(0, rcTty)
            assertEquals("", outTty)
            val (rcNot, outNot, _) = runTty(listOf("--silent"), stdinIsTty = false)
            assertEquals(1, rcNot)
            assertEquals("", outNot)
        }

    @Test fun unknownFlagIsError() =
        runTest {
            val (rc, _, err) = runTty(listOf("-x"))
            assertEquals(2, rc)
            kotlin.test.assertTrue(err.contains("invalid option"))
        }

    @Test fun helpPrints() =
        runTest {
            val (rc, out, _) = runTty(listOf("--help"))
            assertEquals(0, rc)
            kotlin.test.assertTrue(out.startsWith("Usage: tty"))
        }
}
