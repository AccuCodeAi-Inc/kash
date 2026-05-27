package com.accucodeai.kash.interpreter

/**
 * Bash arithmetic evaluator. Integer-only (Long). Operates over a mutable env
 * map so assignments inside an expression (e.g. `i++`, `x=5+y`) write back.
 *
 * Precedence (lowest → highest), all left-associative except `**`, `?:`, assignment:
 *   ,
 *   = += -= *= /= %= &= |= ^= <<= >>=
 *   ? :
 *   ||
 *   &&
 *   |
 *   ^
 *   &
 *   == !=
 *   <  <=  >  >=
 *   << >>
 *   +  -
 *   *  /  %
 *   ** (right-assoc)
 *   unary: + - ! ~
 *   prefix/postfix: ++ --
 *   primary: number, identifier (rvalue=value, lvalue=name), $NAME, ${NAME}, ( expr ),
 *            name[subscript]
 *
 * Array subscripts: when an [arrayStore] is supplied, `a[i]` reads/writes
 * one element. For indexed arrays the subscript is evaluated as another
 * arithmetic expression; for associative arrays it's the raw subscript
 * text. The store decides which by inspecting the variable's type.
 */
internal class ArithEval(
    private val text: String,
    private val env: MutableMap<String, String>,
    private val arrayStore: ArrayStore? = null,
    private val nounset: Boolean = false,
) {
    private var pos = 0

    /**
     * Nonzero while evaluation is suppressed — the parser still consumes
     * tokens (so positions stay in sync), but reads return 0 and writes/
     * division-by-zero checks are no-ops. Used to implement short-circuit
     * evaluation of `?:`, `&&`, `||` so the unused branch's assignments
     * and `i++`/`i--` side effects don't fire. Counter, not flag, so
     * nested skipped branches compose.
     */
    private var noEval = 0

    private inline fun <T> withNoEval(block: () -> T): T {
        noEval++
        try {
            return block()
        } finally {
            noEval--
        }
    }

    /**
     * Side-channel for array reads/writes during arithmetic evaluation.
     * Implemented by the interpreter to bridge into [indexedArrays] and
     * [assocArrays]. Null implementations get reasonable defaults so the
     * env-only call sites (variable expansion of `${var:-default}` etc.)
     * don't need to thread anything new.
     */
    interface ArrayStore {
        /** True if [name] is declared (any flavor: scalar, indexed, assoc). */
        fun hasName(name: String): Boolean

        /** True if [name] currently holds an associative array. */
        fun isAssoc(name: String): Boolean

        /** True if assigning to [name] should fail with `readonly variable`. */
        fun isReadonly(name: String): Boolean = false

        /** Read `name[idx]` as a Long (0 if missing). */
        fun readIndexed(
            name: String,
            idx: Int,
        ): Long

        /** Read `name[key]` as a Long (0 if missing). */
        fun readAssoc(
            name: String,
            key: String,
        ): Long

        /** Write `name[idx] = value`. */
        fun writeIndexed(
            name: String,
            idx: Int,
            value: Long,
        )

        /** Write `name[key] = value`. */
        fun writeAssoc(
            name: String,
            key: String,
            value: Long,
        )
    }

    class Error(
        message: String,
    ) : RuntimeException("arithmetic: $message")

    fun evaluate(): Long {
        val v = parseComma()
        skipWs()
        if (pos < text.length) throw Error("unexpected '${text[pos]}' at position $pos")
        return v
    }

    // ----- grammar -----

    private fun parseComma(): Long {
        var v = parseAssign()
        while (consume(',')) v = parseAssign()
        return v
    }

    // An lvalue captured during a tentative parse — either a plain
    // identifier or a subscripted array element. Used by assignment,
    // pre-/post-increment, and similar operators.
    //
    // Array-element lvalue: bash evaluates `arr[subscript]` exactly once
    // per expression even for compound assignments and ++/-- — the
    // subscript is captured here at parse time. Storing it as a string
    // (for assoc) AND as a pre-evaluated `Int?` (for indexed) avoids the
    // "subscript evaluated twice" bug that surfaces when the subscript
    // has side effects (e.g. `arr[$RANDOM % 6]++` — two `$RANDOM` reads
    // would advance the RNG inconsistently between the read and write
    // halves of the postfix op).
    private class LValue(
        val name: String,
        val rawSubscript: String?,
        /** Cached integer subscript for indexed arrays. Null for scalar names or assoc lookups. */
        var indexedSubscript: Int? = null,
        /** Cached expanded subscript for associative arrays. Lazily filled
         *  on first read/write — the assoc key is the parameter-expanded
         *  form of [rawSubscript], not its literal text. */
        var assocSubscript: String? = null,
        /** True once we've checked [arrayStore]?.isAssoc — caches the verdict. */
        var assocResolved: Boolean = false,
    )

    /**
     * Lightweight $NAME / ${NAME} parameter expansion for assoc
     * subscripts. Mirrors bash's behavior of running parameter expansion
     * on the subscript text before using it as a key — without it
     * (( A[\$k]=2 )) would store the literal text \$k as the key
     * instead of the runtime value of k. Indexed-array subscripts go
     * through full arithmetic evaluation and don't need this.
     */
    private fun expandAssocSub(raw: String): String {
        if ('$' !in raw) return raw
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '$') {
                sb.append(c)
                i++
                continue
            }
            if (i + 1 < raw.length && raw[i + 1] == '{') {
                val end = raw.indexOf('}', i + 2)
                if (end < 0) {
                    sb.append(c)
                    i++
                    continue
                }
                val name = raw.substring(i + 2, end)
                sb.append(env[name] ?: "")
                i = end + 1
                continue
            }
            var j = i + 1
            while (j < raw.length && (raw[j] == '_' || raw[j].isLetterOrDigit())) j++
            if (j == i + 1) {
                sb.append(c)
                i++
                continue
            }
            val name = raw.substring(i + 1, j)
            sb.append(env[name] ?: "")
            i = j
        }
        return sb.toString()
    }

    /**
     * Resolve [lv]'s subscript to its concrete form ONCE per
     * postfix/prefix ++/--/compound-assignment use. After this call
     * [indexedSubscript] is set for indexed arrays; [rawSubscript]
     * carries the string key for assoc arrays.
     */
    private fun resolveSubscript(lv: LValue) {
        val sub = lv.rawSubscript ?: return
        val store = arrayStore ?: return
        if (lv.assocResolved) return
        lv.assocResolved = true
        if (store.isAssoc(lv.name)) {
            // Bash expands parameter references in the assoc-subscript
            // text once, before using the result as the key.
            lv.assocSubscript = expandAssocSub(sub)
        } else {
            // Bash: `a[ ]=v`, `a[""]=v`, `a[$unset]=v` all resolve to
            // index 0 in arith context. ArithEval on empty/whitespace
            // would throw "expected expression"; bypass that for the
            // empty-after-trim case.
            // For indexed arrays we arith-evaluate the subscript text;
            // quotes that the outer evalArithRaw preserved inside
            // `[...]` (for the assoc carve-out) need to be stripped now.
            val noQuotes = sub.filter { it != '"' && it != '\'' }
            lv.indexedSubscript =
                if (noQuotes.isBlank()) {
                    0
                } else {
                    ArithEval(noQuotes, env, arrayStore).evaluate().toInt()
                }
        }
    }

    /** Read the current value of an lvalue (0 if unset, or 0 when eval is suppressed). */
    private fun readLValue(lv: LValue): Long {
        if (noEval > 0) return 0L
        val sub = lv.rawSubscript ?: return lookup(lv.name)
        val store = arrayStore ?: return lookup(lv.name)
        resolveSubscript(lv)
        return if (store.isAssoc(lv.name)) {
            store.readAssoc(lv.name, lv.assocSubscript ?: sub)
        } else {
            store.readIndexed(lv.name, lv.indexedSubscript!!)
        }
    }

    /** Write a value back to an lvalue. No-op when eval is suppressed (skipped branch). */
    private fun writeLValue(
        lv: LValue,
        v: Long,
    ) {
        if (noEval > 0) return
        if (arrayStore?.isReadonly(lv.name) == true) {
            throw Error("${lv.name}: readonly variable")
        }
        val sub = lv.rawSubscript
        if (sub == null) {
            env[lv.name] = v.toString()
            return
        }
        val store =
            arrayStore ?: run {
                env[lv.name] = v.toString()
                return
            }
        resolveSubscript(lv)
        if (store.isAssoc(lv.name)) {
            store.writeAssoc(lv.name, lv.assocSubscript ?: sub, v)
        } else {
            store.writeIndexed(lv.name, lv.indexedSubscript!!, v)
        }
    }

    /**
     * Try to read `name` or `name[subscript]` at the current position as an
     * lvalue. Returns the parsed reference and the byte position to commit
     * to if the caller decides to consume it; returns null if the position
     * isn't an identifier (or, when no `arrayStore` is wired, has no
     * subscript).
     */
    private fun tryReadLValue(): LValue? {
        val saved = pos
        skipWs()
        val name =
            readIdentifier() ?: run {
                pos = saved
                return null
            }
        val afterName = pos
        skipWs()
        if (pos < text.length && text[pos] == '[') {
            val sub = readBracketedSubscript()
            return LValue(name, sub)
        }
        pos = afterName
        return LValue(name, null)
    }

    /**
     * Read a `[...]` subscript starting at `text[pos] == '['`. Returns the
     * raw text between the brackets (with nested `[]` balanced so things
     * like `a[b[0]]` work). Advances past the closing `]`.
     */
    private fun readBracketedSubscript(): String {
        if (pos >= text.length || text[pos] != '[') throw Error("expected '['")
        pos++
        val start = pos
        var depth = 1
        while (pos < text.length && depth > 0) {
            when (text[pos]) {
                '[' -> {
                    depth++
                }

                ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            pos++
        }
        if (pos >= text.length || text[pos] != ']') throw Error("missing ']'")
        val raw = text.substring(start, pos)
        pos++
        return raw
    }

    /** Assignment is right-associative. Returns the value assigned (or the rhs value). */
    private fun parseAssign(): Long {
        val saved = pos
        val lv = tryReadLValue()
        if (lv != null) {
            skipWs()
            val op = tryReadAssignOp()
            if (op != null) {
                val rhs = parseAssign()
                val current = readLValue(lv)
                val newVal =
                    when (op) {
                        "=" -> {
                            rhs
                        }

                        "+=" -> {
                            current + rhs
                        }

                        "-=" -> {
                            current - rhs
                        }

                        "*=" -> {
                            current * rhs
                        }

                        "/=" -> {
                            if (rhs == 0L) {
                                if (noEval > 0) 0L else throw Error("division by 0")
                            } else {
                                current / rhs
                            }
                        }

                        "%=" -> {
                            if (rhs == 0L) {
                                if (noEval > 0) 0L else throw Error("division by 0")
                            } else {
                                current % rhs
                            }
                        }

                        "&=" -> {
                            current and rhs
                        }

                        "|=" -> {
                            current or rhs
                        }

                        "^=" -> {
                            current xor rhs
                        }

                        "<<=" -> {
                            current shl rhs.toInt()
                        }

                        ">>=" -> {
                            current shr rhs.toInt()
                        }

                        else -> {
                            error("unreachable")
                        }
                    }
                writeLValue(lv, newVal)
                return newVal
            }
            pos = saved
        }
        val v = parseTernary()
        // Bash precedence: an assignment operator after a non-lvalue
        // expression (a ternary result, a parenthesized form, etc.) is
        // illegal — `1 ? 20 : x+=2` errors here because the ternary's
        // false branch left `+=2` unconsumed. Inside a suppressed branch
        // the same check applies but only as a parse failure, so the
        // diagnostic doesn't fire spuriously on dead code.
        skipWs()
        if (looksLikeAssignOpHere() && noEval == 0) {
            throw Error("attempted assignment to non-variable")
        }
        return v
    }

    /** True if the position is at the start of an assignment operator. */
    private fun looksLikeAssignOpHere(): Boolean {
        if (pos >= text.length) return false
        for (op in listOf("<<=", ">>=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=")) {
            if (peek2(op)) return true
        }
        return text[pos] == '=' && !peek2("==")
    }

    private fun parseTernary(): Long {
        val c = parseLogicalOr()
        skipWs()
        if (consume('?')) {
            // Short-circuit: only the taken branch is evaluated for side
            // effects. The untaken branch is still PARSED (so the position
            // advances correctly), but reads return 0 and writes/div-by-0
            // checks are suppressed via [noEval].
            //
            // Branch precedence per bash: the true branch is full-expression
            // (assignment allowed — `1 ? a=5 : 10` works); the false branch
            // is conditional-or-lower (no assignment — `1 ? 20 : x+=2` is an
            // error because `x+=2` falls off and the outer parseAssign sees
            // a stray `+=` against the non-lvalue ternary result).
            val cTaken = c != 0L
            val t = if (cTaken) parseAssign() else withNoEval { parseAssign() }
            skipWs()
            if (!consume(':')) throw Error("expected ':' in ternary")
            val f = if (cTaken) withNoEval { parseTernary() } else parseTernary()
            return if (cTaken) t else f
        }
        return c
    }

    private fun parseLogicalOr(): Long {
        var v = parseLogicalAnd()
        while (true) {
            skipWs()
            if (peek2("||")) {
                pos += 2
                // Short-circuit: if LHS already truthy, RHS is parsed
                // but its side effects are suppressed.
                val r =
                    if (v != 0L) {
                        withNoEval { parseLogicalAnd() }
                    } else {
                        parseLogicalAnd()
                    }
                v = boolOf(v != 0L || r != 0L)
            } else {
                break
            }
        }
        return v
    }

    private fun parseLogicalAnd(): Long {
        var v = parseBitOr()
        while (true) {
            skipWs()
            if (peek2("&&")) {
                pos += 2
                // Short-circuit: if LHS already false, RHS is parsed but
                // its side effects are suppressed.
                val r =
                    if (v == 0L) {
                        withNoEval { parseBitOr() }
                    } else {
                        parseBitOr()
                    }
                v = boolOf(v != 0L && r != 0L)
            } else {
                break
            }
        }
        return v
    }

    private fun parseBitOr(): Long {
        var v = parseBitXor()
        while (true) {
            skipWs()
            // `|` but not `||`.
            if (pos < text.length && text[pos] == '|' && !peek2("||")) {
                pos++
                v = v or parseBitXor()
            } else {
                break
            }
        }
        return v
    }

    private fun parseBitXor(): Long {
        var v = parseBitAnd()
        while (true) {
            skipWs()
            if (pos < text.length && text[pos] == '^') {
                pos++
                v = v xor parseBitAnd()
            } else {
                break
            }
        }
        return v
    }

    private fun parseBitAnd(): Long {
        var v = parseEquality()
        while (true) {
            skipWs()
            if (pos < text.length && text[pos] == '&' && !peek2("&&")) {
                pos++
                v = v and parseEquality()
            } else {
                break
            }
        }
        return v
    }

    private fun parseEquality(): Long {
        var v = parseRelational()
        while (true) {
            skipWs()
            when {
                peek2("==") -> {
                    pos += 2
                    v = boolOf(v == parseRelational())
                }

                peek2("!=") -> {
                    pos += 2
                    v = boolOf(v != parseRelational())
                }

                else -> {
                    return v
                }
            }
        }
    }

    private fun parseRelational(): Long {
        var v = parseShift()
        while (true) {
            skipWs()
            when {
                peek2("<=") -> {
                    pos += 2
                    v = boolOf(v <= parseShift())
                }

                peek2(">=") -> {
                    pos += 2
                    v = boolOf(v >= parseShift())
                }

                pos < text.length && text[pos] == '<' && !peek2("<<") -> {
                    pos++
                    v = boolOf(v < parseShift())
                }

                pos < text.length && text[pos] == '>' && !peek2(">>") -> {
                    pos++
                    v = boolOf(v > parseShift())
                }

                else -> {
                    return v
                }
            }
        }
    }

    private fun parseShift(): Long {
        var v = parseAdditive()
        while (true) {
            skipWs()
            when {
                peek2("<<") -> {
                    pos += 2
                    v = v shl parseAdditive().toInt()
                }

                peek2(">>") -> {
                    pos += 2
                    v = v shr parseAdditive().toInt()
                }

                else -> {
                    return v
                }
            }
        }
    }

    private fun parseAdditive(): Long {
        var v = parseMultiplicative()
        while (true) {
            skipWs()
            when {
                // Consume `+`/`-` even when immediately followed by another
                // `+`/`-`. Bash treats `4+++a` as `4 + ++a` (binary `+` then
                // pre-increment) — the disambiguation lives in `parseUnary`'s
                // `++/--` lookahead, NOT here. Earlier kash skipped the
                // operator when the next char was the same sign, leaving the
                // `++` for the outer parser to choke on.
                pos < text.length && text[pos] == '+' -> {
                    pos++
                    v += parseMultiplicative()
                }

                pos < text.length && text[pos] == '-' -> {
                    pos++
                    v -= parseMultiplicative()
                }

                else -> {
                    return v
                }
            }
        }
    }

    private fun parseMultiplicative(): Long {
        var v = parseExponent()
        while (true) {
            skipWs()
            if (pos >= text.length) return v
            when (text[pos]) {
                '*' -> {
                    if (peek2("**")) return v
                    pos++
                    v *= parseExponent()
                }

                '/' -> {
                    pos++
                    val r = parseExponent()
                    if (r == 0L) {
                        if (noEval == 0) throw Error("division by 0")
                        v = 0L
                    } else {
                        v /= r
                    }
                }

                '%' -> {
                    pos++
                    val r = parseExponent()
                    if (r == 0L) {
                        if (noEval == 0) throw Error("division by 0")
                        v = 0L
                    } else {
                        v %= r
                    }
                }

                else -> {
                    return v
                }
            }
        }
    }

    /** `**` is right-associative. */
    private fun parseExponent(): Long {
        val base = parseUnary()
        skipWs()
        if (peek2("**")) {
            pos += 2
            val exp = parseExponent()
            return pow(base, exp)
        }
        return base
    }

    private fun pow(
        b: Long,
        e: Long,
    ): Long {
        if (e < 0) {
            // Bash diagnostic: `2**-1 : exponent less than 0`. Inside a
            // suppressed (short-circuited) branch we swallow it to 0 so
            // dead code can't trip a fatal error.
            if (noEval > 0) return 0
            throw Error("exponent less than 0")
        }
        var r = 1L
        var x = b
        var n = e
        while (n > 0) {
            if ((n and 1) == 1L) r *= x
            x *= x
            n = n shr 1
        }
        return r
    }

    private fun parseUnary(): Long {
        skipWs()
        if (pos >= text.length) throw Error("unexpected end of expression")
        return when (text[pos]) {
            '+' -> {
                if (peek2("++") && lookaheadStartsLValue(pos + 2)) {
                    pos += 2
                    preIncDec(+1)
                } else {
                    pos++
                    parseUnary()
                }
            }

            '-' -> {
                if (peek2("--") && lookaheadStartsLValue(pos + 2)) {
                    pos += 2
                    preIncDec(-1)
                } else {
                    pos++
                    -parseUnary()
                }
            }

            '!' -> {
                pos++
                boolOf(parseUnary() == 0L)
            }

            '~' -> {
                pos++
                parseUnary().inv()
            }

            else -> {
                parsePrimary()
            }
        }
    }

    private fun preIncDec(delta: Int): Long {
        val lv = tryReadLValue() ?: throw Error("++/-- requires a variable")
        val v = readLValue(lv) + delta
        writeLValue(lv, v)
        return v
    }

    /**
     * Peek ahead from [from], skipping whitespace, and report whether the
     * next non-blank char begins an identifier (the only thing pre-`++`/`--`
     * can attach to). Lets `parseUnary` distinguish:
     *   - `++x`  — real pre-increment, consume `++` and call [preIncDec]
     *   - `+++7` — three unary `+` ops on 7 (bash backtracks to `+(+(+7))`)
     *   - `(( ++ ))` — no lvalue at all; we backtrack to a single unary `+`
     *     which then errors with "unexpected ')'", normalized away.
     */
    private fun lookaheadStartsLValue(from: Int): Boolean {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n')) i++
        if (i >= text.length) return false
        val c = text[i]
        return c == '_' || c.isLetter()
    }

    private fun parsePrimary(): Long {
        skipWs()
        if (pos >= text.length) throw Error("unexpected end of expression")
        val c = text[pos]
        if (c == '(') {
            pos++
            val v = parseComma()
            skipWs()
            if (!consume(')')) throw Error("missing ')'")
            return v
        }
        if (c == '$') {
            pos++
            if (pos < text.length && text[pos] == '{') {
                pos++
                val start = pos
                while (pos < text.length && text[pos] != '}') pos++
                val name = text.substring(start, pos)
                if (pos < text.length) pos++
                return lookup(name)
            }
            val name = readIdentifier() ?: throw Error("expected name after '$'")
            return lookup(name)
        }
        if (c.isDigit()) return readNumber()
        if (c == '_' || c.isLetter()) {
            val name = readIdentifier()!!
            // Bash allows `name[subscript]` in arithmetic as both rvalue and
            // l-value reference. Capture the subscript here so postfix ++/--
            // can mutate the element through the array store.
            skipWs()
            val lv =
                if (pos < text.length && text[pos] == '[') {
                    LValue(name, readBracketedSubscript())
                } else {
                    LValue(name, null)
                }
            skipWs()
            if (peek2("++")) {
                pos += 2
                val v = readLValue(lv)
                writeLValue(lv, v + 1)
                return v
            }
            if (peek2("--")) {
                pos += 2
                val v = readLValue(lv)
                writeLValue(lv, v - 1)
                return v
            }
            return readLValue(lv)
        }
        throw Error("unexpected '$c' at position $pos")
    }

    private fun readNumber(): Long {
        val start = pos
        // 0x..., 0..., NN#... bases.
        if (text[pos] == '0' && pos + 1 < text.length && (text[pos + 1] == 'x' || text[pos + 1] == 'X')) {
            pos += 2
            val numStart = pos
            while (pos < text.length && isHexDigit(text[pos])) pos++
            // Bash treats bare `0x` (no digits) as 0; only non-hex trailing
            // chars cause "value too great for base". Without this guard
            // `"".toLong(16)` throws NumberFormatException uncaught.
            if (numStart == pos) return 0L
            return text.substring(numStart, pos).toLong(16)
        }
        while (pos < text.length && text[pos].isDigit()) pos++
        // base# form: NN#...
        if (pos < text.length && text[pos] == '#') {
            val base = text.substring(start, pos).toInt()
            if (base !in 2..64) throw Error("invalid arithmetic base $base")
            pos++
            val numStart = pos
            while (pos < text.length && isBaseChar(text[pos], base)) pos++
            return parseBase(text.substring(numStart, pos), base)
        }
        val raw = text.substring(start, pos)
        // Leading 0 → octal (bash semantics). Use a ULong-fallback for
        // wrap-on-overflow so a 22-digit octal literal doesn't crash the
        // evaluator with NumberFormatException — matches the decimal
        // branch's existing wrap rule below.
        if (raw.length > 1 && raw.startsWith("0")) {
            return raw.toLongOrNull(8) ?: raw.toULongOrNull(8)?.toLong() ?: 0L
        }
        // Two's-complement wrap on overflow — bash signed-int semantics.
        // `2**63` parses cleanly as 9223372036854775808 conceptually, then
        // wraps to Long.MIN_VALUE via the underlying ULong reinterpret.
        // Without this, `-$intmax_min1` (which expands to `--<min>`) walks
        // into readNumber with a 19-digit literal and crashes.
        return raw.toLongOrNull() ?: raw.toULongOrNull()?.toLong() ?: 0L
    }

    private fun isHexDigit(c: Char) = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

    /**
     * Bash digit ordering for `base#NN` (max base = 64):
     *   0..9 → 0..9, a..z → 10..35, A..Z → 36..61, @ → 62, _ → 63.
     * For bases ≤ 36 bash treats lowercase and uppercase as equivalent (10..35).
     */
    private fun baseDigit(
        c: Char,
        base: Int,
    ): Int {
        val v =
            when {
                c.isDigit() -> c - '0'
                c in 'a'..'z' -> c - 'a' + 10
                c in 'A'..'Z' -> if (base > 36) c - 'A' + 36 else c - 'A' + 10
                c == '@' -> 62
                c == '_' -> 63
                else -> -1
            }
        return if (v in 0 until base) v else -1
    }

    private fun isBaseChar(
        c: Char,
        base: Int,
    ): Boolean = baseDigit(c, base) >= 0

    private fun parseBase(
        digits: String,
        base: Int,
    ): Long {
        var result = 0L
        for (c in digits) {
            val d = baseDigit(c, base)
            if (d < 0) throw Error("invalid digit '$c' in base $base")
            result = result * base + d
        }
        return result
    }

    private fun lookup(name: String): Long {
        val raw = env[name]
        if (raw == null) {
            // `set -u`: referencing an unset name in arithmetic is fatal,
            // EXCEPT when it's actually an array variable (bash treats
            // `a` as `a[0]` and only complains if neither indexed nor
            // associative array exists).
            if (nounset && noEval == 0) {
                val isArray = arrayStore?.hasName(name) == true
                if (!isArray) throw Error("$name: unbound variable")
            }
            return 0L
        }
        if (raw.isEmpty()) return 0L
        // bash treats a whitespace-only variable value the same as empty
        // in arithmetic context: `v=" "; $((v))` → 0. Without this guard
        // the recursive ArithEval below would parse " " as a syntax
        // error and propagate it as `unexpected end of expression` even
        // though the user expected the blank-string→0 collapse.
        if (raw.isBlank()) return 0L
        // Security: a variable value containing literal `$(...)` MUST
        // NOT trigger another expansion pass. Bash's array_expand_once
        // baseline says the subscript text we got is final — running
        // the cmdsub during recursive arith lookup would execute shell
        // code the user supplied as data. Throw an arith error so the
        // surrounding evaluator surfaces it (and the array-normalize
        // rule folds it to <ARITH_ERR>).
        if ("\$(" in raw || '`' in raw) {
            throw Error("$raw: arithmetic syntax error: operand expected (error token is \"$raw\")")
        }
        // Recursive bash semantics: a var's value can itself be an arithmetic
        // expression — `A="3+5"; echo $((A))` → 8. Bash also propagates an
        // error from the recursive eval up to the outer expression: if `A="4 + "`
        // (incomplete), `$(( ( 4 + A ) + 4 ))` is an error, NOT silent-0 + 4 = 8.
        // Re-throw `ArithEval.Error` so the outer evaluator's catch fires.
        // NumberFormatException and friends still swallow to 0 — those mean
        // the var holds a non-numeric, non-expression string, which is well-
        // defined as 0 per POSIX §2.6.4.
        return raw.toLongOrNull() ?: try {
            ArithEval(raw, env, arrayStore).evaluate()
        } catch (e: Error) {
            throw e
        } catch (_: Throwable) {
            0L
        }
    }

    // ----- token helpers -----

    private fun skipWs() {
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n')) pos++
    }

    private fun consume(c: Char): Boolean {
        skipWs()
        if (pos < text.length && text[pos] == c) {
            pos++
            return true
        }
        return false
    }

    private fun peek2(s: String): Boolean = pos + s.length <= text.length && text.regionMatches(pos, s, 0, s.length)

    private fun readIdentifier(): String? {
        if (pos >= text.length) return null
        val first = text[pos]
        if (!(first == '_' || first.isLetter())) return null
        val start = pos
        while (pos < text.length && (text[pos] == '_' || text[pos].isLetterOrDigit())) pos++
        return text.substring(start, pos)
    }

    /**
     * Try to read an assignment operator at the current position. Returns null
     * if what follows is `==` (equality), so the parseAssign caller can fall
     * through to ternary.
     */
    private fun tryReadAssignOp(): String? {
        skipWs()
        // Multi-char first.
        for (op in listOf("<<=", ">>=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=")) {
            if (peek2(op)) {
                pos += op.length
                return op
            }
        }
        // Plain `=` but NOT `==`.
        if (pos < text.length && text[pos] == '=' && !peek2("==")) {
            pos++
            return "="
        }
        return null
    }

    private fun boolOf(b: Boolean): Long = if (b) 1L else 0L
}
