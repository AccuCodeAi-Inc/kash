package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.KashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regex `splits(re)` / `splits(re; flags)` and the 2-arg `split(re; flags)`. */
class JqSplitsTest {
    private fun run(
        filter: String,
        input: String,
    ): List<String> = Jq.run(filter, KashJson.parse(input)).toList().map { KashJson.encode(it) }

    @Test fun splits_streams_parts() {
        assertEquals(listOf("\"a\"", "\"b\"", "\"c\""), run("""splits(",")""", "\"a,b,c\""))
    }

    @Test fun splits_on_regex_class() {
        // Split on any run of digits.
        assertEquals(listOf("\"a\"", "\"b\"", "\"c\""), run("""[splits("[0-9]+")] | .[]""", "\"a1b22c\""))
    }

    @Test fun splits_respects_flags_case_insensitive() {
        assertEquals(listOf("\"a\"", "\"b\""), run("""splits("X"; "i")""", "\"aXb\""))
    }

    @Test fun split_two_arg_returns_array() {
        assertEquals(listOf("""["a","b","c"]"""), run("""split(",\\s*"; "")""", "\"a, b,c\""))
    }

    @Test fun split_one_arg_is_still_literal() {
        // 1-arg split stays a plain (non-regex) literal split.
        assertEquals(listOf("""["a.b.c"]"""), run("""split("X")""", "\"a.b.c\""))
        assertEquals(listOf("""["a","b","c"]"""), run("""split(".")""", "\"a.b.c\""))
    }

    @Test fun splits_leading_and_trailing_produce_empties() {
        assertEquals(listOf("\"\"", "\"a\"", "\"\""), run("""[splits(",")] | .[]""", "\",a,\""))
    }

    @Test fun splits_non_string_input_errors() {
        val ex = runCatching { run("""splits(",")""", "123") }.exceptionOrNull()
        assertTrue(ex is JqRuntimeError, "expected JqRuntimeError, got $ex")
    }
}
