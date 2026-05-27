package com.accucodeai.kash.tools.bc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BcParserTest {
    private fun parse(src: String): List<Stmt> {
        val toks = BcLexer(src).tokenize()
        return BcParser(toks).parseProgram()
    }

    @Test fun emptyProgram() {
        assertEquals(emptyList(), parse(""))
    }

    @Test fun bareIntExprStmt() {
        val r = parse("42")
        assertEquals(1, r.size)
        val es = r[0] as Stmt.ExprStmt
        assertTrue(es.autoPrint)
        assertEquals("42", (es.expr as Expr.Num).literal)
    }

    @Test fun assignmentDoesNotAutoPrint() {
        val r = parse("x = 5")
        assertEquals(false, (r[0] as Stmt.ExprStmt).autoPrint)
    }

    @Test fun addPrecedenceOverEq() {
        // a == b + c  →  a == (b + c)
        val es = parse("a == b + c")[0] as Stmt.ExprStmt
        val top = es.expr as Expr.Binary
        assertEquals("==", top.op)
        assertEquals("+", (top.r as Expr.Binary).op)
    }

    @Test fun mulPrecedenceOverAdd() {
        val es = parse("1 + 2 * 3")[0] as Stmt.ExprStmt
        val top = es.expr as Expr.Binary
        assertEquals("+", top.op)
        assertEquals("*", (top.r as Expr.Binary).op)
    }

    @Test fun parensOverride() {
        val es = parse("(1 + 2) * 3")[0] as Stmt.ExprStmt
        val top = es.expr as Expr.Binary
        assertEquals("*", top.op)
    }

    @Test fun powerRightAssociative() {
        // 2 ^ 3 ^ 2 → 2 ^ (3 ^ 2)
        val es = parse("2 ^ 3 ^ 2")[0] as Stmt.ExprStmt
        val top = es.expr as Expr.Binary
        assertEquals("^", top.op)
        val r = top.r as Expr.Binary
        assertEquals("^", r.op)
    }

    @Test fun unaryMinus() {
        val es = parse("-5")[0] as Stmt.ExprStmt
        assertEquals("-", (es.expr as Expr.Unary).op)
    }

    @Test fun compoundAssign() {
        val es = parse("x += 3")[0] as Stmt.ExprStmt
        val a = es.expr as Expr.Assign
        assertEquals("+=", a.op)
        assertEquals("x", (a.target as LValue.Var).name)
    }

    @Test fun arrayRef() {
        val es = parse("a[5]")[0] as Stmt.ExprStmt
        val r = es.expr as Expr.ArrayRef
        assertEquals("a", r.name)
    }

    @Test fun arrayAssign() {
        val es = parse("a[i] = 7")[0] as Stmt.ExprStmt
        val a = es.expr as Expr.Assign
        assertTrue(a.target is LValue.Arr)
    }

    @Test fun ifElse() {
        val r = parse("if (x > 0) y = 1 else y = -1")
        val ifs = r[0] as Stmt.If
        assertTrue(ifs.els != null)
    }

    @Test fun whileLoop() {
        val r = parse("while (i < 10) i = i + 1")
        assertTrue(r[0] is Stmt.While)
    }

    @Test fun forLoop() {
        val r = parse("for (i = 0; i < 5; i = i + 1) x = x + i")
        val f = r[0] as Stmt.For
        assertTrue(f.init is Expr.Assign)
        assertTrue(f.cond is Expr.Binary)
        assertTrue(f.post is Expr.Assign)
    }

    @Test fun blockOfStmts() {
        val r = parse("{ x = 1; y = 2; }")
        val b = r[0] as Stmt.Block
        assertEquals(2, b.stmts.size)
    }

    @Test fun defineSimple() {
        val r = parse("define f(x) { return x * 2 }")
        val d = r[0] as Stmt.FunctionDef
        assertEquals("f", d.name)
        assertEquals(listOf(Param("x", false)), d.params)
    }

    @Test fun defineWithAuto() {
        val r = parse("define f(x) { auto t, u; t = x; return t }")
        val d = r[0] as Stmt.FunctionDef
        assertEquals(2, d.autos.size)
    }

    @Test fun defineArrayParam() {
        val r = parse("define f(a[]) { return a[0] }")
        val d = r[0] as Stmt.FunctionDef
        assertTrue(d.params[0].isArray)
    }

    @Test fun printSingle() {
        val r = parse("""print "hi"""")
        val p = r[0] as Stmt.Print
        assertEquals(1, p.items.size)
        assertTrue(p.items[0] is Expr.StrLit)
    }

    @Test fun printMultiple() {
        val r = parse("""print "x=", x, "\n"""")
        val p = r[0] as Stmt.Print
        assertEquals(3, p.items.size)
    }

    @Test fun semicolonSeparatedStatements() {
        val r = parse("x = 1; y = 2")
        assertEquals(2, r.size)
    }

    @Test fun newlineSeparatedStatements() {
        val r = parse("x = 1\ny = 2\nz = 3")
        assertEquals(3, r.size)
    }

    @Test fun cStyleComment() {
        val r = parse("/* comment */ 5")
        assertEquals(1, r.size)
    }

    @Test fun hashComment() {
        val r = parse("# comment\n5")
        assertEquals(1, r.size)
    }

    @Test fun lineContinuationBackslash() {
        // "12\<NL>34" parses as a single number 1234
        val r = parse("12\\\n34")
        val n = (r[0] as Stmt.ExprStmt).expr as Expr.Num
        assertEquals("1234", n.literal)
    }

    @Test fun lengthCall() {
        val r = parse("length(123)")
        val b = (r[0] as Stmt.ExprStmt).expr as Expr.Builtin
        assertEquals("length", b.name)
    }

    @Test fun scaleAsVarAndCall() {
        val varCase = parse("scale")[0] as Stmt.ExprStmt
        assertTrue(varCase.expr is Expr.VarRef)
        val callCase = parse("scale(1.234)")[0] as Stmt.ExprStmt
        assertTrue(callCase.expr is Expr.Builtin)
    }

    @Test fun parseErrorOnUnclosedParen() {
        assertFailsWith<BcParseError> { parse("1 + (2") }
    }

    @Test fun parseErrorOnBadAssignTarget() {
        assertFailsWith<BcParseError> { parse("5 = x") }
    }

    @Test fun functionCallArgs() {
        val r = parse("f(1, 2, 3)")
        val c = (r[0] as Stmt.ExprStmt).expr as Expr.Call
        assertEquals(3, c.args.size)
    }
}
