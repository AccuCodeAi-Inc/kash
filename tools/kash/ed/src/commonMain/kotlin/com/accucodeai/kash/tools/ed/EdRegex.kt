package com.accucodeai.kash.tools.ed

/**
 * POSIX BRE -> Kotlin/Java regex translation.
 *
 * POSIX ed uses Basic Regular Expressions: `()` and `{}` and `|` and `+` and
 * `?` are literal unless backslash-escaped. We translate by walking the
 * pattern and flipping the backslash polarity for those metacharacters.
 *
 * Anchors `^` and `$` and char classes `[...]` keep their meaning unchanged.
 * `\<` / `\>` (word boundaries) are mapped to `\b`. `&` and `\1..\9` belong
 * to the replacement side and are handled by [substitute].
 */
public object EdRegex {
    public fun compile(re: String): Regex = Regex(translate(re))

    public fun translate(re: String): String {
        val sb = StringBuilder()
        var i = 0
        var inClass = false
        while (i < re.length) {
            val c = re[i]
            if (inClass) {
                sb.append(c)
                if (c == ']') inClass = false
                i++
                continue
            }
            when (c) {
                '[' -> {
                    sb.append('[')
                    inClass = true
                    i++
                    if (i < re.length && re[i] == ']') {
                        sb.append(']')
                        i++ // literal ] right after [
                    }
                    if (i < re.length && re[i] == '^') {
                        sb.append('^')
                        i++
                        if (i < re.length && re[i] == ']') {
                            sb.append(']')
                            i++
                        }
                    }
                }

                '(', ')', '{', '}', '|', '+', '?' -> {
                    // Literal in BRE — escape for Kotlin/Java regex (ERE).
                    sb.append('\\').append(c)
                    i++
                }

                '\\' -> {
                    if (i + 1 < re.length) {
                        val n = re[i + 1]
                        when (n) {
                            '(', ')', '{', '}', '|', '+', '?' -> {
                                // BRE escaped form -> ERE unescaped.
                                sb.append(n)
                                i += 2
                            }

                            '<', '>' -> {
                                sb.append("\\b")
                                i += 2
                            }

                            in '1'..'9' -> {
                                // Backreference — leave as-is; substitute()
                                // handles \N in the replacement side, not
                                // here. Kotlin regex supports \1..\9 same
                                // way as POSIX.
                                sb.append('\\').append(n)
                                i += 2
                            }

                            else -> {
                                sb.append('\\').append(n)
                                i += 2
                            }
                        }
                    } else {
                        sb.append('\\').append('\\')
                        i++
                    }
                }

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * Apply the POSIX ed substitution rules. Supports `&` (whole match),
     * `\1..\9` (capture groups), `\&` (literal `&`), `\\` (literal `\`),
     * `\n` in the replacement = literal newline (POSIX: split the line).
     *
     * Returns the replaced text.
     */
    public fun substituteOne(
        match: MatchResult,
        repl: String,
    ): String {
        val sb = StringBuilder()
        var i = 0
        while (i < repl.length) {
            val c = repl[i]
            when {
                c == '&' -> {
                    sb.append(match.value)
                    i++
                }

                c == '\\' && i + 1 < repl.length -> {
                    val n = repl[i + 1]
                    when (n) {
                        '&' -> {
                            sb.append('&')
                            i += 2
                        }

                        '\\' -> {
                            sb.append('\\')
                            i += 2
                        }

                        'n' -> {
                            sb.append('\n')
                            i += 2
                        }

                        in '1'..'9' -> {
                            val gi = n - '0'
                            val g =
                                if (gi < match.groups.size) {
                                    match.groups[gi]?.value ?: ""
                                } else {
                                    ""
                                }
                            sb.append(g)
                            i += 2
                        }

                        else -> {
                            sb.append(n)
                            i += 2
                        }
                    }
                }

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
