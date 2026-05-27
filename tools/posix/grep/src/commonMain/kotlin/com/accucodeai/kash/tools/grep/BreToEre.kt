package com.accucodeai.kash.tools.grep

/**
 * Translate a POSIX BRE regex into RE2/ERE form by swapping which characters
 * are escaped: in BRE, `\(\)\{\}\+\?\|` are metas and bare `()` `{}` `+?|`
 * are literals; in ERE it's the inverse. Other escapes pass through unchanged.
 * Characters inside `[...]` are not transformed.
 *
 * Mirrors the helper in `:tools:sed`; duplicated here to keep modules isolated.
 */
internal fun breToEre(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    var inClass = false
    var classOpen = -1
    while (i < s.length) {
        val c = s[i]
        if (inClass) {
            sb.append(c)
            val sinceOpen = i - classOpen
            if (c == ']' && sinceOpen > 1 && !(sinceOpen == 2 && s[classOpen + 1] == '^')) {
                inClass = false
            } else if (c == '\\' && i + 1 < s.length) {
                sb.append(s[i + 1])
                i += 2
                continue
            }
            i++
            continue
        }
        when (c) {
            '[' -> {
                inClass = true
                classOpen = i
                sb.append(c)
                i++
            }

            '\\' -> {
                if (i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        '(', ')', '{', '}', '+', '?', '|' -> sb.append(n)
                        else -> sb.append('\\').append(n)
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }

            '(', ')', '{', '}', '+', '?', '|' -> {
                sb.append('\\').append(c)
                i++
            }

            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

/** Escape every ERE metacharacter so [s] matches as a literal string. */
internal fun escapeLiteral(s: String): String {
    val sb = StringBuilder(s.length + 4)
    for (c in s) {
        when (c) {
            '\\', '.', '^', '$', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|' -> {
                sb.append('\\').append(c)
            }

            else -> {
                sb.append(c)
            }
        }
    }
    return sb.toString()
}
