package com.accucodeai.kash.api.util

/**
 * Minimal POSIX-shell glob / fnmatch:
 *  - `*` matches any run (including empty)
 *  - `?` matches one character
 *  - `[abc]` / `[a-z]` / `[!abc]` / `[^abc]` character classes
 *  - `\X` escapes the next character
 *
 * No FNM_PATHNAME / FNM_PERIOD subtleties — callers handle leading-dot
 * and slash policy themselves (see `Expander.matchPathSegment`).
 */
public fun matchGlob(
    pattern: String,
    value: String,
): Boolean = matchPattern(pattern, 0, value, 0)

/** Bash extglob group at p[i]: returns (op, alts, endIndex-after-closing-paren) or null. */
private fun parseExtglob(
    p: String,
    i: Int,
): Triple<Char, List<String>, Int>? {
    if (i + 1 >= p.length) return null
    val op = p[i]
    if (op !in "?+*@!") return null
    if (p[i + 1] != '(') return null
    // Scan for matching close, honoring nesting and bracket classes.
    var depth = 1
    var k = i + 2
    var altStart = k
    val alts = mutableListOf<String>()
    while (k < p.length) {
        val c = p[k]
        when {
            c == '\\' && k + 1 < p.length -> {
                k += 2
            }

            c == '[' -> {
                // Find the closing `]` of this bracket expression,
                // skipping over POSIX sub-tokens — `[:NAME:]`, `[.X.]`,
                // and `[=X=]` — whose inner `]` is structural, not the
                // bracket terminator. Mirrors the same scanner used by
                // matchPattern's `[` branch so bash's nested collating-
                // element-inside-extglob patterns (e.g. `@([[.].])A])`)
                // parse the same way here. An unclosed `[` reverts the
                // whole construct to literal matching.
                var scanFrom = k + 1
                if (scanFrom < p.length && (p[scanFrom] == '!' || p[scanFrom] == '^')) scanFrom++
                if (scanFrom < p.length && p[scanFrom] == ']') scanFrom++
                var close = -1
                var bk = scanFrom
                while (bk < p.length) {
                    val bc = p[bk]
                    if (bc == '\\' && bk + 1 < p.length) {
                        bk += 2
                        continue
                    }
                    if (bc == '[' && bk + 1 < p.length &&
                        (p[bk + 1] == ':' || p[bk + 1] == '.' || p[bk + 1] == '=')
                    ) {
                        val tag = p[bk + 1]
                        val inner = findClosingSubBracket(p, bk + 2, tag, abortOnRBracket = tag == '=')
                        if (inner < 0) {
                            bk++
                            continue
                        }
                        bk = inner + 2
                        continue
                    }
                    if (bc == ']') {
                        close = bk
                        break
                    }
                    bk++
                }
                if (close < 0) return null
                k = close + 1
            }

            c == '(' -> {
                depth++
                k++
            }

            c == ')' -> {
                depth--
                if (depth == 0) {
                    alts += p.substring(altStart, k)
                    return Triple(op, alts, k + 1)
                }
                k++
            }

            c == '|' && depth == 1 -> {
                alts += p.substring(altStart, k)
                k++
                altStart = k
            }

            else -> {
                k++
            }
        }
    }
    return null
}

private fun matchPattern(
    p: String,
    pi: Int,
    s: String,
    si: Int,
): Boolean {
    var i = pi
    var j = si
    while (i < p.length) {
        // Extglob: @(...) +(...) *(...) ?(...) !(...)
        if (p[i] in "?+*@!" && i + 1 < p.length && p[i + 1] == '(') {
            val eg = parseExtglob(p, i)
            if (eg != null) {
                val (op, alts, nextI) = eg
                return matchExtglob(op, alts, p, nextI, s, j)
            }
        }
        when (val c = p[i]) {
            '*' -> {
                // Collapse runs of `*` to a single match-any. Stop the
                // collapse if the next `*` opens an extglob group
                // (`**(e|f)` is `*` followed by `*(e|f)`, not just `*`).
                while (i + 1 < p.length && p[i + 1] == '*' &&
                    !(i + 2 < p.length && p[i + 2] == '(')
                ) {
                    i++
                }
                i++ // consume the (last) `*`
                if (i == p.length) return true
                for (k in j..s.length) if (matchPattern(p, i, s, k)) return true
                return false
            }

            '?' -> {
                if (j >= s.length) return false
                i++
                j++
            }

            '[' -> {
                // POSIX bracket-expression rule: `]` immediately after `[`
                // (or after `[!` / `[^`) is a literal class member, not
                // the closer. Skip past the leading negator and the
                // member-`]`. POSIX classes `[:alpha:]` are entire
                // sub-tokens — the `]` inside them is structural, not the
                // overall bracket-expression terminator, so skip them too.
                // Collating elements `[.X.]` and equivalence classes
                // `[=X=]` are likewise sub-tokens whose inner `]` doesn't
                // terminate the bracket expression. Backslash-escaped `\]`
                // is consumed verbatim — the `]` doesn't close.
                var scanFrom = i + 1
                if (scanFrom < p.length && (p[scanFrom] == '!' || p[scanFrom] == '^')) scanFrom++
                if (scanFrom < p.length && p[scanFrom] == ']') scanFrom++
                var close = -1
                var k = scanFrom
                while (k < p.length) {
                    val ch = p[k]
                    if (ch == '\\' && k + 1 < p.length) {
                        k += 2
                        continue
                    }
                    if (ch == '[' && k + 1 < p.length && (p[k + 1] == ':' || p[k + 1] == '.' || p[k + 1] == '=')) {
                        val tag = p[k + 1]
                        // Asymmetric scan rule:
                        //  - `[.X.]` collating: body MAY contain `]` (scan
                        //    until `.]`, ignoring stray `]`).
                        //  - `[=X=]` equiv class: body may NOT contain `]`
                        //    — if a `]` is seen before `=]`, the equiv-class
                        //    parse aborts and the `[=` degrades to two
                        //    literal class members. POSIX XCU §9.3.5.
                        //  - `[:NAME:]` character class: same as collating
                        //    for outer-]-finding purposes; `]` inside the
                        //    name isn't valid, but bash's scanner walks
                        //    until `:]` regardless.
                        val abortOnRBracket = tag == '='
                        val inner = findClosingSubBracket(p, k + 2, tag, abortOnRBracket)
                        if (inner < 0) {
                            k++
                            continue
                        }
                        k = inner + 2
                        continue
                    }
                    if (ch == ']') {
                        close = k
                        break
                    }
                    k++
                }
                if (close < 0) {
                    // POSIX/bash: an unterminated bracket expression isn't
                    // a parse error — the `[` degrades to a literal
                    // pattern character, and matching continues past it.
                    // See lib/glob/sm_loop.c in bash for the reference
                    // behavior; this implementation is independent.
                    if (j >= s.length || s[j] != '[') return false
                    i++
                    j++
                    continue
                }
                if (j >= s.length) return false
                if (!matchClass(p.substring(i + 1, close), s[j])) return false
                i = close + 1
                j++
            }

            '\\' -> {
                if (i + 1 >= p.length) {
                    // Trailing backslash with nothing to escape: treat as
                    // a literal `\` (bash tolerates this in patterns).
                    if (j >= s.length || s[j] != '\\') return false
                    i++
                    j++
                    continue
                }
                if (j >= s.length || s[j] != p[i + 1]) return false
                i += 2
                j++
            }

            else -> {
                if (j >= s.length || s[j] != c) return false
                i++
                j++
            }
        }
    }
    return j == s.length
}

/**
 * Match an extglob group followed by [tailP] against [s] starting at [si].
 *  - `@(a|b)` exactly one alt
 *  - `?(a|b)` zero or one
 *  - `*(a|b)` zero or more
 *  - `+(a|b)` one or more
 *  - `!(a|b)` anything not matching the alts (then rest must match tail)
 */
private fun matchExtglob(
    op: Char,
    alts: List<String>,
    tailP: String,
    tailStart: Int,
    s: String,
    si: Int,
): Boolean {
    fun tailMatches(at: Int) = matchPattern(tailP, tailStart, s, at)
    return when (op) {
        '@' -> {
            for (a in alts) {
                for (k in si..s.length) {
                    if (matchPatternRange(a, s, si, k) && tailMatches(k)) {
                        return true
                    }
                }
            }
            false
        }

        '?' -> {
            if (tailMatches(si)) return true
            for (a in alts) {
                for (k in si..s.length) {
                    if (matchPatternRange(a, s, si, k) && tailMatches(k)) return true
                }
            }
            false
        }

        '*' -> {
            matchRepeat(alts, tailP, tailStart, s, si, minReps = 0)
        }

        '+' -> {
            matchRepeat(alts, tailP, tailStart, s, si, minReps = 1)
        }

        '!' -> {
            // Match if some prefix [si..k) does NOT match any alt as a whole,
            // and the tail matches at k. Try every prefix.
            for (k in si..s.length) {
                var anyAlt = false
                for (a in alts) {
                    if (matchPatternRange(a, s, si, k)) {
                        anyAlt = true
                        break
                    }
                }
                if (!anyAlt && tailMatches(k)) return true
            }
            false
        }

        else -> {
            false
        }
    }
}

/** True iff pattern [p] matches the substring s[si..end). */
private fun matchPatternRange(
    p: String,
    s: String,
    si: Int,
    end: Int,
): Boolean = matchPattern(p, 0, s.substring(si, end), 0)

private fun matchRepeat(
    alts: List<String>,
    tailP: String,
    tailStart: Int,
    s: String,
    si: Int,
    minReps: Int,
): Boolean {
    // BFS over reachable positions after N repetitions.
    val seen = mutableSetOf<Int>()
    var frontier = mutableSetOf(si)
    var reps = 0
    if (minReps == 0 && matchPattern(tailP, tailStart, s, si)) return true
    while (frontier.isNotEmpty()) {
        val next = mutableSetOf<Int>()
        for (pos in frontier) {
            for (a in alts) {
                for (k in (pos + 1)..s.length) {
                    if (matchPatternRange(a, s, pos, k) && k !in seen) {
                        next += k
                    }
                }
            }
        }
        reps++
        for (k in next) {
            if (reps >= minReps && matchPattern(tailP, tailStart, s, k)) return true
        }
        seen += next
        frontier = next
        if (reps > s.length + 1) break
    }
    return false
}

/**
 * Find the closing index of a bracket sub-token `[tag…tag]`. Returns the
 * index of the matched `tag`, or -1 if no proper closer is found. With
 * [abortOnRBracket] = true (the `[=…=]` equivalence-class case), a stray
 * `]` encountered before the `tag]` terminator aborts the scan — POSIX
 * XCU §9.3.5 forbids `]` in an equiv-class body, so bash falls back to
 * treating the `[=` as two literal class members and the `]` as the
 * outer bracket terminator. Backslash escapes consume two characters.
 */
private fun findClosingSubBracket(
    p: String,
    from: Int,
    tag: Char,
    abortOnRBracket: Boolean,
): Int {
    var k = from
    while (k < p.length) {
        val ch = p[k]
        if (ch == '\\' && k + 1 < p.length) {
            k += 2
            continue
        }
        if (ch == tag && k + 1 < p.length && p[k + 1] == ']') return k
        if (abortOnRBracket && ch == ']') return -1
        k++
    }
    return -1
}

private fun matchClass(
    klass: String,
    c: Char,
): Boolean {
    if (klass.isEmpty()) return false
    var negate = false
    var start = 0
    if (klass[0] == '!' || klass[0] == '^') {
        negate = true
        start = 1
    }
    var k = start
    var matched = false
    while (k < klass.length) {
        // POSIX bracket-expression character class: `[:alpha:]`,
        // `[:digit:]`, etc. The opening `[:` must be followed by a
        // class name ended by `:]`. If the lookahead doesn't match,
        // treat `[` as a literal class member.
        if (klass[k] == '[' && k + 1 < klass.length && klass[k + 1] == ':') {
            val close = klass.indexOf(":]", k + 2)
            if (close >= 0) {
                val name = klass.substring(k + 2, close)
                if (matchPosixClass(name, c)) matched = true
                k = close + 2
                continue
            }
        }
        // POSIX collating element `[.NAME.]` or equivalence class `[=X=]`.
        // We resolve each to a single representative character (C locale
        // semantics — no real locale-aware collation). A range `[.x.]-Y`
        // or `Y-[.x.]` uses the resolved char as the endpoint. An
        // unrecognized collating name (e.g. `[.yyz.]`) resolves to no
        // character — it can't match anything and can't anchor a range.
        if (klass[k] == '[' && k + 1 < klass.length && (klass[k + 1] == '.' || klass[k + 1] == '=')) {
            val tag = klass[k + 1]
            // Asymmetric body rule: `[=X=]` body can't contain `]` (POSIX
            // XCU §9.3.5); finding `]` first aborts the equiv-class parse
            // and degrades `[=` to two literal class members. `[.X.]`
            // collating bodies may contain `]`.
            val close = findClosingSubBracket(klass, k + 2, tag, abortOnRBracket = tag == '=')
            if (close >= 0) {
                val name = klass.substring(k + 2, close)
                val tokenEnd = close + 2
                val resolved = if (tag == '.') resolveCollatingElement(name) else resolveEquivalenceClass(name)
                // Range form: [collating]-X  or  [collating]-[collating]
                if (resolved != null && tokenEnd + 1 < klass.length && klass[tokenEnd] == '-') {
                    val rhsEnd: Int
                    val rhs: Char?
                    if (klass[tokenEnd + 1] == '[' && tokenEnd + 2 < klass.length &&
                        (klass[tokenEnd + 2] == '.' || klass[tokenEnd + 2] == '=')
                    ) {
                        val rTag = klass[tokenEnd + 2]
                        val rClose = klass.indexOf("$rTag]", tokenEnd + 3)
                        if (rClose < 0) {
                            // Malformed — fall through to literal handling.
                            if (klass[k] == c) matched = true
                            k++
                            continue
                        }
                        val rName = klass.substring(tokenEnd + 3, rClose)
                        rhs = if (rTag == '.') resolveCollatingElement(rName) else resolveEquivalenceClass(rName)
                        rhsEnd = rClose + 2
                    } else {
                        rhs = klass[tokenEnd + 1]
                        rhsEnd = tokenEnd + 2
                    }
                    if (rhs != null) {
                        if (c in resolved..rhs) matched = true
                        k = rhsEnd
                        continue
                    }
                    // Invalid range end → treat collating LHS as literal,
                    // then leave the `-` and rest for the next iteration.
                    if (c == resolved) matched = true
                    k = tokenEnd
                    continue
                }
                if (resolved != null && c == resolved) matched = true
                k = tokenEnd
                continue
            }
            // Unterminated — treat `[` as literal and let the rest be
            // scanned char-by-char (matches bash's tolerance for
            // ill-formed brackets inside a bracket expression).
        }
        // Backslash inside a bracket expression escapes the next char.
        if (klass[k] == '\\' && k + 1 < klass.length) {
            if (k + 3 < klass.length && klass[k + 2] == '-') {
                if (c in klass[k + 1]..klass[k + 3]) matched = true
                k += 4
            } else {
                if (klass[k + 1] == c) matched = true
                k += 2
            }
            continue
        }
        if (k + 2 < klass.length && klass[k + 1] == '-') {
            if (c in klass[k]..klass[k + 2]) matched = true
            k += 3
        } else {
            if (klass[k] == c) matched = true
            k++
        }
    }
    return if (negate) !matched else matched
}

/**
 * Translate a POSIX collating-element name (`[.NAME.]`) to a single Char.
 * Single-character names resolve to themselves (so `[.a.]` ≡ `a`). Named
 * elements cover the punctuation/whitespace names used by bash's
 * conformance corpus. Unknown names return null — bash treats unrecognized
 * collating elements as no-match (they can appear in a pattern without
 * raising an error, but match nothing).
 */
private fun resolveCollatingElement(name: String): Char? {
    if (name.length == 1) return name[0]
    return when (name) {
        "NUL", "null" -> ' '
        "alert", "BEL", "bell" -> '\u0007'
        "backspace", "BS" -> '\b'
        "tab", "HT", "horizontal-tab" -> '\t'
        "newline", "LF" -> '\n'
        "vertical-tab", "VT" -> '\u000B'
        "form-feed", "FF" -> '\u000C'
        "carriage-return", "CR" -> '\r'
        "space", "SP" -> ' '
        "exclamation-mark" -> '!'
        "quotation-mark" -> '"'
        "number-sign" -> '#'
        "dollar-sign" -> '$'
        "percent-sign" -> '%'
        "ampersand" -> '&'
        "apostrophe" -> '\''
        "left-parenthesis" -> '('
        "right-parenthesis" -> ')'
        "asterisk" -> '*'
        "plus-sign" -> '+'
        "comma" -> ','
        "hyphen", "hyphen-minus", "minus-sign" -> '-'
        "period", "full-stop", "dot" -> '.'
        "slash", "solidus" -> '/'
        "colon" -> ':'
        "semicolon" -> ';'
        "less-than-sign" -> '<'
        "equals-sign", "equal-sign" -> '='
        "greater-than-sign" -> '>'
        "question-mark" -> '?'
        "at-sign", "commercial-at" -> '@'
        "left-square-bracket", "left-bracket" -> '['
        "backslash", "reverse-solidus" -> '\\'
        "right-square-bracket", "right-bracket" -> ']'
        "circumflex", "circumflex-accent" -> '^'
        "underscore", "low-line" -> '_'
        "grave-accent" -> '`'
        "left-curly-bracket", "left-brace" -> '{'
        "vertical-line" -> '|'
        "right-curly-bracket", "right-brace" -> '}'
        "tilde" -> '~'
        else -> null
    }
}

/**
 * Translate a POSIX equivalence-class name (`[=X=]`) to a single Char. In
 * the C locale, the equivalence class of any character is just that
 * character itself — no locale-aware folding. Multi-char or unknown names
 * are unsupported and return null.
 */
private fun resolveEquivalenceClass(name: String): Char? = if (name.length == 1) name[0] else null

/**
 * POSIX character class names (`[:alpha:]`, `[:digit:]`, ...) per
 * [POSIX 9.3.5](https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap09.html#tag_09_03_05).
 * All evaluated in the C locale — no LC_CTYPE-dependent character sets.
 * Unknown class names match nothing (POSIX says undefined behavior;
 * bash leaves them out).
 */
private fun matchPosixClass(
    name: String,
    c: Char,
): Boolean =
    when (name) {
        "alpha" -> c.isLetter()
        "alnum" -> c.isLetterOrDigit()
        "digit" -> c in '0'..'9'
        "xdigit" -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        "upper" -> c in 'A'..'Z'
        "lower" -> c in 'a'..'z'
        "space" -> c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'
        "blank" -> c == ' ' || c == '\t'
        "cntrl" -> c.code < 32 || c.code == 127
        "graph" -> c.code in 33..126
        "print" -> c.code in 32..126
        "punct" -> c.code in 33..126 && !c.isLetterOrDigit() && c != ' '
        "ascii" -> c.code in 0..127
        "word" -> c.isLetterOrDigit() || c == '_'
        else -> false
    }
