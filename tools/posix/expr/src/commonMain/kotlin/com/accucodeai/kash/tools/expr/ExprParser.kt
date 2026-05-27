package com.accucodeai.kash.tools.expr

/**
 * POSIX `expr`: tokenized expression evaluator.
 *
 * The argv stream IS the expression — each token is its own arg. Grammar:
 *
 * ```
 * expr  ::= or
 * or    ::= and ('|' and)*
 * and   ::= cmp ('&' cmp)*
 * cmp   ::= sum (('<'|'<='|'='|'!='|'>='|'>') sum)*
 * sum   ::= prod (('+'|'-') prod)*
 * prod  ::= match (('*'|'/'|'%') match)*
 * match ::= primary (':' primary)*
 * primary ::= NUMBER | STRING | '(' expr ')'
 *           | 'length' primary
 *           | 'substr' primary primary primary
 *           | 'index' primary primary
 *           | 'match' primary primary
 *           | '+' TOKEN    (string-quote next token)
 * ```
 *
 * Values are Long or String; output is always stringified. A value is
 * "null/zero" iff it is the empty string or the literal "0".
 */

internal sealed class Value {
    abstract fun asString(): String

    abstract fun asLongOrNull(): Long?

    fun isTruthy(): Boolean {
        val s = asString()
        return s.isNotEmpty() && s != "0"
    }
}

internal data class StrVal(
    val s: String,
) : Value() {
    override fun asString(): String = s

    override fun asLongOrNull(): Long? = if (LOOKS_LIKE_NUMBER.matches(s)) s.toLongOrNull() else null
}

internal data class IntVal(
    val n: Long,
) : Value() {
    override fun asString(): String = n.toString()

    override fun asLongOrNull(): Long = n
}

private val LOOKS_LIKE_NUMBER = Regex("^-?[0-9]+$")

internal class ExprError(
    message: String,
    val exit: Int = 2,
) : RuntimeException(message)

internal class ExprParser(
    private val tokens: List<String>,
) {
    private var i = 0

    fun parse(): Value {
        if (tokens.isEmpty()) throw ExprError("syntax error: empty expression")
        val v = parseOr()
        if (i < tokens.size) throw ExprError("syntax error: unexpected token '${tokens[i]}'")
        return v
    }

    private fun peek(): String? = if (i < tokens.size) tokens[i] else null

    private fun consume(): String = tokens[i++]

    // ----- precedence layers (low → high) -----

    private fun parseOr(): Value {
        var left = parseAnd()
        while (peek() == "|") {
            consume()
            val right = parseAnd()
            left =
                if (left.isTruthy()) {
                    left
                } else if (right.isTruthy()) {
                    right
                } else {
                    IntVal(0)
                }
        }
        return left
    }

    private fun parseAnd(): Value {
        var left = parseCmp()
        while (peek() == "&") {
            consume()
            val right = parseCmp()
            left = if (left.isTruthy() && right.isTruthy()) left else IntVal(0)
        }
        return left
    }

    private fun parseCmp(): Value {
        var left = parseSum()
        while (peek() in CMP_OPS) {
            val op = consume()
            val right = parseSum()
            val result = compare(left, right, op)
            left = IntVal(if (result) 1L else 0L)
        }
        return left
    }

    private fun parseSum(): Value {
        var left = parseProd()
        while (peek() == "+" || peek() == "-") {
            val op = consume()
            val right = parseProd()
            val a = requireInt(left, op)
            val b = requireInt(right, op)
            left =
                IntVal(
                    when (op) {
                        "+" -> safeAdd(a, b)
                        else -> safeSub(a, b)
                    },
                )
        }
        return left
    }

    private fun parseProd(): Value {
        var left = parseMatch()
        while (peek() == "*" || peek() == "/" || peek() == "%") {
            val op = consume()
            val right = parseMatch()
            val a = requireInt(left, op)
            val b = requireInt(right, op)
            left =
                IntVal(
                    when (op) {
                        "*" -> {
                            safeMul(a, b)
                        }

                        "/" -> {
                            if (b == 0L) throw ExprError("division by zero")
                            a / b
                        }

                        else -> {
                            if (b == 0L) throw ExprError("division by zero")
                            a % b
                        }
                    },
                )
        }
        return left
    }

    private fun parseMatch(): Value {
        var left = parsePrimary()
        while (peek() == ":") {
            consume()
            val pattern = parsePrimary()
            left = doMatch(left.asString(), pattern.asString())
        }
        return left
    }

    private fun parsePrimary(): Value {
        val tok = peek() ?: throw ExprError("syntax error: unexpected end")
        return when (tok) {
            "(" -> {
                consume()
                val v = parseOr()
                if (peek() != ")") throw ExprError("syntax error: missing ')'")
                consume()
                v
            }

            "+" -> {
                // Force the next token to be treated as a string operand.
                consume()
                val next = peek() ?: throw ExprError("syntax error: '+' missing operand")
                consume()
                StrVal(next)
            }

            "length" -> {
                consume()
                val s = parsePrimary().asString()
                IntVal(s.codePointLength().toLong())
            }

            "substr" -> {
                consume()
                val s = parsePrimary().asString()
                val pos = requireInt(parsePrimary(), "substr")
                val len = requireInt(parsePrimary(), "substr")
                StrVal(substrCodepoints(s, pos, len))
            }

            "index" -> {
                consume()
                val s = parsePrimary().asString()
                val chars = parsePrimary().asString()
                IntVal(indexCodepoints(s, chars).toLong())
            }

            "match" -> {
                consume()
                val s = parsePrimary().asString()
                val pat = parsePrimary().asString()
                doMatch(s, pat)
            }

            else -> {
                consume()
                if (LOOKS_LIKE_NUMBER.matches(tok)) IntVal(tok.toLong()) else StrVal(tok)
            }
        }
    }

    private fun compare(
        a: Value,
        b: Value,
        op: String,
    ): Boolean {
        val ai = a.asLongOrNull()
        val bi = b.asLongOrNull()
        return if (ai != null && bi != null) {
            when (op) {
                "<" -> ai < bi
                "<=" -> ai <= bi
                "=", "==" -> ai == bi
                "!=" -> ai != bi
                ">=" -> ai >= bi
                ">" -> ai > bi
                else -> error("unreachable")
            }
        } else {
            val sa = a.asString()
            val sb = b.asString()
            when (op) {
                "<" -> sa < sb
                "<=" -> sa <= sb
                "=", "==" -> sa == sb
                "!=" -> sa != sb
                ">=" -> sa >= sb
                ">" -> sa > sb
                else -> error("unreachable")
            }
        }
    }

    private fun requireInt(
        v: Value,
        op: String,
    ): Long = v.asLongOrNull() ?: throw ExprError("non-integer argument to '$op'")

    private fun safeAdd(
        a: Long,
        b: Long,
    ): Long {
        val r = a + b
        if (((a xor r) and (b xor r)) < 0) throw ExprError("integer overflow")
        return r
    }

    private fun safeSub(
        a: Long,
        b: Long,
    ): Long {
        val r = a - b
        if (((a xor b) and (a xor r)) < 0) throw ExprError("integer overflow")
        return r
    }

    private fun safeMul(
        a: Long,
        b: Long,
    ): Long {
        if (a == 0L || b == 0L) return 0L
        val r = a * b
        if (r / b != a) throw ExprError("integer overflow")
        return r
    }

    companion object {
        private val CMP_OPS = setOf("<", "<=", "=", "==", "!=", ">=", ">")
    }
}

// ---- regex match impl ----

private fun doMatch(
    s: String,
    bre: String,
): Value {
    val (re, hasCapture) = compileBre(bre)
    val m = re.find(s)
    return if (hasCapture) {
        if (m == null) {
            StrVal("")
        } else {
            // First capture group's contents.
            StrVal(m.groupValues.getOrNull(1) ?: "")
        }
    } else {
        if (m == null) {
            IntVal(0L)
        } else {
            IntVal(m.value.codePointLength().toLong())
        }
    }
}

/**
 * Translate a POSIX BRE (the subset `expr` uses) into a Kotlin Regex,
 * anchored at start. Handles: `\(...\)` → groups, `\|` is literal in BRE so
 * `|` is literal too — must be escaped. `*` repeats. `.` matches any char.
 * `[...]` carries over (negation `[^...]` too). `^` only meaningful at
 * start; `$` only at end. `\d` etc. are NOT POSIX BRE — we don't translate.
 */
private fun compileBre(bre: String): Pair<Regex, Boolean> {
    val sb = StringBuilder()
    sb.append("^") // POSIX: anchored at start of string
    var hasCapture = false
    var i = 0
    while (i < bre.length) {
        val c = bre[i]
        when {
            c == '\\' && i + 1 < bre.length -> {
                val n = bre[i + 1]
                when (n) {
                    '(' -> {
                        sb.append('(')
                        hasCapture = true
                    }

                    ')' -> {
                        sb.append(')')
                    }

                    '|' -> {
                        sb.append("\\|")
                    }

                    '.' -> {
                        sb.append("\\.")
                    }

                    '*' -> {
                        sb.append("\\*")
                    }

                    '+' -> {
                        sb.append("\\+")
                    }

                    '?' -> {
                        sb.append("\\?")
                    }

                    '\\' -> {
                        sb.append("\\\\")
                    }

                    '{' -> {
                        sb.append('{')
                    }

                    '}' -> {
                        sb.append('}')
                    }

                    in '1'..'9' -> {
                        sb.append("\\").append(n)
                    }

                    // backreference
                    'n' -> {
                        sb.append("\n")
                    }

                    't' -> {
                        sb.append("\t")
                    }

                    else -> {
                        // Unknown \X: pass through as literal char.
                        sb.append(Regex.escape(n.toString()))
                    }
                }
                i += 2
            }

            // In BRE, these are literal unless escaped.
            c == '(' || c == ')' || c == '{' || c == '}' || c == '|' || c == '+' || c == '?' -> {
                sb.append('\\').append(c)
                i++
            }

            c == '[' -> {
                // Copy a bracket expression verbatim until matching ']'.
                val end = findBracketEnd(bre, i)
                sb.append(bre, i, end + 1)
                i = end + 1
            }

            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return Regex(sb.toString()) to hasCapture
}

private fun findBracketEnd(
    s: String,
    start: Int,
): Int {
    var i = start + 1
    if (i < s.length && s[i] == '^') i++
    if (i < s.length && s[i] == ']') i++ // leading ']' is literal
    while (i < s.length && s[i] != ']') i++
    return if (i < s.length) i else s.length - 1
}

// ---- codepoint-aware helpers ----

internal fun String.codePointLength(): Int {
    var n = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        i += if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        n++
    }
    return n
}

internal fun substrCodepoints(
    s: String,
    pos: Long,
    len: Long,
): String {
    if (pos < 1 || len < 1) return ""
    val cps = mutableListOf<String>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            cps.add(s.substring(i, i + 2))
            i += 2
        } else {
            cps.add(c.toString())
            i++
        }
    }
    val start = (pos - 1).toInt()
    if (start >= cps.size) return ""
    val end = minOf(cps.size.toLong(), (pos - 1) + len).toInt()
    return cps.subList(start, end).joinToString("")
}

internal fun indexCodepoints(
    s: String,
    chars: String,
): Int {
    if (chars.isEmpty() || s.isEmpty()) return 0
    val charSet = HashSet<String>()
    var i = 0
    while (i < chars.length) {
        val c = chars[i]
        if (c.isHighSurrogate() && i + 1 < chars.length && chars[i + 1].isLowSurrogate()) {
            charSet.add(chars.substring(i, i + 2))
            i += 2
        } else {
            charSet.add(c.toString())
            i++
        }
    }
    var idx = 0
    var pos = 0
    while (pos < s.length) {
        idx++
        val c = s[pos]
        val unit =
            if (c.isHighSurrogate() && pos + 1 < s.length && s[pos + 1].isLowSurrogate()) {
                s.substring(pos, pos + 2).also { pos += 2 }
            } else {
                c.toString().also { pos += 1 }
            }
        if (unit in charSet) return idx
    }
    return 0
}
