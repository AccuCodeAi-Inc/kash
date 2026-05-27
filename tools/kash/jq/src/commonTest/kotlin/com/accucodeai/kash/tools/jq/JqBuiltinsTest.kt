package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.KashJson
import kotlin.test.Test
import kotlin.test.assertEquals

class JqBuiltinsTest {
    private fun runStr(
        filter: String,
        input: String,
    ): List<String> = Jq.run(filter, KashJson.parse(input)).toList().map { KashJson.encode(it) }

    @Test fun length() {
        assertEquals(listOf("3"), runStr("length", "[1,2,3]"))
        assertEquals(listOf("5"), runStr("length", "\"hello\""))
        assertEquals(listOf("2"), runStr("length", """{"a":1,"b":2}"""))
        assertEquals(listOf("0"), runStr("length", "null"))
    }

    @Test fun keys_and_values() {
        assertEquals(listOf("""["a","b"]"""), runStr("keys", """{"b":2,"a":1}"""))
        assertEquals(listOf("""[1,2]"""), runStr("values", """{"a":1,"b":2}"""))
    }

    @Test fun type() {
        assertEquals(listOf(""""array""""), runStr("type", "[]"))
        assertEquals(listOf(""""null""""), runStr("type", "null"))
        assertEquals(listOf(""""number""""), runStr("type", "1"))
    }

    @Test fun map_and_select() {
        assertEquals(listOf("[2,4,6]"), runStr("map(. * 2)", "[1,2,3]"))
        assertEquals(listOf("3", "4"), runStr(".[] | select(. > 2)", "[1,2,3,4]"))
    }

    @Test fun sort_and_sort_by() {
        assertEquals(listOf("[1,2,3]"), runStr("sort", "[3,1,2]"))
        assertEquals(
            listOf("""[{"n":"a","age":1},{"n":"b","age":3}]"""),
            runStr("sort_by(.age)", """[{"n":"b","age":3},{"n":"a","age":1}]"""),
        )
    }

    @Test fun group_by() {
        assertEquals(
            listOf("[[1,1],[2,2]]"),
            runStr("group_by(.)", "[1,2,1,2]"),
        )
    }

    @Test fun unique_and_unique_by() {
        assertEquals(listOf("[1,2,3]"), runStr("unique", "[3,1,2,1,3,2]"))
    }

    @Test fun min_max() {
        assertEquals(listOf("1"), runStr("min", "[3,1,2]"))
        assertEquals(listOf("3"), runStr("max", "[3,1,2]"))
    }

    @Test fun add() {
        assertEquals(listOf("6"), runStr("add", "[1,2,3]"))
        assertEquals(listOf(""""abc""""), runStr("add", """["a","b","c"]"""))
        assertEquals(listOf("[1,2,3]"), runStr("add", "[[1],[2],[3]]"))
    }

    @Test fun has_and_in() {
        assertEquals(listOf("true"), runStr("""has("a")""", """{"a":1}"""))
        assertEquals(listOf("false"), runStr("""has("b")""", """{"a":1}"""))
        assertEquals(listOf("true"), runStr("0 | in([1,2])", "null"))
    }

    @Test fun contains() {
        assertEquals(listOf("true"), runStr("""contains("ell")""", """"hello""""))
        assertEquals(listOf("true"), runStr("contains([2])", "[1,2,3]"))
    }

    @Test fun to_from_entries() {
        assertEquals(
            listOf("""{"a":1,"b":2}"""),
            runStr("to_entries | from_entries", """{"a":1,"b":2}"""),
        )
    }

    @Test fun strings() {
        assertEquals(listOf(""""hello""""), runStr("ascii_downcase", """"HELLO""""))
        assertEquals(listOf(""""HELLO""""), runStr("ascii_upcase", """"hello""""))
        assertEquals(listOf("""["a","b","c"]"""), runStr("""split(",")""", """"a,b,c""""))
        assertEquals(listOf(""""a,b,c""""), runStr("""join(",")""", """["a","b","c"]"""))
        assertEquals(listOf("true"), runStr("""startswith("hel")""", """"hello""""))
    }

    @Test fun range() {
        assertEquals(listOf("0", "1", "2"), runStr("range(3)", "null"))
        assertEquals(listOf("2", "3", "4"), runStr("range(2; 5)", "null"))
        assertEquals(listOf("0", "2", "4"), runStr("range(0; 6; 2)", "null"))
    }

    @Test fun tonumber_tostring() {
        assertEquals(listOf("42"), runStr("tonumber", """"42""""))
        assertEquals(listOf(""""42""""), runStr("tostring", "42"))
    }

    @Test fun reverse() {
        assertEquals(listOf("[3,2,1]"), runStr("reverse", "[1,2,3]"))
    }

    @Test fun first_last_any_all() {
        assertEquals(listOf("1"), runStr("first", "[1,2,3]"))
        assertEquals(listOf("3"), runStr("last", "[1,2,3]"))
        assertEquals(listOf("true"), runStr("any", "[false,true]"))
        assertEquals(listOf("false"), runStr("all", "[true,false]"))
    }
}
