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

/** CLI flags added beyond Tier 1: -R, -S, -j, -e. */
class JqCliFlagsTest {
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
    }

    // ---- -R / --raw-input ----------------------------------------------------
    @Test fun raw_input_treats_each_line_as_a_string() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-R", "ascii_upcase"), stdin = "foo\nbar\n")
            assertEquals(0, r.exitCode)
            assertEquals("\"FOO\"\n\"BAR\"\n", h.stdout())
        }

    @Test fun raw_input_with_slurp_is_one_string() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-R", "-s", "length"), stdin = "ab\ncd\n")
            assertEquals(0, r.exitCode)
            // "ab\ncd\n" is 6 characters.
            assertEquals("6\n", h.stdout())
        }

    @Test fun combined_short_flags_expand() =
        runTest {
            val h = Harness()
            // -Rr = -R -r : raw line in, raw value out.
            val r = h.run(listOf("-Rr", "split(\",\") | .[0]"), stdin = "x,y,z\n")
            assertEquals(0, r.exitCode)
            assertEquals("x\n", h.stdout())
        }

    @Test fun raw_input_separate_flags() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-R", "-r", "."), stdin = "hello\n")
            assertEquals(0, r.exitCode)
            assertEquals("hello\n", h.stdout())
        }

    // ---- -S / --sort-keys ----------------------------------------------------
    @Test fun sort_keys_orders_object_keys() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-S", "-c", "."), stdin = """{"b":1,"a":2,"c":3}""")
            assertEquals(0, r.exitCode)
            assertEquals("""{"a":2,"b":1,"c":3}""" + "\n", h.stdout())
        }

    @Test fun sort_keys_is_recursive() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-S", "-c", "."), stdin = """{"z":{"y":1,"x":2}}""")
            assertEquals("""{"z":{"x":2,"y":1}}""" + "\n", h.stdout())
        }

    // ---- -j / --join-output --------------------------------------------------
    @Test fun join_output_omits_newlines() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-j", ".[]"), stdin = """["a","b","c"]""")
            assertEquals(0, r.exitCode)
            assertEquals("abc", h.stdout())
        }

    // ---- -e / --exit-status --------------------------------------------------
    @Test fun exit_status_truthy_is_zero() =
        runTest {
            val r = Harness().run(listOf("-e", ".a"), stdin = """{"a":1}""")
            assertEquals(0, r.exitCode)
        }

    @Test fun exit_status_false_is_one() =
        runTest {
            val r = Harness().run(listOf("-e", ".a"), stdin = """{"a":false}""")
            assertEquals(1, r.exitCode)
        }

    @Test fun exit_status_null_is_one() =
        runTest {
            val r = Harness().run(listOf("-e", ".missing"), stdin = """{"a":1}""")
            assertEquals(1, r.exitCode)
        }

    @Test fun exit_status_no_output_is_four() =
        runTest {
            val r = Harness().run(listOf("-e", ".[]"), stdin = "[]")
            assertEquals(4, r.exitCode)
        }
}
