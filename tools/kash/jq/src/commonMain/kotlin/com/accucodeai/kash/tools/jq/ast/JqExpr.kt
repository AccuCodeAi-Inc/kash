package com.accucodeai.kash.tools.jq.ast

import com.accucodeai.kash.json.JsonValue

/**
 * jq filter AST. Sealed; all nodes are internal — they live behind the [com.accucodeai.kash.tools.jq.Jq] facade.
 *
 * v1 scope: read-only filter language. Path-expression / assignment nodes are
 * intentionally absent — they belong to a v2 layer that gives every node both
 * a value- and path-producing interpretation.
 */
internal sealed interface JqExpr

internal data object Identity : JqExpr

/** `..` */
internal data object RecursiveDescent : JqExpr

internal data class Literal(
    val value: JsonValue,
) : JqExpr

/** Strings with interpolation segments. Plain strings have a single [StringPart.Text]. */
internal data class InterpolatedString(
    val parts: List<StringPart>,
) : JqExpr

internal sealed interface StringPart {
    data class Text(
        val s: String,
    ) : StringPart

    data class Expr(
        val e: JqExpr,
    ) : StringPart
}

internal data class VarRef(
    val name: String,
) : JqExpr

internal data class FieldAccess(
    val source: JqExpr,
    val name: String,
    val optional: Boolean,
) : JqExpr

/** `expr[index]`, `expr[a:b]`, `expr[]` — `index` is `null` for iterate-all. */
internal data class Index(
    val source: JqExpr,
    val index: IndexKind,
    val optional: Boolean,
) : JqExpr

internal sealed interface IndexKind {
    data class At(
        val key: JqExpr,
    ) : IndexKind

    data class Slice(
        val from: JqExpr?,
        val to: JqExpr?,
    ) : IndexKind

    data object Iterate : IndexKind
}

/** `a | b` */
internal data class Pipe(
    val left: JqExpr,
    val right: JqExpr,
) : JqExpr

/** `a, b` */
internal data class Comma(
    val left: JqExpr,
    val right: JqExpr,
) : JqExpr

internal data class ArrayConstruct(
    val body: JqExpr?,
) : JqExpr

internal data class ObjectConstruct(
    val entries: List<ObjectEntry>,
) : JqExpr

internal data class ObjectEntry(
    val key: JqExpr,
    val value: JqExpr,
)

internal data class Negate(
    val operand: JqExpr,
) : JqExpr

internal data class BinaryOp(
    val op: BinOp,
    val left: JqExpr,
    val right: JqExpr,
) : JqExpr

internal enum class BinOp { Add, Sub, Mul, Div, Mod, Eq, Ne, Lt, Le, Gt, Ge, And, Or, Alternative }

internal data class IfThenElse(
    val cond: JqExpr,
    val thenBranch: JqExpr,
    val elifBranches: List<Pair<JqExpr, JqExpr>>,
    val elseBranch: JqExpr,
) : JqExpr

internal data class TryCatch(
    val body: JqExpr,
    val handler: JqExpr?,
) : JqExpr

/** Postfix `?` — equivalent to `try expr` with empty handler. */
internal data class Optional(
    val body: JqExpr,
) : JqExpr

internal data class Reduce(
    val source: JqExpr,
    val varName: String,
    val init: JqExpr,
    val update: JqExpr,
) : JqExpr

internal data class Foreach(
    val source: JqExpr,
    val varName: String,
    val init: JqExpr,
    val update: JqExpr,
    val extract: JqExpr?,
) : JqExpr

/** `expr as $x | body` */
internal data class BindAs(
    val source: JqExpr,
    val varName: String,
    val body: JqExpr,
) : JqExpr

/** Function/builtin invocation. v1 only supports zero-arg, one-arg, and two-arg forms. */
internal data class FuncCall(
    val name: String,
    val args: List<JqExpr>,
) : JqExpr

/** `error` / `error(msg)` */
internal data class ErrorExpr(
    val message: JqExpr?,
) : JqExpr

/**
 * A `@name` format/encoding applied as a filter to the input (e.g. `@base64`,
 * `@csv`). [name] excludes the leading `@`. The `@fmt "interp"` form (format
 * applied to a string's interpolations) is not modeled — the grammar only
 * admits a bare `@name`.
 */
internal data class FormatStr(
    val name: String,
) : JqExpr

/**
 * `path = value` — for each path produced by [path], for each value produced
 * by [value], emit the input with that path set to that value.
 */
internal data class Assign(
    val path: JqExpr,
    val value: JqExpr,
) : JqExpr

/**
 * `path |= transform` — for each path produced by [path], emit input with the
 * value at that path replaced by `transform` applied to the current value.
 * Sugared forms `+=`, `-=`, `*=`, `/=`, `%=`, `//=` are lowered to this with a
 * synthesized [transform] (e.g. `+= y` becomes `|= . + y`).
 */
internal data class UpdateAssign(
    val path: JqExpr,
    val transform: JqExpr,
) : JqExpr

/**
 * `def name(params): body; rest` — bind [name] for the duration of [rest].
 *
 * Filter params (`def f(g): ...`) are passed by name (call-by-need): the
 * argument expression is evaluated in the caller's environment every time the
 * body references the param.
 *
 * Value params (`def f($v): ...`) are evaluated once eagerly in the caller's
 * environment and bound as a variable. If the argument produces multiple
 * values, the body runs once per value (cartesian with filter params).
 *
 * Self-recursion works because the slot for [name] is added to the captured
 * environment before the body is evaluated.
 */
internal data class FunctionDef(
    val name: String,
    val params: List<JqParam>,
    val body: JqExpr,
    val rest: JqExpr,
) : JqExpr

internal sealed interface JqParam {
    val name: String

    data class FilterParam(
        override val name: String,
    ) : JqParam

    data class ValueParam(
        override val name: String,
    ) : JqParam
}
