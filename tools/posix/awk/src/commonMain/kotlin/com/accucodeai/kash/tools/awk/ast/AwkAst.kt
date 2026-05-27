package com.accucodeai.kash.tools.awk.ast

/**
 * AST for awk programs.
 *
 * Shape:
 *  - [AwkProgram] holds a flat list of [AwkItem]s.
 *  - Each item is a pattern-action rule ([Rule]) or a function def ([FunctionDef]).
 *  - Statements are a separate sealed hierarchy ([AwkStmt]); pattern-action
 *    actions are a [BlockStmt].
 *  - Expressions ([AwkExpr]) are a third hierarchy.
 *
 * Designed so unimplemented features (the process-spawn forms of
 * `getline` and `print` redirection, `system()`) can be added by
 * extending the relevant sealed family without touching call sites
 * that already handle the implemented forms.
 */
internal data class AwkProgram(
    val items: List<AwkItem>,
)

internal sealed interface AwkItem

internal data class Rule(
    val pattern: Pattern,
    /** `null` action defaults to `{ print }` per POSIX. */
    val action: BlockStmt?,
) : AwkItem

internal data class FunctionDef(
    val name: String,
    val params: List<String>,
    val body: BlockStmt,
) : AwkItem

// -- Patterns ---------------------------------------------------------------

internal sealed interface Pattern {
    /** Every record. */
    data object Always : Pattern

    data object Begin : Pattern

    data object End : Pattern

    /** An expression that's coerced to boolean per POSIX truthiness. */
    data class Expr(
        val expr: AwkExpr,
    ) : Pattern

    /** `pat1, pat2 { ... }` — toggled state machine. */
    data class Range(
        val from: AwkExpr,
        val to: AwkExpr,
    ) : Pattern
}

// -- Statements -------------------------------------------------------------

internal sealed interface AwkStmt

internal data class BlockStmt(
    val stmts: List<AwkStmt>,
) : AwkStmt

internal data class IfStmt(
    val cond: AwkExpr,
    val thenBranch: AwkStmt,
    val elseBranch: AwkStmt?,
) : AwkStmt

internal data class WhileStmt(
    val cond: AwkExpr,
    val body: AwkStmt,
) : AwkStmt

internal data class DoWhileStmt(
    val body: AwkStmt,
    val cond: AwkExpr,
) : AwkStmt

internal data class ForStmt(
    val init: AwkStmt?,
    val cond: AwkExpr?,
    val step: AwkStmt?,
    val body: AwkStmt,
) : AwkStmt

internal data class ForInStmt(
    val varName: String,
    val arrayName: String,
    val body: AwkStmt,
) : AwkStmt

internal data class ExprStmt(
    val expr: AwkExpr,
) : AwkStmt

/** `print [arg, …] [> file | >> file | | cmd]`. File redirections are
 *  wired; the `| cmd` form parses but raises a runtime error until the
 *  process-spawn bridge lands. */
internal data class PrintStmt(
    val args: List<AwkExpr>,
    val output: OutputTarget = OutputTarget.Stdout,
) : AwkStmt

/** `printf fmt, args… [> file | …]`. Same redirection support as [PrintStmt]. */
internal data class PrintfStmt(
    val args: List<AwkExpr>,
    val output: OutputTarget = OutputTarget.Stdout,
) : AwkStmt

internal sealed interface OutputTarget {
    data object Stdout : OutputTarget

    /** `print expr > file`. */
    data class ToFile(
        val path: AwkExpr,
    ) : OutputTarget

    /** `print expr >> file`. */
    data class AppendFile(
        val path: AwkExpr,
    ) : OutputTarget

    /** `print expr | cmd` — requires process-spawn; currently raises at runtime. */
    data class ToPipe(
        val cmd: AwkExpr,
    ) : OutputTarget
}

internal data class DeleteStmt(
    val arrayName: String,
    /** `null` deletes the whole array. */
    val subscript: List<AwkExpr>?,
) : AwkStmt

internal data object NextStmt : AwkStmt

internal data object NextFileStmt : AwkStmt

internal data class ExitStmt(
    val exprOrNull: AwkExpr?,
) : AwkStmt

internal data class ReturnStmt(
    val exprOrNull: AwkExpr?,
) : AwkStmt

internal data object BreakStmt : AwkStmt

internal data object ContinueStmt : AwkStmt

// -- Expressions ------------------------------------------------------------

internal sealed interface AwkExpr

/** Numeric literal (integer or floating). */
internal data class NumLit(
    val value: Double,
) : AwkExpr

internal data class StrLit(
    val value: String,
) : AwkExpr

/** `/re/` literal — body is the raw regex source (escapes preserved for the engine). */
internal data class RegexLit(
    val pattern: String,
) : AwkExpr

/** Bare variable reference. */
internal data class VarRef(
    val name: String,
) : AwkExpr

/** `$expr` — field reference. `$0` is the whole record. */
internal data class FieldRef(
    val index: AwkExpr,
) : AwkExpr

/** `a[i]`, `a[i, j, …]`. Multi-subscript is joined with SUBSEP at eval time. */
internal data class ArrayRef(
    val name: String,
    val subscript: List<AwkExpr>,
) : AwkExpr

/** `(i, j) in a` / `i in a`. */
internal data class InExpr(
    val subscript: List<AwkExpr>,
    val arrayName: String,
) : AwkExpr

internal data class FunCall(
    val name: String,
    val args: List<AwkExpr>,
) : AwkExpr

internal data class Assign(
    val target: AwkExpr,
    val op: AssignOp,
    val value: AwkExpr,
) : AwkExpr

internal enum class AssignOp { Eq, PlusEq, MinusEq, MultEq, DivEq, ModEq, PowEq }

internal data class Ternary(
    val cond: AwkExpr,
    val thenExpr: AwkExpr,
    val elseExpr: AwkExpr,
) : AwkExpr

internal data class Binary(
    val op: BinOp,
    val left: AwkExpr,
    val right: AwkExpr,
) : AwkExpr

internal enum class BinOp {
    Add,
    Sub,
    Mul,
    Div,
    Mod,
    Pow,
    Lt,
    Le,
    Gt,
    Ge,
    Eq,
    Ne,
    And,
    Or,
    Match,
    NotMatch,
    Concat,
}

internal data class Unary(
    val op: UnaryOp,
    val operand: AwkExpr,
) : AwkExpr

internal enum class UnaryOp { Negate, Plus, Not, PreInc, PreDec, PostInc, PostDec }

/** `(expr)` — pure grouping; not strictly needed at AST level, but useful
 *  for round-tripping and debug output. Kept thin so the evaluator doesn't
 *  special-case it. */
internal data class Grouping(
    val inner: AwkExpr,
) : AwkExpr

/** `getline` in all five forms (variable target optional, source optional). */
internal data class GetlineExpr(
    val target: AwkExpr?, // null → into $0
    val source: GetlineSource,
) : AwkExpr

internal sealed interface GetlineSource {
    /** Bare `getline` — next record from main input. */
    data object MainInput : GetlineSource

    /** `getline < file`. */
    data class FromFile(
        val path: AwkExpr,
    ) : GetlineSource

    /** `cmd | getline`. */
    data class FromCommand(
        val cmd: AwkExpr,
    ) : GetlineSource
}
