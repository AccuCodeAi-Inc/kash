package com.accucodeai.kash.tools.bc

/**
 * Recursive-descent parser for the bc subset described in [Stmt] / [Expr].
 *
 * Precedence (low → high), matching POSIX bc:
 *   assignment (=, +=, -=, *=, /=, %=, ^=)   right-associative
 *   logical OR (||)
 *   logical AND (&&)
 *   logical NOT (!)
 *   relational (==, !=, <, <=, >, >=)
 *   add / sub  (+, -)
 *   mul / div / mod  (*, /, %)
 *   unary minus
 *   exponent  (^, right-associative)
 *   primary
 */
internal class BcParser(
    tokens: List<Token>,
) {
    private val toks = tokens
    private var pos = 0

    fun parseProgram(): List<Stmt> {
        val out = mutableListOf<Stmt>()
        skipNewlines()
        while (peek().kind != Tok.EOF) {
            val s = parseStmt()
            out.add(s)
            skipTerminators()
        }
        return out
    }

    // --- statements ---

    private fun parseStmt(): Stmt {
        val t = peek()
        return when (t.kind) {
            Tok.LBRACE -> {
                parseBlock()
            }

            Tok.KW_IF -> {
                parseIf()
            }

            Tok.KW_WHILE -> {
                parseWhile()
            }

            Tok.KW_FOR -> {
                parseFor()
            }

            Tok.KW_DEFINE -> {
                parseDefine()
            }

            Tok.KW_RETURN -> {
                parseReturn()
            }

            Tok.KW_BREAK -> {
                eat()
                Stmt.Break
            }

            Tok.KW_CONTINUE -> {
                eat()
                Stmt.Continue
            }

            Tok.KW_QUIT -> {
                eat()
                Stmt.Quit
            }

            Tok.KW_HALT -> {
                eat()
                Stmt.Halt
            }

            Tok.KW_PRINT -> {
                parsePrint()
            }

            Tok.NEWLINE, Tok.SEMI -> {
                eat()
                Stmt.Empty
            }

            else -> {
                parseExprStmt()
            }
        }
    }

    private fun parseBlock(): Stmt.Block {
        expect(Tok.LBRACE)
        skipTerminators()
        val out = mutableListOf<Stmt>()
        while (peek().kind != Tok.RBRACE && peek().kind != Tok.EOF) {
            out.add(parseStmt())
            skipTerminators()
        }
        expect(Tok.RBRACE)
        return Stmt.Block(out)
    }

    private fun parseIf(): Stmt {
        expect(Tok.KW_IF)
        expect(Tok.LPAREN)
        val cond = parseExpr()
        expect(Tok.RPAREN)
        skipNewlines()
        val thenStmt = parseStmt()
        skipNewlines()
        val elseStmt =
            if (peek().kind == Tok.KW_ELSE) {
                eat()
                skipNewlines()
                parseStmt()
            } else {
                null
            }
        return Stmt.If(cond, thenStmt, elseStmt)
    }

    private fun parseWhile(): Stmt {
        expect(Tok.KW_WHILE)
        expect(Tok.LPAREN)
        val cond = parseExpr()
        expect(Tok.RPAREN)
        skipNewlines()
        val body = parseStmt()
        return Stmt.While(cond, body)
    }

    private fun parseFor(): Stmt {
        expect(Tok.KW_FOR)
        expect(Tok.LPAREN)
        val init = if (peek().kind == Tok.SEMI) null else parseExpr()
        expect(Tok.SEMI)
        val cond = if (peek().kind == Tok.SEMI) null else parseExpr()
        expect(Tok.SEMI)
        val post = if (peek().kind == Tok.RPAREN) null else parseExpr()
        expect(Tok.RPAREN)
        skipNewlines()
        val body = parseStmt()
        return Stmt.For(init, cond, post, body)
    }

    private fun parseDefine(): Stmt {
        expect(Tok.KW_DEFINE)
        val nameTok = expectAny(Tok.IDENT)
        expect(Tok.LPAREN)
        val params = mutableListOf<Param>()
        if (peek().kind != Tok.RPAREN) {
            params.add(parseParam())
            while (peek().kind == Tok.COMMA) {
                eat()
                params.add(parseParam())
            }
        }
        expect(Tok.RPAREN)
        skipNewlines()
        expect(Tok.LBRACE)
        skipTerminators()
        val autos = mutableListOf<Param>()
        if (peek().kind == Tok.KW_AUTO) {
            eat()
            autos.add(parseParam())
            while (peek().kind == Tok.COMMA) {
                eat()
                autos.add(parseParam())
            }
            skipTerminators()
        }
        val body = mutableListOf<Stmt>()
        while (peek().kind != Tok.RBRACE && peek().kind != Tok.EOF) {
            body.add(parseStmt())
            skipTerminators()
        }
        expect(Tok.RBRACE)
        return Stmt.FunctionDef(nameTok.text, params, autos, body)
    }

    private fun parseParam(): Param {
        val n = expectAny(Tok.IDENT).text
        return if (peek().kind == Tok.LBRACK) {
            eat()
            expect(Tok.RBRACK)
            Param(n, true)
        } else {
            Param(n, false)
        }
    }

    private fun parseReturn(): Stmt {
        expect(Tok.KW_RETURN)
        // optional ( expr ) or bare expr (until newline/semi)
        val k = peek().kind
        return if (k == Tok.NEWLINE || k == Tok.SEMI || k == Tok.RBRACE || k == Tok.EOF) {
            Stmt.Return(null)
        } else {
            val v = parseExpr()
            Stmt.Return(v)
        }
    }

    private fun parsePrint(): Stmt {
        expect(Tok.KW_PRINT)
        val items = mutableListOf<Expr>()
        items.add(parsePrintItem())
        while (peek().kind == Tok.COMMA) {
            eat()
            items.add(parsePrintItem())
        }
        return Stmt.Print(items)
    }

    private fun parsePrintItem(): Expr = if (peek().kind == Tok.STRING) Expr.StrLit(eat().text) else parseExpr()

    private fun parseExprStmt(): Stmt {
        val e = parseExpr()
        // POSIX bc: assignments (and assignment-only expression statements)
        // don't auto-print; bare expressions do. We approximate: auto-print
        // unless the top node is Assign.
        val autoPrint = e !is Expr.Assign
        return Stmt.ExprStmt(e, autoPrint)
    }

    // --- expressions: Pratt-ish recursive descent ---

    private fun parseExpr(): Expr = parseAssign()

    private fun parseAssign(): Expr {
        val left = parseOr()
        val k = peek().kind
        if (k in assignTokens) {
            val opTok = eat()
            val right = parseAssign()
            val lv =
                when (left) {
                    is Expr.VarRef -> {
                        LValue.Var(left.name)
                    }

                    is Expr.ArrayRef -> {
                        LValue.Arr(left.name, left.index)
                    }

                    is Expr.Builtin -> {
                        // assignment to scale/ibase/obase is rendered as Builtin in parsing —
                        // but we actually represent those via VarRef("scale"). Reach here only
                        // for sqrt/length which aren't lvalues.
                        throw BcParseError("invalid assignment target", opTok.line)
                    }

                    else -> {
                        throw BcParseError("invalid assignment target", opTok.line)
                    }
                }
            return Expr.Assign(opTok.text, lv, right)
        }
        return left
    }

    private fun parseOr(): Expr {
        var l = parseAnd()
        while (peek().kind == Tok.OR) {
            eat()
            val r = parseAnd()
            l = Expr.Binary("||", l, r)
        }
        return l
    }

    private fun parseAnd(): Expr {
        var l = parseNot()
        while (peek().kind == Tok.AND) {
            eat()
            val r = parseNot()
            l = Expr.Binary("&&", l, r)
        }
        return l
    }

    private fun parseNot(): Expr =
        if (peek().kind == Tok.NOT) {
            eat()
            val e = parseNot()
            Expr.Unary("!", e)
        } else {
            parseRel()
        }

    private fun parseRel(): Expr {
        var l = parseAdd()
        val k = peek().kind
        if (k in relTokens) {
            val op = eat().text
            val r = parseAdd()
            l = Expr.Binary(op, l, r)
        }
        return l
    }

    private fun parseAdd(): Expr {
        var l = parseMul()
        while (peek().kind == Tok.PLUS || peek().kind == Tok.MINUS) {
            val op = eat().text
            val r = parseMul()
            l = Expr.Binary(op, l, r)
        }
        return l
    }

    private fun parseMul(): Expr {
        var l = parseUnary()
        while (peek().kind == Tok.STAR || peek().kind == Tok.SLASH || peek().kind == Tok.PERCENT) {
            val op = eat().text
            val r = parseUnary()
            l = Expr.Binary(op, l, r)
        }
        return l
    }

    private fun parseUnary(): Expr {
        if (peek().kind == Tok.MINUS) {
            eat()
            val e = parseUnary()
            return Expr.Unary("-", e)
        }
        if (peek().kind == Tok.PLUS) {
            eat()
            return parseUnary()
        }
        return parsePow()
    }

    private fun parsePow(): Expr {
        val l = parsePrimary()
        if (peek().kind == Tok.CARET) {
            eat()
            val r = parseUnary() // right-associative; allow `-` on RHS via Unary path
            return Expr.Binary("^", l, r)
        }
        return l
    }

    private fun parsePrimary(): Expr {
        val t = peek()
        return when (t.kind) {
            Tok.NUMBER -> {
                eat()
                Expr.Num(t.text)
            }

            Tok.LPAREN -> {
                eat()
                val e = parseExpr()
                expect(Tok.RPAREN)
                e
            }

            Tok.KW_LENGTH -> {
                parseBuiltinCall("length")
            }

            Tok.KW_SQRT -> {
                parseBuiltinCall("sqrt")
            }

            Tok.KW_SCALE -> {
                parseScale()
            }

            // scale could be variable OR scale(x)
            Tok.KW_IBASE -> {
                eat()
                Expr.VarRef("ibase")
            }

            Tok.KW_OBASE -> {
                eat()
                Expr.VarRef("obase")
            }

            Tok.KW_LAST -> {
                eat()
                Expr.VarRef("last")
            }

            Tok.IDENT -> {
                val nm = eat().text
                when (peek().kind) {
                    Tok.LPAREN -> {
                        eat()
                        val args = mutableListOf<Expr>()
                        if (peek().kind != Tok.RPAREN) {
                            args.add(parseExpr())
                            while (peek().kind == Tok.COMMA) {
                                eat()
                                args.add(parseExpr())
                            }
                        }
                        expect(Tok.RPAREN)
                        Expr.Call(nm, args)
                    }

                    Tok.LBRACK -> {
                        eat()
                        val idx = parseExpr()
                        expect(Tok.RBRACK)
                        Expr.ArrayRef(nm, idx)
                    }

                    else -> {
                        Expr.VarRef(nm)
                    }
                }
            }

            Tok.MINUS -> {
                eat()
                Expr.Unary("-", parseUnary())
            }

            // shouldn't usually reach
            else -> {
                throw BcParseError("unexpected token: ${t.kind} '${t.text}'", t.line)
            }
        }
    }

    private fun parseBuiltinCall(name: String): Expr {
        eat() // keyword
        expect(Tok.LPAREN)
        val arg = parseExpr()
        expect(Tok.RPAREN)
        return Expr.Builtin(name, arg)
    }

    private fun parseScale(): Expr {
        // `scale` may be either the variable or a builtin call `scale(x)`
        eat()
        return if (peek().kind == Tok.LPAREN) {
            eat()
            val arg = parseExpr()
            expect(Tok.RPAREN)
            Expr.Builtin("scale", arg)
        } else {
            Expr.VarRef("scale")
        }
    }

    // --- helpers ---

    private fun peek(off: Int = 0): Token = toks[(pos + off).coerceAtMost(toks.size - 1)]

    private fun eat(): Token = toks[pos++]

    private fun expect(k: Tok): Token {
        val t = peek()
        if (t.kind != k) throw BcParseError("expected $k, got ${t.kind} '${t.text}'", t.line)
        return eat()
    }

    private fun expectAny(vararg k: Tok): Token {
        val t = peek()
        if (t.kind !in k) throw BcParseError("expected ${k.joinToString(",")}, got ${t.kind} '${t.text}'", t.line)
        return eat()
    }

    private fun skipNewlines() {
        while (peek().kind == Tok.NEWLINE) eat()
    }

    private fun skipTerminators() {
        while (peek().kind == Tok.NEWLINE || peek().kind == Tok.SEMI) eat()
    }

    companion object {
        private val assignTokens =
            setOf(
                Tok.ASSIGN,
                Tok.PLUS_ASSIGN,
                Tok.MINUS_ASSIGN,
                Tok.STAR_ASSIGN,
                Tok.SLASH_ASSIGN,
                Tok.PERCENT_ASSIGN,
                Tok.CARET_ASSIGN,
            )
        private val relTokens =
            setOf(Tok.EQ, Tok.NE, Tok.LT, Tok.LE, Tok.GT, Tok.GE)
    }
}
