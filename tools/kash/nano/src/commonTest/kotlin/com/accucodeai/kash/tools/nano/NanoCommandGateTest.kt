package com.accucodeai.kash.tools.nano

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

class NanoCommandGateTest {
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
                NanoCommand()
                    .run(
                        args = emptyList(),
                        ctx = ctx(stdinTty = false, stdoutTty = true, terminal = FakeTerminalControl(), err = err),
                    ).exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "not a TTY")
        }

    @Test fun refusesWhenStdoutNotTty() =
        runTest {
            val err = Buffer()
            val rc =
                NanoCommand()
                    .run(
                        emptyList(),
                        ctx(stdinTty = true, stdoutTty = false, terminal = FakeTerminalControl(), err = err),
                    ).exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "not a TTY")
        }

    @Test fun refusesWhenTerminalControlIsNull() =
        runTest {
            val err = Buffer()
            val rc =
                NanoCommand()
                    .run(
                        emptyList(),
                        ctx(stdinTty = true, stdoutTty = true, terminal = null, err = err),
                    ).exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "no terminal control")
        }

    @Test fun versionFlagPrintsAndExitsZero() =
        runTest {
            val out = Buffer()
            val rc =
                NanoCommand()
                    .run(
                        listOf("--version"),
                        ctx(stdinTty = false, stdoutTty = false, terminal = null, out = out),
                    ).exitCode
            assertEquals(0, rc)
            assertContains(out.readString(), "nano")
        }

    @Test fun unknownFlagYieldsUsageExitCode2() =
        runTest {
            val err = Buffer()
            val rc =
                NanoCommand()
                    .run(
                        listOf("--no-such-option"),
                        ctx(stdinTty = false, stdoutTty = false, terminal = null, err = err),
                    ).exitCode
            assertEquals(2, rc)
            assertContains(err.readString(), "Usage")
        }
}
