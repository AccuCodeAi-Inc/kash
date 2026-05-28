package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.interpreter.Interpreter.Stdio

private const val HISTORY_USAGE =
    "history: usage: history [-c] [-d offset] [n] or history -anrw [filename] or history -ps arg [arg...]"

/**
 * Bash `history` builtin. Implements the common read/print/clear/append
 * operations against [Interpreter.history]; intentionally skips history
 * expansion (`-p '!!'`), which is a separate big feature.
 *
 * Auto-loads `$HISTFILE` on first invocation that isn't `-c` or `-r`,
 * matching bash's lazy-load when an interactive shell first touches the
 * history. The flag [Interpreter.historyLoaded] guards against re-load.
 */
internal suspend fun Interpreter.runHistoryIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var clear = false
    var append = false
    var read = false
    var write = false
    var readNew = false
    var print = false
    var saveStr = false
    var deleteOffset: Int? = null
    var positional = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--" -> {
                i++
                break
            }

            a == "-c" -> {
                clear = true
                i++
            }

            a == "-a" -> {
                append = true
                i++
            }

            a == "-r" -> {
                read = true
                i++
            }

            a == "-w" -> {
                write = true
                i++
            }

            a == "-n" -> {
                readNew = true
                i++
            }

            a == "-p" -> {
                print = true
                i++
            }

            a == "-s" -> {
                saveStr = true
                i++
            }

            a == "-d" -> {
                val v = args.getOrNull(i + 1)
                val n = v?.toIntOrNull()
                if (n == null) {
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}history: ${v ?: ""}: numeric argument required\n",
                    )
                    return 1
                }
                deleteOffset = n
                i += 2
            }

            a.startsWith("-") && a != "-" -> {
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}history: ${a.drop(1).take(1).let { "-$it" }}: invalid option\n",
                )
                stdio.stderr.writeUtf8("$HISTORY_USAGE\n")
                return 2
            }

            else -> {
                positional.add(a)
                i++
            }
        }
    }

    // Bash: -a, -n, -r, -w are mutually exclusive.
    val fileOps = listOf(append, readNew, read, write).count { it }
    if (fileOps > 1) {
        stdio.stderr.writeUtf8(
            "${shellDiagPrefix()}history: cannot use more than one of -anrw\n",
        )
        return 2
    }

    if (clear) {
        history.clear()
        historyLoaded = true // explicit clear counts as "loaded" — don't auto-reload.
        if (!append && !read && !write && !readNew && !saveStr && deleteOffset == null) {
            return 0
        }
    }

    if (saveStr) {
        // `history -s WORDS...`: join args with spaces, append as one entry.
        history.addLast(positional.joinToString(" "))
        return 0
    }

    if (print) {
        // History expansion not implemented. Bash succeeds silently on no
        // args; for non-empty args we'd need to interpret `!!`/`!N`/`!str`.
        // Emit the arg verbatim — matches bash's "no expansion happened"
        // path closely enough for most tests.
        for (arg in positional) {
            stdio.stdout.writeUtf8(arg + "\n")
        }
        return 0
    }

    if (deleteOffset != null) {
        val idx = deleteOffset - 1
        if (idx < 0 || idx >= history.size) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}history: $deleteOffset: history position out of range\n",
            )
            return 1
        }
        history.removeAt(idx)
        return 0
    }

    if (read || readNew) {
        val path = positional.firstOrNull() ?: env["HISTFILE"]
        if (path == null) return 0
        return loadHistoryFromFile(path, stdio, append = readNew)
    }

    if (write) {
        val path = positional.firstOrNull() ?: env["HISTFILE"]
        if (path == null) return 0
        return writeHistoryToFile(path, stdio, appendOnly = false)
    }

    if (append) {
        val path = positional.firstOrNull() ?: env["HISTFILE"]
        if (path == null) return 0
        return writeHistoryToFile(path, stdio, appendOnly = true)
    }

    // Print mode. Auto-load HISTFILE if not yet loaded.
    if (!historyLoaded) {
        val hf = env["HISTFILE"]
        if (hf != null) {
            // Best-effort load; failures are silent (bash also tolerates a
            // missing HISTFILE on auto-load).
            loadHistoryFromFile(hf, stdio, append = true, silentMissing = true)
        }
        historyLoaded = true
    }

    val limit = positional.firstOrNull()?.toIntOrNull()
    val start = if (limit != null && limit < history.size) history.size - limit else 0
    for (j in start until history.size) {
        val n = (j + 1).toString().padStart(5)
        stdio.stdout.writeUtf8("$n  ${history[j]}\n")
    }
    return 0
}

private suspend fun Interpreter.loadHistoryFromFile(
    path: String,
    stdio: Stdio,
    append: Boolean,
    silentMissing: Boolean = false,
): Int {
    val resolved = Paths.resolve(cwd, path)
    if (!process.fs.exists(resolved)) {
        if (silentMissing) return 0
        stdio.stderr.writeUtf8("${shellDiagPrefix()}history: $path: cannot read: No such file or directory\n")
        return 1
    }
    val text =
        try {
            process.fs
                .readBytes(resolved)
                .decodeToString()
        } catch (t: Throwable) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}history: $path: ${t.message ?: "read error"}\n",
            )
            return 1
        }
    if (!append) history.clear()
    for (line in text.split('\n')) {
        if (line.isEmpty()) continue
        history.addLast(line)
    }
    historyLoaded = true
    return 0
}

private suspend fun Interpreter.writeHistoryToFile(
    path: String,
    stdio: Stdio,
    appendOnly: Boolean,
): Int {
    val resolved = Paths.resolve(cwd, path)
    val payload = history.joinToString("\n", postfix = if (history.isNotEmpty()) "\n" else "")
    try {
        if (appendOnly && process.fs.exists(resolved)) {
            val prior =
                process.fs
                    .readBytes(resolved)
                    .decodeToString()
            process.fs.writeBytes(resolved, (prior + payload).encodeToByteArray())
        } else {
            process.fs.writeBytes(resolved, payload.encodeToByteArray())
        }
    } catch (t: Throwable) {
        stdio.stderr.writeUtf8(
            "${shellDiagPrefix()}history: $path: ${t.message ?: "cannot write"}\n",
        )
        return 1
    }
    return 0
}
