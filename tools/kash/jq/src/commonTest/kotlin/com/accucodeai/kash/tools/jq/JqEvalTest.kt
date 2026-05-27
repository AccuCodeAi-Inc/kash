package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.JsonValue
import com.accucodeai.kash.json.KashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JqEvalTest {
    private fun run(
        filter: String,
        input: String,
    ): List<JsonValue> = Jq.run(filter, KashJson.parse(input)).toList()

    private fun runStr(
        filter: String,
        input: String,
    ): List<String> = run(filter, input).map { KashJson.encode(it) }

    @Test fun identity() {
        assertEquals(listOf("42"), runStr(".", "42"))
        assertEquals(listOf("""{"a":1}"""), runStr(".", """{"a":1}"""))
    }

    @Test fun field_access() {
        assertEquals(listOf("1"), runStr(".a", """{"a":1}"""))
        assertEquals(listOf("null"), runStr(".missing", """{"a":1}"""))
    }

    @Test fun nested_field_access() {
        assertEquals(listOf(""""x""""), runStr(".a.b.c", """{"a":{"b":{"c":"x"}}}"""))
    }

    @Test fun optional_field() {
        assertEquals(emptyList(), runStr(".a?", "1"))
    }

    @Test fun array_index_and_slice() {
        assertEquals(listOf("2"), runStr(".[1]", "[1,2,3]"))
        assertEquals(listOf("3"), runStr(".[-1]", "[1,2,3]"))
        assertEquals(listOf("[2,3]"), runStr(".[1:]", "[1,2,3]"))
        assertEquals(listOf("[1,2]"), runStr(".[:2]", "[1,2,3]"))
        assertEquals(listOf("[2]"), runStr(".[1:2]", "[1,2,3]"))
    }

    @Test fun iterate() {
        assertEquals(listOf("1", "2", "3"), runStr(".[]", "[1,2,3]"))
        assertEquals(listOf("1", "2"), runStr(".[]", """{"a":1,"b":2}"""))
    }

    @Test fun pipe() {
        assertEquals(listOf("3"), runStr(".a | .b", """{"a":{"b":3}}"""))
    }

    @Test fun comma_emits_multiple() {
        assertEquals(listOf("1", "2"), runStr(".a, .b", """{"a":1,"b":2}"""))
    }

    @Test fun recursive_descent() {
        val r = runStr("..", """{"a":[1,2]}""")
        assertEquals(listOf("""{"a":[1,2]}""", "[1,2]", "1", "2"), r)
    }

    @Test fun array_construct() {
        assertEquals(listOf("[1,2,3]"), runStr("[.[]]", "[1,2,3]"))
    }

    @Test fun object_construct_shorthand() {
        assertEquals(listOf("""{"a":1}"""), runStr("{a}", """{"a":1,"b":2}"""))
    }

    @Test fun object_construct_dynamic() {
        assertEquals(listOf("""{"x":1}"""), runStr("{x: .a}", """{"a":1}"""))
    }

    @Test fun arithmetic() {
        assertEquals(listOf("3"), runStr("1 + 2", "null"))
        assertEquals(listOf("6"), runStr(". + 1 | . * 2", "2"))
        assertEquals(listOf("2"), runStr("5 % 3", "null"))
    }

    @Test fun string_concat() {
        assertEquals(listOf(""""hello world""""), runStr(""""hello " + "world"""", "null"))
    }

    @Test fun interpolation() {
        assertEquals(listOf(""""x=42""""), runStr(""""x=\(.x)"""", """{"x":42}"""))
    }

    @Test fun comparison_and_logic() {
        assertEquals(listOf("true"), runStr(". > 1", "2"))
        assertEquals(listOf("false"), runStr(". == 0", "1"))
        assertEquals(listOf("true"), runStr(". > 0 and . < 10", "5"))
    }

    @Test fun alternative_op() {
        assertEquals(listOf("3"), runStr(".a // 3", """{}"""))
        assertEquals(listOf("1"), runStr(".a // 3", """{"a":1}"""))
        assertEquals(listOf("3"), runStr(".a // 3", """{"a":null}"""))
        assertEquals(listOf("3"), runStr(".a // 3", """{"a":false}"""))
    }

    @Test fun if_then_else() {
        assertEquals(listOf(""""big""""), runStr("if . > 5 then \"big\" else \"small\" end", "10"))
        assertEquals(listOf(""""small""""), runStr("if . > 5 then \"big\" else \"small\" end", "2"))
    }

    @Test fun try_catch() {
        assertEquals(listOf(""""err""""), runStr("try error(\"err\") catch .", "null"))
        assertEquals(emptyList(), runStr("try error", "null"))
    }

    @Test fun var_binding() {
        val d = '$'
        assertEquals(listOf("5"), runStr(". as ${d}x | ${d}x + 1", "4"))
    }

    @Test fun reduce() {
        assertEquals(listOf("10"), runStr("reduce .[] as \$x (0; . + \$x)", "[1,2,3,4]"))
    }

    @Test fun unknown_field_on_number_errors() {
        assertFailsWith<JqRuntimeError> { run(".x", "1") }
    }

    @Test fun parse_error_is_jqparseerror() {
        assertFailsWith<JqParseError> { Jq.compile(".(") }
    }
}
