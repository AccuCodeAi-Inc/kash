package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.json.KashJson
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `input` / `inputs` and the shared input cursor — exercised both at the
 * engine level ([Jq.applyAll]) and through the CLI (`-n`, streaming consume).
 */
class JqInputsTest {
    private fun values(vararg json: String) = json.map { KashJson.parse(it) }

    private fun encode(s: Sequence<com.accucodeai.kash.json.JsonValue>) = s.toList().map { KashJson.encode(it) }

    // ---- engine: applyAll ----------------------------------------------------
    @Test fun inputs_under_null_input_collects_all() {
        val out = encode(Jq.compile("[inputs]").applyAll(values("1", "2", "3"), nullInput = true))
        assertEquals(listOf("[1,2,3]"), out)
    }

    @Test fun input_pulls_next_in_main_loop() {
        // `., input` on 1,2,3,4 → main pulls 1, input pulls 2 → (1,2);
        // main pulls 3, input pulls 4 → (3,4).
        val out = encode(Jq.compile("., input").applyAll(values("1", "2", "3", "4")))
        assertEquals(listOf("1", "2", "3", "4"), out)
    }

    @Test fun input_exhausted_raises() {
        val ex = runCatching { Jq.compile("., input").applyAll(values("1")).toList() }.exceptionOrNull()
        assertTrue(ex is JqRuntimeError && ex.message!!.contains("No more inputs"), "got $ex")
    }

    @Test fun slurp_consumes_stream_so_inputs_is_empty() {
        // -s makes one array input; inputs then has nothing.
        val out = encode(Jq.compile("length, [inputs]").applyAll(values("1", "2", "3"), slurp = true))
        assertEquals(listOf("3", "[]"), out)
    }

    @Test fun reduce_over_inputs_sums() {
        val out =
            encode(Jq.compile("reduce inputs as \$x (0; . + \$x)").applyAll(values("10", "20", "5"), nullInput = true))
        assertEquals(listOf("35"), out)
    }

    // ---- CLI -----------------------------------------------------------------
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

    @Test fun cli_null_input_slurps_inputs_into_array() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-cn", "[inputs]"), stdin = "1\n2\n3\n")
            assertEquals(0, r.exitCode)
            assertEquals("[1,2,3]\n", h.stdout())
        }

    @Test fun cli_input_consumes_next_value() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-c", "[., input]"), stdin = "1 2 3 4")
            assertEquals(0, r.exitCode)
            // main=1,input=2 ; main=3,input=4
            assertEquals("[1,2]\n[3,4]\n", h.stdout())
        }

    @Test fun cli_raw_null_input_reads_lines() =
        runTest {
            val h = Harness()
            val r = h.run(listOf("-Rnc", "[inputs]"), stdin = "alpha\nbeta\n")
            assertEquals(0, r.exitCode)
            assertEquals("""["alpha","beta"]""" + "\n", h.stdout())
        }
}
