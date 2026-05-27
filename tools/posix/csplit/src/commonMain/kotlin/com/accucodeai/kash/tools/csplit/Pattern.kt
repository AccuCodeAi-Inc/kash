package com.accucodeai.kash.tools.csplit

/**
 * One csplit pattern. The optional [offset] is the GNU-style `+N` / `-N`
 * suffix on regex patterns; for `LineNum` patterns no offset applies.
 *
 * A trailing `{N}` or `{*}` becomes [repeat], which is the count of
 * *additional* re-applications of the immediately preceding pattern. `{*}`
 * is represented as [REPEAT_INFINITE].
 */
public sealed interface Pattern {
    public val offset: Int
    public val repeat: Int

    public data class Regex(
        val pattern: String,
        override val offset: Int = 0,
        override val repeat: Int = 0,
        /** When true, this is `%REGEX%` (skip) — the piece preceding the
         *  match is discarded rather than written. */
        val skip: Boolean = false,
    ) : Pattern

    public data class LineNum(
        val line: Int,
        override val repeat: Int = 0,
    ) : Pattern {
        override val offset: Int get() = 0
    }
}

public const val REPEAT_INFINITE: Int = -1

/**
 * Parse a sequence of csplit operands into [Pattern]s. Operands like `{N}`
 * or `{*}` mutate the immediately preceding pattern's [Pattern.repeat]
 * field and are not themselves emitted.
 *
 * Throws [IllegalArgumentException] on malformed operands.
 */
public fun parsePatterns(operands: List<String>): List<Pattern> {
    val out = mutableListOf<Pattern>()
    for (raw in operands) {
        if (raw.startsWith("{") && raw.endsWith("}")) {
            val inside = raw.substring(1, raw.length - 1)
            val n =
                if (inside == "*") {
                    REPEAT_INFINITE
                } else {
                    inside.toIntOrNull()
                        ?: throw IllegalArgumentException("invalid repetition: $raw")
                }
            if (out.isEmpty()) throw IllegalArgumentException("repetition before any pattern: $raw")
            val last = out.removeAt(out.size - 1)
            out +=
                when (last) {
                    is Pattern.Regex -> last.copy(repeat = n)
                    is Pattern.LineNum -> last.copy(repeat = n)
                }
            continue
        }
        out += parseOne(raw)
    }
    return out
}

private fun parseOne(raw: String): Pattern {
    if (raw.isEmpty()) throw IllegalArgumentException("empty pattern")
    val first = raw[0]
    return when {
        first == '/' || first == '%' -> {
            parseRegex(raw, skip = first == '%')
        }

        first == '-' || first.isDigit() -> {
            // line number must be positive
            val n = raw.toIntOrNull() ?: throw IllegalArgumentException("invalid line number: $raw")
            if (n < 1) throw IllegalArgumentException("line number must be ≥ 1: $raw")
            Pattern.LineNum(line = n)
        }

        else -> {
            throw IllegalArgumentException("invalid pattern: $raw")
        }
    }
}

private fun parseRegex(
    raw: String,
    skip: Boolean,
): Pattern.Regex {
    val delim = raw[0]
    // find closing delimiter, allowing backslash-escape inside.
    var i = 1
    val pat = StringBuilder()
    var closed = false
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            // keep the escape sequence in the regex string verbatim;
            // Kotlin Regex understands standard \-escapes.
            pat.append(c).append(raw[i + 1])
            i += 2
            continue
        }
        if (c == delim) {
            closed = true
            i++
            break
        }
        pat.append(c)
        i++
    }
    if (!closed) throw IllegalArgumentException("unterminated regex: $raw")
    val rest = raw.substring(i)
    val off =
        if (rest.isEmpty()) {
            0
        } else {
            rest.toIntOrNull() ?: throw IllegalArgumentException("invalid offset: $rest")
        }
    return Pattern.Regex(pattern = pat.toString(), offset = off, skip = skip)
}
