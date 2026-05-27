package com.accucodeai.kash.interpreter

/**
 * Translate a bash glob pattern to a RE2 regex source string.
 *
 * Returns null when the pattern uses an extglob construct (`?(`, `*(`, `+(`,
 * `@(`, `!(`) or a bracket-expression feature RE2 can't natively express
 * (collating elements `[.X.]`, equivalence classes `[=X=]`). Caller must
 * fall back to the recursive matcher in [com.accucodeai.kash.api.util.matchGlob]
 * for those.
 *
 * The output is intended to be compiled with the `s` (dotall) flag so that
 * `*` (translated to `.*`) matches newlines — bash's semantics.
 *
 * Bash glob handled:
 *   `*`               → `.*`
 *   `?`               → `.`
 *   `\X`              → escaped literal X (trailing `\` → literal `\`)
 *   `[abc]`           → `[abc]`
 *   `[!abc]`          → `[^abc]` (bash alternate negation)
 *   `[^abc]`          → `[^abc]`
 *   `[[:class:]]`     → `[[:class:]]` (RE2 supports POSIX classes natively)
 *   unterminated `[`  → literal `\[` (bash's tolerance)
 *
 * RE2 itself (no ReDoS by construction) provides linear-time matching, so
 * compiled patterns are safe to apply to untrusted input.
 */
internal object GlobToRegex {
    // RE2 metacharacters that need escaping when emitted as literal text.
    private val RE2_META = "\\.+*?()[]{}^$|".toSet()

    /**
     * Translate [glob] to RE2 source. Null = extglob or bracket feature we
     * can't express; caller falls back to the recursive matcher.
     *
     * [longest] controls quantifier greediness: `*` → `.*` (longest, default,
     * matches bash `${var##pat}` / `${var%%pat}` and unanchored `${var//pat}`)
     * vs. `.*?` (shortest, for `${var#pat}` / `${var%pat}`).
     */
    fun translate(
        glob: String,
        longest: Boolean = true,
    ): String? {
        if (glob.isEmpty()) return ""
        val sb = StringBuilder(glob.length * 2)
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            // Extglob: <op>( where op is ?+*@! → bail; recursive matcher
            // handles these.
            if (c in EXTGLOB_OPS && i + 1 < glob.length && glob[i + 1] == '(') {
                return null
            }
            when (c) {
                '*' -> {
                    // Adjacent `*` collapse — same set in bash unless
                    // `globstar` is on (which only matters in pathnames).
                    // Stop if the next `*` opens an extglob group: that
                    // `*` is the operator of `*(p)`, not part of the
                    // glob-star run. Caller handles the rest via the
                    // extglob bail-out below.
                    while (i + 1 < glob.length && glob[i + 1] == '*' &&
                        !(i + 2 < glob.length && glob[i + 2] == '(')
                    ) {
                        i++
                    }
                    sb.append(if (longest) ".*" else ".*?")
                    i++
                }

                '?' -> {
                    sb.append('.')
                    i++
                }

                '[' -> {
                    val close = findBracketClose(glob, i)
                    if (close == null) {
                        // Unterminated — bash degrades to literal `[`.
                        sb.append("\\[")
                        i++
                    } else {
                        val body = glob.substring(i + 1, close)
                        val translated = translateBracket(body)
                        if (translated == null) {
                            // Collating element / equivalence class — bail.
                            return null
                        }
                        if (translated.isEmpty()) {
                            // Empty bracket body (e.g. `[]` not consumed by
                            // member-`]` rule) — treat as literal `[`.
                            sb.append("\\[")
                            i++
                        } else {
                            sb.append('[').append(translated).append(']')
                            i = close + 1
                        }
                    }
                }

                '\\' -> {
                    if (i + 1 < glob.length) {
                        sb.append(escLit(glob[i + 1]))
                        i += 2
                    } else {
                        // Trailing `\` — bash tolerates as literal backslash.
                        sb.append("\\\\")
                        i++
                    }
                }

                else -> {
                    sb.append(escLit(c))
                    i++
                }
            }
        }
        return sb.toString()
    }

    private const val EXTGLOB_OPS = "?+*@!"

    private fun escLit(c: Char): String = if (c in RE2_META) "\\$c" else c.toString()

    /**
     * Find the closing `]` of a bracket expression starting at [open].
     * Handles bash quirks:
     *  - `]` immediately after `[`, `[!`, or `[^` is a literal class member
     *  - POSIX class sub-tokens `[:X:]`, collating `[.X.]`, equivalence
     *    `[=X=]` are skipped wholesale — their inner `]` doesn't terminate
     *  - `\]` doesn't terminate
     *
     * Returns null when unterminated.
     */
    private fun findBracketClose(
        glob: String,
        open: Int,
    ): Int? {
        var scanFrom = open + 1
        if (scanFrom < glob.length && (glob[scanFrom] == '!' || glob[scanFrom] == '^')) scanFrom++
        if (scanFrom < glob.length && glob[scanFrom] == ']') scanFrom++
        var k = scanFrom
        while (k < glob.length) {
            val ch = glob[k]
            when {
                ch == '\\' && k + 1 < glob.length -> {
                    k += 2
                }

                ch == '[' &&
                    k + 1 < glob.length &&
                    (glob[k + 1] == ':' || glob[k + 1] == '.' || glob[k + 1] == '=') -> {
                    val tag = glob[k + 1]
                    val inner = glob.indexOf("$tag]", k + 2)
                    if (inner < 0) {
                        k++
                    } else {
                        k = inner + 2
                    }
                }

                ch == ']' -> {
                    return k
                }

                else -> {
                    k++
                }
            }
        }
        return null
    }

    /**
     * Translate bracket body (the text between `[` and `]`). Returns null
     * when the body contains a feature RE2 can't express — caller bails.
     */
    private fun translateBracket(body: String): String? {
        if (body.isEmpty()) return ""
        val sb = StringBuilder(body.length + 4)
        var k = 0
        // Negation: bash allows both `!` and `^`; RE2 only `^`.
        if (body[k] == '!' || body[k] == '^') {
            sb.append('^')
            k++
        }
        // POSIX permits `]` as the first member char (after any negation).
        // RE2 treats a `]` immediately following `[` or `[^` as literal.
        if (k < body.length && body[k] == ']') {
            sb.append(']')
            k++
        }
        while (k < body.length) {
            val c = body[k]
            when {
                c == '[' && k + 1 < body.length && body[k + 1] == ':' -> {
                    // POSIX class — RE2 supports natively.
                    val end = body.indexOf(":]", k + 2)
                    if (end < 0) {
                        // Malformed — emit literal `[` and continue.
                        sb.append("\\[")
                        k++
                    } else {
                        sb.append(body.substring(k, end + 2))
                        k = end + 2
                    }
                }

                c == '[' && k + 1 < body.length && (body[k + 1] == '.' || body[k + 1] == '=') -> {
                    // Collating element / equivalence class — bail to recursive matcher.
                    return null
                }

                c == '\\' && k + 1 < body.length -> {
                    val n = body[k + 1]
                    // Inside a character class only a few chars need escaping.
                    sb.append(if (n in "]^[\\-") "\\$n" else n.toString())
                    k += 2
                }

                c == ']' -> {
                    // Shouldn't reach here when findBracketClose is correct,
                    // but be defensive.
                    sb.append("\\]")
                    k++
                }

                c == '\\' -> {
                    // Trailing `\` inside class — emit literal.
                    sb.append("\\\\")
                    k++
                }

                c == '[' -> {
                    // Bare `[` inside class.
                    sb.append("\\[")
                    k++
                }

                else -> {
                    sb.append(c)
                    k++
                }
            }
        }
        val out = sb.toString()
        // RE2 rejects `[]` and `[^]`. If translation reduced to a negator
        // alone (or to truly empty), signal "empty" so caller emits literal.
        if (out == "^" || out.isEmpty()) return ""
        return out
    }
}
