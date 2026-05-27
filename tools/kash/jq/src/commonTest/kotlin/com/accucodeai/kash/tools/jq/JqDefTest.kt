package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.KashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JqDefTest {
    private fun runStr(
        filter: String,
        input: String,
    ): List<String> = Jq.run(filter, KashJson.parse(input)).toList().map { KashJson.encode(it) }

    @Test fun nullary_def() {
        assertEquals(listOf("3"), runStr("def inc: . + 1; 2 | inc", "null"))
    }

    @Test fun def_visible_for_rest_only() {
        // Before the def is bound, the name is unresolved as a builtin -> error.
        assertFailsWith<JqRuntimeError> { Jq.run("foo", KashJson.parse("null")).toList() }
    }

    @Test fun filter_param_is_lazy() {
        // `each(f)` re-runs f for each input.
        assertEquals(listOf("[2,4,6]"), runStr("def each(f): map(f); [1,2,3] | each(. * 2)", "null"))
    }

    @Test fun filter_param_thunk_uses_current_input() {
        // The body of f's parameter gets the *current* input when invoked, not the original.
        assertEquals(listOf("[10,20]"), runStr("def each(f): map(f); [10,20] | each(.)", "null"))
    }

    @Test fun value_param_eager() {
        val d = '$'
        assertEquals(listOf("11"), runStr("def add(${d}v): . + ${d}v; 10 | add(1)", "null"))
    }

    @Test fun value_param_multi_arg_cartesian() {
        val d = '$'
        // f($v) with arg producing (1,2) runs body once per value.
        assertEquals(
            listOf("11", "12"),
            runStr("def add(${d}v): . + ${d}v; 10 | add(1, 2)", "null"),
        )
    }

    @Test fun recursion() {
        // factorial.
        assertEquals(
            listOf("120"),
            runStr("def fact: if . <= 1 then 1 else . * (. - 1 | fact) end; 5 | fact", "null"),
        )
    }

    @Test fun nested_def_shadows_outer() {
        assertEquals(
            listOf("2"),
            runStr("def f: 1; def g: def f: 2; f; g", "null"),
        )
    }

    @Test fun two_arg_def_with_mixed_params() {
        val d = '$'
        assertEquals(
            listOf("[2,4,6]"),
            runStr(
                "def scale(f; ${d}k): map(f * ${d}k); [1,2,3] | scale(.; 2)",
                "null",
            ),
        )
    }

    @Test fun def_inside_def() {
        assertEquals(
            listOf("5"),
            runStr(
                "def outer: def inner: . + 1; inner | inner | inner; 2 | outer",
                "null",
            ),
        )
    }

    @Test fun overloading_by_arity() {
        assertEquals(
            listOf("1", "2"),
            runStr(
                "def f: 1; def f(x): 2; f, f(.)",
                "null",
            ),
        )
    }
}
