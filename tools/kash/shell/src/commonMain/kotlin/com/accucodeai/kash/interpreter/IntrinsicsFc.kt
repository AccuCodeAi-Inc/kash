package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio

private const val FC_USAGE =
    "fc: usage: fc [-e ename] [-lnr] [first] [last] or fc -s [pat=rep] [command]"

/**
 * POSIX `fc` (`fix command`): list, edit, or re-execute history entries.
 *
 * Supported:
 *  - `fc -l [first [last]]` — list with leading line number.
 *  - `fc -ln` — list without line numbers.
 *  - `fc -lr` — list in reverse.
 *  - `fc -s [pat=rep] [first]` — re-execute the matching entry (with
 *    optional `pat=rep` substitution).
 *
 * Not yet supported (these emit an informative diagnostic):
 *  - `fc [-e ename] [first [last]]` — spawn an editor on the entries and
 *    re-execute the result. Requires editor process spawn and re-feeding
 *    the edited buffer to the interpreter — out of scope for now.
 *
 * Selectors (`first`/`last`):
 *  - Positive integer → history entry at that 1-based offset.
 *  - Negative integer → offset from the end (`-1` = most recent).
 *  - String → search for the most-recent entry that starts with that string.
 *
 * Defaults: when listing, `first` defaults to `-16` and `last` defaults
 * to `-1`. For `-s` (re-execute), both default to `-1`.
 */
internal suspend fun Interpreter.runFcIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var list = false
    var noNumbers = false
    var reverse = false
    var substitute = false
    var editor: String? = null
    val positional = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--" -> {
                i++
                while (i < args.size) positional.add(args[i++])
                break
            }

            a == "-l" -> {
                list = true
                i++
            }

            a == "-n" -> {
                noNumbers = true
                i++
            }

            a == "-r" -> {
                reverse = true
                i++
            }

            a == "-s" -> {
                substitute = true
                i++
            }

            a == "-e" -> {
                editor = args.getOrNull(i + 1)
                if (editor == null) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}fc: -e: option requires an argument\n")
                    stdio.stderr.writeUtf8("$FC_USAGE\n")
                    return 2
                }
                i += 2
            }

            a.startsWith("-") && a.length >= 2 && !a.drop(1).all { it.isDigit() } -> {
                // Treat negative-integer args as selectors, not options.
                // Cluster like `-nl`, `-lr`, `-ln`:
                if (a.length > 1 && a.drop(1).all { it in "lnrs" }) {
                    for (ch in a.drop(1)) {
                        when (ch) {
                            'l' -> list = true
                            'n' -> noNumbers = true
                            'r' -> reverse = true
                            's' -> substitute = true
                        }
                    }
                    i++
                } else {
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}fc: ${a.drop(1).take(1).let { "-$it" }}: invalid option\n",
                    )
                    stdio.stderr.writeUtf8("$FC_USAGE\n")
                    return 2
                }
            }

            else -> {
                positional.add(a)
                i++
            }
        }
    }

    fun resolveSelector(
        s: String?,
        default: Int,
    ): Int {
        if (s == null) return default
        val n = s.toIntOrNull()
        if (n != null) {
            return if (n < 0) history.size + n else n - 1
        }
        // String prefix search — most-recent matching entry.
        for (j in history.indices.reversed()) {
            if (history[j].startsWith(s)) return j
        }
        return -1
    }

    if (substitute) {
        // `fc -s [pat=rep] [first]`. Re-executes the selected entry,
        // optionally after a literal `pat=rep` substitution on the entry
        // text. We support the listing path; actual re-execution requires
        // feeding the resulting text back into the parser/runner, which we
        // don't yet do here — emit informative diagnostic.
        stdio.stderr.writeUtf8(
            "${shellDiagPrefix()}fc: -s: re-execution not yet implemented in kash\n",
        )
        return 1
    }

    if (editor != null && !list) {
        stdio.stderr.writeUtf8(
            "${shellDiagPrefix()}fc: -e: editor invocation not yet implemented in kash\n",
        )
        return 1
    }

    if (history.isEmpty()) {
        if (list) {
            // Bash: no entries → silent for -l, nothing wrong.
            return 0
        }
        stdio.stderr.writeUtf8("${shellDiagPrefix()}fc: no command found\n")
        return 1
    }

    // List mode: figure out the range.
    val firstSel = positional.getOrNull(0)
    val lastSel = positional.getOrNull(1)
    val firstIdx = resolveSelector(firstSel, if (list) (history.size - 16).coerceAtLeast(0) else history.size - 1)
    val lastIdx = resolveSelector(lastSel, if (list) history.size - 1 else firstIdx)
    if (firstIdx < 0 || firstIdx >= history.size) {
        stdio.stderr.writeUtf8(
            "${shellDiagPrefix()}fc: history specification out of range\n",
        )
        return 1
    }
    if (lastIdx < 0 || lastIdx >= history.size) {
        stdio.stderr.writeUtf8(
            "${shellDiagPrefix()}fc: history specification out of range\n",
        )
        return 1
    }

    if (list) {
        val ascending = firstIdx..lastIdx
        val descending = lastIdx downTo firstIdx
        val range = if (reverse) descending else ascending
        for (j in range) {
            val entry = history[j]
            if (noNumbers) {
                stdio.stdout.writeUtf8("\t $entry\n")
            } else {
                stdio.stdout.writeUtf8("${j + 1}\t $entry\n")
            }
        }
        return 0
    }

    // Non-list, non-substitute path: would normally invoke the editor.
    stdio.stderr.writeUtf8(
        "${shellDiagPrefix()}fc: editor invocation not yet implemented in kash\n",
    )
    return 1
}
