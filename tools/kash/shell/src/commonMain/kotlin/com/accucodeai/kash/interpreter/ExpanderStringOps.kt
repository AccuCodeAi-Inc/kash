package com.accucodeai.kash.interpreter

// Parameter-expansion string operators (#/##, %/%%, /pat/repl, ^^/,, @U/@L/@Q, substring) extracted from Expander.

internal fun Expander.applyStringOp(
    op: String,
    value: String,
    arg1: String,
    arg2: String?,
    /**
     * Variable name used as context for `@OP` transforms that need
     * access to the variable's attributes/storage (A/a/K/k). Defaults
     * to empty for the per-element spread path where the "name" is a
     * synthetic per-index slot.
     */
    name: String = "",
): String =
    when (op) {
        "^" -> caseMod(value, arg1, upper = true, all = false)
        "^^" -> caseMod(value, arg1, upper = true, all = true)
        "," -> caseMod(value, arg1, upper = false, all = false)
        ",," -> caseMod(value, arg1, upper = false, all = true)
        "~" -> caseToggle(value, arg1, all = false)
        "~~" -> caseToggle(value, arg1, all = true)
        "#" -> stripPrefix(value, arg1, longest = false)
        "##" -> stripPrefix(value, arg1, longest = true)
        "%" -> stripSuffix(value, arg1, longest = false)
        "%%" -> stripSuffix(value, arg1, longest = true)
        "/" -> patternSubst(value, arg1, arg2 ?: "", all = false, anchor = null)
        "//" -> patternSubst(value, arg1, arg2 ?: "", all = true, anchor = null)
        "/#" -> patternSubst(value, arg1, arg2 ?: "", all = false, anchor = '#')
        "/%" -> patternSubst(value, arg1, arg2 ?: "", all = false, anchor = '%')
        "@" -> transformOp(value, arg1, name)
        else -> value
    }

internal val perElementOps =
    setOf("^", "^^", ",", ",,", "~", "~~", "#", "##", "%", "%%", "/", "//", "/#", "/%", "@")

internal fun Expander.stripPrefix(
    value: String,
    pattern: String,
    longest: Boolean,
): String {
    if (pattern.isEmpty()) return value
    val n = CompiledGlob.compile(pattern).matchAtStart(value, longest = longest) ?: return value
    return value.substring(n)
}

internal fun Expander.stripSuffix(
    value: String,
    pattern: String,
    longest: Boolean,
): String {
    if (pattern.isEmpty()) return value
    val n = CompiledGlob.compile(pattern).matchAtEnd(value, longest = longest) ?: return value
    return value.substring(0, value.length - n)
}

internal fun Expander.patternSubst(
    value: String,
    pattern: String,
    repl: String,
    all: Boolean,
    anchor: Char?,
): String {
    // bash 5.2 `patsub_replacement` (default on): unescaped `&` in the
    // replacement expands to the matched substring; `\&` is a literal
    // `&`. Turn the shopt off for pre-5.2 literal-replacement semantics.
    val ampSub = shoptOptions["patsub_replacement"] ?: true

    fun expandRepl(matched: String): String {
        // Two-stage processing that mirrors bash's replacement-quoting
        // followed by string-replace, collapsed into one scan:
        //
        //  Stage A (per char of `repl`):
        //   - `CTLESC \`        → emit literal `\\` for stage B
        //   - `CTLESC &`        → emit literal `\&` for stage B
        //   - `CTLESC X` other  → emit X (strip the source marker)
        //   - plain X           → emit X as-is
        //
        //  Stage B (per emitted pair):
        //   - `\\`              → emit `\`
        //   - `\&`              → emit `&` literal (ampSub disabled)
        //   - `\X` other        → emit X (strip value-derived backslash)
        //   - bare `&` (ampSub) → emit matched substring
        //   - bare X            → emit X
        //
        // Net effect:
        //   - Source-derived `\'` (CTLESC + `'`): stage A emits `'`,
        //     stage B emits `'`. Surrounding `'` from sq processing
        //     contributes literal `'`. So `${test//"'"/\'\\\'\'}` →
        //     `'\''`-style requote works from the outer string shape,
        //     not from re-emitting `\` here.
        //   - Value-derived `\'` (e.g. from $var inside a dq context
        //     that retained the backslash): enters without CTLESC,
        //     stage A passes through, stage B sees `\X` and strips
        //     the backslash. So dq-retained `\'` correctly strips
        //     to `'`.
        if ('&' !in repl && '\\' !in repl && Sentinels.CTLESC !in repl) return repl
        // Stage A — convert CTLESC markers to the string-replace input form.
        val stage1 =
            if (Sentinels.CTLESC !in repl) {
                repl
            } else {
                val a = StringBuilder(repl.length)
                var i = 0
                while (i < repl.length) {
                    val c = repl[i]
                    if (c == Sentinels.CTLESC && i + 1 < repl.length) {
                        val next = repl[i + 1]
                        when (next) {
                            '\\' -> {
                                a.append('\\')
                                a.append('\\')
                            }

                            '&' -> {
                                a.append('\\')
                                a.append('&')
                            }

                            else -> {
                                a.append(next)
                            }
                        }
                        i += 2
                    } else {
                        a.append(c)
                        i++
                    }
                }
                a.toString()
            }
        // Stage B — string-replace over stage1.
        val sb = StringBuilder()
        var j = 0
        while (j < stage1.length) {
            val c = stage1[j]
            if (c == '\\' && j + 1 < stage1.length) {
                val next = stage1[j + 1]
                when {
                    next == '\\' -> {
                        sb.append('\\')
                        j += 2
                    }

                    next == '&' && ampSub -> {
                        sb.append('&')
                        j += 2
                    }

                    else -> {
                        // Strip the value-derived backslash; emit the
                        // char it preceded.
                        sb.append(next)
                        j += 2
                    }
                }
                continue
            }
            if (c == '&' && ampSub) sb.append(matched) else sb.append(c)
            j++
        }
        return sb.toString()
    }
    // bash: EMPTY pattern with `#` / `%` anchor matches the empty string
    // at the corresponding edge. So `${var/#/x}` prepends `x`,
    // `${var/%/x}` appends `x` — and `&` in the replacement still
    // expands to the (empty) matched substring under
    // patsub_replacement (bash man page §Parameter Expansion).
    if (pattern.isEmpty()) {
        return when (anchor) {
            '#' -> expandRepl("") + value
            '%' -> value + expandRepl("")
            else -> value
        }
    }
    val nocase = shoptOptions["nocasematch"] == true
    val g = CompiledGlob.compile(pattern, caseInsensitive = nocase)
    return when (anchor) {
        '#' -> {
            val n = g.matchAtStart(value, longest = true) ?: return value
            expandRepl(value.substring(0, n)) + value.substring(n)
        }

        '%' -> {
            val n = g.matchAtEnd(value, longest = true) ?: return value
            value.substring(0, value.length - n) + expandRepl(value.substring(value.length - n))
        }

        null -> {
            if (all) g.replaceAll(value, ::expandRepl) else g.replaceFirst(value, ::expandRepl)
        }

        else -> {
            error("patternSubst: unexpected anchor $anchor")
        }
    }
}

internal fun Expander.caseMod(
    value: String,
    pattern: String,
    upper: Boolean,
    all: Boolean,
): String {
    val effective = pattern.ifEmpty { "?" }
    val g = CompiledGlob.compile(effective)
    val chars = value.toCharArray()
    if (all) {
        // Bash applies the pattern to each character independently. The
        // hot path is `?` (any char) → match every char. Test per-char
        // against [g.matchesFull] — one regex apply, no allocation beyond
        // a 1-char substring.
        for (i in chars.indices) {
            if (g.matchesFull(chars[i].toString())) {
                chars[i] = if (upper) chars[i].uppercaseChar() else chars[i].lowercaseChar()
            }
        }
    } else {
        // `${var^pat}` / `${var,pat}` — only the first char, and only if
        // the pattern matches it. (Bash quirk: when pattern doesn't match
        // the first char, the operation is a no-op, not a continued search.)
        if (chars.isNotEmpty() && g.matchesFull(chars[0].toString())) {
            chars[0] = if (upper) chars[0].uppercaseChar() else chars[0].lowercaseChar()
        }
    }
    return chars.concatToString()
}

internal fun Expander.caseToggle(
    value: String,
    pattern: String,
    all: Boolean,
): String {
    val effective = pattern.ifEmpty { "?" }
    val g = CompiledGlob.compile(effective)
    val chars = value.toCharArray()

    fun flip(ch: Char): Char =
        when {
            ch.isUpperCase() -> ch.lowercaseChar()
            ch.isLowerCase() -> ch.uppercaseChar()
            else -> ch
        }
    if (all) {
        for (i in chars.indices) {
            if (g.matchesFull(chars[i].toString())) chars[i] = flip(chars[i])
        }
    } else {
        if (chars.isNotEmpty() && g.matchesFull(chars[0].toString())) {
            chars[0] = flip(chars[0])
        }
    }
    return chars.concatToString()
}

/**
 * `${var@OP}` transform operator. Implements the bash parameter-
 * transformation set:
 *
 *   Q  shell-quote the value so it can be re-parsed
 *   E  expand backslash escapes (same form as `$'...'`)
 *   L  lowercase whole value
 *   U  uppercase whole value
 *   u  uppercase first character only
 *   C  capitalize: upper first, lower the rest
 *   A  emit a `declare …` line that recreates the variable (delegated
 *      to [parameterTransformOp], which has access to the var's attrs
 *      and array storage)
 *   a  emit just the attribute-flag letters
 *   K  emit `[k]="v"`-style quoted key/value pairs for arrays
 *   k  same but unquoted
 *   P  prompt expansion — not yet implemented; returns raw value
 *
 * Unknown ops return the raw value so other parameter expansion at
 * least continues.
 */
internal fun Expander.transformOp(
    value: String,
    op: String,
    name: String = "",
): String =
    when (op) {
        "" -> {
            // bash: `${var@}` with no op letter is bad substitution.
            throw com.accucodeai.kash.parser.ParseException(
                "\${$name@}: bad substitution",
            )
        }

        "Q" -> {
            quoteForShell(value)
        }

        "E" -> {
            // @E: interpret backslash escapes the same way `$'...'`
            // ANSI-C quoting would. Inverse direction from `backslashEscape`
            // (which escapes INTO that form for @Q). For literal value
            // ` \t\n` (4 chars: space, backslash-t, backslash-n) → ` ` + TAB + LF.
            decodeAnsiCEscapes(value)
        }

        "L" -> {
            value.lowercase()
        }

        "U" -> {
            value.uppercase()
        }

        "u" -> {
            if (value.isEmpty()) value else value[0].uppercaseChar() + value.substring(1)
        }

        "A", "a", "K", "k" -> {
            parameterTransformOp(op, name) ?: ""
        }

        "P" -> {
            // bash 5.x: `${var@P}` expands PS1-style escapes via the
            // prompt-escape interpreter in PromptExpand.kt.
            decodePrompt(value)
        }

        else -> {
            // bash 5.x recognized `@` ops: Q E P A a K k U L u. Any other
            // op letter is "bad substitution." Surface as a runtime error.
            throw com.accucodeai.kash.parser.ParseException(
                "\${$name@$op}: bad substitution",
            )
        }
    }

internal fun Expander.quoteForShell(value: String): String {
    if (value.isEmpty()) return "''"
    // bash `@Q` quoting strategy:
    //   - No control chars (< 0x20), no backslash → POSIX single-quote
    //     form. Embedded `'` becomes `'\''` (close-quote, escaped
    //     quote, reopen) — the standard POSIX re-quote idiom.
    //   - Otherwise (control char or backslash present) → ANSI-C `$'...'`.
    // Only control characters force ANSI-C `$'...'` form. A literal
    // backslash by itself stays in POSIX `'...'` quoting — bash quotes
    // `\t\n` (literal characters) as `'\t\n'`, not `$'\\t\\n'`.
    val needsAnsiC = value.any { it.code < 0x20 || it.code == 0x7f }
    return if (!needsAnsiC) {
        "'" + value.replace("'", "'\\''") + "'"
    } else {
        "$'" + backslashEscape(value).replace("'", "\\'") + "'"
    }
}

internal fun Expander.backslashEscape(value: String): String {
    val sb = StringBuilder()
    for (c in value) {
        when (c) {
            '\\' -> {
                sb.append("\\\\")
            }

            '\n' -> {
                sb.append("\\n")
            }

            '\t' -> {
                sb.append("\\t")
            }

            '\r' -> {
                sb.append("\\r")
            }

            '\b' -> {
                sb.append("\\b")
            }

            '\u0007' -> {
                sb.append("\\a")
            }

            '\u000C' -> {
                sb.append("\\f")
            }

            '\u000B' -> {
                sb.append("\\v")
            }

            else -> {
                if (c.code < 0x20 || c.code == 0x7F) {
                    sb.append("\\")
                    sb.append(c.code.toString(8).padStart(3, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

/**
 * Decode `$'...'`-style ANSI-C backslash escapes inside [value].
 * Recognized: `\a \b \e \E \f \n \r \t \v \\ \' \" \?`, octal `\nnn`
 * (1–3 octal digits), hex `\xHH` (1–2 hex digits), unicode `\uHHHH` /
 * `\UHHHHHHHH`, control `\cX`.
 */
internal fun decodeAnsiCEscapes(value: String): String {
    if ('\\' !in value) return value
    val sb = StringBuilder(value.length)
    var i = 0
    val n = value.length
    while (i < n) {
        val c = value[i]
        if (c != '\\' || i + 1 >= n) {
            sb.append(c)
            i++
            continue
        }
        val esc = value[i + 1]
        when (esc) {
            'a' -> {
                sb.append('')
                i += 2
            }

            'b' -> {
                sb.append('\b')
                i += 2
            }

            'e', 'E' -> {
                sb.append('')
                i += 2
            }

            'f' -> {
                sb.append('')
                i += 2
            }

            'n' -> {
                sb.append('\n')
                i += 2
            }

            'r' -> {
                sb.append('\r')
                i += 2
            }

            't' -> {
                sb.append('\t')
                i += 2
            }

            'v' -> {
                sb.append('')
                i += 2
            }

            '\\' -> {
                sb.append('\\')
                i += 2
            }

            '\'' -> {
                sb.append('\'')
                i += 2
            }

            '"' -> {
                sb.append('"')
                i += 2
            }

            '?' -> {
                sb.append('?')
                i += 2
            }

            in '0'..'7' -> {
                var j = i + 1
                val end = minOf(i + 4, n)
                var v = 0
                while (j < end && value[j] in '0'..'7') {
                    v = (v shl 3) or (value[j].code - '0'.code)
                    j++
                }
                sb.append(v.toChar())
                i = j
            }

            'x' -> {
                var j = i + 2
                val end = minOf(i + 4, n)
                var v = 0
                var any = false
                while (j < end && (value[j].isDigit() || value[j] in 'a'..'f' || value[j] in 'A'..'F')) {
                    v = (v shl 4) or value[j].digitToInt(16)
                    j++
                    any = true
                }
                if (any) {
                    sb.append(v.toChar())
                    i = j
                } else {
                    sb.append('\\').append('x')
                    i += 2
                }
            }

            'u' -> {
                var j = i + 2
                val end = minOf(i + 6, n)
                var v = 0
                var any = false
                while (j < end && (value[j].isDigit() || value[j] in 'a'..'f' || value[j] in 'A'..'F')) {
                    v = (v shl 4) or value[j].digitToInt(16)
                    j++
                    any = true
                }
                if (any) {
                    sb.append(v.toChar())
                    i = j
                } else {
                    sb.append('\\').append('u')
                    i += 2
                }
            }

            'c' -> {
                if (i + 2 < n) {
                    val k = value[i + 2]
                    sb.append((k.code and 0x1f).toChar())
                    i += 3
                } else {
                    sb.append('\\').append('c')
                    i += 2
                }
            }

            else -> {
                // bash: unknown escape → emit backslash + char literally
                sb.append('\\').append(esc)
                i += 2
            }
        }
    }
    return sb.toString()
}

internal fun Expander.substring(
    value: String,
    offsetText: String,
    lengthText: String?,
    paramName: String = "",
): String {
    // Both operands are arithmetic expressions per bash spec —
    // `${z:${#z}-3:3}` needs the `${#z}-3` evaluated as `16-3=13`,
    // not parsed as the literal string "16-3" (which trims to 0).
    val offset =
        evalSliceArith(offsetText) ?: run {
            // Arith eval failed; record the bash-style diagnostic and
            // signal substring expansion failure with an empty result.
            // bash: `<name>: <arith-msg>` for offset failure.
            pendingDiagnostics +=
                "$paramName: ${offsetText.trim()}: arithmetic syntax error: operand expected (error token is \"${offsetText.trim()}\")"
            commandAbortFlag[0] = true
            return ""
        }
    val start = if (offset < 0) (value.length + offset).coerceAtLeast(0) else offset.coerceAtMost(value.length)
    if (lengthText == null) return value.substring(start)
    val len =
        evalSliceArith(lengthText) ?: run {
            pendingDiagnostics +=
                "$paramName: ${lengthText.trim()}: arithmetic syntax error: operand expected (error token is \"${lengthText.trim()}\")"
            commandAbortFlag[0] = true
            return ""
        }
    if (len < 0 && value.length + len < start) {
        // bash: `${var:OFFSET:LENGTH}` with negative length where the
        // computed end (value.length + len) is before start → emit
        // "substring expression < 0", showing the raw length expression.
        pendingDiagnostics += "${lengthText.trim()}: substring expression < 0"
        commandAbortFlag[0] = true
        return ""
    }
    val end = if (len < 0) (value.length + len).coerceAtLeast(start) else (start + len).coerceAtMost(value.length)
    return value.substring(start, end.coerceAtLeast(start))
}

/**
 * Evaluate a substring/slice offset or length expression — pre-expands
 * special sigils (`$#`, `$?`, `$$`, `$!`) that ArithEval doesn't natively
 * understand, then evaluates the result as an integer arith expression.
 * Returns null when ArithEval throws (caller decides whether to fall back,
 * emit a diagnostic, or abort). Empty input returns 0 (bash's documented
 * default for missing offset operands).
 *
 * Mirrors the Interpreter-side `preexpandArithmetic` for the
 * substring-offset/length context where the Expander has no
 * Interpreter-flag wiring.
 */
internal fun Expander.evalSliceArith(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    val expanded =
        trimmed
            .replace("\$#", positional.size.toString())
            .replace("\$?", lastExit.toString())
            .replace("\$\$", shellPid.toString())
            .replace("\$!", lastBgPid?.toString() ?: "0")
    return try {
        com.accucodeai.kash.interpreter
            .ArithEval(expanded, env)
            .evaluate()
            .toInt()
    } catch (_: Throwable) {
        null
    }
}
