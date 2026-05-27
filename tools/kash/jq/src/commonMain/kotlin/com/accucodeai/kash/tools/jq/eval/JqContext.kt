package com.accucodeai.kash.tools.jq.eval

import com.accucodeai.kash.json.JsonValue
import com.accucodeai.kash.tools.jq.ast.JqExpr
import com.accucodeai.kash.tools.jq.ast.JqParam

/**
 * Immutable evaluation context. New bindings produce a new context via
 * [withVar] / [withFunc]. Function bindings use [FunctionSlot] so that a
 * recursive `def` can refer to itself: the slot enters the captured-funcs
 * map before its binding is set, breaking the would-be cyclic data dependency.
 */
internal data class JqContext(
    val vars: Map<String, JsonValue> = emptyMap(),
    val funcs: Map<String, FunctionSlot> = emptyMap(),
) {
    fun withVar(
        name: String,
        value: JsonValue,
    ): JqContext = copy(vars = vars + (name to value))

    fun withFunc(
        key: String,
        slot: FunctionSlot,
    ): JqContext = copy(funcs = funcs + (key to slot))

    fun withFuncs(more: Map<String, FunctionSlot>): JqContext = copy(funcs = funcs + more)
}

/**
 * Mutable slot used to back a function binding. Decoupling the slot from the
 * binding lets us put the slot in the captured-funcs map first, then assign
 * the binding (which itself refers to that map) — letting a function reference
 * itself for recursion without a fixed-point dance over immutable data.
 */
internal class FunctionSlot {
    lateinit var binding: JqFunction
}

internal sealed interface JqFunction

/** A user `def`. Captures the function environment visible at definition site. */
internal data class UserDef(
    val params: List<JqParam>,
    val body: JqExpr,
    val capturedFuncs: Map<String, FunctionSlot>,
) : JqFunction

/**
 * A filter-typed parameter — call-by-name. When the parameter is referenced
 * inside the function body, [expr] is re-evaluated with the *current* input
 * but in the *caller's* lexical environment.
 */
internal data class FilterThunk(
    val expr: JqExpr,
    val callerCtx: JqContext,
) : JqFunction
