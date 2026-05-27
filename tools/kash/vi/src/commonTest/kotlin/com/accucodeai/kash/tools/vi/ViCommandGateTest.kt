package com.accucodeai.kash.tools.vi

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.FakeTerminalControl
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ViCommandGateTest {
    private fun ctx(
        stdinTty: Boolean,
        stdoutTty: Boolean,
        terminal: com.accucodeai.kash.api.terminal.TerminalControl?,
        out: Buffer = Buffer(),
        err: Buffer = Buffer(),
    ): CommandContext =
        bareCommandContext(
            fs = InMemoryFs(),
            env = mutableMapOf(),
            cwd = "/home",
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            stdinIsTty = stdinTty,
            stdoutIsTty = stdoutTty,
            terminal = terminal,
        )

    @Test fun refusesWhenStdinNotTty() =
        runTest {
            val err = Buffer()
            val rc =
                ViCommand()
                    .run(
                        emptyList(),
                        ctx(stdinTty = false, stdoutTty = true, terminal = FakeTerminalControl(), err = err),
                    ).exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "not a TTY")
        }

    @Test fun refusesWhenStdoutNotTty() =
        runTest {
            val err = Buffer()
            val rc =
                ViCommand()
                    .run(
                        emptyList(),
                        ctx(stdinTty = true, stdoutTty = false, terminal = FakeTerminalControl(), err = err),
                    ).exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "not a TTY")
        }

    @Test fun refusesWhenTerminalIsNull() =
        runTest {
            val err = Buffer()
            val rc =
                ViCommand()
                    .run(
                        emptyList(),
                        ctx(stdinTty = true, stdoutTty = true, terminal = null, err = err),
                    ).exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "no terminal control")
        }

    @Test fun versionFlagPrints() =
        runTest {
            val out = Buffer()
            val rc =
                ViCommand()
                    .run(
                        listOf("--version"),
                        ctx(stdinTty = false, stdoutTty = false, terminal = null, out = out),
                    ).exitCode
            assertEquals(0, rc)
            assertContains(out.readString(), "vi")
        }

    @Test fun unknownFlagUsageExit2() =
        runTest {
            val err = Buffer()
            val rc =
                ViCommand()
                    .run(
                        listOf("--bogus"),
                        ctx(stdinTty = false, stdoutTty = false, terminal = null, err = err),
                    ).exitCode
            assertEquals(2, rc)
            assertContains(err.readString(), "Usage")
        }

    @Test fun plusN_parsedAsGotoLine() {
        // We can't easily inspect parseArgs since it's private, but we can
        // confirm via behaviour: with bogus terminal context the editor
        // never runs, but +N is accepted (no usage error).
        runTest {
            val err = Buffer()
            val rc =
                ViCommand()
                    .run(
                        listOf("+5", "file.txt"),
                        ctx(stdinTty = false, stdoutTty = false, terminal = null, err = err),
                    ).exitCode
            // Non-TTY refuses (exit 1) — not usage error (exit 2).
            assertEquals(1, rc)
        }
    }

    @Test fun plusSearch_parsedOK() {
        runTest {
            val err = Buffer()
            val rc =
                ViCommand()
                    .run(
                        listOf("+/pat", "file.txt"),
                        ctx(stdinTty = false, stdoutTty = false, terminal = null, err = err),
                    ).exitCode
            assertEquals(1, rc)
        }
    }
}
