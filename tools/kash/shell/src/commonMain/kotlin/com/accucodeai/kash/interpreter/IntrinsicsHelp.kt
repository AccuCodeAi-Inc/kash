package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.matchGlob
import com.accucodeai.kash.interpreter.Interpreter.Stdio

/**
 * Bash `help [-dms] [pattern ...]`. Prints synopsis and description for
 * each builtin whose name matches one of the patterns.
 *
 * Topic text lives in [HelpTopics] — original, intentionally concise
 * vibe text per builtin, not lifted from bash's GPL-3 help strings.
 *
 * Flags:
 *  - `-d` — print only the short description.
 *  - `-s` — print only the synopsis.
 *  - `-m` — man-page-style block (NAME / SYNOPSIS / DESCRIPTION).
 *
 * Patterns are bash glob patterns over topic names. With no pattern,
 * lists every topic with its short description (two-column layout).
 */
internal suspend fun Interpreter.runHelpIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var modeDesc = false
    var modeSyn = false
    var modeMan = false
    val patterns = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--" -> {
                i++
                while (i < args.size) patterns.add(args[i++])
                break
            }

            a == "-d" -> {
                modeDesc = true
            }

            a == "-s" -> {
                modeSyn = true
            }

            a == "-m" -> {
                modeMan = true
            }

            a.startsWith("-") && a.length > 1 && !a.drop(1).any { it !in "dsm" } -> {
                for (ch in a.drop(1)) {
                    when (ch) {
                        'd' -> modeDesc = true
                        's' -> modeSyn = true
                        'm' -> modeMan = true
                    }
                }
            }

            a.startsWith("-") && a != "-" -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}help: $a: invalid option\n")
                stdio.stderr.writeUtf8("help: usage: help [-dms] [pattern ...]\n")
                return 2
            }

            else -> {
                patterns.add(a)
            }
        }
        i++
    }

    if (patterns.isEmpty()) {
        // Two-column listing of every topic.
        val entries = HelpTopics.all.values.toList()
        val nameWidth = entries.maxOf { it.name.length }
        for (t in entries) {
            val padded = t.name.padEnd(nameWidth + 2)
            stdio.stdout.writeUtf8("$padded${t.short}\n")
        }
        return 0
    }

    // Match each pattern against the topic names. Bash uses glob semantics;
    // we honor literal exact match first, then glob fallback.
    var anyMatch = false
    var exit = 0
    for (pat in patterns) {
        val matched =
            HelpTopics.all.values.filter { topic ->
                topic.name == pat || matchGlob(pat, topic.name)
            }
        if (matched.isEmpty()) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}help: no help topics match `$pat'.  Try `help help' or `man -k $pat' or `info $pat'.\n",
            )
            exit = 1
            continue
        }
        anyMatch = true
        for (t in matched) {
            when {
                modeMan -> {
                    writeManPage(t, stdio)
                }

                modeSyn -> {
                    stdio.stdout.writeUtf8(t.synopsis + "\n")
                }

                modeDesc -> {
                    stdio.stdout.writeUtf8("${t.name} - ${t.short}\n")
                }

                else -> {
                    stdio.stdout.writeUtf8(t.synopsis + "\n")
                    // bash wraps long body to terminal width; we ship the
                    // text pre-wrapped at source authoring time and emit
                    // verbatim with a leading 4-space indent.
                    for (line in t.long.split('\n')) {
                        stdio.stdout.writeUtf8("    $line\n")
                    }
                }
            }
        }
    }
    return if (anyMatch) 0 else exit
}

private suspend fun writeManPage(
    t: HelpTopic,
    stdio: Stdio,
) {
    stdio.stdout.writeUtf8("NAME\n    ${t.name} - ${t.short}\n\n")
    stdio.stdout.writeUtf8("SYNOPSIS\n    ${t.synopsis}\n\n")
    stdio.stdout.writeUtf8("DESCRIPTION\n    ${t.long}\n\n")
    stdio.stdout.writeUtf8("SEE ALSO\n    bash(1), kash documentation.\n\n")
    stdio.stdout.writeUtf8("IMPLEMENTATION\n    kash shell — Anthropic Claude / kash project.\n")
}
