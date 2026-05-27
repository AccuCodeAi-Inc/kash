package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.interpreter.Interpreter.Stdio

/**
 * Bash directory stack: `dirs`/`pushd`/`popd`. Stack state lives on
 * [Interpreter.dirStack]. Conceptually the "full
 * stack" displayed by `dirs` is `[cwd] + dirStack`, with index 0 = top =
 * current directory.
 *
 * All three commands print the resulting stack on success (single-line
 * space-separated, `~`-substituted, matching bash). Indexing with `+N`
 * counts from the left (top); `-N` from the right (bottom).
 */
private fun Interpreter.fullStack(): List<String> = listOf(cwd) + dirStack

private fun Interpreter.displayDir(
    s: String,
    longForm: Boolean,
): String {
    if (longForm) return s
    val home = env["HOME"] ?: return s
    // bash skips tilde abbreviation when HOME is empty or just `/` —
    // otherwise every absolute path's leading `/` would match the home
    // prefix and the listing turns into a sea of `~`s.
    if (home.isEmpty() || home == "/") return s
    return when {
        s == home -> "~"
        s.startsWith("$home/") -> "~" + s.substring(home.length)
        else -> s
    }
}

private const val PUSHD_USAGE = "pushd: usage: pushd [-n] [+N | -N | dir]"
private const val POPD_USAGE = "popd: usage: popd [-n] [+N | -N]"
private const val DIRS_USAGE = "dirs: usage: dirs [-clpv] [+N] [-N]"

/** True if [s] looks like `-X`/`+X` where X is non-empty and not all digits. */
private fun looksLikeBadNumeric(s: String): Boolean =
    (s.startsWith("+") || s.startsWith("-")) && s.length > 1 && s.drop(1).any { !it.isDigit() }

/**
 * Diagnose a non-existent / non-directory path the bash way:
 *  - missing → "No such file or directory"
 *  - exists but is a regular file → "Not a directory"
 */
private fun Interpreter.dirErrorWording(path: String): String {
    val fs = process.machine.fs
    return if (!fs.exists(path)) "No such file or directory" else "Not a directory"
}

private suspend fun Interpreter.printStack(
    stdio: Stdio,
    longForm: Boolean = false,
    vertical: Boolean = false,
    onePerLine: Boolean = false,
) {
    val full = fullStack()
    when {
        vertical -> {
            val width = (full.size - 1).toString().length
            for ((j, d) in full.withIndex()) {
                val n = j.toString().padStart(width)
                stdio.stdout.writeUtf8(" $n  ${displayDir(d, longForm)}\n")
            }
        }

        onePerLine -> {
            for (d in full) stdio.stdout.writeUtf8(displayDir(d, longForm) + "\n")
        }

        else -> {
            stdio.stdout.writeUtf8(full.joinToString(" ") { displayDir(it, longForm) } + "\n")
        }
    }
}

/**
 * `dirs [-clpv] [+N|-N]`. With no operands, prints the stack. With
 * `+N`/`-N`, prints that single entry. `-c` clears the stack.
 */
internal suspend fun Interpreter.runDirsIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var clear = false
    var vertical = false
    var longForm = false
    var onePerLine = false
    var indexArg: String? = null
    for (a in args) {
        when {
            a == "-c" -> {
                clear = true
            }

            a == "-v" -> {
                vertical = true
            }

            a == "-l" -> {
                longForm = true
            }

            a == "-p" -> {
                onePerLine = true
            }

            a.startsWith("+") && a.length > 1 && a.drop(1).all { it.isDigit() } -> {
                indexArg = a
            }

            a.startsWith("-") && a.length > 1 && a.drop(1).all { it.isDigit() } -> {
                indexArg = a
            }

            looksLikeBadNumeric(a) -> {
                // `dirs -m` / `dirs +xyz` — bash treats this as an invalid
                // numeric, prints both the per-flag diagnostic AND the
                // usage line on stderr.
                stdio.stderr.writeUtf8("${shellDiagPrefix()}dirs: $a: invalid number\n")
                stdio.stderr.writeUtf8("$DIRS_USAGE\n")
                return 2
            }

            a.startsWith("-") -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}dirs: $a: invalid option\n")
                stdio.stderr.writeUtf8("$DIRS_USAGE\n")
                return 2
            }

            else -> {
                // Bare positional (e.g. `dirs 7`) — bash rejects with
                // "invalid option" + usage.
                stdio.stderr.writeUtf8("${shellDiagPrefix()}dirs: $a: invalid option\n")
                stdio.stderr.writeUtf8("$DIRS_USAGE\n")
                return 2
            }
        }
    }
    if (clear) {
        dirStack.clear()
        return 0
    }
    if (indexArg != null) {
        val full = fullStack()
        val plus = indexArg.startsWith("+")
        val n = indexArg.drop(1).toIntOrNull() ?: 0
        val realIdx = if (plus) n else full.size - 1 - n
        if (realIdx < 0 || realIdx >= full.size) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}dirs: ${indexArg.drop(1)}: directory stack index out of range\n",
            )
            return 1
        }
        val rendered = displayDir(full[realIdx], longForm)
        // `dirs -v +N` / `-v -N`: render as " <idx>  <dir>" — bash mixes
        // the vertical-numbered format with single-entry selection.
        if (vertical) {
            stdio.stdout.writeUtf8(" $realIdx  $rendered\n")
        } else {
            stdio.stdout.writeUtf8("$rendered\n")
        }
        return 0
    }
    printStack(stdio, longForm = longForm, vertical = vertical, onePerLine = onePerLine)
    return 0
}

/**
 * `pushd [DIR | +N | -N]`.
 *  - With DIR: cd into DIR and push the old cwd onto the stack.
 *  - With `+N`/`-N`: rotate the stack so that entry N becomes the top.
 *  - No arg: swap the top two entries (current cwd and dirStack[0]).
 */
internal suspend fun Interpreter.runPushdIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    val fs = process.machine.fs
    // `-n` is a flag, not a target: push but don't cd. Strip it from
    // args first so the remaining target is the path / +N / -N.
    val noCd = args.contains("-n")
    val filtered = args.filter { it != "-n" }
    val target = filtered.firstOrNull()
    if (target != null && looksLikeBadNumeric(target)) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}pushd: $target: invalid number\n")
        stdio.stderr.writeUtf8("$PUSHD_USAGE\n")
        return 2
    }
    when {
        target == null -> {
            if (dirStack.isEmpty()) {
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}pushd: no other directory\n",
                )
                return 1
            }
            val other = dirStack.removeAt(0)
            if (!fs.isDirectory(other)) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}pushd: $other: Not a directory\n")
                dirStack.add(0, other)
                return 1
            }
            val prev = cwd
            env["OLDPWD"] = prev
            cwd = other
            env["PWD"] = other
            dirStack.add(0, prev)
        }

        (target.startsWith("+") || target.startsWith("-")) &&
            target.length > 1 &&
            target.drop(1).all { it.isDigit() } -> {
            val plus = target.startsWith("+")
            val n = target.drop(1).toInt()
            val full = fullStack().toMutableList()
            val pivot = if (plus) n else full.size - 1 - n
            if (pivot < 0 || pivot >= full.size) {
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}pushd: $target: directory stack index out of range\n",
                )
                return 1
            }
            if (noCd) {
                // bash `-n +N` / `-n -N`: rotate the stack without
                // changing the cwd. We keep [cwd] in slot 0 and only
                // rotate the entries below it. For pivot=0 this is a
                // no-op (the cwd already is the "top"); bash skips the
                // trailing `dirs` print in that case.
                if (pivot == 0) return 0
                val sub = dirStack.toMutableList()
                val realPivot = pivot - 1
                val rotatedSub = sub.drop(realPivot) + sub.take(realPivot)
                dirStack.clear()
                dirStack.addAll(rotatedSub)
            } else {
                val rotated = full.drop(pivot) + full.take(pivot)
                val newCwd = rotated[0]
                if (!fs.isDirectory(newCwd)) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}pushd: $newCwd: Not a directory\n")
                    return 1
                }
                env["OLDPWD"] = cwd
                cwd = newCwd
                env["PWD"] = newCwd
                dirStack.clear()
                dirStack.addAll(rotated.drop(1))
            }
        }

        else -> {
            val resolved = Paths.resolve(cwd, target)
            if (!fs.isDirectory(resolved)) {
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}pushd: $target: ${dirErrorWording(resolved)}\n",
                )
                return 1
            }
            if (noCd) {
                // `-n DIR`: insert DIR at position 1 of the displayed
                // stack (i.e. dirStack[0]); cwd stays put.
                dirStack.add(0, resolved)
            } else {
                val prev = cwd
                env["OLDPWD"] = prev
                cwd = resolved
                env["PWD"] = resolved
                dirStack.add(0, prev)
            }
        }
    }
    printStack(stdio)
    return 0
}

/**
 * `popd [+N | -N]`.
 *  - No arg: discard the entry at position 1 (right below the current
 *    cwd) and cd into it — i.e. undo the last `pushd`.
 *  - `+N`/`-N`: remove that entry from the stack. If N=0 (top), the
 *    shell cds into the new top.
 */
internal suspend fun Interpreter.runPopdIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    val fs = process.machine.fs
    // `-n` keeps the cwd put while still removing an entry from the
    // dirStack. Same arg-filter trick we use in pushd.
    val noCd = args.contains("-n")
    val filtered = args.filter { it != "-n" }
    val target = filtered.firstOrNull()
    if (target != null && looksLikeBadNumeric(target)) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}popd: $target: invalid number\n")
        stdio.stderr.writeUtf8("$POPD_USAGE\n")
        return 2
    }
    if (target == null) {
        if (dirStack.isEmpty()) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}popd: directory stack empty\n")
            return 1
        }
        val newCwd = dirStack.removeAt(0)
        if (!fs.isDirectory(newCwd)) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}popd: $newCwd: Not a directory\n")
            return 1
        }
        env["OLDPWD"] = cwd
        cwd = newCwd
        env["PWD"] = newCwd
        printStack(stdio)
        return 0
    }
    if ((target.startsWith("+") || target.startsWith("-")) &&
        target.length > 1 &&
        target.drop(1).all { it.isDigit() }
    ) {
        val plus = target.startsWith("+")
        val n = target.drop(1).toInt()
        val full = fullStack()
        val idx = if (plus) n else full.size - 1 - n
        if (idx < 0 || idx >= full.size) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}popd: $target: directory stack index out of range\n",
            )
            return 1
        }
        if (idx == 0) {
            // Removing the top — the current cwd. With `-n`, just drop
            // the entry at dirStack[0] without changing cwd. Without
            // `-n`, cd into the new top.
            if (dirStack.isEmpty()) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}popd: directory stack empty\n")
                return 1
            }
            if (noCd) {
                dirStack.removeAt(0)
            } else {
                val newCwd = dirStack.removeAt(0)
                if (!fs.isDirectory(newCwd)) {
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}popd: $newCwd: Not a directory\n",
                    )
                    return 1
                }
                env["OLDPWD"] = cwd
                cwd = newCwd
                env["PWD"] = newCwd
            }
        } else {
            // Remove from dirStack (which is full minus cwd at index 0).
            dirStack.removeAt(idx - 1)
        }
        printStack(stdio)
        return 0
    }
    stdio.stderr.writeUtf8("${shellDiagPrefix()}popd: $target: invalid argument\n")
    return 2
}
