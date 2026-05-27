package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.KashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JqRegexTest {
    private fun runStr(
        filter: String,
        input: String,
    ): List<String> = Jq.run(filter, KashJson.parse(input)).toList().map { KashJson.encode(it) }

    // ----- test ---------------------------------------------------------------

    @Test fun test_returns_bool() {
        assertEquals(listOf("true"), runStr("""test("ell")""", """"hello""""))
        assertEquals(listOf("false"), runStr("""test("xyz")""", """"hello""""))
    }

    @Test fun test_with_case_insensitive_flag() {
        assertEquals(listOf("true"), runStr("""test("HELLO"; "i")""", """"hello""""))
        assertEquals(listOf("false"), runStr("""test("HELLO")""", """"hello""""))
    }

    @Test fun test_anchored() {
        assertEquals(listOf("true"), runStr("""test("^hel")""", """"hello""""))
        assertEquals(listOf("false"), runStr("""test("^ell")""", """"hello""""))
    }

    @Test fun test_on_non_string_input_errors() {
        assertFailsWith<JqRuntimeError> { Jq.run("""test("x")""", KashJson.parse("42")).toList() }
    }

    @Test fun bad_pattern_is_runtime_error() {
        assertFailsWith<JqRuntimeError> { Jq.run("""test("[unclosed")""", KashJson.parse("\"x\"")).toList() }
    }

    // ----- match --------------------------------------------------------------

    @Test fun match_returns_object() {
        val expected = """{"offset":1,"length":3,"string":"ell","captures":[]}"""
        assertEquals(listOf(expected), runStr("""match("ell")""", """"hello""""))
    }

    @Test fun match_no_match_emits_nothing() {
        assertEquals(emptyList(), runStr("""match("xyz")""", """"hello""""))
    }

    @Test fun match_with_g_flag_emits_all() {
        val out = runStr("""[match("l"; "g") | .offset]""", """"hello"""")
        assertEquals(listOf("[2,3]"), out)
    }

    @Test fun match_with_named_capture() {
        val expected =
            """{"offset":0,"length":3,"string":"abc",""" +
                """"captures":[{"offset":0,"length":3,"string":"abc","name":"word"}]}"""
        assertEquals(listOf(expected), runStr("""match("(?P<word>[a-z]+)")""", """"abc""""))
    }

    // ----- capture ------------------------------------------------------------

    @Test fun capture_returns_named_groups_as_object() {
        assertEquals(
            listOf("""{"y":"2024","m":"01","d":"15"}"""),
            runStr(
                """capture("(?P<y>\\d{4})-(?P<m>\\d{2})-(?P<d>\\d{2})")""",
                """"2024-01-15"""",
            ),
        )
    }

    // ----- scan ---------------------------------------------------------------

    @Test fun scan_emits_each_match() {
        assertEquals(
            listOf(""""1"""", """"2"""", """"3""""),
            runStr("""scan("\\d")""", """"a1b2c3""""),
        )
    }

    @Test fun scan_with_captures_emits_arrays() {
        assertEquals(
            listOf("""["1","a"]""", """["2","b"]"""),
            runStr("""scan("(\\d)(\\w)")""", """"1a 2b""""),
        )
    }

    // ----- sub ----------------------------------------------------------------

    @Test fun sub_replaces_first_match() {
        assertEquals(listOf(""""HEllo""""), runStr("""sub("he"; "HE")""", """"hello""""))
    }

    @Test fun sub_with_named_capture_var() {
        val d = '$'
        assertEquals(
            listOf(""""[a]bcabc""""),
            runStr("""sub("(?P<x>a)"; "[\(${d}x)]")""", """"abcabc""""),
        )
    }

    // ----- gsub ---------------------------------------------------------------

    @Test fun gsub_replaces_all() {
        assertEquals(listOf(""""heLLo""""), runStr("""gsub("l"; "L")""", """"hello""""))
    }

    @Test fun gsub_no_match_returns_input() {
        assertEquals(listOf(""""hello""""), runStr("""gsub("x"; "Y")""", """"hello""""))
    }

    @Test fun gsub_uses_named_capture_in_replacement() {
        val d = '$'
        assertEquals(
            listOf(""""[a][b][c]""""),
            runStr("""gsub("(?P<c>[a-z])"; "[\(${d}c)]")""", """"abc""""),
        )
    }
}
