package com.accucodeai.kash.shared.regex

/**
 * POSIX regex-syntax transforms shared by every tool that accepts BRE / fixed
 * patterns and feeds them to [LinearRegex] (which speaks ERE/RE2). Previously
 * each of `grep`, `git grep`, and `sed` carried its own copy; they live here
 * so the translation is defined once.
 *
 * Translate a POSIX **BRE** regex into ERE/RE2 form by swapping which
 * characters are escaped: in BRE, `\(\)\{\}\+\?\|` are metacharacters and bare
 * `()` `{}` `+?|` are literals; in ERE it's the inverse. Other escapes pass
 * through unchanged. Characters inside `[...]` are never transformed.
 */
public fun breToEre(s: String): String {
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

                        // GNU word-edge anchors `\<` `\>` → RE2's `\b`.
                        '<', '>' -> sb.append("\\b")

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
public fun escapeLiteral(s: String): String {
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
