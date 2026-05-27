package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.KashJson
import com.accucodeai.kash.json.jsonString
import kotlin.test.Test
import kotlin.test.assertEquals

class JqExtraBuiltinsTest {
    private fun runStr(
        filter: String,
        input: String,
    ): List<String> = Jq.run(filter, KashJson.parse(input)).toList().map { KashJson.encode(it) }

    @Test fun walk_uppercases_string_leaves() {
        assertEquals(
            listOf("""{"a":"X","b":["Y","Z"]}"""),
            runStr("walk(if type == \"string\" then ascii_upcase else . end)", """{"a":"x","b":["y","z"]}"""),
        )
    }

    @Test fun walk_bottom_up_order() {
        // Sums-up: leaves become themselves, arrays become their summed length+1, etc.
        // Simpler: walk that doubles every number reaches numbers inside containers.
        assertEquals(
            listOf("[[2,4],[6,8]]"),
            runStr("walk(if type == \"number\" then . * 2 else . end)", "[[1,2],[3,4]]"),
        )
    }

    @Test fun limit_takes_first_n() {
        assertEquals(listOf("0", "1", "2"), runStr("[limit(3; range(100))] | .[]", "null"))
    }

    @Test fun limit_zero_emits_nothing() {
        assertEquals(emptyList(), runStr("limit(0; range(5))", "null"))
    }

    @Test fun first_of_filter() {
        assertEquals(listOf("0"), runStr("first(range(5))", "null"))
    }

    @Test fun last_of_filter() {
        assertEquals(listOf("4"), runStr("last(range(5))", "null"))
    }

    @Test fun nth_of_filter() {
        assertEquals(listOf("2"), runStr("nth(2; range(10))", "null"))
    }

    @Test fun until_iterates_to_fixed_point() {
        assertEquals(listOf("16"), runStr("until(. >= 10; . * 2)", "1"))
    }

    @Test fun while_emits_until_cond_fails() {
        assertEquals(listOf("1", "2", "4", "8"), runStr("[while(. < 10; . * 2)] | .[]", "1"))
    }

    @Test fun raw_format_unquotes_strings() {
        assertEquals("hello", Jq.format(jsonString("hello"), raw = true))
        assertEquals("\"hello\"", Jq.format(jsonString("hello"), raw = false))
        // non-string ignores raw
        val n = KashJson.parse("42")
        assertEquals("42", Jq.format(n, raw = true))
    }

    @Test fun pretty_format_indents() {
        val v = KashJson.parse("""{"a":1}""")
        val pretty = Jq.format(v, pretty = true)
        assertEquals(true, pretty.contains('\n'))
    }
}
