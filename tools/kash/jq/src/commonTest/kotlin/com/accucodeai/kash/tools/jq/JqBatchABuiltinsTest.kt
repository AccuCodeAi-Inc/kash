package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.KashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Batch A builtins (index/rindex/indices, flatten, inside, trimstr, ascii,
 * explode/implode, type filters, abs, toarray, transpose, delpaths) tested
 * against real jq semantics with realistic recipes.
 */
class JqBatchABuiltinsTest {
    private fun runStr(
        filter: String,
        input: String,
    ): List<String> = Jq.run(filter, KashJson.parse(input)).toList().map { KashJson.encode(it) }

    // ---- index / rindex / indices: strings ----------------------------------
    @Test fun index_substring() {
        assertEquals(listOf("1"), runStr("""index(", ")""", "\"a, b, cd, efg, hijk\""))
    }

    @Test fun rindex_substring() {
        assertEquals(listOf("13"), runStr("""rindex(", ")""", "\"a, b, cd, efg, hijk\""))
    }

    @Test fun indices_substring_all() {
        assertEquals(listOf("[0,2,4]"), runStr("""indices("a")""", "\"aXaXa\""))
    }

    @Test fun index_missing_substring_is_null() {
        assertEquals(listOf("null"), runStr("""index("zzz")""", "\"abc\""))
    }

    // ---- index / rindex / indices: arrays ------------------------------------
    @Test fun indices_element_in_array() {
        assertEquals(listOf("[1,3]"), runStr("indices(1)", "[0,1,2,1,3]"))
    }

    @Test fun indices_subsequence_in_array() {
        // jq: [0,1,2,1,3,1,4] | indices([1,2]) => [1]
        assertEquals(listOf("[1]"), runStr("indices([1,2])", "[0,1,2,1,3,1,4]"))
    }

    @Test fun index_first_element() {
        assertEquals(listOf("1"), runStr("index(1)", "[0,1,2,1,3]"))
    }

    @Test fun rindex_last_element() {
        assertEquals(listOf("3"), runStr("rindex(1)", "[0,1,2,1,3]"))
    }

    // ---- flatten -------------------------------------------------------------
    @Test fun flatten_full_depth() {
        assertEquals(listOf("[1,2,3,4,5]"), runStr("flatten", "[1,[2,[3,[4,5]]]]"))
    }

    @Test fun flatten_depth_one() {
        assertEquals(listOf("[1,2,[3,[4,5]]]"), runStr("flatten(1)", "[1,[2,[3,[4,5]]]]"))
    }

    @Test fun flatten_depth_two() {
        assertEquals(listOf("[1,2,3,[4,5]]"), runStr("flatten(2)", "[1,[2,[3,[4,5]]]]"))
    }

    // ---- inside --------------------------------------------------------------
    @Test fun inside_array() {
        // jq: [1,5] | inside([1,2,5,3,7]) => true
        assertEquals(listOf("true"), runStr("inside([1,2,5,3,7])", "[1,5]"))
    }

    @Test fun inside_object() {
        assertEquals(listOf("true"), runStr("""inside({"a":1,"b":2})""", """{"a":1}"""))
    }

    @Test fun inside_substring() {
        assertEquals(listOf("true"), runStr("""inside("foobar")""", "\"bar\""))
    }

    // ---- trimstr -------------------------------------------------------------
    @Test fun trimstr_both_ends() {
        assertEquals(listOf("\"oo\""), runStr("""trimstr("f")""", "\"foof\""))
    }

    @Test fun trimstr_prefix_only() {
        assertEquals(listOf("\"bar\""), runStr("""trimstr("foo")""", "\"foobar\""))
    }

    @Test fun trimstr_no_match_unchanged() {
        assertEquals(listOf("\"abc\""), runStr("""trimstr("x")""", "\"abc\""))
    }

    // ---- ascii / explode / implode -------------------------------------------
    @Test fun explode_basic() {
        assertEquals(listOf("[97,98,99]"), runStr("explode", "\"abc\""))
    }

    @Test fun implode_basic() {
        assertEquals(listOf("\"abc\""), runStr("implode", "[97,98,99]"))
    }

    @Test fun explode_implode_roundtrip_unicode() {
        // U+1F600 grinning face — surrogate-pair safe
        assertEquals(listOf("\"😀x\""), runStr("explode | implode", "\"😀x\""))
    }

    @Test fun ascii_codepoint_to_char() {
        assertEquals(listOf("\"A\""), runStr("ascii", "65"))
    }

    @Test fun ascii_via_explode_map() {
        assertEquals(listOf("\"ABC\""), runStr("explode | map(. ) | implode", "\"ABC\""))
    }

    // ---- type filters --------------------------------------------------------
    @Test fun numbers_filter() {
        assertEquals(listOf("1", "2"), runStr(".[] | numbers", """[1,"a",2,null,true]"""))
    }

    @Test fun strings_filter() {
        assertEquals(listOf("\"a\""), runStr(".[] | strings", """[1,"a",2,null]"""))
    }

    @Test fun nulls_filter() {
        assertEquals(listOf("null"), runStr(".[] | nulls", """[1,null,2]"""))
    }

    @Test fun booleans_filter() {
        assertEquals(listOf("true", "false"), runStr(".[] | booleans", """[1,true,"x",false]"""))
    }

    @Test fun arrays_filter() {
        assertEquals(listOf("[1]", "[]"), runStr(".[] | arrays", """[1,[1],{},[]]"""))
    }

    @Test fun objects_filter() {
        assertEquals(listOf("{}", """{"k":1}"""), runStr(".[] | objects", """[1,{},[2],{"k":1}]"""))
    }

    @Test fun iterables_filter() {
        assertEquals(listOf("[1]", "{}"), runStr(".[] | iterables", """[1,[1],"s",{}]"""))
    }

    @Test fun scalars_filter() {
        assertEquals(listOf("1", "\"s\"", "null"), runStr(".[] | scalars", """[1,[1],"s",{},null]"""))
    }

    // ---- abs -----------------------------------------------------------------
    @Test fun abs_negative_int() {
        assertEquals(listOf("5"), runStr("abs", "-5"))
    }

    @Test fun abs_negative_double() {
        assertEquals(listOf("2.5"), runStr("abs", "-2.5"))
    }

    @Test fun abs_positive_unchanged() {
        assertEquals(listOf("7"), runStr("abs", "7"))
    }

    // ---- toarray -------------------------------------------------------------
    @Test fun toarray_wraps_scalar() {
        assertEquals(listOf("[3]"), runStr("toarray", "3"))
    }

    @Test fun toarray_identity_on_array() {
        assertEquals(listOf("[1,2]"), runStr("toarray", "[1,2]"))
    }

    // ---- transpose -----------------------------------------------------------
    @Test fun transpose_rectangular() {
        assertEquals(listOf("[[1,3],[2,4]]"), runStr("transpose", "[[1,2],[3,4]]"))
    }

    @Test fun transpose_ragged_pads_null() {
        // jq pads shorter rows with null
        assertEquals(listOf("[[1,3],[2,null]]"), runStr("transpose", "[[1,2],[3]]"))
    }

    // ---- delpaths ------------------------------------------------------------
    @Test fun delpaths_multiple() {
        assertEquals(
            listOf("""{"b":2}"""),
            runStr("""delpaths([["a"],["c"]])""", """{"a":1,"b":2,"c":3}"""),
        )
    }

    @Test fun delpaths_nested_and_index() {
        assertEquals(
            listOf("""{"a":[1,3]}"""),
            runStr("""delpaths([["a",1]])""", """{"a":[1,2,3]}"""),
        )
    }

    @Test fun delpaths_empty_is_identity() {
        assertEquals(listOf("""{"a":1}"""), runStr("delpaths([])", """{"a":1}"""))
    }

    // ---- error paths ---------------------------------------------------------
    @Test fun explode_on_number_errors() {
        val threw =
            try {
                runStr("explode", "5")
                false
            } catch (e: JqRuntimeError) {
                true
            }
        assertTrue(threw, "explode on a number should raise a runtime error")
    }
}
