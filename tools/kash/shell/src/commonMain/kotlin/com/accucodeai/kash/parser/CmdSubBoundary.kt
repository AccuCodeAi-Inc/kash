package com.accucodeai.kash.parser

// Boundary finders for `$(...)` and `${...}` bodies embedded in raw
// text. The lexer's own scanners (`Lexer.readCommandSubstitution`,
// `Lexer.skipBraceExpansionBody`) handle these during tokenization;
// these stand-alone helpers run against already-captured text for
// callers that need to find the boundary without re-running the lexer
// (preexpand-style consumers like `interpreter.preexpandArithmetic`
// and `parser.antlr.AstBuilder.splitArithFor`).

/**
 * Find the index of the closing `}` of a `${...}` body starting at
 * [start] (one past the opening `{`). Brace-balanced. Returns -1 if
 * unbalanced.
 *
 * Used by callers that need to skip past a brace expansion / brace
 * funsub as one opaque unit without parsing its contents.
 */
internal fun findBraceClose(
    src: String,
    start: Int,
): Int {
    var d = 1
    var k = start
    while (k < src.length && d > 0) {
        when (src[k]) {
            '{' -> d++
            '}' -> d--
        }
        if (d > 0) k++
    }
    return if (d != 0) -1 else k
}

/**
 * Case-aware boundary finder for `$(...)` command substitutions.
 *
 * Reference: POSIX §2.6.3 — the cmdsub body parses as a full command
 * list, so a `)` closing a `case x in p) ... esac` pattern must NOT
 * be treated as the cmdsub terminator. Mirrors the case-keyword state
 * machine in [Lexer.readCommandSubstitution] (OVERFITPASS A1 fix),
 * specialized to a flat string argument.
 *
 * @param src the source text containing the cmdsub.
 * @param start index of the first character INSIDE the `$(...)` —
 *   i.e. one past the opening `(`.
 * @return index in [src] of the matching closing `)`, or -1 if
 *   unbalanced. The returned index is INCLUSIVE (points AT the `)`).
 */
internal fun findCmdSubClose(
    src: String,
    start: Int,
): Int {
    var i = start
    var depth = 1
    val caseStack = ArrayDeque<Pair<Boolean, Boolean>>()

    fun caseTop() = caseStack.lastOrNull()

    fun setCaseTop(
        pe: Boolean,
        lastPipe: Boolean,
    ) {
        caseStack[caseStack.size - 1] = pe to lastPipe
    }
    val casePatternParenDepths = ArrayDeque<Int>()
    var pendingCaseWord = false
    var sawCaseWordContent = false
    while (i < src.length && depth > 0) {
        val c = src[i]
        val isWs = c == ' ' || c == '\t' || c == '\n'
        if (pendingCaseWord && isWs && sawCaseWordContent) {
            pendingCaseWord = false
            sawCaseWordContent = false
        }
        if (pendingCaseWord && !isWs && c != '#') {
            sawCaseWordContent = true
        }
        when {
            c == '\\' && i + 1 < src.length -> {
                i += 2
            }

            c == '\'' -> {
                val end = src.indexOf('\'', i + 1)
                i = if (end < 0) src.length else end + 1
            }

            c == '"' -> {
                i++
                while (i < src.length && src[i] != '"') {
                    val ch = src[i]
                    if (ch == '\\' && i + 1 < src.length) {
                        i += 2
                        continue
                    }
                    if (ch == '$' && i + 1 < src.length && src[i + 1] == '(') {
                        val end = findCmdSubClose(src, i + 2)
                        i = if (end < 0) src.length else end + 1
                        continue
                    }
                    if (ch == '$' && i + 1 < src.length && src[i + 1] == '{') {
                        val end = findBraceClose(src, i + 2)
                        i = if (end < 0) src.length else end + 1
                        continue
                    }
                    i++
                }
                if (i < src.length) i++
            }

            c == '`' -> {
                val end = src.indexOf('`', i + 1)
                i = if (end < 0) src.length else end + 1
            }

            c == '$' && i + 1 < src.length && src[i + 1] == '(' -> {
                val end = findCmdSubClose(src, i + 2)
                i = if (end < 0) src.length else end + 1
            }

            c == '$' && i + 1 < src.length && src[i + 1] == '{' -> {
                val end = findBraceClose(src, i + 2)
                i = if (end < 0) src.length else end + 1
            }

            c == '(' -> {
                val top = caseTop()
                if (top != null && top.first) {
                    casePatternParenDepths.addLast(depth)
                }
                depth++
                i++
            }

            c == ')' -> {
                val top = caseTop()
                when {
                    depth == 1 && top != null && top.first -> {
                        setCaseTop(false, false)
                        i++
                    }

                    casePatternParenDepths.isNotEmpty() &&
                        casePatternParenDepths.last() == depth - 1 -> {
                        casePatternParenDepths.removeLast()
                        depth--
                        if (caseStack.isNotEmpty()) setCaseTop(false, false)
                        i++
                    }

                    else -> {
                        depth--
                        if (depth == 0) return i
                        i++
                    }
                }
            }

            c == ';' && i + 1 < src.length && src[i + 1] == ';' -> {
                val t = caseTop()
                if (t != null) setCaseTop(true, false)
                i += 2
            }

            c == '|' -> {
                val t = caseTop()
                if (depth == 1 && t != null && t.first) setCaseTop(true, true)
                i++
            }

            c.isLetter() || c == '_' -> {
                val ws = i
                while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                val prev = if (ws > 0) src[ws - 1] else ' '
                val isWordStart = !(prev.isLetterOrDigit() || prev == '_' || prev == '$')
                if (isWordStart) {
                    val w = src.substring(ws, i)
                    if (!pendingCaseWord) {
                        when (w) {
                            "case" -> {
                                caseStack.addLast(false to false)
                                pendingCaseWord = true
                                sawCaseWordContent = false
                            }

                            "in" -> {
                                val t = caseTop()
                                if (t != null && !t.first) setCaseTop(true, false)
                            }

                            "esac" -> {
                                val t = caseTop()
                                val nextIsPipe = i < src.length && src[i] == '|'
                                val isPattern = t != null && t.first && (t.second || nextIsPipe)
                                if (!isPattern && caseStack.isNotEmpty()) caseStack.removeLast()
                            }
                        }
                    }
                }
            }

            else -> {
                i++
            }
        }
    }
    return -1
}
