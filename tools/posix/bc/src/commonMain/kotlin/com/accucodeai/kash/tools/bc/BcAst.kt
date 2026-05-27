package com.accucodeai.kash.tools.bc

/**
 * AST for the bc language subset we ship.
 *
 * Notes vs POSIX:
 * - Identifiers are single letters (POSIX), but we also accept multi-letter
 *   names so simple GNU-style scripts work. The interpreter doesn't care.
 * - `last` is exposed as the special identifier `.` and the keyword `last`.
 * - Postfix ++/-- and `?:` ternary are NOT implemented.
 */
internal sealed class Expr {
    data class Num(
        val literal: String,
    ) : Expr()

    data class StrLit(
        val value: String,
    ) : Expr() // print-only

    data class VarRef(
        val name: String,
    ) : Expr()

    data class ArrayRef(
        val name: String,
        val index: Expr,
    ) : Expr()

    data class Call(
        val name: String,
        val args: List<Expr>,
    ) : Expr()

    data class Unary(
        val op: String,
        val expr: Expr,
    ) : Expr()

    data class Binary(
        val op: String,
        val l: Expr,
        val r: Expr,
    ) : Expr()

    data class Assign(
        val op: String,
        val target: LValue,
        val value: Expr,
    ) : Expr()

    data class Builtin(
        val name: String,
        val arg: Expr,
    ) : Expr() // length, scale, sqrt
}

internal sealed class LValue {
    data class Var(
        val name: String,
    ) : LValue()

    data class Arr(
        val name: String,
        val index: Expr,
    ) : LValue()

    data class Special(
        val which: String,
    ) : LValue() // scale | ibase | obase | last
}

internal sealed class Stmt {
    data class ExprStmt(
        val expr: Expr,
        val autoPrint: Boolean,
    ) : Stmt()

    data class Print(
        val items: List<Expr>,
    ) : Stmt()

    data class Block(
        val stmts: List<Stmt>,
    ) : Stmt()

    data class If(
        val cond: Expr,
        val then: Stmt,
        val els: Stmt?,
    ) : Stmt()

    data class While(
        val cond: Expr,
        val body: Stmt,
    ) : Stmt()

    data class For(
        val init: Expr?,
        val cond: Expr?,
        val post: Expr?,
        val body: Stmt,
    ) : Stmt()

    data class FunctionDef(
        val name: String,
        val params: List<Param>,
        val autos: List<Param>,
        val body: List<Stmt>,
    ) : Stmt()

    data class Return(
        val value: Expr?,
    ) : Stmt()

    object Break : Stmt()

    object Continue : Stmt()

    object Quit : Stmt()

    object Halt : Stmt()

    object Empty : Stmt()
}

/**
 * Function parameter — name plus whether it is an array (declared as `a[]`).
 */
internal data class Param(
    val name: String,
    val isArray: Boolean,
)
