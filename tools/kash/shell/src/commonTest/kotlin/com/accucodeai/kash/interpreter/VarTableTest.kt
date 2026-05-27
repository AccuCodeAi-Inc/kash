package com.accucodeai.kash.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural contract for [VarTable] — scope-chain semantics that the
 * pre-refactor parallel-map design couldn't enforce, plus deep-copy and
 * clear semantics that [Interpreter.forkSubshell] relies on.
 */
class VarTableTest {
    @Test fun fresh_table_is_empty() {
        val t = VarTable()
        assertNull(t.find("missing"))
        assertEquals(0, t.scopeDepth)
        assertTrue(t.visibleNames().isEmpty())
    }

    @Test fun findOrCreate_creates_global_at_top_level() {
        val t = VarTable()
        val v = t.findOrCreate("x")
        v.value = VariableValue.Scalar("hi")
        assertEquals("hi", t.find("x")?.scalarOrNull)
        assertTrue("x" in t.globalNames())
    }

    @Test fun pushScope_then_local_shadows_global() {
        val t = VarTable()
        t.findOrCreate("x").value = VariableValue.Scalar("global")
        t.pushScope()
        val local = t.shadowLocal("x")
        local.value = VariableValue.Scalar("local")
        assertEquals("local", t.find("x")?.scalarOrNull)
        t.popScope()
        assertEquals("global", t.find("x")?.scalarOrNull)
    }

    @Test fun shadowLocal_inherits_outer_attrs() {
        val t = VarTable()
        val outer = t.findOrCreate("x")
        outer.value = VariableValue.Scalar("hi")
        outer.attrs += VarAttr.Integer
        t.pushScope()
        val local = t.shadowLocal("x")
        assertTrue(VarAttr.Integer in local.attrs)
        // Mutating the local's attrs MUST NOT touch the outer's set.
        local.attrs += VarAttr.Upper
        assertFalse(VarAttr.Upper in outer.attrs)
    }

    @Test fun unset_clears_local_value_but_keeps_frame() {
        val t = VarTable()
        t.findOrCreate("x").value = VariableValue.Scalar("global")
        t.pushScope()
        t.shadowLocal("x").value = VariableValue.Scalar("local")
        assertTrue(t.unset("x"))
        // Bash 5 semantics: unset of a local marks it Unset but leaves
        // the local frame entry in place — the outer global is NOT
        // revealed mid-function. Subsequent `x=...` writes hit the
        // local; only on popScope does the global become visible again.
        assertEquals(null, t.find("x")?.scalarOrNull)
        assertTrue(t.find("x")?.isSet == false)
        t.popScope()
        assertEquals("global", t.find("x")?.scalarOrNull)
        assertTrue(t.unset("x"))
        assertNull(t.find("x"))
    }

    @Test fun deepCopy_isolates_value_and_attrs() {
        val t = VarTable()
        val a = t.findOrCreate("a")
        a.value = VariableValue.Indexed(mutableMapOf(0 to "x", 5 to "y"))
        a.attrs += VarAttr.Readonly
        val copy = t.deepCopy()
        val aCopy = copy.find("a")
        assertNotNull(aCopy)
        assertNotSame(a, aCopy)
        assertNotSame(a.indexedOrNull, aCopy.indexedOrNull)
        // Mutate copy's element map; original is untouched.
        aCopy.indexedOrNull!![99] = "new"
        assertFalse(99 in a.indexedOrNull!!)
        // Mutate copy's attrs; original is untouched.
        aCopy.attrs -= VarAttr.Readonly
        assertTrue(VarAttr.Readonly in a.attrs)
    }

    @Test fun deepCopy_carries_scope_chain() {
        val t = VarTable()
        t.findOrCreate("g").value = VariableValue.Scalar("global")
        t.pushScope()
        t.shadowLocal("g").value = VariableValue.Scalar("local")
        val copy = t.deepCopy()
        assertEquals(1, copy.scopeDepth)
        assertEquals("local", copy.find("g")?.scalarOrNull)
        copy.popScope()
        assertEquals("global", copy.find("g")?.scalarOrNull)
    }

    @Test fun clearShellInternal_drops_everything() {
        val t = VarTable()
        t.findOrCreate("a").value = VariableValue.Scalar("hi")
        t.pushScope()
        t.shadowLocal("b").value = VariableValue.Scalar("bye")
        t.clearShellInternal()
        assertNull(t.find("a"))
        assertNull(t.find("b"))
        assertEquals(0, t.scopeDepth)
    }

    @Test fun visibleNames_dedups_shadowed() {
        val t = VarTable()
        t.findOrCreate("x").value = VariableValue.Scalar("g")
        t.findOrCreate("y").value = VariableValue.Scalar("g")
        t.pushScope()
        t.shadowLocal("x").value = VariableValue.Scalar("l")
        assertEquals(setOf("x", "y"), t.visibleNames())
    }

    @Test fun variable_isSet_distinguishes_declared_from_assigned_empty() {
        val v = Variable("a")
        assertFalse(v.isSet)
        v.attrs += VarAttr.Indexed
        // `declare -a a` without value: still Unset even with the type attr.
        assertFalse(v.isSet)
        v.value = VariableValue.Indexed()
        // Now `a=()` — empty-assigned, isSet true.
        assertTrue(v.isSet)
        assertTrue(v.isIndexed)
    }

    @Test fun variableValue_copy_clones_element_maps() {
        val original = VariableValue.Indexed(mutableMapOf(0 to "a", 1 to "b"))
        val cloned = original.copy() as VariableValue.Indexed
        assertNotSame(original.elements, cloned.elements)
        cloned.elements[99] = "z"
        assertFalse(99 in original.elements)
    }
}
