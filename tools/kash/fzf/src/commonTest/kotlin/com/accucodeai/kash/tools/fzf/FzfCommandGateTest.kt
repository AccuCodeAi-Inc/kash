package com.accucodeai.kash.tools.fzf

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.FakeTerminalControl
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FzfCommandGateTest {
    private fun ctx(
        stdinTty: Boolean,
        stdinText: String = "",
        terminal: com.accucodeai.kash.api.terminal.TerminalControl? = FakeTerminalControl(),
        out: Buffer = Buffer(),
        err: Buffer = Buffer(),
        fs: com.accucodeai.kash.fs.FileSystem = InMemoryFs(),
    ): CommandContext {
        val inBuf = Buffer()
        if (stdinText.isNotEmpty()) inBuf.writeString(stdinText)
        return bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = "/home",
            stdin = inBuf.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            stdinIsTty = stdinTty,
            stdoutIsTty = true,
            terminal = terminal,
        )
    }

    @Test fun versionFlag() =
        runTest {
            val out = Buffer()
            val rc =
                FzfCommand()
                    .run(listOf("--version"), ctx(stdinTty = true, out = out))
                    .exitCode
            assertEquals(0, rc)
            assertContains(out.readString(), "fzf")
        }

    @Test fun helpFlag() =
        runTest {
            val out = Buffer()
            val rc =
                FzfCommand()
                    .run(listOf("--help"), ctx(stdinTty = true, out = out))
                    .exitCode
            assertEquals(0, rc)
            assertContains(out.readString(), "Usage")
        }

    @Test fun unknownFlagYieldsUsageExit2() =
        runTest {
            val err = Buffer()
            val rc =
                FzfCommand()
                    .run(listOf("--no-such"), ctx(stdinTty = true, err = err))
                    .exitCode
            assertEquals(2, rc)
            assertContains(err.readString(), "Usage")
        }

    @Test fun refusesWhenNoTerminalControl() =
        runTest {
            val err = Buffer()
            val rc =
                FzfCommand()
                    .run(
                        emptyList(),
                        ctx(stdinTty = false, stdinText = "a\nb\n", terminal = null, err = err),
                    ).exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "no terminal control")
        }

    @Test fun emptyStdinNoCandidates() =
        runTest {
            val err = Buffer()
            val rc =
                FzfCommand()
                    .run(emptyList(), ctx(stdinTty = false, stdinText = "", err = err))
                    .exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "no candidates")
        }

    @Test fun pipeAndPickFirstWithEnter() =
        runTest {
            val out = Buffer()
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.ENTER)
            val rc =
                FzfCommand()
                    .run(
                        emptyList(),
                        ctx(stdinTty = false, stdinText = "alpha\nbeta\ngamma\n", terminal = term, out = out),
                    ).exitCode
            assertEquals(0, rc)
            assertEquals("alpha\n", out.readString())
        }

    @Test fun pipeWithQueryFilter() =
        runTest {
            val out = Buffer()
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.ENTER)
            val rc =
                FzfCommand()
                    .run(
                        listOf("-q", "ga"),
                        ctx(stdinTty = false, stdinText = "alpha\nbeta\ngamma\n", terminal = term, out = out),
                    ).exitCode
            assertEquals(0, rc)
            assertEquals("gamma\n", out.readString())
        }

    @Test fun escCancelExit130() =
        runTest {
            val out = Buffer()
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.ESC)
            val rc =
                FzfCommand()
                    .run(
                        emptyList(),
                        ctx(stdinTty = false, stdinText = "a\n", terminal = term, out = out),
                    ).exitCode
            assertEquals(130, rc)
            assertEquals("", out.readString())
        }

    @Test fun multiModeOutputsAllSelected() =
        runTest {
            val out = Buffer()
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.TAB)
            term.pushKey(Key.Named.TAB)
            term.pushKey(Key.Named.ENTER)
            val rc =
                FzfCommand()
                    .run(
                        listOf("-m"),
                        ctx(stdinTty = false, stdinText = "x\ny\nz\n", terminal = term, out = out),
                    ).exitCode
            assertEquals(0, rc)
            assertEquals("x\ny\n", out.readString())
        }

    @Test fun argsParseSmartCase() {
        val r = FzfCommand().parseArgs(emptyList())
        assertTrue(r is FzfCommand.Parsed.Ok)
        assertEquals(null, r.caseSensitive)
    }

    @Test fun argsParseForceInsensitive() {
        val r = FzfCommand().parseArgs(listOf("-i"))
        assertTrue(r is FzfCommand.Parsed.Ok)
        assertEquals(false, r.caseSensitive)
    }

    @Test fun argsParseMulti() {
        val r = FzfCommand().parseArgs(listOf("--multi"))
        assertTrue(r is FzfCommand.Parsed.Ok)
        assertTrue(r.multi)
    }

    @Test fun argsParseQueryEquals() {
        val r = FzfCommand().parseArgs(listOf("--query=foo"))
        assertTrue(r is FzfCommand.Parsed.Ok)
        assertEquals("foo", r.query)
    }

    @Test fun argsParseCustomPrompt() {
        val r = FzfCommand().parseArgs(listOf("--prompt=PICK> "))
        assertTrue(r is FzfCommand.Parsed.Ok)
        assertEquals("PICK> ", r.prompt)
    }

    @Test fun argsParseSilentlyAcceptsHeight() {
        val r = FzfCommand().parseArgs(listOf("--height=40%"))
        assertTrue(r is FzfCommand.Parsed.Ok)
    }

    @Test fun argsParseSilentlyAcceptsReverse() {
        val r = FzfCommand().parseArgs(listOf("--reverse"))
        assertTrue(r is FzfCommand.Parsed.Ok)
    }
}
