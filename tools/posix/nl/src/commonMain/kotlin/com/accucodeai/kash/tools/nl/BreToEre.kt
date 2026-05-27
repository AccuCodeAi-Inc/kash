package com.accucodeai.kash.tools.nl

/**
 * Translate POSIX BRE to RE2/ERE form by swapping which characters are escaped.
 * In BRE, `\(`, `\)`, `\{`, `\}`, `\+`, `\?`, `\|` are metacharacters while bare
 * `()`, `{}`, `+`, `?`, `|` are literals; ERE is the inverse. Backreferences
 * `\1`..`\9` pass through unchanged. Other escapes pass through unchanged.
 * Character classes `[...]` are copied verbatim.
 *
 * Self-contained to keep modules isolated; mirrors the helper in `:tools:grep`.
 */
internal fun breToEre(bre: String): String {
    val out = StringBuilder(bre.length)
    var i = 0
    while (i < bre.length) {
        val c = bre[i]
        when (c) {
            '\\' -> {
                if (i + 1 >= bre.length) {
                    out.append('\\')
                    i++
                    continue
                }
                val next = bre[i + 1]
                when (next) {
                    // BRE meta — strip the backslash so ERE treats it as meta
                    '(', ')', '{', '}', '+', '?', '|' -> {
                        out.append(next)
                        i += 2
                    }

                    else -> {
                        // Backref \1-\9, \n, \t, \\, \. etc — pass through.
                        out.append('\\').append(next)
                        i += 2
                    }
                }
            }

            '(', ')', '{', '}', '+', '?', '|' -> {
                // Bare in BRE means literal — escape for ERE.
                out.append('\\').append(c)
                i++
            }

            '[' -> {
                // Copy character class verbatim. Handle leading `]` and `[:class:]`.
                out.append('[')
                i++
                if (i < bre.length && bre[i] == '^') {
                    out.append('^')
                    i++
                }
                if (i < bre.length && bre[i] == ']') {
                    out.append(']')
                    i++
                }
                while (i < bre.length && bre[i] != ']') {
                    out.append(bre[i])
                    i++
                }
                if (i < bre.length) {
                    out.append(']')
                    i++
                }
            }

            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}
