package com.accucodeai.kash.tools.jq.parser.antlr

import com.accucodeai.kash.json.JsonValue
import com.accucodeai.kash.json.jsonBool
import com.accucodeai.kash.json.jsonNull
import com.accucodeai.kash.json.jsonNumber
import com.accucodeai.kash.json.jsonString
import com.accucodeai.kash.tools.jq.JqParseError
import com.accucodeai.kash.tools.jq.ast.ArrayConstruct
import com.accucodeai.kash.tools.jq.ast.Assign
import com.accucodeai.kash.tools.jq.ast.BinOp
import com.accucodeai.kash.tools.jq.ast.BinaryOp
import com.accucodeai.kash.tools.jq.ast.BindAs
import com.accucodeai.kash.tools.jq.ast.Comma
import com.accucodeai.kash.tools.jq.ast.ErrorExpr
import com.accucodeai.kash.tools.jq.ast.FieldAccess
import com.accucodeai.kash.tools.jq.ast.Foreach
import com.accucodeai.kash.tools.jq.ast.FuncCall
import com.accucodeai.kash.tools.jq.ast.FunctionDef
import com.accucodeai.kash.tools.jq.ast.Identity
import com.accucodeai.kash.tools.jq.ast.IfThenElse
import com.accucodeai.kash.tools.jq.ast.Index
import com.accucodeai.kash.tools.jq.ast.IndexKind
import com.accucodeai.kash.tools.jq.ast.InterpolatedString
import com.accucodeai.kash.tools.jq.ast.JqExpr
import com.accucodeai.kash.tools.jq.ast.JqParam
import com.accucodeai.kash.tools.jq.ast.Literal
import com.accucodeai.kash.tools.jq.ast.Negate
import com.accucodeai.kash.tools.jq.ast.ObjectConstruct
import com.accucodeai.kash.tools.jq.ast.ObjectEntry
import com.accucodeai.kash.tools.jq.ast.Optional
import com.accucodeai.kash.tools.jq.ast.Pipe
import com.accucodeai.kash.tools.jq.ast.RecursiveDescent
import com.accucodeai.kash.tools.jq.ast.Reduce
import com.accucodeai.kash.tools.jq.ast.StringPart
import com.accucodeai.kash.tools.jq.ast.TryCatch
import com.accucodeai.kash.tools.jq.ast.UpdateAssign
import com.accucodeai.kash.tools.jq.ast.VarRef

/**
 * Walks a [JqParser] parse tree and emits the existing [JqExpr] AST.
 *
 * The visitor mirrors the hand-written precedence-climbing parser one rule
 * at a time — `pipe` is right-assoc, `comma`/`alt`/`or`/`and`/`add`/`mul`
 * are left-assoc, `cmp` is non-assoc. The grammar takes care of structure;
 * the visitor just maps it to nodes.
 *
 * Why not extend [JqParserBaseVisitor]? It would require [ParseTree.accept]
 * dispatch through the generated visitor wiring; for a small grammar the
 * hand `when` is shorter and lets us keep all the AST-construction logic
 * in one place without 30+ trivial overrides.
 */
internal class JqAstBuilder {
    fun program(ctx: JqParser.ProgramContext): JqExpr = pipe(ctx.pipe())

    private fun pipe(ctx: JqParser.PipeContext): JqExpr {
        // `pipe : comma (PIPE pipe)?` produces a right-leaning chain of
        // PipeContexts. Collect the heads iteratively rather than recursing
        // — `a | b | c | … | z` ten thousand deep would otherwise blow the
        // JVM stack. Then right-fold so the resulting AST keeps `|`'s
        // right-associativity: Pipe(a, Pipe(b, Pipe(c, …))).
        val heads = mutableListOf<JqExpr>()
        var cur: JqParser.PipeContext? = ctx
        while (cur != null) {
            heads += comma(cur.comma())
            cur = cur.pipe()
        }
        var acc = heads.last()
        for (i in heads.size - 2 downTo 0) acc = Pipe(heads[i], acc)
        return acc
    }

    private fun comma(ctx: JqParser.CommaContext): JqExpr {
        val parts = ctx.assign()
        var acc = assign(parts[0])
        for (i in 1 until parts.size) acc = Comma(acc, assign(parts[i]))
        return acc
    }

    private fun assign(ctx: JqParser.AssignContext): JqExpr {
        val parts = ctx.asExpr()
        val left = asExpr(parts[0])
        val op = ctx.assignOp() ?: return left
        val right = asExpr(parts[1])
        return when {
            op.ASSIGN() != null -> Assign(left, right)
            op.SETPIPE() != null -> UpdateAssign(left, right)
            op.SETPLUS() != null -> UpdateAssign(left, BinaryOp(BinOp.Add, Identity, right))
            op.SETMINUS() != null -> UpdateAssign(left, BinaryOp(BinOp.Sub, Identity, right))
            op.SETMULT() != null -> UpdateAssign(left, BinaryOp(BinOp.Mul, Identity, right))
            op.SETDIV() != null -> UpdateAssign(left, BinaryOp(BinOp.Div, Identity, right))
            op.SETMOD() != null -> UpdateAssign(left, BinaryOp(BinOp.Mod, Identity, right))
            op.SETALT() != null -> UpdateAssign(left, BinaryOp(BinOp.Alternative, Identity, right))
            else -> throw JqParseError("internal: unknown assignment operator")
        }
    }

    private fun asExpr(ctx: JqParser.AsExprContext): JqExpr {
        val left = alt(ctx.alt())
        val pipeCtx = ctx.pipe() ?: return left
        val varName = stripDollar(ctx.VAR()!!.text)
        return BindAs(left, varName, pipe(pipeCtx))
    }

    private fun alt(ctx: JqParser.AltContext): JqExpr {
        val parts = ctx.orExpr()
        var acc = orExpr(parts[0])
        for (i in 1 until parts.size) acc = BinaryOp(BinOp.Alternative, acc, orExpr(parts[i]))
        return acc
    }

    private fun orExpr(ctx: JqParser.OrExprContext): JqExpr {
        val parts = ctx.andExpr()
        var acc = andExpr(parts[0])
        for (i in 1 until parts.size) acc = BinaryOp(BinOp.Or, acc, andExpr(parts[i]))
        return acc
    }

    private fun andExpr(ctx: JqParser.AndExprContext): JqExpr {
        val parts = ctx.cmp()
        var acc = cmp(parts[0])
        for (i in 1 until parts.size) acc = BinaryOp(BinOp.And, acc, cmp(parts[i]))
        return acc
    }

    private fun cmp(ctx: JqParser.CmpContext): JqExpr {
        val parts = ctx.addExpr()
        val left = addExpr(parts[0])
        val op = ctx.cmpOp() ?: return left
        val right = addExpr(parts[1])
        val binOp =
            when {
                op.EQ_EQ() != null -> BinOp.Eq
                op.NEQ() != null -> BinOp.Ne
                op.LT() != null -> BinOp.Lt
                op.LE() != null -> BinOp.Le
                op.GT() != null -> BinOp.Gt
                op.GE() != null -> BinOp.Ge
                else -> throw JqParseError("internal: unknown comparison operator")
            }
        return BinaryOp(binOp, left, right)
    }

    private fun addExpr(ctx: JqParser.AddExprContext): JqExpr {
        val parts = ctx.mulExpr()
        val ops = ctx.addOp()
        var acc = mulExpr(parts[0])
        for (i in 1 until parts.size) {
            val op = if (ops[i - 1].PLUS() != null) BinOp.Add else BinOp.Sub
            acc = BinaryOp(op, acc, mulExpr(parts[i]))
        }
        return acc
    }

    private fun mulExpr(ctx: JqParser.MulExprContext): JqExpr {
        val parts = ctx.unary()
        val ops = ctx.mulOp()
        var acc = unary(parts[0])
        for (i in 1 until parts.size) {
            val mop = ops[i - 1]
            val op =
                when {
                    mop.STAR() != null -> BinOp.Mul
                    mop.SLASH() != null -> BinOp.Div
                    mop.PERCENT() != null -> BinOp.Mod
                    else -> throw JqParseError("internal: unknown multiplicative operator")
                }
            acc = BinaryOp(op, acc, unary(parts[i]))
        }
        return acc
    }

    private fun unary(ctx: JqParser.UnaryContext): JqExpr {
        // Unwrap any number of leading `-` iteratively so `------x` (or a
        // pathologically long minus chain) doesn't recurse.
        var negations = 0
        var cur: JqParser.UnaryContext = ctx
        while (cur is JqParser.UnaryNegContext) {
            negations++
            cur = cur.unary()
        }
        var e =
            when (cur) {
                is JqParser.UnaryPostfixContext -> postfix(cur.postfix())
                else -> throw JqParseError("internal: unknown unary form")
            }
        repeat(negations) { e = Negate(e) }
        return e
    }

    private fun postfix(ctx: JqParser.PostfixContext): JqExpr {
        var e = term(ctx.term())
        for (op in ctx.postOp()) {
            e =
                when (op) {
                    is JqParser.PostFieldContext -> {
                        FieldAccess(e, op.IDENT().text, op.QUESTION() != null)
                    }

                    is JqParser.PostIndexContext -> {
                        Index(e, indexBody(op.indexBody()), op.QUESTION() != null)
                    }

                    is JqParser.PostOptContext -> {
                        Optional(e)
                    }

                    else -> {
                        throw JqParseError("internal: unknown postfix operator")
                    }
                }
        }
        return e
    }

    private fun indexBody(ctx: JqParser.IndexBodyContext): IndexKind =
        when (ctx) {
            is JqParser.IndexIterateContext -> {
                IndexKind.Iterate
            }

            is JqParser.IndexAtContext -> {
                IndexKind.At(pipe(ctx.pipe()))
            }

            is JqParser.IndexSliceToContext -> {
                IndexKind.Slice(null, pipe(ctx.pipe()))
            }

            is JqParser.IndexSliceFromContext -> {
                val pipes = ctx.pipe()
                IndexKind.Slice(pipe(pipes[0]), pipes.getOrNull(1)?.let { pipe(it) })
            }

            else -> {
                throw JqParseError("internal: unknown index body")
            }
        }

    private fun term(ctx: JqParser.TermContext): JqExpr =
        when (ctx) {
            is JqParser.TermIdentityContext -> {
                Identity
            }

            is JqParser.TermRecurseContext -> {
                RecursiveDescent
            }

            is JqParser.TermDotFieldContext -> {
                FieldAccess(Identity, ctx.IDENT().text, ctx.QUESTION() != null)
            }

            is JqParser.TermDotIndexContext -> {
                Index(Identity, indexBody(ctx.indexBody()), ctx.QUESTION() != null)
            }

            is JqParser.TermDotStringContext -> {
                // `."key"` — only static (uninterpolated) string keys are
                // legal; dynamic keys after `.` aren't allowed in jq.
                val s = stringLit(ctx.stringLit())
                if (s is InterpolatedString && s.parts.size == 1 && s.parts[0] is StringPart.Text) {
                    FieldAccess(Identity, (s.parts[0] as StringPart.Text).s, false)
                } else {
                    throw JqParseError("dynamic key after `.` not supported")
                }
            }

            is JqParser.TermNumberContext -> {
                Literal(numLiteral(ctx.NUMBER().text))
            }

            is JqParser.TermNullContext -> {
                Literal(jsonNull())
            }

            is JqParser.TermTrueContext -> {
                Literal(jsonBool(true))
            }

            is JqParser.TermFalseContext -> {
                Literal(jsonBool(false))
            }

            is JqParser.TermStringContext -> {
                stringLit(ctx.stringLit())
            }

            is JqParser.TermVarContext -> {
                VarRef(stripDollar(ctx.VAR().text))
            }

            is JqParser.TermParenContext -> {
                pipe(ctx.pipe())
            }

            is JqParser.TermArrayLitContext -> {
                ArrayConstruct(ctx.pipe()?.let { pipe(it) })
            }

            is JqParser.TermObjectLitContext -> {
                val entries =
                    ctx
                        .objEntries()
                        ?.objEntry()
                        ?.map { objEntry(it) }
                        .orEmpty()
                ObjectConstruct(entries)
            }

            is JqParser.TermIfContext -> {
                ifExpr(ctx.ifExpr())
            }

            is JqParser.TermTryContext -> {
                tryExpr(ctx.tryExpr())
            }

            is JqParser.TermReduceContext -> {
                reduceExpr(ctx.reduceExpr())
            }

            is JqParser.TermForeachContext -> {
                foreachExpr(ctx.foreachExpr())
            }

            is JqParser.TermDefContext -> {
                defExpr(ctx.defExpr())
            }

            is JqParser.TermNotContext -> {
                FuncCall("not", emptyList())
            }

            is JqParser.TermFuncCallContext -> {
                val name = ctx.IDENT().text
                val argsCtx = ctx.funcArgs()
                if (argsCtx == null) {
                    if (name == "error") {
                        ErrorExpr(null)
                    } else {
                        FuncCall(name, emptyList())
                    }
                } else {
                    val args = argsCtx.pipe().map { pipe(it) }
                    if (name == "error") ErrorExpr(args.firstOrNull()) else FuncCall(name, args)
                }
            }

            is JqParser.TermFormatContext -> {
                throw JqParseError("format strings (@${ctx.FORMAT().text}) not supported in v1")
            }

            else -> {
                throw JqParseError("internal: unknown term form ${ctx::class.simpleName}")
            }
        }

    private fun stringLit(ctx: JqParser.StringLitContext): JqExpr {
        val parts =
            ctx.stringPart().map { p ->
                when (p) {
                    is JqParser.StringPartTextContext -> {
                        StringPart.Text(processEscapes(p.STRING_TEXT().text))
                    }

                    is JqParser.StringPartInterpContext -> {
                        StringPart.Expr(pipe(p.pipe()))
                    }

                    else -> {
                        throw JqParseError("internal: unknown string part")
                    }
                }
            }
        return InterpolatedString(if (parts.isEmpty()) listOf(StringPart.Text("")) else parts)
    }

    private fun objEntry(ctx: JqParser.ObjEntryContext): ObjectEntry =
        when (ctx) {
            is JqParser.ObjEntryIdentContext -> {
                val name = ctx.IDENT().text
                val key = Literal(jsonString(name))
                val value = ctx.objValue()?.let { objValue(it) } ?: FieldAccess(Identity, name, false)
                ObjectEntry(key, value)
            }

            is JqParser.ObjEntryStringContext -> {
                ObjectEntry(stringLit(ctx.stringLit()), objValue(ctx.objValue()))
            }

            is JqParser.ObjEntryVarContext -> {
                val name = stripDollar(ctx.VAR().text)
                ObjectEntry(Literal(jsonString(name)), VarRef(name))
            }

            is JqParser.ObjEntryParenContext -> {
                ObjectEntry(pipe(ctx.pipe()), objValue(ctx.objValue()))
            }

            else -> {
                throw JqParseError("internal: unknown object entry shape")
            }
        }

    private fun objValue(ctx: JqParser.ObjValueContext): JqExpr = alt(ctx.alt())

    private fun ifExpr(ctx: JqParser.IfExprContext): JqExpr {
        val pipes = ctx.pipe()
        val cond = pipe(pipes[0])
        val thenB = pipe(pipes[1])
        // The grammar matches: KW_IF p0 KW_THEN p1 (KW_ELIF p_i KW_THEN p_{i+1})* (KW_ELSE p_last)? KW_END
        // After p1, every pair of remaining pipes is an elif (cond,then). A
        // trailing odd one is the else body.
        val elifs = mutableListOf<Pair<JqExpr, JqExpr>>()
        val hasElse = ctx.KW_ELSE() != null
        val elifCount = (pipes.size - 2 - (if (hasElse) 1 else 0)) / 2
        for (i in 0 until elifCount) {
            val condIx = 2 + i * 2
            elifs += pipe(pipes[condIx]) to pipe(pipes[condIx + 1])
        }
        val elseB = if (hasElse) pipe(pipes.last()) else Identity
        return IfThenElse(cond, thenB, elifs, elseB)
    }

    private fun tryExpr(ctx: JqParser.TryExprContext): JqExpr {
        val postfixes = ctx.postfix()
        val body = postfix(postfixes[0])
        val handler = if (postfixes.size > 1) postfix(postfixes[1]) else null
        return TryCatch(body, handler)
    }

    private fun reduceExpr(ctx: JqParser.ReduceExprContext): JqExpr {
        val src = postfix(ctx.postfix())
        val name = stripDollar(ctx.VAR().text)
        val pipes = ctx.pipe()
        return Reduce(src, name, pipe(pipes[0]), pipe(pipes[1]))
    }

    private fun foreachExpr(ctx: JqParser.ForeachExprContext): JqExpr {
        val src = postfix(ctx.postfix())
        val name = stripDollar(ctx.VAR().text)
        val pipes = ctx.pipe()
        val init = pipe(pipes[0])
        val update = pipe(pipes[1])
        val extract = pipes.getOrNull(2)?.let { pipe(it) }
        return Foreach(src, name, init, update, extract)
    }

    private fun defExpr(ctx: JqParser.DefExprContext): JqExpr {
        val name = ctx.IDENT().text
        val params =
            ctx
                .defParams()
                ?.defParam()
                ?.map { p ->
                    if (p.IDENT() != null) {
                        JqParam.FilterParam(p.IDENT()!!.text)
                    } else {
                        JqParam.ValueParam(stripDollar(p.VAR()!!.text))
                    }
                }.orEmpty()
        val pipes = ctx.pipe()
        return FunctionDef(name, params, pipe(pipes[0]), pipe(pipes[1]))
    }

    // --- helpers --------------------------------------------------------

    private fun stripDollar(varText: String): String = varText.removePrefix("$")

    private fun numLiteral(text: String): JsonValue {
        val l = text.toLongOrNull()
        return if (l != null) jsonNumber(l) else jsonNumber(text.toDouble())
    }

    /**
     * Resolve jq string escape sequences within a STRING_TEXT run. The
     * lexer emits the raw source characters (including backslash escapes
     * other than `\(`); we expand them here to match the hand lexer's
     * post-tokenization output.
     */
    private fun processEscapes(raw: String): String {
        if ('\\' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= raw.length) throw JqParseError("dangling escape in string literal")
            when (val n = raw[i + 1]) {
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

                'u' -> {
                    if (i + 6 > raw.length) throw JqParseError("bad unicode escape in string literal")
                    val hex = raw.substring(i + 2, i + 6)
                    out.append(hex.toInt(16).toChar())
                    i += 6
                }

                else -> {
                    out.append(n)
                    i += 2
                }
            }
        }
        return out.toString()
    }
}
