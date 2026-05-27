package com.accucodeai.kash.tools.less

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
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

class LessCommandGateTest {
    private fun ctx(
        stdinTty: Boolean,
        stdoutTty: Boolean,
        terminal: com.accucodeai.kash.api.terminal.TerminalControl?,
        out: Buffer = Buffer(),
        err: Buffer = Buffer(),
        stdin: Buffer = Buffer(),
        fs: InMemoryFs = InMemoryFs(),
    ): CommandContext =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = "/home",
            stdin = stdin.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            stdinIsTty = stdinTty,
            stdoutIsTty = stdoutTty,
            terminal = terminal,
        )

    @Test fun refusesWhenStdoutNotTty() =
        runTest {
            val err = Buffer()
            val rc =
                LessCommand()
                    .run(
                        args = emptyList(),
                        ctx = ctx(stdinTty = true, stdoutTty = false, terminal = FakeTerminalControl(), err = err),
                    ).exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "not a TTY")
        }

    @Test fun refusesWhenTerminalControlNull() =
        runTest {
            val err = Buffer()
            val rc =
                LessCommand()
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
                LessCommand()
                    .run(
                        listOf("--version"),
                        ctx(stdinTty = false, stdoutTty = false, terminal = null, out = out),
                    ).exitCode
            assertEquals(0, rc)
            assertContains(out.readString(), "less")
        }

    @Test fun helpFlagYieldsUsageExitCode2() =
        runTest {
            val err = Buffer()
            val rc =
                LessCommand()
                    .run(
                        listOf("-h"),
                        ctx(stdinTty = false, stdoutTty = false, terminal = null, err = err),
                    ).exitCode
            assertEquals(2, rc)
            assertContains(err.readString(), "Usage")
        }

    @Test fun unknownFlagYieldsUsageExitCode2() =
        runTest {
            val err = Buffer()
            val rc =
                LessCommand()
                    .run(
                        listOf("--no-such-option"),
                        ctx(stdinTty = false, stdoutTty = false, terminal = null, err = err),
                    ).exitCode
            assertEquals(2, rc)
            assertContains(err.readString(), "Usage")
        }

    @Test fun stdinIsAllowedToBeAPipe() =
        runTest {
            // Common invocation: `cmd | less`. stdin is NOT a tty; stdout
            // IS a tty. The pager should still start and quit on 'q'.
            val term = FakeTerminalControl()
            term.pushChars("q")
            val stdin = Buffer().apply { writeString("hello from pipe\n") }
            val rc =
                LessCommand()
                    .run(
                        emptyList(),
                        ctx(stdinTty = false, stdoutTty = true, terminal = term, stdin = stdin),
                    ).exitCode
            assertEquals(0, rc)
            assertContains(term.output.toString(), "hello from pipe")
        }

    @Test fun missingFileReportsError() =
        runTest {
            val err = Buffer()
            val rc =
                LessCommand()
                    .run(
                        listOf("nope.txt"),
                        ctx(stdinTty = true, stdoutTty = true, terminal = FakeTerminalControl(), err = err),
                    ).exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "No such file")
        }

    @Test fun directoryArgReportsError() =
        runTest {
            val err = Buffer()
            val fs = InMemoryFs()
            fs.mkdirs("/home/d")
            val rc =
                LessCommand()
                    .run(
                        listOf("d"),
                        ctx(
                            stdinTty = true,
                            stdoutTty = true,
                            terminal = FakeTerminalControl(),
                            err = err,
                            fs = fs,
                        ),
                    ).exitCode
            assertEquals(1, rc)
            assertContains(err.readString(), "is a directory")
        }

    @Test fun fileArgIsRead() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home")
            fs.writeBytes("/home/f.txt", "file content here\nsecond line\n".encodeToByteArray())
            val term = FakeTerminalControl()
            term.pushChars("q")
            val rc =
                LessCommand()
                    .run(
                        listOf("f.txt"),
                        ctx(
                            stdinTty = true,
                            stdoutTty = true,
                            terminal = term,
                            fs = fs,
                        ),
                    ).exitCode
            assertEquals(0, rc)
            assertContains(term.output.toString(), "file content here")
        }

    @Test fun lineNumbersFlagShowsGutter() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home")
            fs.writeBytes("/home/f.txt", "alpha\nbeta\n".encodeToByteArray())
            val term = FakeTerminalControl(cols = 40, rows = 10)
            term.pushChars("q")
            val rc =
                LessCommand()
                    .run(
                        listOf("-N", "f.txt"),
                        ctx(
                            stdinTty = true,
                            stdoutTty = true,
                            terminal = term,
                            fs = fs,
                        ),
                    ).exitCode
            assertEquals(0, rc)
            assertContains(term.output.toString(), "1 alpha")
        }
}
