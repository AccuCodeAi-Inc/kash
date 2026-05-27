package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio

/**
 * Bash `bind` — readline configuration surface.
 *
 * kash uses [com.accucodeai.kash.api.BasicLineEditor] (a hand-rolled
 * line editor on top of [com.accucodeai.kash.api.terminal.TerminalControl])
 * rather than GNU readline. To keep scripts and rc files that call `bind`
 * working, the builtin accepts the common forms and returns success:
 *
 *  - `bind -l`            — list readline function names we recognize.
 *  - `bind -p` / `bind -P` — list current bindings in re-readable form.
 *  - `bind -v` / `bind -V` — list readline variable settings.
 *  - `bind -s` / `bind -S` — macro / sequence listings (we have none).
 *  - `bind -q name`       — query a function binding (exit 0/1).
 *  - `bind -r seq`        — remove a binding (accepted, no-op).
 *  - `bind -f file`       — load bindings from a file (accepted, no-op).
 *  - `bind -m keymap ...` — operate on a keymap (accepted, no-op).
 *  - `bind -x "seq":cmd`  — execute-command binding (accepted, no-op).
 *  - `bind "seq":fn`      — function binding (accepted, no-op).
 *
 * Unknown options error out with the usage line, matching bash.
 */
internal suspend fun Interpreter.runBindIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var listFunctions = false
    var printBindings = false
    var printVariables = false
    var printMacros = false
    var query: String? = null
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
                listFunctions = true
            }

            a == "-p" || a == "-P" -> {
                printBindings = true
            }

            a == "-v" || a == "-V" -> {
                printVariables = true
            }

            a == "-s" || a == "-S" -> {
                printMacros = true
            }

            a == "-q" -> {
                val v =
                    args.getOrNull(i + 1) ?: run {
                        stdio.stderr.writeUtf8(
                            "${shellDiagPrefix()}bind: -q: option requires an argument\n",
                        )
                        return 2
                    }
                query = v
                i++
            }

            // No-op options that consume an argument.
            a == "-r" || a == "-f" || a == "-m" || a == "-u" || a == "-x" -> {
                if (args.getOrNull(i + 1) == null) {
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}bind: $a: option requires an argument\n",
                    )
                    return 2
                }
                i++
            }

            a == "-X" -> {
                // List execute-command bindings — we have none.
            }

            a.startsWith("-") && a != "-" -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}bind: $a: invalid option\n")
                stdio.stderr.writeUtf8(
                    "bind: usage: bind [-lpsvPSVX] [-m keymap] [-f filename] [-q name] [-u name] [-r keyseq] [-x keyseq:shell-command] [keyseq:readline-function or readline-command]\n",
                )
                return 2
            }

            else -> {
                positional.add(a)
            }
        }
        i++
    }

    if (query != null) {
        // We don't track function-to-keyseq bindings, so report "not bound".
        // Exit 0 if the function name is at least known; 1 otherwise.
        val knownFunctions = readlineFunctionList
        return if (query in knownFunctions) {
            stdio.stdout.writeUtf8("$query is not bound to any keys\n")
            0
        } else {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}bind: $query: unknown function name\n",
            )
            1
        }
    }

    if (listFunctions) {
        for (fn in readlineFunctionList) stdio.stdout.writeUtf8(fn + "\n")
        return 0
    }

    if (printBindings) {
        // No tracked bindings yet — emit a stub that mirrors bash's
        // "all bindings unbound" output shape so callers parsing the
        // result aren't blindsided. Use a minimal but well-formed set.
        for (fn in readlineFunctionList.take(5)) {
            stdio.stdout.writeUtf8("# $fn (not bound)\n")
        }
        return 0
    }

    if (printVariables) {
        // kash doesn't expose readline variables; the only meaningful
        // thing to report is the lone synthetic value below so callers
        // parsing the result see well-formed `var: value` lines.
        stdio.stdout.writeUtf8("editing-mode: emacs\n")
        return 0
    }

    if (printMacros) {
        // No macros tracked.
        return 0
    }

    // Bare `bind seq:func` form — accept silently. The line editor
    // currently has fixed key bindings and doesn't honor user overrides.
    return 0
}

/**
 * Names of readline-style editing functions kash's line editor mimics.
 * Used by `bind -l` and `bind -q`. The set is small on purpose — only
 * the operations BasicLineEditor actually performs are listed, so
 * `bind -q` honestly reflects what's wired.
 */
private val readlineFunctionList: List<String> =
    listOf(
        "abort",
        "accept-line",
        "backward-char",
        "backward-delete-char",
        "backward-kill-word",
        "beginning-of-line",
        "clear-screen",
        "complete",
        "delete-char",
        "end-of-line",
        "forward-char",
        "kill-line",
        "kill-whole-line",
        "next-history",
        "previous-history",
        "self-insert",
    )
