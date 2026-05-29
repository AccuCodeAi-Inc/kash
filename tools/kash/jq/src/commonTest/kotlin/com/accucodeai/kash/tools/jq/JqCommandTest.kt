package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Recipe-driven tests for the `jq` CLI adapter: file args, slurp, and the
 * --arg / --argjson / --slurpfile / --rawfile binding family.
 */
class JqCommandTest {
    private class Harness(
        val fs: InMemoryFs = InMemoryFs(),
    ) {
        val out = Buffer()
        val err = Buffer()

        suspend fun run(
            args: List<String>,
            stdin: String = "",
        ): CommandResult {
            val ctx: CommandContext =
                bareCommandContext(
                    fs,
                    mutableMapOf(),
                    "/work",
                    Buffer().apply { write(stdin.encodeToByteArray()) }.asSuspendSource(),
                    out.asSuspendSink(),
                    err.asSuspendSink(),
                )
            return JqCommand().run(args, ctx)
        }

        fun stdout(): String = out.readString()

        fun stderr(): String = err.readString()
    }

    private suspend fun InMemoryFs.put(
        path: String,
        content: String,
    ) {
        writeBytes(path, content.encodeToByteArray())
    }

    // ---- stdin (baseline) ----------------------------------------------------
    @Test fun reads_from_stdin() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-r", ".name"), stdin = """{"name":"alice"}""")
            assertEquals(0, r.exitCode)
            assertEquals("alice\n", h.stdout())
        }

    // ---- file arguments ------------------------------------------------------
    @Test fun reads_single_file() =
        runTest {
            val h = Harness()
            h.fs.put("/work/a.json", """{"x":1}""")
            val r = h.run(listOf("-c", ".x", "a.json"))
            assertEquals(0, r.exitCode)
            assertEquals("1\n", h.stdout())
        }

    @Test fun concatenates_multiple_files() =
        runTest {
            val h = Harness()
            h.fs.put("/work/a.json", "1 2")
            h.fs.put("/work/b.json", "3 4")
            val r = h.run(listOf("-c", ".", "a.json", "b.json"))
            assertEquals(0, r.exitCode)
            assertEquals("1\n2\n3\n4\n", h.stdout())
        }

    @Test fun slurp_across_files() =
        runTest {
            val h = Harness()
            h.fs.put("/work/a.json", "1 2")
            h.fs.put("/work/b.json", "3")
            val r = h.run(listOf("-c", "-s", "add", "a.json", "b.json"))
            assertEquals(0, r.exitCode)
            assertEquals("6\n", h.stdout())
        }

    @Test fun missing_file_errors() =
        runTest {
            val h = Harness()
            val r = h.run(listOf(".", "nope.json"))
            assertEquals(2, r.exitCode)
            assertEquals(true, h.stderr().contains("Could not open"))
        }

    // ---- --arg / --argjson ---------------------------------------------------
    @Test fun arg_binds_string() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-n", "-c", "--arg", "who", "world", "{greeting: \$who}"))
            assertEquals(0, r.exitCode)
            assertEquals("""{"greeting":"world"}""" + "\n", h.stdout())
        }

    @Test fun argjson_binds_parsed_json() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-n", "-c", "--argjson", "nums", "[1,2,3]", "\$nums | add"))
            assertEquals(0, r.exitCode)
            assertEquals("6\n", h.stdout())
        }

    @Test fun argjson_malformed_errors() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-n", "--argjson", "x", "{bad", "."))
            assertEquals(2, r.exitCode)
            assertEquals(true, h.stderr().contains("Invalid JSON"))
        }

    @Test fun arg_used_with_stdin_input() =
        runTest {
            val h = Harness()
            val r =
                h.run(
                    listOf("-c", "--arg", "suffix", "!", "{out: (.msg + \$suffix)}"),
                    stdin = """{"msg":"hi"}""",
                )
            assertEquals(0, r.exitCode)
            assertEquals("""{"out":"hi!"}""" + "\n", h.stdout())
        }

    // ---- --slurpfile / --rawfile ---------------------------------------------
    @Test fun slurpfile_binds_array() =
        runTest {
            val h = Harness()
            h.fs.put("/work/data.json", "1 2 3")
            val r = h.run(listOf("-n", "-c", "--slurpfile", "d", "data.json", "\$d"))
            assertEquals(0, r.exitCode)
            assertEquals("[1,2,3]\n", h.stdout())
        }

    @Test fun rawfile_binds_string() =
        runTest {
            val h = Harness()
            h.fs.put("/work/note.txt", "line one\nline two\n")
            val r = h.run(listOf("-n", "-r", "--rawfile", "t", "note.txt", "\$t"))
            assertEquals(0, r.exitCode)
            assertEquals("line one\nline two\n\n", h.stdout())
        }

    @Test fun rawfile_missing_errors() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-n", "--rawfile", "t", "absent.txt", "."))
            assertEquals(2, r.exitCode)
            assertEquals(true, h.stderr().contains("Could not open"))
        }

    // ---- usage / parse errors ------------------------------------------------
    @Test fun arg_missing_value_is_usage_error() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-n", "--arg", "only"))
            assertEquals(2, r.exitCode)
        }

    @Test fun double_dash_separates_filter() =
        runTest {
            val h = Harness()
            h.fs.put("/work/a.json", """{"x":9}""")
            val r = h.run(listOf("-c", "--", ".x", "a.json"))
            assertEquals(0, r.exitCode)
            assertEquals("9\n", h.stdout())
        }
}
