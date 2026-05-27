package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import kotlinx.io.Buffer
import kotlinx.io.readString

/**
 * Read one delimiter-terminated chunk, reporting whether the delimiter was
 * found. Bash's mapfile distinguishes a complete "line" (ending in `delim`)
 * from a final partial line at EOF — the latter is stored verbatim with no
 * synthesized delimiter. [readUtf8LineOrNull] hides that distinction, so
 * mapfile reimplements its own read loop.
 */
private suspend fun readUtf8LineWithDelim(
    src: SuspendSource,
    delim: Byte,
): Pair<String, Boolean>? {
    val chunk = Buffer()
    val accum = Buffer()
    var any = false
    while (true) {
        val n = src.readAtMostTo(chunk, 1L)
        if (n == -1L) break
        any = true
        val b = chunk.readByte()
        if (b == delim) return accum.readString() to true
        accum.writeByte(b)
    }
    return if (any) accum.readString() to false else null
}

/**
 * Bash `mapfile`/`readarray [-d delim] [-n count] [-O origin] [-s skip]
 * [-t] [-u fd] [-C callback] [-c quantum] [array]`. Reads lines from
 * stdin (or `-u FD`, which we currently only honor for fd 0) into the
 * named indexed array, defaulting to `MAPFILE`.
 *
 * Options:
 *  - `-d DELIM`: line delimiter (default newline). Only single-char
 *    delimiters honored; multi-char delimiters reject with exit 2.
 *  - `-n COUNT`: stop after COUNT lines (0 = unlimited).
 *  - `-O ORIGIN`: first index to assign (default 0).
 *  - `-s SKIP`: discard the first SKIP lines.
 *  - `-t`: strip the trailing delimiter from each stored line.
 *  - `-u FD`: read from file descriptor FD (only fd 0 supported here).
 *  - `-C CB`, `-c QUANTUM`: accepted but ignored (callback hooks are
 *    bash-internal — we don't yet evaluate them).
 *
 * On entry the named array is cleared, then filled. The scalar `$NAME`
 * is removed so the array isn't shadowed by a stale scalar binding.
 */
internal suspend fun Interpreter.runMapfileIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var maxCount = 0
    var origin = 0
    var originExplicit = false
    var skip = 0
    var stripDelim = false
    var fd = 0
    var delim: Char = '\n'
    var callback: String? = null
    var quantum = 1

    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--" -> {
                i++
                break
            }

            a == "-t" -> {
                stripDelim = true
                i++
            }

            a == "-n" -> {
                val v = args.getOrNull(i + 1)
                if (v?.toIntOrNull() == null) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}mapfile: -n: numeric argument required\n")
                    return 2
                }
                maxCount = v.toInt()
                i += 2
            }

            a == "-O" -> {
                val v = args.getOrNull(i + 1)
                if (v?.toIntOrNull() == null) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}mapfile: -O: numeric argument required\n")
                    return 2
                }
                origin = v.toInt()
                originExplicit = true
                i += 2
            }

            a == "-s" -> {
                val v = args.getOrNull(i + 1)
                if (v?.toIntOrNull() == null) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}mapfile: -s: numeric argument required\n")
                    return 2
                }
                skip = v.toInt()
                i += 2
            }

            a == "-u" -> {
                val v = args.getOrNull(i + 1)
                if (v?.toIntOrNull() == null) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}mapfile: -u: numeric argument required\n")
                    return 2
                }
                fd = v.toInt()
                i += 2
            }

            a == "-d" -> {
                val v =
                    args.getOrNull(i + 1) ?: run {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}mapfile: -d: argument required\n")
                        return 2
                    }
                // Bash: empty operand means NUL delimiter (we model as
                // NUL char); multi-char operands silently truncate to the
                // first character.
                delim = if (v.isEmpty()) '\u0000' else v[0]
                i += 2
            }

            a == "-C" -> {
                val v =
                    args.getOrNull(i + 1) ?: run {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}mapfile: -C: argument required\n")
                        return 2
                    }
                callback = v
                i += 2
            }

            a == "-c" -> {
                val v = args.getOrNull(i + 1)
                if (v?.toIntOrNull() == null || v.toInt() < 1) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}mapfile: -c: numeric argument required\n")
                    return 2
                }
                quantum = v.toInt()
                i += 2
            }

            a.startsWith("-") && a != "-" -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}mapfile: $a: invalid option\n")
                return 2
            }

            else -> {
                break
            }
        }
    }

    val name = args.getOrNull(i) ?: "MAPFILE"
    // `-u FD`: read from the named file descriptor. fd=0 keeps the
    // [Stdio.stdin] route (cheaper, no fdTable lookup); higher fds resolve
    // through the process fd table — the same path `read -u FD` uses.
    val readSource =
        if (fd == 0) {
            stdio.stdin
        } else {
            val entry = process.fdTable[fd]
            if (entry?.ofd?.source == null) {
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}mapfile: $fd: invalid file descriptor: Bad file descriptor\n",
                )
                return 1
            }
            entry.ofd.source!!
        }

    val arr = indexedArrays.getOrPut(name) { mutableMapOf() }
    // Bash: default `-O 0` clears the destination array; an explicit
    // `-O N` (even with N=0) does NOT clear, so pre-populated elements
    // outside the range mapfile touches survive. Verified against
    // bash 5.3 directly.
    if (!originExplicit) arr.clear()

    var idx = origin
    var taken = 0
    var skipped = 0
    val delimByte = delim.code.toByte()
    while (true) {
        val (chunk, sawDelim) = readUtf8LineWithDelim(readSource, delimByte) ?: break
        if (skipped < skip) {
            skipped++
            continue
        }
        // Bash semantics: a partial final line (file ends without delim)
        // is stored AS-IS, no synthesized delim. With -t the delim would
        // be stripped anyway. Without -t, only reconstitute the delim if
        // we actually saw one in source.
        val value =
            when {
                stripDelim -> chunk

                // Bash `-d ''` (NUL delim) always strips the NUL —
                // bash array elements are NUL-terminated C strings
                // internally, so the delim character can't survive
                // in the value regardless of `-t`. Verified against
                // bash 5.3 directly.
                delim == '\u0000' -> chunk

                sawDelim -> chunk + delim

                else -> chunk
            }
        arr[idx] = value
        // -C CALLBACK -c QUANTUM: every QUANTUM lines (default 1), invoke
        // `CALLBACK INDEX LINE` as a shell command. Eval-parsed so a
        // callback like `echo` or a defined function both work. Errors in
        // the callback don't abort the mapfile read.
        if (callback != null && (taken + 1) % quantum == 0) {
            val cmd = "$callback $idx ${escapeAnsiC(value)}"
            try {
                runEvalIntrinsic(listOf(cmd), stdio)
            } catch (_: Throwable) {
                // Swallow — mapfile keeps reading regardless of callback
                // outcome, matching the documented shell behavior.
            }
        }
        idx++
        taken++
        if (maxCount > 0 && taken >= maxCount) break
    }
    env.remove(name)
    return 0
}

/**
 * Quote [value] as a single shell word that survives an `eval`-style
 * re-parse. Uses ANSI-C `$'…'` form so embedded newlines, tabs, and other
 * control bytes survive verbatim — needed because mapfile callbacks
 * receive each line WITH its terminating delimiter, and a naive
 * `'…value\n…'` single-quoted form loses the newline through some token-
 * stream paths.
 */
private fun escapeAnsiC(value: String): String {
    val sb = StringBuilder()
    sb.append("$'")
    for (c in value) {
        when (c) {
            '\\' -> {
                sb.append("\\\\")
            }

            '\'' -> {
                sb.append("\\'")
            }

            '\n' -> {
                sb.append("\\n")
            }

            '\r' -> {
                sb.append("\\r")
            }

            '\t' -> {
                sb.append("\\t")
            }

            else -> {
                if (c.code < 0x20) {
                    sb.append('\\').append('x').append(c.code.toString(16).padStart(2, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
    }
    sb.append("'")
    return sb.toString()
}
