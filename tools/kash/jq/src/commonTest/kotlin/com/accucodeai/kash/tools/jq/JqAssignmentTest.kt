package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.KashJson
import kotlin.test.Test
import kotlin.test.assertEquals

class JqAssignmentTest {
    private fun runStr(
        filter: String,
        input: String,
    ): List<String> = Jq.run(filter, KashJson.parse(input)).toList().map { KashJson.encode(it) }

    // ----- = (plain assignment) ----------------------------------------------

    @Test fun assign_simple_field() {
        assertEquals(listOf("""{"a":1}"""), runStr(".a = 1", """{}"""))
    }

    @Test fun assign_overwrites() {
        assertEquals(listOf("""{"a":99}"""), runStr(".a = 99", """{"a":1}"""))
    }

    @Test fun assign_nested_creates_intermediate_objects() {
        assertEquals(listOf("""{"a":{"b":{"c":7}}}"""), runStr(".a.b.c = 7", """{}"""))
    }

    @Test fun assign_to_array_element() {
        assertEquals(listOf("[10,2,3]"), runStr(".[0] = 10", "[1,2,3]"))
    }

    @Test fun assign_to_iterate_paths_replaces_all() {
        // .[]= sets every element of the array
        assertEquals(listOf("[7,7,7]"), runStr(".[] = 7", "[1,2,3]"))
    }

    // ----- |= (update assignment) --------------------------------------------

    @Test fun update_assign_transforms_value() {
        assertEquals(listOf("""{"a":11}"""), runStr(".a |= . + 10", """{"a":1}"""))
    }

    @Test fun update_assign_each_element() {
        assertEquals(listOf("[2,4,6]"), runStr(".[] |= . * 2", "[1,2,3]"))
    }

    // ----- sugared compound assignments --------------------------------------

    @Test fun plus_equals() {
        assertEquals(listOf("""{"n":5}"""), runStr(".n += 3", """{"n":2}"""))
    }

    @Test fun times_equals() {
        assertEquals(listOf("""{"n":12}"""), runStr(".n *= 3", """{"n":4}"""))
    }

    @Test fun alt_assign_fills_when_null() {
        assertEquals(listOf("""{"a":7}"""), runStr(".a //= 7", """{"a":null}"""))
        assertEquals(listOf("""{"a":1}"""), runStr(".a //= 7", """{"a":1}"""))
    }

    // ----- path builtins ------------------------------------------------------

    @Test fun del_field() {
        assertEquals(listOf("""{"b":2}"""), runStr("del(.a)", """{"a":1,"b":2}"""))
    }

    @Test fun del_array_index() {
        assertEquals(listOf("[1,3]"), runStr("del(.[1])", "[1,2,3]"))
    }

    @Test fun del_multiple_indices_in_descending_order() {
        // jq's contract: del across siblings doesn't shift indices.
        assertEquals(listOf("[2,4]"), runStr("del(.[0,2,4])", "[1,2,3,4,5]"))
    }

    @Test fun setpath() {
        assertEquals(
            listOf("""{"a":{"b":42}}"""),
            runStr("""setpath(["a","b"]; 42)""", """{}"""),
        )
    }

    @Test fun getpath() {
        assertEquals(listOf("7"), runStr("""getpath(["a","b"])""", """{"a":{"b":7}}"""))
        assertEquals(listOf("null"), runStr("""getpath(["a","z"])""", """{"a":{"b":7}}"""))
    }

    @Test fun path_returns_path_array() {
        assertEquals(listOf("""["a","b"]"""), runStr("path(.a.b)", """{"a":{"b":1}}"""))
    }

    @Test fun paths_filter_finds_locations_matching_predicate() {
        val out = runStr("""[paths(type == "number")]""", """{"a":1,"b":{"c":2,"d":"x"}}""")
        assertEquals(listOf("""[["a"],["b","c"]]"""), out)
    }

    @Test fun paths_zero_arg_lists_all_paths() {
        val out = runStr("[paths]", """{"a":[1,2]}""")
        assertEquals(listOf("""[["a"],["a",0],["a",1]]"""), out)
    }
}
