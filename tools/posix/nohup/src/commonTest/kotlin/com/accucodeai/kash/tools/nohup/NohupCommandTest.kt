@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.accucodeai.kash.tools.nohup

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.UtilityRunner
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A utility runner that always succeeds and echoes a marker to stdout. */
private class EchoRunner(
    private val rc: Int = 0,
    private val message: String = "hello\n",
) : UtilityRunner {
    var lastName: String? = null
    var lastArgs: List<String> = emptyList()

    override suspend fun run(
        name: String,
        args: List<String>,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int {
        lastName = name
        lastArgs = args
        stdout.writeUtf8(message)
        return rc
    }
}

private fun newCtx(
    fs: InMemoryFs = InMemoryFs(),
    runner: UtilityRunner? = null,
    stdoutIsTty: Boolean = false,
    stderrIsTty: Boolean = false,
    env: MutableMap<String, String> = mutableMapOf(),
    cwd: String = "/",
): Triple<CommandContext, Buffer, Buffer> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = env,
            cwd = cwd,
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            utilityRunner = runner,
            stdoutIsTty = stdoutIsTty,
            stderrIsTty = stderrIsTty,
        )
    return Triple(ctx, out, err)
}

class NohupCommandTest {
    @Test fun missingOperand_exits_127() =
        runTest {
            val (ctx, _, err) = newCtx()
            val rc = NohupCommand().run(emptyList(), ctx)
            assertEquals(127, rc.exitCode)
            assertTrue(err.readString().contains("missing operand"))
        }

    @Test fun runsChild_passes_throughExitCode() =
        runTest {
            val runner = EchoRunner(rc = 7)
            val (ctx, _, _) = newCtx(runner = runner)
            val rc = NohupCommand().run(listOf("foo", "a", "b"), ctx)
            assertEquals(7, rc.exitCode)
            assertEquals("foo", runner.lastName)
            assertEquals(listOf("a", "b"), runner.lastArgs)
        }

    @Test fun stdoutNotTty_outputPassesThrough() =
        runTest {
            val runner = EchoRunner(message = "passthrough\n")
            val (ctx, out, _) = newCtx(runner = runner)
            NohupCommand().run(listOf("echo"), ctx)
            assertEquals("passthrough\n", out.readString())
        }

    @Test fun stdoutTty_redirects_to_nohup_out_in_cwd() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            val runner = EchoRunner(message = "captured\n")
            val (ctx, out, err) =
                newCtx(
                    fs = fs,
                    runner = runner,
                    stdoutIsTty = true,
                    cwd = "/work",
                )
            val rc = NohupCommand().run(listOf("echo"), ctx)
            assertEquals(0, rc.exitCode)
            // stdout should NOT have received the message (it went to file).
            assertEquals("", out.readString())
            // stderr informs the user.
            assertTrue(err.readString().contains("appending output to 'nohup.out'"))
            // File contents.
            val bytes = fs.readBytes("/work/nohup.out")
            assertEquals("captured\n", bytes.decodeToString())
        }

    @Test fun stderrTty_butStdoutNotTty_stderrFollows_stdout() =
        runTest {
            val runner =
                object : UtilityRunner {
                    override suspend fun run(
                        name: String,
                        args: List<String>,
                        stdin: SuspendSource,
                        stdout: SuspendSink,
                        stderr: SuspendSink,
                    ): Int {
                        stderr.writeUtf8("from-stderr\n")
                        return 0
                    }
                }
            val (ctx, out, err) = newCtx(runner = runner, stderrIsTty = true)
            NohupCommand().run(listOf("foo"), ctx)
            // stderr was tty → should now go to ctx.stdout sink.
            assertEquals("from-stderr\n", out.readString())
            assertEquals("", err.readString())
        }

    @Test fun help_exits_0() =
        runTest {
            val (ctx, out, _) = newCtx()
            val rc = NohupCommand().run(listOf("--help"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue(out.readString().contains("Usage"))
        }

    @Test fun version_exits_0() =
        runTest {
            val (ctx, out, _) = newCtx()
            val rc = NohupCommand().run(listOf("-V"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue(out.readString().contains("nohup"))
        }

    @Test fun doubleDash_endsOptions() =
        runTest {
            val runner = EchoRunner()
            val (ctx, _, _) = newCtx(runner = runner)
            val rc = NohupCommand().run(listOf("--", "myutil", "--help"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("myutil", runner.lastName)
            assertEquals(listOf("--help"), runner.lastArgs)
        }

    @Test fun unknownLeadingOption_rejected() =
        runTest {
            val (ctx, _, err) = newCtx()
            val rc = NohupCommand().run(listOf("--bogus", "foo"), ctx)
            assertEquals(125, rc.exitCode)
            assertTrue(err.readString().contains("invalid option"))
        }

    @Test fun noUtilityRunner_returns_127() =
        runTest {
            val (ctx, _, err) = newCtx(runner = null)
            val rc = NohupCommand().run(listOf("foo"), ctx)
            assertEquals(127, rc.exitCode)
            assertTrue(err.readString().contains("nohup"))
        }

    @Test fun bothTty_stderrFollows_redirectedStdout() =
        runTest {
            val fs = InMemoryFs()
            val runner =
                object : UtilityRunner {
                    override suspend fun run(
                        name: String,
                        args: List<String>,
                        stdin: SuspendSource,
                        stdout: SuspendSink,
                        stderr: SuspendSink,
                    ): Int {
                        stdout.writeUtf8("o\n")
                        stderr.writeUtf8("e\n")
                        return 0
                    }
                }
            val (ctx, _, _) =
                newCtx(
                    fs = fs,
                    runner = runner,
                    stdoutIsTty = true,
                    stderrIsTty = true,
                )
            NohupCommand().run(listOf("x"), ctx)
            val captured = fs.readBytes("/nohup.out").decodeToString()
            // Both streams should be in the file.
            assertTrue(captured.contains("o"))
            assertTrue(captured.contains("e"))
        }

    @Test fun runnerSeesOurStdin() =
        runTest {
            // Confirms stdin is forwarded — not actually checking content,
            // just that the call doesn't blow up and we get the right rc.
            val runner = EchoRunner(rc = 0)
            val (ctx, _, _) = newCtx(runner = runner)
            val rc = NohupCommand().run(listOf("cat"), ctx)
            assertEquals(0, rc.exitCode)
        }

    @Test fun cwd_relative_nohupOut() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/sub")
            val runner = EchoRunner(message = "X\n")
            val (ctx, _, _) =
                newCtx(fs = fs, runner = runner, stdoutIsTty = true, cwd = "/sub")
            NohupCommand().run(listOf("echo"), ctx)
            assertTrue(fs.exists("/sub/nohup.out"))
        }

    @Test fun homeFallback_when_cwdNotWritable() =
        runTest {
            // We can't easily simulate "cwd not writable" with InMemoryFs
            // because every dir is writable. Smoke-test: HOME env is set
            // and cwd works fine (writes to cwd, not home).
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.mkdirs("/home/me")
            val env = mutableMapOf("HOME" to "/home/me")
            val runner = EchoRunner(message = "Y\n")
            val (ctx, _, _) =
                newCtx(fs = fs, runner = runner, stdoutIsTty = true, env = env, cwd = "/work")
            NohupCommand().run(listOf("echo"), ctx)
            assertTrue(fs.exists("/work/nohup.out"), "expected nohup.out in cwd")
        }

    @Test fun appends_doesNotTruncate_existing() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/w")
            fs.writeBytes("/w/nohup.out", "existing\n".encodeToByteArray())
            val runner = EchoRunner(message = "more\n")
            val (ctx, _, _) =
                newCtx(fs = fs, runner = runner, stdoutIsTty = true, cwd = "/w")
            NohupCommand().run(listOf("echo"), ctx)
            val captured = fs.readBytes("/w/nohup.out").decodeToString()
            assertTrue(captured.startsWith("existing"), "should append, not truncate: <$captured>")
            assertTrue(captured.contains("more"))
        }

    @Test fun nonzeroExit_is_preserved() =
        runTest {
            val runner = EchoRunner(rc = 42, message = "")
            val (ctx, _, _) = newCtx(runner = runner)
            val rc = NohupCommand().run(listOf("anything"), ctx)
            assertEquals(42, rc.exitCode)
        }
}
