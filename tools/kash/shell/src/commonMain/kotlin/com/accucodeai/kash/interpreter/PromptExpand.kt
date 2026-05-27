package com.accucodeai.kash.interpreter

import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

// PS1-style prompt escape interpreter for `${var@P}`.
// Bash reference: ::prompt decoder (GPL — behavior referenced,
// not transcribed). This is a pure string→string rewrite over an already-
// expanded value; it does not invoke the lexer or parser.

internal fun Expander.decodePrompt(input: String): String {
    if (input.isEmpty()) return input
    // Bash decodes escapes first, then runs parameter/command/arithmetic
    // expansion over the result. We do escapes here and a minimal
    // parameter-expansion pass at the end (just `$NAME` and `${NAME}` —
    // operators inside `${...}` are not common in PS1 strings).
    val out = StringBuilder(input.length + 8)
    var i = 0
    val n = input.length
    val posix = posixMode
    while (i < n) {
        val c = input[i]
        // POSIX mode: bare `!` is the history-number expansion (`!!` is a
        // literal `!`). Outside POSIX mode, only `\!` triggers it.
        if (posix && c == '!') {
            if (i + 1 < n && input[i + 1] == '!') {
                out.append('!')
                i += 2
                continue
            }
            out.append('1')
            i++
            continue
        }
        if (c != '\\') {
            out.append(c)
            i++
            continue
        }
        if (i + 1 >= n) {
            out.append('\\')
            i++
            continue
        }
        val esc = input[i + 1]
        when (esc) {
            'a' -> {
                out.append('')
                i += 2
            }

            'e', 'E' -> {
                out.append('')
                i += 2
            }

            'n' -> {
                out.append('\n')
                i += 2
            }

            'r' -> {
                out.append('\r')
                i += 2
            }

            '\\' -> {
                out.append('\\')
                i += 2
            }

            '[', ']' -> {
                // Readline non-printing markers — bash's @P emits these as
                // `\001`/`\002` when a line editor is active (the markers
                // tell readline "the wrapped chars don't affect cursor
                // position"). When no editor is active they're stripped:
                // `@P` is being asked for a display string, and the
                // markers would just leak through as control bytes.
                if (lineEditorActive()) {
                    out.append(if (esc == '[') '' else '')
                }
                i += 2
            }

            '$' -> {
                // bash: '#' for euid==0, else '$'. We don't expose euid; '$'.
                out.append('$')
                i += 2
            }

            'h' -> {
                val host = env["HOSTNAME"].orEmpty()
                val dot = host.indexOf('.')
                out.append(if (dot >= 0) host.substring(0, dot) else host)
                i += 2
            }

            'H' -> {
                out.append(env["HOSTNAME"].orEmpty())
                i += 2
            }

            'u' -> {
                out.append(env["USER"] ?: env["LOGNAME"] ?: "")
                i += 2
            }

            's' -> {
                val sh = env["SHELL"].orEmpty()
                val slash = sh.lastIndexOf('/')
                out.append(
                    if (slash >= 0) {
                        sh.substring(slash + 1)
                    } else if (sh.isNotEmpty()) {
                        sh
                    } else {
                        "bash"
                    },
                )
                i += 2
            }

            'v' -> {
                val ver = env["BASH_VERSION"].orEmpty()
                val parts = ver.split('.')
                out.append(if (parts.size >= 2) parts[0] + "." + parts[1] else ver)
                i += 2
            }

            'V' -> {
                out.append(env["BASH_VERSION"].orEmpty())
                i += 2
            }

            'w' -> {
                out.append(formatCwd(full = true))
                i += 2
            }

            'W' -> {
                out.append(formatCwd(full = false))
                i += 2
            }

            'j' -> {
                out.append("0")
                i += 2
            }

            'l' -> {
                out.append("tty")
                i += 2
            }

            '!' -> {
                out.append("1")
                i += 2
            }

            '#' -> {
                out.append("0")
                i += 2
            }

            'd' -> {
                // bash man page PROMPTING: "the date in 'Weekday Month Date' format"
                out.append(strftimeFormat("%a %b %e"))
                i += 2
            }

            't' -> {
                // bash man page PROMPTING: "the current time in 24-hour HH:MM:SS"
                out.append(strftimeFormat("%H:%M:%S"))
                i += 2
            }

            'T' -> {
                // bash man page PROMPTING: "the current time in 12-hour HH:MM:SS"
                out.append(strftimeFormat("%I:%M:%S"))
                i += 2
            }

            '@' -> {
                // bash man page PROMPTING: "the current time in 12-hour am/pm"
                out.append(strftimeFormat("%I:%M %p"))
                i += 2
            }

            'A' -> {
                // bash man page PROMPTING: "the current time in 24-hour HH:MM"
                out.append(strftimeFormat("%H:%M"))
                i += 2
            }

            'D' -> {
                // bash man page PROMPTING: `\D{format}` — strftime-formatted
                // date. Empty format → "Weekday Month Date" (locale-default).
                if (i + 2 < n && input[i + 2] == '{') {
                    val close = input.indexOf('}', i + 3)
                    if (close >= 0) {
                        val fmt = input.substring(i + 3, close)
                        val effective = fmt.ifEmpty { "%a %b %e %H:%M:%S" }
                        out.append(strftimeFormat(effective))
                        i = close + 1
                        continue
                    }
                }
                out.append('\\').append('D')
                i += 2
            }

            in '0'..'7' -> {
                // 1–3 octal digits → single byte (treated as codepoint).
                var j = i + 1
                val end = minOf(i + 4, n)
                var value = 0
                while (j < end && input[j] in '0'..'7') {
                    value = (value shl 3) or (input[j].code - '0'.code)
                    j++
                }
                out.append(value.toChar())
                i = j
            }

            else -> {
                // Unknown escape: bash drops the backslash and keeps the
                // character.
                out.append(esc)
                i += 2
            }
        }
    }
    return expandPromptVars(out.toString())
}

/**
 * Per-character parameter-expansion pass on the prompt's decoded text.
 * Handles bare `$NAME` and `\${NAME}` plus the `:-default` / `:+alt` /
 * `##*PAT` / `%%PAT*` / `#PAT` / `%PAT` operators that are common in
 * real-world prompts (venv-aware, git-aware PS1s).
 *
 * Reference: bash man page PROMPTING — "After the string is decoded,
 * it is expanded via parameter expansion, command substitution,
 * arithmetic expansion, and quote removal." Command and arithmetic
 * substitution are NOT yet implemented here (they'd require parsing
 * the prompt as full shell, which destabilized `bash/new-exp`); the
 * common parameter operators cover the practical use cases.
 */
private fun Expander.expandPromptVars(s: String): String {
    if ('$' !in s) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '$' || i + 1 >= s.length) {
            sb.append(c)
            i++
            continue
        }
        val next = s[i + 1]
        if (next == '{') {
            val close = findMatchingBrace(s, i + 1)
            if (close < 0) {
                sb.append(c)
                i++
                continue
            }
            val body = s.substring(i + 2, close)
            sb.append(evalPromptBraceParam(body))
            i = close + 1
        } else if (next.isLetter() || next == '_') {
            var j = i + 1
            while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
            sb.append(env[s.substring(i + 1, j)].orEmpty())
            i = j
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

/** Find matching `}` for a `${` opener at [openBrace], tracking nested braces. */
private fun findMatchingBrace(
    s: String,
    openBrace: Int,
): Int {
    var depth = 1
    var i = openBrace + 1
    while (i < s.length) {
        when (s[i]) {
            '{' -> {
                depth++
            }

            '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

/**
 * Evaluate `NAME[op[ARG]]` from a `\${...}` body, where NAME is a
 * shell-identifier prefix and the remainder (if any) is one of the
 * common parameter expansion operators. Falls back to bare-name lookup
 * if the operator is unrecognized.
 *
 * Operators supported:
 *  - `:-WORD` / `-WORD` — default value when name is unset/empty.
 *  - `:+WORD` / `+WORD` — alternative value when name IS set.
 *  - `:?WORD` / `?WORD` — error when name is unset (we just substitute WORD).
 *  - `#PAT` — strip shortest prefix matching PAT.
 *  - `##PAT` — strip longest prefix matching PAT.
 *  - `%PAT` — strip shortest suffix matching PAT.
 *  - `%%PAT` — strip longest suffix matching PAT.
 */
private fun Expander.evalPromptBraceParam(body: String): String {
    // Find end of NAME (letters/digits/underscore).
    var k = 0
    while (k < body.length && (body[k].isLetterOrDigit() || body[k] == '_')) k++
    if (k == 0) {
        // Body doesn't start with a name (e.g. `${#X}`, `${!X}`, etc.).
        // Conservative: emit nothing.
        return ""
    }
    val name = body.substring(0, k)
    val rest = body.substring(k)
    val value = env[name]
    if (rest.isEmpty()) return value.orEmpty()
    return when {
        rest.startsWith(":-") -> if (value.isNullOrEmpty()) expandPromptVars(rest.substring(2)) else value
        rest.startsWith("-") -> if (value == null) expandPromptVars(rest.substring(1)) else value
        rest.startsWith(":+") -> if (value.isNullOrEmpty()) "" else expandPromptVars(rest.substring(2))
        rest.startsWith("+") -> if (value == null) "" else expandPromptVars(rest.substring(1))
        rest.startsWith(":?") -> if (value.isNullOrEmpty()) expandPromptVars(rest.substring(2)) else value
        rest.startsWith("?") -> if (value == null) expandPromptVars(rest.substring(1)) else value
        rest.startsWith("##") -> stripPrefixGlob(value.orEmpty(), expandPromptVars(rest.substring(2)), longest = true)
        rest.startsWith("#") -> stripPrefixGlob(value.orEmpty(), expandPromptVars(rest.substring(1)), longest = false)
        rest.startsWith("%%") -> stripSuffixGlob(value.orEmpty(), expandPromptVars(rest.substring(2)), longest = true)
        rest.startsWith("%") -> stripSuffixGlob(value.orEmpty(), expandPromptVars(rest.substring(1)), longest = false)
        else -> value.orEmpty()
    }
}

/** Strip the [shortest|longest] prefix of [s] matching glob [pat]. */
private fun stripPrefixGlob(
    s: String,
    pat: String,
    longest: Boolean,
): String {
    if (pat.isEmpty()) return s
    val range = if (longest) s.indices.reversed() else s.indices
    for (i in range) {
        if (globMatch(s.substring(0, i + 1), pat)) return s.substring(i + 1)
    }
    return s
}

/** Strip the [shortest|longest] prefix of [s] matching glob [pat] from the end. */
private fun stripSuffixGlob(
    s: String,
    pat: String,
    longest: Boolean,
): String {
    if (pat.isEmpty()) return s
    val range = if (longest) 0 until s.length else (s.length - 1) downTo 0
    for (i in range) {
        if (globMatch(s.substring(i), pat)) return s.substring(0, i)
    }
    return s
}

/**
 * Simple glob matcher for prompt-parameter pattern operators. Supports
 * `*` (zero or more), `?` (one char), and literals. Bracket expressions
 * are not handled — the prompt-context use cases are basename / prefix
 * strips, which only need `*`.
 */
private fun globMatch(
    s: String,
    pat: String,
): Boolean {
    fun matches(
        si: Int,
        pi: Int,
    ): Boolean {
        if (pi == pat.length) return si == s.length
        return when (pat[pi]) {
            '*' -> {
                // Greedy: try matching zero, one, ... chars.
                for (k in si..s.length) {
                    if (matches(k, pi + 1)) return true
                }
                false
            }

            '?' -> {
                si < s.length && matches(si + 1, pi + 1)
            }

            else -> {
                si < s.length && s[si] == pat[pi] && matches(si + 1, pi + 1)
            }
        }
    }
    return matches(0, 0)
}

/**
 * Format `time` (default: current wall-clock) against a strftime-style
 * [format] string. Supports the subset of codes documented in the bash
 * man page PROMPTING section for `\D{format}` and the time-of-day
 * escapes (`\d \t \T \@ \A`).
 *
 * Codes implemented: `%Y %y %m %d %e %H %I %M %S %T %R %F %D %p %a %A
 * %b %B %j %u %w %s %Z %n %t %%`. Anything else is emitted literally
 * (matching most libc strftime implementations).
 *
 * Reference: POSIX `strftime(3)` + bash man page PROMPTING.
 */
private fun Expander.strftimeFormat(format: String): String {
    val now = wallClock.now()
    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
    val ldt = now.toLocalDateTime(tz)
    val sb = StringBuilder(format.length)
    var i = 0
    while (i < format.length) {
        val c = format[i]
        if (c != '%' || i + 1 >= format.length) {
            sb.append(c)
            i++
            continue
        }
        val code = format[i + 1]
        when (code) {
            'Y' -> {
                sb.append(ldt.year.toString().padStart(4, '0'))
            }

            'y' -> {
                sb.append((ldt.year % 100).toString().padStart(2, '0'))
            }

            'm' -> {
                sb.append(
                    ldt.month.number
                        .toString()
                        .padStart(2, '0'),
                )
            }

            'd' -> {
                sb.append(ldt.day.toString().padStart(2, '0'))
            }

            'e' -> {
                sb.append(ldt.day.toString().padStart(2, ' '))
            }

            // space-padded
            'H' -> {
                sb.append(ldt.hour.toString().padStart(2, '0'))
            }

            'I' -> {
                // 12-hour, 1-12.
                val h12 = ((ldt.hour + 11) % 12) + 1
                sb.append(h12.toString().padStart(2, '0'))
            }

            'M' -> {
                sb.append(ldt.minute.toString().padStart(2, '0'))
            }

            'S' -> {
                sb.append(ldt.second.toString().padStart(2, '0'))
            }

            'T' -> {
                sb.append(
                    "${ldt.hour.toString().padStart(
                        2,
                        '0',
                    )}:${ldt.minute.toString().padStart(2, '0')}:${ldt.second.toString().padStart(2, '0')}",
                )
            }

            'R' -> {
                sb.append("${ldt.hour.toString().padStart(2, '0')}:${ldt.minute.toString().padStart(2, '0')}")
            }

            'F' -> {
                sb.append(
                    "${ldt.year.toString().padStart(
                        4,
                        '0',
                    )}-${ldt.month.number.toString().padStart(2, '0')}-${ldt.day.toString().padStart(2, '0')}",
                )
            }

            'D' -> {
                sb.append(
                    "${
                        ldt.month.number.toString().padStart(
                            2,
                            '0',
                        )}/${ldt.day.toString().padStart(2, '0')}/${(ldt.year % 100).toString().padStart(2, '0')}",
                )
            }

            'p' -> {
                sb.append(if (ldt.hour < 12) "AM" else "PM")
            }

            'a' -> {
                sb.append(DAY_NAMES_SHORT[(ldt.dayOfWeek.ordinal + 1) - 1])
            }

            'A' -> {
                sb.append(DAY_NAMES_FULL[(ldt.dayOfWeek.ordinal + 1) - 1])
            }

            'b' -> {
                sb.append(MONTH_NAMES_SHORT[ldt.month.number - 1])
            }

            'B' -> {
                sb.append(MONTH_NAMES_FULL[ldt.month.number - 1])
            }

            'j' -> {
                sb.append(ldt.dayOfYear.toString().padStart(3, '0'))
            }

            'u' -> {
                sb.append((ldt.dayOfWeek.ordinal + 1).toString())
            }

            // ISO: Mon=1..Sun=7
            'w' -> {
                // POSIX: Sun=0..Sat=6.
                val w = (ldt.dayOfWeek.ordinal + 1) % 7
                sb.append(w.toString())
            }

            's' -> {
                sb.append(now.epochSeconds.toString())
            }

            'Z' -> {
                sb.append(tz.id)
            }

            'n' -> {
                sb.append('\n')
            }

            't' -> {
                sb.append('\t')
            }

            '%' -> {
                sb.append('%')
            }

            else -> {
                // Unknown code — emit `%X` literally (matches libc).
                sb.append('%').append(code)
            }
        }
        i += 2
    }
    return sb.toString()
}

private val DAY_NAMES_SHORT =
    arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DAY_NAMES_FULL =
    arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val MONTH_NAMES_SHORT =
    arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTH_NAMES_FULL =
    arrayOf(
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December",
    )

private fun Expander.formatCwd(full: Boolean): String {
    val pwd = env["PWD"].orEmpty()
    val home = env["HOME"].orEmpty()
    val shown =
        when {
            home.isEmpty() -> pwd
            pwd == home -> "~"
            home.isNotEmpty() && pwd.startsWith("$home/") -> "~" + pwd.substring(home.length)
            else -> pwd
        }
    if (full) return shown
    if (shown == "/" || shown == "~") return shown
    val slash = shown.lastIndexOf('/')
    return if (slash >= 0) shown.substring(slash + 1) else shown
}
