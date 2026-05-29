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
 * Output formatting: jq pretty-prints by default; `-c` compacts; `--tab` and
 * `--indent N` control the indent. Mirrors upstream jq's 2-space default.
 */
class JqPrettyPrintTest {
    private class Harness {
        val out = Buffer()
        val err = Buffer()

        suspend fun run(
            args: List<String>,
            stdin: String = "",
        ): CommandResult {
            val ctx: CommandContext =
                bareCommandContext(
                    InMemoryFs(),
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

    @Test fun default_is_pretty_two_space() =
        runTest {
            val h = Harness()
            h.run(listOf("."), stdin = """{"a":1,"b":[2,3]}""")
            assertEquals(
                "{\n  \"a\": 1,\n  \"b\": [\n    2,\n    3\n  ]\n}\n",
                h.stdout(),
            )
        }

    @Test fun compact_flag_collapses() =
        runTest {
            val h = Harness()
            h.run(listOf("-c", "."), stdin = """{"a":1,"b":[2,3]}""")
            assertEquals("""{"a":1,"b":[2,3]}""" + "\n", h.stdout())
        }

    @Test fun tab_indent() =
        runTest {
            val h = Harness()
            h.run(listOf("--tab", "."), stdin = "[1,2]")
            assertEquals("[\n\t1,\n\t2\n]\n", h.stdout())
        }

    @Test fun indent_n_spaces() =
        runTest {
            val h = Harness()
            h.run(listOf("--indent", "4", "."), stdin = "[1]")
            assertEquals("[\n    1\n]\n", h.stdout())
        }

    @Test fun indent_zero_is_compact() =
        runTest {
            val h = Harness()
            h.run(listOf("--indent", "0", "."), stdin = "[1,2]")
            assertEquals("[1,2]\n", h.stdout())
        }

    @Test fun scalars_unaffected_by_pretty() =
        runTest {
            val h = Harness()
            h.run(listOf("."), stdin = "42")
            assertEquals("42\n", h.stdout())
        }

    @Test fun empty_containers_stay_inline() =
        runTest {
            val h = Harness()
            h.run(listOf("."), stdin = """{"a":[],"b":{}}""")
            assertEquals("{\n  \"a\": [],\n  \"b\": {}\n}\n", h.stdout())
        }
}
