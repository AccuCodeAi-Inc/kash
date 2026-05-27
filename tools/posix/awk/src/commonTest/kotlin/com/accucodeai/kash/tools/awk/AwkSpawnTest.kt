package com.accucodeai.kash.tools.awk

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.ShellRunner
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the process-spawn forms — `system()`,
 * `print | "cmd"`, and `cmd | getline`. The `ShellRunner` is faked: it
 * pattern-matches on script string and synthesizes the expected
 * stdout / consumes the expected stdin without spawning a real process.
 * That gives a deterministic, in-process check that the engine and
 * AwkCommand bridge wire correctly.
 */
class AwkSpawnTest {
    private fun ctx(
        runner: ShellRunner,
        fs: InMemoryFs = InMemoryFs(),
        stdin: String = "",
        env: MutableMap<String, String> = mutableMapOf(),
    ): Triple<CommandContext, Buffer, Buffer> {
        val out = Buffer()
        val err = Buffer()
        val inBuf = Buffer()
        if (stdin.isNotEmpty()) inBuf.writeString(stdin)
        return Triple(
            bareCommandContext(
                fs,
                env,
                "/work",
                inBuf.asSuspendSource(),
                out.asSuspendSink(),
                err.asSuspendSink(),
                shellRunner = runner,
            ),
            out,
            err,
        )
    }

    /** Echoes the script string to stdout and returns 0. */
    private val echoScript: ShellRunner =
        ShellRunner { inv ->
            inv.stdout.writeUtf8("ran: ${inv.script}\n")
            0
        }

    @Test fun `system() runs through the shell runner and returns exit code`() =
        runTest {
            var captured = ""
            val runner =
                ShellRunner { inv ->
                    captured = inv.script
                    inv.stdout.writeUtf8("hello from system\n")
                    42
                }
            val (c, out, _) = ctx(runner)
            val src = "BEGIN { rc = system(\"echo hi\"); print \"rc=\" rc }"
            val r = AwkCommand().run(listOf(src), c)
            assertEquals(0, r.exitCode)
            assertEquals("echo hi", captured)
            // The runner's stdout passes through to the awk stdout
            // before the next print, so order is: command-output, then
            // print of rc.
            assertEquals("hello from system\nrc=42\n", out.readString())
        }

    @Test fun `system() with no shell runner emits a clear error and returns 1`() =
        runTest {
            val out = Buffer()
            val err = Buffer()
            val c =
                bareCommandContext(
                    InMemoryFs(),
                    mutableMapOf(),
                    "/work",
                    Buffer().asSuspendSource(),
                    out.asSuspendSink(),
                    err.asSuspendSink(),
                    // No shellRunner — should produce a stderr message and rc=1.
                )
            val src = "BEGIN { rc = system(\"true\"); print \"rc=\" rc }"
            AwkCommand().run(listOf(src), c)
            assertTrue("no shell runner available" in err.readString())
            assertEquals("rc=1\n", out.readString())
        }

    @Test fun `print pipe to command streams records to the spawned shell`() =
        runTest {
            // Capture everything the spawned command receives on stdin.
            val sb = StringBuilder()
            val runner =
                ShellRunner { inv ->
                    val s = inv.stdin
                    if (s != null) sb.append(s.readUtf8Text())
                    0
                }
            val (c, _, _) = ctx(runner, stdin = "alpha\nbeta\ngamma\n")
            // Sort/dedupe-style script — pipe each record to a fake
            // command. The runner just records what it received.
            val src = "{ print \$0 | \"sort\" }"
            AwkCommand().run(listOf(src), c)
            // Engine closes the pipe at end-of-run; spawned reader sees
            // all three records in order.
            assertEquals("alpha\nbeta\ngamma\n", sb.toString())
        }

    @Test fun `cmd pipe getline reads records back from the spawned command`() =
        runTest {
            val runner =
                ShellRunner { inv ->
                    when (inv.script) {
                        "list-fruits" -> {
                            inv.stdout.writeUtf8("apple\nbanana\ncherry\n")
                            0
                        }

                        else -> {
                            1
                        }
                    }
                }
            val (c, out, _) = ctx(runner)
            val src =
                """
                BEGIN {
                    while (("list-fruits" | getline fruit) > 0) print "got:", fruit
                }
                """.trimIndent()
            val r = AwkCommand().run(listOf(src), c)
            assertEquals(0, r.exitCode)
            assertEquals("got: apple\ngot: banana\ngot: cherry\n", out.readString())
        }

    @Test fun `cmd pipe getline returns minus one when no shell runner is wired`() =
        runTest {
            val out = Buffer()
            val err = Buffer()
            val c =
                bareCommandContext(
                    InMemoryFs(),
                    mutableMapOf(),
                    "/work",
                    Buffer().asSuspendSource(),
                    out.asSuspendSink(),
                    err.asSuspendSink(),
                )
            val src = "BEGIN { rc = (\"any-cmd\" | getline x); print \"rc=\" rc }"
            AwkCommand().run(listOf(src), c)
            assertEquals("rc=-1\n", out.readString())
        }
}
