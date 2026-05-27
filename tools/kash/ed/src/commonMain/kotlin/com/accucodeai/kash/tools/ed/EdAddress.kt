package com.accucodeai.kash.tools.ed

/**
 * One parsed ed address — a *primary* (anchor) plus a list of `+N`/`-N`
 * offsets applied left-to-right.
 *
 * Primaries cover the POSIX set: explicit line number, `.`, `$`, mark
 * (`'a`..`'z`), forward (`/re/`) / backward (`?re?`) regex search. An
 * "empty" primary (sourced from a bare `+`/`-` form like `+5`) means
 * "current line"; a fully empty address means "no address supplied" and
 * the command decides the default.
 */
public data class EdAddress(
    val primary: Primary,
    val offsets: List<Int> = emptyList(),
) {
    public sealed interface Primary {
        public data class Number(
            val n: Int,
        ) : Primary

        public data object Dot : Primary

        public data object Last : Primary

        public data class Mark(
            val ch: Char,
        ) : Primary

        public data class SearchForward(
            val re: String?,
        ) : Primary // null = reuse lastSearch

        public data class SearchBackward(
            val re: String?,
        ) : Primary

        /** No anchor written — used when only offsets (or nothing) appeared. */
        public data object Empty : Primary
    }

    public companion object {
        /** Address meaning "current line" with no modifications. */
        public val DOT: EdAddress = EdAddress(Primary.Dot)
        public val LAST: EdAddress = EdAddress(Primary.Last)
    }
}

/**
 * Parsed pair of addresses with an indicator of which separator was used.
 * `;` sets `.` to the first resolved address before resolving the second
 * (POSIX-required side-effect).
 */
public data class EdRange(
    val first: EdAddress?,
    val second: EdAddress?,
    val semicolon: Boolean = false,
)

/**
 * Cursor-style parser. Mutates [pos] in place — callers consult [pos] after
 * each parse to learn how far into [input] the recognizer advanced.
 */
public class EdLineCursor(
    public val input: String,
    public var pos: Int = 0,
) {
    public fun peek(): Char? = if (pos < input.length) input[pos] else null

    public fun next(): Char = input[pos++]

    public fun eof(): Boolean = pos >= input.length

    public fun match(ch: Char): Boolean {
        if (peek() == ch) {
            pos++
            return true
        }
        return false
    }
}

public object EdAddressParser {
    /**
     * Parse 0, 1, or 2 addresses from [c]. Returns the pair plus a "saw
     * anything" flag the caller uses to decide whether to apply each
     * command's default.
     */
    public fun parseRange(c: EdLineCursor): EdRange {
        skipSpaces(c)
        // Special forms.
        when (c.peek()) {
            '%', ',' -> {
                c.next()
                val second = tryParseAddress(c)
                return EdRange(EdAddress(EdAddress.Primary.Number(1)), second ?: EdAddress.LAST, false)
            }

            ';' -> {
                c.next()
                val second = tryParseAddress(c)
                return EdRange(EdAddress.DOT, second ?: EdAddress.LAST, true)
            }

            else -> {
                Unit
            }
        }
        val first = tryParseAddress(c)
        skipSpaces(c)
        val sep = c.peek()
        if (sep != ',' && sep != ';') return EdRange(first, null, false)
        c.next()
        val second = tryParseAddress(c)
        return EdRange(first ?: EdAddress(EdAddress.Primary.Number(1)), second, sep == ';')
    }

    private fun skipSpaces(c: EdLineCursor) {
        while (!c.eof() && c.peek() == ' ') c.pos++
    }

    private fun tryParseAddress(c: EdLineCursor): EdAddress? {
        skipSpaces(c)
        val start = c.pos
        val primary: EdAddress.Primary? =
            when (val ch = c.peek()) {
                null -> {
                    null
                }

                in '0'..'9' -> {
                    var n = 0
                    while (!c.eof() && c.peek()!! in '0'..'9') {
                        n = n * 10 + (c.next() - '0')
                    }
                    EdAddress.Primary.Number(n)
                }

                '.' -> {
                    c.next()
                    EdAddress.Primary.Dot
                }

                '$' -> {
                    c.next()
                    EdAddress.Primary.Last
                }

                '\'' -> {
                    c.next()
                    val mch = c.peek() ?: throw EdError("address mark missing letter")
                    c.next()
                    EdAddress.Primary.Mark(mch)
                }

                '/' -> {
                    c.next()
                    val re = readDelimited(c, '/')
                    EdAddress.Primary.SearchForward(re.ifEmpty { null })
                }

                '?' -> {
                    c.next()
                    val re = readDelimited(c, '?')
                    EdAddress.Primary.SearchBackward(re.ifEmpty { null })
                }

                '+', '-', '^' -> {
                    null
                }

                // offsets only — empty primary
                else -> {
                    null
                }
            }
        // Parse offsets.
        val offsets = mutableListOf<Int>()
        var anyOffset = false
        while (true) {
            skipSpaces(c)
            val sign: Int =
                when (c.peek()) {
                    '+' -> {
                        c.next()
                        +1
                    }

                    '-', '^' -> {
                        c.next()
                        -1
                    }

                    else -> {
                        break
                    }
                }
            anyOffset = true
            skipSpaces(c)
            var n = 0
            var sawDigit = false
            while (!c.eof() && c.peek()!! in '0'..'9') {
                n = n * 10 + (c.next() - '0')
                sawDigit = true
            }
            offsets.add(sign * if (sawDigit) n else 1)
        }
        return if (primary == null && !anyOffset) {
            if (c.pos == start) null else null
        } else {
            EdAddress(primary ?: EdAddress.Primary.Empty, offsets)
        }
    }

    /** Read characters up to (and consuming) the delimiter or EOL. Handles `\<delim>` escapes. */
    public fun readDelimited(
        c: EdLineCursor,
        delim: Char,
    ): String {
        val sb = StringBuilder()
        while (!c.eof()) {
            val ch = c.next()
            if (ch == delim) return sb.toString()
            if (ch == '\\' && !c.eof()) {
                val nx = c.next()
                if (nx == delim) {
                    sb.append(nx)
                } else {
                    sb.append(ch)
                    sb.append(nx)
                }
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}

public class EdError(
    message: String,
) : RuntimeException(message)

public object EdAddressResolver {
    /**
     * Resolve an [EdAddress] against the supplied buffer state. Returns the
     * 1-indexed line number; throws [EdError] when the address can't be
     * resolved (no match for a regex, mark unset, out of range).
     */
    public fun resolve(
        addr: EdAddress,
        buf: EdBuffer,
        from: Int = buf.dot,
    ): Int {
        val anchor =
            when (val p = addr.primary) {
                EdAddress.Primary.Dot -> {
                    buf.dot
                }

                EdAddress.Primary.Last -> {
                    buf.size
                }

                EdAddress.Primary.Empty -> {
                    from
                }

                is EdAddress.Primary.Number -> {
                    p.n
                }

                is EdAddress.Primary.Mark -> {
                    buf.marks[p.ch] ?: throw EdError("mark not set")
                }

                is EdAddress.Primary.SearchForward -> {
                    searchForward(
                        buf,
                        p.re ?: buf.lastSearch ?: throw EdError("no previous search"),
                    )
                }

                is EdAddress.Primary.SearchBackward -> {
                    searchBackward(buf, p.re ?: buf.lastSearch ?: throw EdError("no previous search"))
                }
            }
        var n = anchor
        for (o in addr.offsets) n += o
        // POSIX permits address 0 only for "before line 1" (a/r). The bare
        // resolver accepts 0; commands enforce their own bounds.
        if (n < 0 || n > buf.size) throw EdError("address out of range")
        return n
    }

    private fun searchForward(
        buf: EdBuffer,
        re: String,
    ): Int {
        if (buf.size == 0) throw EdError("no match")
        val regex = EdRegex.compile(re)
        val start = buf.dot
        for (i in 1..buf.size) {
            val idx = ((start + i - 1) % buf.size) + 1
            if (regex.containsMatchIn(buf.line(idx))) return idx
        }
        throw EdError("no match")
    }

    private fun searchBackward(
        buf: EdBuffer,
        re: String,
    ): Int {
        if (buf.size == 0) throw EdError("no match")
        val regex = EdRegex.compile(re)
        val start = buf.dot
        for (i in 1..buf.size) {
            val idx = ((start - i - 1 + buf.size * buf.size) % buf.size) + 1
            if (regex.containsMatchIn(buf.line(idx))) return idx
        }
        throw EdError("no match")
    }
}
