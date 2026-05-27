package com.accucodeai.kash.tools.awk.parser.antlr

import com.accucodeai.kash.tools.awk.AwkParseError
import com.accucodeai.kash.tools.awk.ast.ArrayRef
import com.accucodeai.kash.tools.awk.ast.Assign
import com.accucodeai.kash.tools.awk.ast.AssignOp
import com.accucodeai.kash.tools.awk.ast.AwkExpr
import com.accucodeai.kash.tools.awk.ast.AwkItem
import com.accucodeai.kash.tools.awk.ast.AwkProgram
import com.accucodeai.kash.tools.awk.ast.AwkStmt
import com.accucodeai.kash.tools.awk.ast.BinOp
import com.accucodeai.kash.tools.awk.ast.Binary
import com.accucodeai.kash.tools.awk.ast.BlockStmt
import com.accucodeai.kash.tools.awk.ast.BreakStmt
import com.accucodeai.kash.tools.awk.ast.ContinueStmt
import com.accucodeai.kash.tools.awk.ast.DeleteStmt
import com.accucodeai.kash.tools.awk.ast.DoWhileStmt
import com.accucodeai.kash.tools.awk.ast.ExitStmt
import com.accucodeai.kash.tools.awk.ast.ExprStmt
import com.accucodeai.kash.tools.awk.ast.FieldRef
import com.accucodeai.kash.tools.awk.ast.ForInStmt
import com.accucodeai.kash.tools.awk.ast.ForStmt
import com.accucodeai.kash.tools.awk.ast.FunCall
import com.accucodeai.kash.tools.awk.ast.FunctionDef
import com.accucodeai.kash.tools.awk.ast.GetlineExpr
import com.accucodeai.kash.tools.awk.ast.GetlineSource
import com.accucodeai.kash.tools.awk.ast.Grouping
import com.accucodeai.kash.tools.awk.ast.IfStmt
import com.accucodeai.kash.tools.awk.ast.InExpr
import com.accucodeai.kash.tools.awk.ast.NextFileStmt
import com.accucodeai.kash.tools.awk.ast.NextStmt
import com.accucodeai.kash.tools.awk.ast.NumLit
import com.accucodeai.kash.tools.awk.ast.OutputTarget
import com.accucodeai.kash.tools.awk.ast.Pattern
import com.accucodeai.kash.tools.awk.ast.PrintStmt
import com.accucodeai.kash.tools.awk.ast.PrintfStmt
import com.accucodeai.kash.tools.awk.ast.RegexLit
import com.accucodeai.kash.tools.awk.ast.ReturnStmt
import com.accucodeai.kash.tools.awk.ast.Rule
import com.accucodeai.kash.tools.awk.ast.StrLit
import com.accucodeai.kash.tools.awk.ast.Ternary
import com.accucodeai.kash.tools.awk.ast.Unary
import com.accucodeai.kash.tools.awk.ast.UnaryOp
import com.accucodeai.kash.tools.awk.ast.VarRef
import com.accucodeai.kash.tools.awk.ast.WhileStmt

/**
 * Walks the awk parse tree and builds the typed AST.
 *
 * Same shape as `JqAstBuilder` — per-rule methods, no left-recursion in the
 * grammar means no recursive folding here either; the layered rules
 * produce flat lists we walk iteratively for left-associative operators
 * and right-fold for right-associative ones.
 */
internal class AwkAstBuilder {
    fun program(ctx: AwkParser.ProgramContext): AwkProgram {
        val items = ctx.item().map { item(it) }
        return AwkProgram(items)
    }

    private fun item(ctx: AwkParser.ItemContext): AwkItem =
        when (ctx) {
            is AwkParser.ItemFunctionContext -> functionDef(ctx.functionDef())
            is AwkParser.ItemRuleContext -> rule(ctx.rule_())
            else -> throw AwkParseError("internal: unknown item form")
        }

    private fun functionDef(ctx: AwkParser.FunctionDefContext): FunctionDef {
        val name = ctx.IDENT().text
        val params = ctx.paramList()?.IDENT()?.map { it.text } ?: emptyList()
        return FunctionDef(name, params, blockStmt(ctx.actionBlock()))
    }

    private fun rule(ctx: AwkParser.RuleContext): Rule =
        when (ctx) {
            is AwkParser.RuleWithActionContext -> {
                Rule(pattern(ctx.pattern()), blockStmt(ctx.actionBlock()))
            }

            is AwkParser.RulePatternOnlyContext -> {
                Rule(pattern(ctx.pattern()), action = null)
            }

            is AwkParser.RuleActionOnlyContext -> {
                Rule(Pattern.Always, blockStmt(ctx.actionBlock()))
            }

            else -> {
                throw AwkParseError("internal: unknown rule form")
            }
        }

    private fun pattern(ctx: AwkParser.PatternContext): Pattern =
        when (ctx) {
            is AwkParser.PatBeginContext -> {
                Pattern.Begin
            }

            is AwkParser.PatEndContext -> {
                Pattern.End
            }

            is AwkParser.PatExprContext -> {
                Pattern.Expr(expr(ctx.expr()))
            }

            is AwkParser.PatRangeContext -> {
                val es = ctx.expr()
                Pattern.Range(expr(es[0]), expr(es[1]))
            }

            else -> {
                throw AwkParseError("internal: unknown pattern form")
            }
        }

    private fun blockStmt(ctx: AwkParser.ActionBlockContext): BlockStmt =
        BlockStmt(ctx.stmtList()?.stmt()?.map { stmt(it) } ?: emptyList())

    private fun stmt(ctx: AwkParser.StmtContext): AwkStmt =
        when (ctx) {
            is AwkParser.StmtBlockContext -> {
                blockStmt(ctx.actionBlock())
            }

            is AwkParser.StmtIfContext -> {
                val stmts = ctx.stmt()
                IfStmt(
                    cond = expr(ctx.expr()),
                    thenBranch = stmt(stmts[0]),
                    elseBranch = stmts.getOrNull(1)?.let { stmt(it) },
                )
            }

            is AwkParser.StmtWhileContext -> {
                WhileStmt(expr(ctx.expr()), stmt(ctx.stmt()))
            }

            is AwkParser.StmtDoWhileContext -> {
                DoWhileStmt(stmt(ctx.stmt()), expr(ctx.expr()))
            }

            is AwkParser.StmtForContext -> {
                ForStmt(
                    init = ctx.forSimpleInit()?.let { ExprStmt(expr(it.expr())) },
                    cond = ctx.expr()?.let { expr(it) },
                    step = ctx.forSimpleStep()?.let { ExprStmt(expr(it.expr())) },
                    body = stmt(ctx.stmt()),
                )
            }

            is AwkParser.StmtForInContext -> {
                val idents = ctx.IDENT()
                ForInStmt(idents[0].text, idents[1].text, stmt(ctx.stmt()))
            }

            is AwkParser.StmtForInMultiContext -> {
                // `for ((i, j) in a) { ... }` — POSIX permits this; v1 we
                // lower the (i, j) tuple to a single synthetic key via
                // SUBSEP, but for now reject so user gets a clear message.
                throw AwkParseError("for ((expr-list) in arr) is not supported yet")
            }

            is AwkParser.StmtBreakContext -> {
                BreakStmt
            }

            is AwkParser.StmtContinueContext -> {
                ContinueStmt
            }

            is AwkParser.StmtNextContext -> {
                NextStmt
            }

            is AwkParser.StmtNextfileContext -> {
                NextFileStmt
            }

            is AwkParser.StmtExitContext -> {
                ExitStmt(ctx.expr()?.let { expr(it) })
            }

            is AwkParser.StmtReturnContext -> {
                ReturnStmt(ctx.expr()?.let { expr(it) })
            }

            is AwkParser.StmtDeleteElemContext -> {
                DeleteStmt(
                    arrayName = ctx.IDENT().text,
                    subscript = ctx.exprList().expr().map { expr(it) },
                )
            }

            is AwkParser.StmtDeleteArrContext -> {
                DeleteStmt(arrayName = ctx.IDENT().text, subscript = null)
            }

            is AwkParser.StmtPrintContext -> {
                PrintStmt(printArgs(ctx.printArgs()), printRedir(ctx.printRedir()))
            }

            is AwkParser.StmtPrintfContext -> {
                PrintfStmt(printArgs(ctx.printArgs()), printRedir(ctx.printRedir()))
            }

            is AwkParser.StmtExprContext -> {
                ExprStmt(expr(ctx.expr()))
            }

            is AwkParser.StmtEmptyContext -> {
                BlockStmt(emptyList())
            }

            else -> {
                throw AwkParseError("internal: unknown statement form ${ctx::class.simpleName}")
            }
        }

    private fun printArgs(ctx: AwkParser.PrintArgsContext?): List<AwkExpr> {
        if (ctx == null) return emptyList()
        // Parenthesized form: `print (a, b)` uses regular `expr`.
        // Bare form: `print a, b` uses `printExpr` (no top-level `>`).
        val regular = ctx.expr()
        if (regular.isNotEmpty()) return regular.map { expr(it) }
        return ctx.printExpr().map { printExpr(it) }
    }

    private fun printRedir(ctx: AwkParser.PrintRedirContext?): OutputTarget {
        if (ctx == null) return OutputTarget.Stdout
        return when (ctx) {
            is AwkParser.PrintRedirFileContext -> OutputTarget.ToFile(printExpr(ctx.printExpr()))
            is AwkParser.PrintRedirAppendContext -> OutputTarget.AppendFile(printExpr(ctx.printExpr()))
            is AwkParser.PrintRedirPipeContext -> OutputTarget.ToPipe(printExpr(ctx.printExpr()))
            else -> throw AwkParseError("internal: unknown print redirection")
        }
    }

    // -- Print-arg expression walkers ---------------------------------------
    //
    // Parallel to expr/assignExpr/.../relExpr but built from the
    // `printExpr` parse-tree contexts. Same AwkExpr output, only the
    // grammar rules differ (no top-level `>` in printRelExpr).

    private fun printExpr(ctx: AwkParser.PrintExprContext): AwkExpr = printAssignExpr(ctx.printAssignExpr())

    private fun printAssignExpr(ctx: AwkParser.PrintAssignExprContext): AwkExpr {
        val left = printTernaryExpr(ctx.printTernaryExpr())
        val opCtx = ctx.assignOp() ?: return left
        val right = printAssignExpr(ctx.printAssignExpr()!!)
        val op =
            when {
                opCtx.ASSIGN() != null -> AssignOp.Eq
                opCtx.PLUSEQ() != null -> AssignOp.PlusEq
                opCtx.MINUSEQ() != null -> AssignOp.MinusEq
                opCtx.MULTEQ() != null -> AssignOp.MultEq
                opCtx.DIVEQ() != null -> AssignOp.DivEq
                opCtx.MODEQ() != null -> AssignOp.ModEq
                opCtx.POWEQ() != null -> AssignOp.PowEq
                else -> throw AwkParseError("internal: unknown assignment operator")
            }
        return Assign(left, op, right)
    }

    private fun printTernaryExpr(ctx: AwkParser.PrintTernaryExprContext): AwkExpr {
        val cond = printLogOr(ctx.printLogOr())
        if (ctx.QUESTION() == null) return cond
        val branches = ctx.printTernaryExpr()
        return Ternary(cond, printTernaryExpr(branches[0]), printTernaryExpr(branches[1]))
    }

    private fun printLogOr(ctx: AwkParser.PrintLogOrContext): AwkExpr {
        val ops = ctx.printLogAnd()
        var acc = printLogAnd(ops[0])
        for (i in 1 until ops.size) acc = Binary(BinOp.Or, acc, printLogAnd(ops[i]))
        return acc
    }

    private fun printLogAnd(ctx: AwkParser.PrintLogAndContext): AwkExpr {
        val ops = ctx.printInExpr()
        var acc = printInExpr(ops[0])
        for (i in 1 until ops.size) acc = Binary(BinOp.And, acc, printInExpr(ops[i]))
        return acc
    }

    private fun printInExpr(ctx: AwkParser.PrintInExprContext): AwkExpr {
        val matchCtx = ctx.printMatchExpr()
        val exprListCtx = ctx.exprList()
        val arrIdent = ctx.IDENT()
        return when {
            matchCtx != null && arrIdent != null -> {
                InExpr(listOf(printMatchExpr(matchCtx)), arrIdent.text)
            }

            matchCtx != null -> {
                printMatchExpr(matchCtx)
            }

            exprListCtx != null && arrIdent != null -> {
                InExpr(exprListCtx.expr().map { expr(it) }, arrIdent.text)
            }

            else -> {
                throw AwkParseError("internal: unknown print-in-expression form")
            }
        }
    }

    private fun printMatchExpr(ctx: AwkParser.PrintMatchExprContext): AwkExpr {
        val rels = ctx.printRelExpr()
        var acc = printRelExpr(rels[0])
        val ops =
            (0 until ctx.childCount).mapNotNull { i ->
                val c = ctx.getChild(i) ?: return@mapNotNull null
                when (c.text) {
                    "~" -> BinOp.Match
                    "!~" -> BinOp.NotMatch
                    else -> null
                }
            }
        for (i in 1 until rels.size) acc = Binary(ops[i - 1], acc, printRelExpr(rels[i]))
        return acc
    }

    private fun printRelExpr(ctx: AwkParser.PrintRelExprContext): AwkExpr {
        val concats = ctx.concatExpr()
        val left = concatExpr(concats[0])
        val opCtx = ctx.printRelOp() ?: return left
        val right = concatExpr(concats[1])
        val op =
            when {
                opCtx.LT() != null -> BinOp.Lt
                opCtx.LE() != null -> BinOp.Le
                opCtx.EQEQ() != null -> BinOp.Eq
                opCtx.NEQ() != null -> BinOp.Ne
                opCtx.GE() != null -> BinOp.Ge
                else -> throw AwkParseError("internal: unknown print-rel operator")
            }
        return Binary(op, left, right)
    }

    // -- Expressions --------------------------------------------------------

    private fun expr(ctx: AwkParser.ExprContext): AwkExpr =
        when (ctx) {
            is AwkParser.ExprCmdGetlineContext -> {
                val left = assignExpr(ctx.assignExpr())
                val target = ctx.IDENT()?.text?.let { VarRef(it) }
                GetlineExpr(target = target, source = GetlineSource.FromCommand(left))
            }

            is AwkParser.ExprAssignContext -> {
                assignExpr(ctx.assignExpr())
            }

            else -> {
                throw AwkParseError("internal: unknown expr form ${ctx::class.simpleName}")
            }
        }

    private fun assignExpr(ctx: AwkParser.AssignExprContext): AwkExpr {
        val left = ternaryExpr(ctx.ternaryExpr())
        val opCtx = ctx.assignOp() ?: return left
        val right = assignExpr(ctx.assignExpr()!!)
        val op =
            when {
                opCtx.ASSIGN() != null -> AssignOp.Eq
                opCtx.PLUSEQ() != null -> AssignOp.PlusEq
                opCtx.MINUSEQ() != null -> AssignOp.MinusEq
                opCtx.MULTEQ() != null -> AssignOp.MultEq
                opCtx.DIVEQ() != null -> AssignOp.DivEq
                opCtx.MODEQ() != null -> AssignOp.ModEq
                opCtx.POWEQ() != null -> AssignOp.PowEq
                else -> throw AwkParseError("internal: unknown assignment operator")
            }
        return Assign(left, op, right)
    }

    private fun ternaryExpr(ctx: AwkParser.TernaryExprContext): AwkExpr {
        val cond = logOr(ctx.logOr())
        if (ctx.QUESTION() == null) return cond
        val branches = ctx.ternaryExpr()
        return Ternary(cond, ternaryExpr(branches[0]), ternaryExpr(branches[1]))
    }

    private fun logOr(ctx: AwkParser.LogOrContext): AwkExpr {
        val ops = ctx.logAnd()
        var acc = logAnd(ops[0])
        for (i in 1 until ops.size) acc = Binary(BinOp.Or, acc, logAnd(ops[i]))
        return acc
    }

    private fun logAnd(ctx: AwkParser.LogAndContext): AwkExpr {
        val ops = ctx.inExpr()
        var acc = inExpr(ops[0])
        for (i in 1 until ops.size) acc = Binary(BinOp.And, acc, inExpr(ops[i]))
        return acc
    }

    private fun inExpr(ctx: AwkParser.InExprContext): AwkExpr {
        // The grammar has two alternatives sharing this context class:
        //   matchExpr (KW_IN IDENT)?            -- single-key form
        //   '(' exprList ')' KW_IN IDENT         -- multi-key form
        // ANTLR doesn't generate distinct subclasses without alternative
        // labels, so we sniff the tokens directly.
        val matchCtx = ctx.matchExpr()
        val exprListCtx = ctx.exprList()
        val arrIdent = ctx.IDENT()
        return when {
            matchCtx != null && arrIdent != null -> {
                InExpr(listOf(matchExpr(matchCtx)), arrIdent.text)
            }

            matchCtx != null -> {
                matchExpr(matchCtx)
            }

            exprListCtx != null && arrIdent != null -> {
                InExpr(exprListCtx.expr().map { expr(it) }, arrIdent.text)
            }

            else -> {
                throw AwkParseError("internal: unknown in-expression form")
            }
        }
    }

    private fun matchExpr(ctx: AwkParser.MatchExprContext): AwkExpr {
        val rels = ctx.relExpr()
        var acc = relExpr(rels[0])
        // Match/NotMatch tokens alternate with operands; collect the
        // operator tokens in source order.
        val ops =
            (0 until ctx.childCount).mapNotNull { i ->
                val c = ctx.getChild(i) ?: return@mapNotNull null
                when (c.text) {
                    "~" -> BinOp.Match
                    "!~" -> BinOp.NotMatch
                    else -> null
                }
            }
        for (i in 1 until rels.size) acc = Binary(ops[i - 1], acc, relExpr(rels[i]))
        return acc
    }

    private fun relExpr(ctx: AwkParser.RelExprContext): AwkExpr {
        val concats = ctx.concatExpr()
        val left = concatExpr(concats[0])
        val opCtx = ctx.relOp() ?: return left
        val right = concatExpr(concats[1])
        val op =
            when {
                opCtx.LT() != null -> BinOp.Lt
                opCtx.LE() != null -> BinOp.Le
                opCtx.EQEQ() != null -> BinOp.Eq
                opCtx.NEQ() != null -> BinOp.Ne
                opCtx.GT() != null -> BinOp.Gt
                opCtx.GE() != null -> BinOp.Ge
                else -> throw AwkParseError("internal: unknown comparison operator")
            }
        return Binary(op, left, right)
    }

    private fun concatExpr(ctx: AwkParser.ConcatExprContext): AwkExpr {
        val parts = ctx.addExpr()
        var acc: AwkExpr = addExpr(parts[0])
        for (i in 1 until parts.size) acc = Binary(BinOp.Concat, acc, addExpr(parts[i]))
        return acc
    }

    private fun addExpr(ctx: AwkParser.AddExprContext): AwkExpr {
        val parts = ctx.mulExpr()
        var acc = mulExpr(parts[0])
        // Operator tokens alternate between PLUS and MINUS — match by
        // child traversal so we don't have to inspect token types.
        var opIdx = 0
        for (i in 1 until parts.size) {
            // The first child is parts[0]'s tree; subsequent children
            // alternate operator, operand. Find the next PLUS/MINUS.
            while (opIdx < ctx.childCount) {
                val t = ctx.getChild(opIdx)?.text
                if (t == "+" || t == "-") break
                opIdx++
            }
            val op = if (ctx.getChild(opIdx)!!.text == "+") BinOp.Add else BinOp.Sub
            acc = Binary(op, acc, mulExpr(parts[i]))
            opIdx++
        }
        return acc
    }

    private fun mulExpr(ctx: AwkParser.MulExprContext): AwkExpr {
        val parts = ctx.powExpr()
        var acc = powExpr(parts[0])
        var opIdx = 0
        for (i in 1 until parts.size) {
            while (opIdx < ctx.childCount) {
                val t = ctx.getChild(opIdx)?.text
                if (t == "*" || t == "/" || t == "%") break
                opIdx++
            }
            val op =
                when (ctx.getChild(opIdx)!!.text) {
                    "*" -> BinOp.Mul
                    "/" -> BinOp.Div
                    else -> BinOp.Mod
                }
            acc = Binary(op, acc, powExpr(parts[i]))
            opIdx++
        }
        return acc
    }

    private fun powExpr(ctx: AwkParser.PowExprContext): AwkExpr {
        val left = unaryExpr(ctx.unaryExpr())
        val rhs = ctx.powExpr() ?: return left
        return Binary(BinOp.Pow, left, powExpr(rhs))
    }

    private fun unaryExpr(ctx: AwkParser.UnaryExprContext): AwkExpr =
        when (ctx) {
            is AwkParser.UnaryNotContext -> Unary(UnaryOp.Not, unaryExpr(ctx.unaryExpr()))
            is AwkParser.UnaryNegContext -> Unary(UnaryOp.Negate, unaryExpr(ctx.unaryExpr()))
            is AwkParser.UnaryPlusContext -> Unary(UnaryOp.Plus, unaryExpr(ctx.unaryExpr()))
            is AwkParser.UnaryPassthroughContext -> preIncDec(ctx.preIncDec())
            else -> throw AwkParseError("internal: unknown unary form")
        }

    private fun preIncDec(ctx: AwkParser.PreIncDecContext): AwkExpr =
        when (ctx) {
            is AwkParser.PreIncrementContext -> Unary(UnaryOp.PreInc, preIncDec(ctx.preIncDec()))
            is AwkParser.PreDecrementContext -> Unary(UnaryOp.PreDec, preIncDec(ctx.preIncDec()))
            is AwkParser.PreIncPassthroughContext -> postfixExpr(ctx.postfixExpr())
            else -> throw AwkParseError("internal: unknown pre-inc/dec form")
        }

    private fun postfixExpr(ctx: AwkParser.PostfixExprContext): AwkExpr {
        val base = fieldExpr(ctx.fieldExpr())
        val op = ctx.postIncDecOp() ?: return base
        return when {
            op.INC() != null -> Unary(UnaryOp.PostInc, base)
            op.DEC() != null -> Unary(UnaryOp.PostDec, base)
            else -> throw AwkParseError("internal: unknown postfix op")
        }
    }

    private fun fieldExpr(ctx: AwkParser.FieldExprContext): AwkExpr =
        when (ctx) {
            is AwkParser.FieldOfContext -> FieldRef(fieldExpr(ctx.fieldExpr()))
            is AwkParser.FieldPassthroughContext -> primary(ctx.primary())
            else -> throw AwkParseError("internal: unknown field-expr form")
        }

    private fun primary(ctx: AwkParser.PrimaryContext): AwkExpr =
        when (ctx) {
            is AwkParser.PrimNumberContext -> {
                NumLit(ctx.NUMBER().text.toDouble())
            }

            is AwkParser.PrimStringContext -> {
                StrLit(decodeStringLit(ctx.STRING().text))
            }

            is AwkParser.PrimRegexContext -> {
                RegexLit(decodeRegexLit(ctx.REGEX().text))
            }

            is AwkParser.PrimParenContext -> {
                Grouping(expr(ctx.expr()))
            }

            is AwkParser.PrimFuncCallContext -> {
                val name = ctx.IDENT().text
                val args = ctx.callArgs()?.expr()?.map { expr(it) } ?: emptyList()
                FunCall(name, args)
            }

            is AwkParser.PrimArrayRefContext -> {
                ArrayRef(ctx.IDENT().text, ctx.exprList().expr().map { expr(it) })
            }

            is AwkParser.PrimVarContext -> {
                val name = ctx.IDENT().text
                // POSIX: `length` without parens is sugar for `length($0)`.
                // The same isn't true for any other builtin, so handle the
                // single case rather than make it a general "callable as
                // a bare name" mechanism.
                if (name == "length") FunCall("length", emptyList()) else VarRef(name)
            }

            is AwkParser.PrimGetlineContext -> {
                val source =
                    ctx.getlineSource()?.let {
                        when (it) {
                            is AwkParser.GetlineFromFileContext -> GetlineSource.FromFile(primary(it.primary()))
                            else -> throw AwkParseError("internal: unknown getline source")
                        }
                    } ?: GetlineSource.MainInput
                val target = ctx.IDENT()?.text?.let { VarRef(it) }
                GetlineExpr(target = target, source = source)
            }

            else -> {
                throw AwkParseError("internal: unknown primary form ${ctx::class.simpleName}")
            }
        }

    // -- Literal decoding ---------------------------------------------------

    private fun decodeStringLit(raw: String): String {
        // raw = "..." with quotes still on it. Strip and process escapes.
        val body = raw.substring(1, raw.length - 1)
        if ('\\' !in body) return body
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= body.length) throw AwkParseError("dangling escape in string literal")
            when (val n = body[i + 1]) {
                'n' -> {
                    out.append('\n')
                    i += 2
                }

                't' -> {
                    out.append('\t')
                    i += 2
                }

                'r' -> {
                    out.append('\r')
                    i += 2
                }

                '"' -> {
                    out.append('"')
                    i += 2
                }

                '\\' -> {
                    out.append('\\')
                    i += 2
                }

                '/' -> {
                    out.append('/')
                    i += 2
                }

                'b' -> {
                    out.append('\b')
                    i += 2
                }

                'f' -> {
                    out.append('')
                    i += 2
                }

                'a' -> {
                    out.append('')
                    i += 2
                }

                'v' -> {
                    out.append('')
                    i += 2
                }

                'x' -> {
                    // \xHH — 1-2 hex digits. Variable-length.
                    var j = i + 2
                    while (j < body.length && j - (i + 2) < 2 && body[j].isHexDigit()) j++
                    if (j == i + 2) throw AwkParseError("bad \\x escape in string literal")
                    out.append(body.substring(i + 2, j).toInt(16).toChar())
                    i = j
                }

                in '0'..'7' -> {
                    // Octal: \N, \NN, \NNN (up to 3 digits).
                    var j = i + 1
                    while (j < body.length && j - (i + 1) < 3 && body[j] in '0'..'7') j++
                    out.append(body.substring(i + 1, j).toInt(8).toChar())
                    i = j
                }

                else -> {
                    // POSIX awk: unknown escape preserves the backslash.
                    out.append('\\')
                    out.append(n)
                    i += 2
                }
            }
        }
        return out.toString()
    }

    private fun decodeRegexLit(raw: String): String {
        // raw = "/body/". We keep the body as-is — the regex engine
        // processes its own escapes. Only `\/` (escaped slash) collapses
        // to `/` since the engine treats `/` as literal anyway.
        val body = raw.substring(1, raw.length - 1)
        if ('\\' !in body) return body
        // Replace `\/` with `/`; leave every other backslash alone for
        // the regex engine.
        return body.replace("\\/", "/")
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
