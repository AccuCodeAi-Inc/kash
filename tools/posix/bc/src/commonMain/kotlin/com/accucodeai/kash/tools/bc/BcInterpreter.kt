package com.accucodeai.kash.tools.bc

/**
 * Tree-walking interpreter for the bc AST.
 *
 * State held in [BcEnv]: global vars + arrays + the special pseudo-variables
 * `scale`, `ibase`, `obase`, `last`, plus a function table. Function calls
 * push a new local scope; `auto` and parameters live there.
 *
 * Output is collected via [emit] — the [BcCommand] passes a closure that
 * writes to ctx.stdout. We auto-emit trailing newlines for expression-results
 * (POSIX) but not for `print`.
 */
internal class BcInterpreter(
    private val emit: (String) -> Unit,
    private val emitErr: (String) -> Unit = {},
) {
    private val globals = HashMap<String, BcNumber>()
    private val arrays = HashMap<String, HashMap<Int, BcNumber>>()
    private val functions = HashMap<String, Stmt.FunctionDef>()
    private val scopes: ArrayDeque<Scope> = ArrayDeque()

    private var scale: Int = 0
    private var ibase: Int = 10
    private var obase: Int = 10
    private var last: BcNumber = BcNumber.ZERO

    /** Sentinel return value thrown by `return` to unwind a function call. */
    private class ReturnSignal(
        val value: BcNumber,
    ) : RuntimeException()

    private object BreakSignal : RuntimeException()

    private object ContinueSignal : RuntimeException()

    object QuitSignal : RuntimeException()

    private class Scope {
        val vars = HashMap<String, BcNumber>()
        val arrays = HashMap<String, HashMap<Int, BcNumber>>()
        val arrayShadows = HashSet<String>() // names that were shadowed (copy-on-call)
    }

    fun loadMathLib() {
        BcMathLib.installInto(this)
    }

    internal fun defineFunction(def: Stmt.FunctionDef) {
        functions[def.name] = def
    }

    fun run(program: List<Stmt>) {
        for (s in program) {
            try {
                execStmt(s)
            } catch (_: QuitSignal) {
                throw QuitSignal
            }
        }
    }

    // --- statements ---

    private fun execStmt(s: Stmt) {
        when (s) {
            is Stmt.ExprStmt -> {
                val v = evalExpr(s.expr)
                last = v
                if (s.autoPrint) emit(formatForOutput(v) + "\n")
            }

            is Stmt.Print -> {
                val sb = StringBuilder()
                for (item in s.items) {
                    when (item) {
                        is Expr.StrLit -> sb.append(processStringEscapes(item.value))
                        else -> sb.append(formatForOutput(evalExpr(item)))
                    }
                }
                emit(sb.toString())
            }

            is Stmt.Block -> {
                s.stmts.forEach { execStmt(it) }
            }

            is Stmt.If -> {
                if (toBool(evalExpr(s.cond))) execStmt(s.then) else s.els?.let { execStmt(it) }
            }

            is Stmt.While -> {
                while (toBool(evalExpr(s.cond))) {
                    try {
                        execStmt(s.body)
                    } catch (_: BreakSignal) {
                        return
                    } catch (_: ContinueSignal) {
                    }
                }
            }

            is Stmt.For -> {
                s.init?.let { evalExpr(it) }
                while (s.cond?.let { toBool(evalExpr(it)) } != false) {
                    try {
                        execStmt(s.body)
                    } catch (_: BreakSignal) {
                        return
                    } catch (_: ContinueSignal) {
                    }
                    s.post?.let { evalExpr(it) }
                }
            }

            is Stmt.FunctionDef -> {
                functions[s.name] = s
            }

            is Stmt.Return -> {
                throw ReturnSignal(s.value?.let { evalExpr(it) } ?: BcNumber.ZERO)
            }

            Stmt.Break -> {
                throw BreakSignal
            }

            Stmt.Continue -> {
                throw ContinueSignal
            }

            Stmt.Quit -> {
                throw QuitSignal
            }

            Stmt.Halt -> {
                throw QuitSignal
            }

            Stmt.Empty -> {
                Unit
            }
        }
    }

    // --- expressions ---

    private fun evalExpr(e: Expr): BcNumber =
        when (e) {
            is Expr.Num -> {
                BcNumber.parse(e.literal, ibase)
            }

            is Expr.StrLit -> {
                throw BcRuntimeError("string used in numeric context")
            }

            is Expr.VarRef -> {
                when (e.name) {
                    "scale" -> BcNumber.fromInt(scale)
                    "ibase" -> BcNumber.fromInt(ibase)
                    "obase" -> BcNumber.fromInt(obase)
                    "last" -> last
                    else -> readVar(e.name)
                }
            }

            is Expr.ArrayRef -> {
                val idx = toArrayIndex(evalExpr(e.index))
                readArray(e.name, idx)
            }

            is Expr.Unary -> {
                when (e.op) {
                    "-" -> -evalExpr(e.expr)
                    "!" -> if (toBool(evalExpr(e.expr))) BcNumber.ZERO else BcNumber.ONE
                    else -> throw BcRuntimeError("bad unary op ${e.op}")
                }
            }

            is Expr.Binary -> {
                evalBinary(e)
            }

            is Expr.Assign -> {
                evalAssign(e)
            }

            is Expr.Call -> {
                evalCall(e.name, e.args)
            }

            is Expr.Builtin -> {
                evalBuiltin(e.name, e.arg)
            }
        }

    private fun evalBinary(e: Expr.Binary): BcNumber {
        // short-circuit boolean
        if (e.op == "&&") {
            return if (!toBool(evalExpr(e.l))) {
                BcNumber.ZERO
            } else if (toBool(evalExpr(e.r))) {
                BcNumber.ONE
            } else {
                BcNumber.ZERO
            }
        }
        if (e.op == "||") {
            return if (toBool(evalExpr(e.l))) {
                BcNumber.ONE
            } else if (toBool(evalExpr(e.r))) {
                BcNumber.ONE
            } else {
                BcNumber.ZERO
            }
        }
        val l = evalExpr(e.l)
        val r = evalExpr(e.r)
        return when (e.op) {
            "+" -> l + r
            "-" -> l - r
            "*" -> l.mulBc(r, scale)
            "/" -> l.divBc(r, scale)
            "%" -> l.modBc(r, scale)
            "^" -> l.powBc(r, scale)
            "==" -> boolOf(l.compareTo(r) == 0)
            "!=" -> boolOf(l.compareTo(r) != 0)
            "<" -> boolOf(l.compareTo(r) < 0)
            "<=" -> boolOf(l.compareTo(r) <= 0)
            ">" -> boolOf(l.compareTo(r) > 0)
            ">=" -> boolOf(l.compareTo(r) >= 0)
            else -> throw BcRuntimeError("bad binary op ${e.op}")
        }
    }

    private fun evalAssign(e: Expr.Assign): BcNumber {
        val rhs = evalExpr(e.value)
        val newValue =
            if (e.op == "=") {
                rhs
            } else {
                val cur = readLValue(e.target)
                when (e.op) {
                    "+=" -> cur + rhs
                    "-=" -> cur - rhs
                    "*=" -> cur.mulBc(rhs, scale)
                    "/=" -> cur.divBc(rhs, scale)
                    "%=" -> cur.modBc(rhs, scale)
                    "^=" -> cur.powBc(rhs, scale)
                    else -> throw BcRuntimeError("bad assign op ${e.op}")
                }
            }
        writeLValue(e.target, newValue)
        return newValue
    }

    private fun readLValue(lv: LValue): BcNumber =
        when (lv) {
            is LValue.Var -> {
                when (lv.name) {
                    "scale" -> BcNumber.fromInt(scale)
                    "ibase" -> BcNumber.fromInt(ibase)
                    "obase" -> BcNumber.fromInt(obase)
                    "last" -> last
                    else -> readVar(lv.name)
                }
            }

            is LValue.Arr -> {
                readArray(lv.name, toArrayIndex(evalExpr(lv.index)))
            }

            is LValue.Special -> {
                readVar(lv.which)
            }
        }

    private fun writeLValue(
        lv: LValue,
        value: BcNumber,
    ) {
        when (lv) {
            is LValue.Var -> {
                when (lv.name) {
                    "scale" -> {
                        scale = clampNonNeg(value, "scale")
                    }

                    "ibase" -> {
                        val v = clampNonNeg(value, "ibase")
                        if (v !in 2..16) throw BcRuntimeError("ibase out of range: $v")
                        ibase = v
                    }

                    "obase" -> {
                        val v = clampNonNeg(value, "obase")
                        if (v < 2) throw BcRuntimeError("obase out of range: $v")
                        obase = v
                    }

                    "last" -> {
                        last = value
                    }

                    else -> {
                        writeVar(lv.name, value)
                    }
                }
            }

            is LValue.Arr -> {
                writeArray(lv.name, toArrayIndex(evalExpr(lv.index)), value)
            }

            is LValue.Special -> {
                writeVar(lv.which, value)
            }
        }
    }

    private fun evalCall(
        name: String,
        args: List<Expr>,
    ): BcNumber {
        val def = functions[name] ?: throw BcRuntimeError("undefined function: $name")
        if (def.params.size != args.size) {
            throw BcRuntimeError("function $name: expected ${def.params.size} args, got ${args.size}")
        }
        val scope = Scope()
        // bind params. Scalar = by value; array = by value (POSIX requires
        // call-by-value for arrays).
        for ((p, a) in def.params.zip(args)) {
            if (p.isArray) {
                val argName =
                    (a as? Expr.VarRef)?.name
                        ?: throw BcRuntimeError("function $name: array param '${p.name}' requires array argument")
                val copy = HashMap(readWholeArray(argName))
                scope.arrays[p.name] = copy
            } else {
                scope.vars[p.name] = evalExpr(a)
            }
        }
        for (auto in def.autos) {
            if (auto.isArray) scope.arrays[auto.name] = HashMap() else scope.vars[auto.name] = BcNumber.ZERO
        }
        scopes.addLast(scope)
        try {
            for (s in def.body) execStmt(s)
        } catch (r: ReturnSignal) {
            scopes.removeLast()
            return r.value
        }
        scopes.removeLast()
        return BcNumber.ZERO
    }

    private fun evalBuiltin(
        name: String,
        arg: Expr,
    ): BcNumber =
        when (name) {
            "length" -> BcNumber.fromInt(evalExpr(arg).lengthDigits())
            "scale" -> BcNumber.fromInt(evalExpr(arg).scale)
            "sqrt" -> BcNumber.sqrt(evalExpr(arg), scale)
            else -> throw BcRuntimeError("unknown builtin: $name")
        }

    // --- variable storage ---

    internal fun readVar(name: String): BcNumber {
        val top = scopes.lastOrNull()
        if (top != null && top.vars.containsKey(name)) return top.vars[name]!!
        return globals[name] ?: BcNumber.ZERO
    }

    internal fun writeVar(
        name: String,
        value: BcNumber,
    ) {
        val top = scopes.lastOrNull()
        if (top != null && top.vars.containsKey(name)) {
            top.vars[name] = value
        } else {
            globals[name] = value
        }
    }

    private fun readArray(
        name: String,
        idx: Int,
    ): BcNumber {
        val top = scopes.lastOrNull()
        if (top != null && top.arrays.containsKey(name)) return top.arrays[name]!![idx] ?: BcNumber.ZERO
        return arrays[name]?.get(idx) ?: BcNumber.ZERO
    }

    private fun writeArray(
        name: String,
        idx: Int,
        value: BcNumber,
    ) {
        val top = scopes.lastOrNull()
        if (top != null && top.arrays.containsKey(name)) {
            top.arrays[name]!![idx] = value
            return
        }
        val m = arrays.getOrPut(name) { HashMap() }
        m[idx] = value
    }

    private fun readWholeArray(name: String): Map<Int, BcNumber> {
        val top = scopes.lastOrNull()
        if (top != null && top.arrays.containsKey(name)) return top.arrays[name]!!
        return arrays[name] ?: emptyMap()
    }

    // --- helpers ---

    private fun toBool(v: BcNumber): Boolean = !v.isZero()

    private fun boolOf(b: Boolean) = if (b) BcNumber.ONE else BcNumber.ZERO

    private fun toArrayIndex(v: BcNumber): Int {
        val intV = v.withScale(0)
        if (intV.sign < 0) throw BcRuntimeError("negative array index")
        // accept up to Int.MAX_VALUE
        return try {
            intV.unscaled.toInt()
        } catch (
            _: BcRuntimeError,
        ) {
            throw BcRuntimeError("array index out of range")
        }
    }

    private fun clampNonNeg(
        v: BcNumber,
        what: String,
    ): Int {
        val i = v.withScale(0)
        if (i.sign < 0) throw BcRuntimeError("negative value for $what")
        return try {
            i.unscaled.toInt()
        } catch (_: BcRuntimeError) {
            throw BcRuntimeError("$what too large")
        }
    }

    /**
     * POSIX bc bare-expression printing: respects current obase.
     */
    private fun formatForOutput(v: BcNumber): String = if (obase == 10) v.formatBase10() else v.formatBase(obase, scale)

    private fun processStringEscapes(s: String): String {
        // GNU bc honors a small set of backslash escapes inside print strings:
        // \\, \n, \t, \r, \a, \b, \q. Leave unrecognized escapes alone.
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> {
                        sb.append('\n')
                    }

                    't' -> {
                        sb.append('\t')
                    }

                    'r' -> {
                        sb.append('\r')
                    }

                    '\\' -> {
                        sb.append('\\')
                    }

                    'a' -> {
                        sb.append('\u0007')
                    }

                    'b' -> {
                        sb.append('\b')
                    }

                    '"' -> {
                        sb.append('"')
                    }

                    'q' -> {
                        sb.append('"')
                    }

                    else -> {
                        sb.append(c)
                        sb.append(n)
                    }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun currentScale(): Int = scale

    /** Test-only: read globals after a run. */
    fun globalSnapshot(): Map<String, BcNumber> = globals.toMap()
}
