package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.matchGlob
import com.accucodeai.kash.ast.ArithCommand
import com.accucodeai.kash.ast.ArithForCommand
import com.accucodeai.kash.ast.CaseCommand
import com.accucodeai.kash.ast.CondCommand
import com.accucodeai.kash.ast.CondExpr
import com.accucodeai.kash.ast.ForCommand
import com.accucodeai.kash.ast.Group
import com.accucodeai.kash.ast.IfCommand
import com.accucodeai.kash.ast.Statement
import com.accucodeai.kash.ast.Subshell
import com.accucodeai.kash.ast.WhileCommand
import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.interpreter.Interpreter.BreakException
import com.accucodeai.kash.interpreter.Interpreter.ContinueException
import com.accucodeai.kash.interpreter.Interpreter.ReturnException
import com.accucodeai.kash.interpreter.Interpreter.ScriptAbortException
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.parser.isShellIdentifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

// Control-flow commands (if/for/while/case/cond/group/subshell) extracted from Interpreter.

// -------- Control flow --------

internal suspend fun Interpreter.runIf(
    cmd: IfCommand,
    stdio: Stdio,
): Int {
    for (clause in cmd.clauses) {
        // POSIX errexit: the condition of `if` is not subject to set -e.
        errexitSuppressed++
        val condExit =
            try {
                runStatements(clause.condition, stdio.stdin, stdio)
            } finally {
                errexitSuppressed--
            }
        if (condExit == 0) return runStatements(clause.body, stdio.stdin, stdio)
    }
    cmd.elseBody?.let { return runStatements(it, stdio.stdin, stdio) }
    return 0
}

internal suspend fun Interpreter.runFor(
    cmd: ForCommand,
    stdio: Stdio,
): Int {
    // Bash defers this check to execution and uses the `<scriptname>: line N:`
    // (no `-c`) diagnostic shape. In POSIX mode the failure is FATAL —
    // bash aborts the (sub)shell rather than continuing to the next
    // statement, which the errors test exercises with a subshell
    // wrapper around `for invalid-name`.
    if (!isShellIdentifier(cmd.variable)) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}`${cmd.variable}': not a valid identifier\n")
        if (options.posixModeRuntime) throw ScriptAbortException(1)
        return 1
    }
    val items: List<String> = cmd.words?.let { flatExpandArgs(it) } ?: positional
    if (cmd.isSelect) return runSelect(cmd, items, stdio)
    // xtrace: bash emits `+ for VAR in ITEM1 ITEM2 ...` once per
    // iteration (before the body runs), so the trace appears N times
    // for an N-item list. Emit it inside the loop below.
    var exit = 0
    loopDepth++
    try {
        for (item in items) {
            if (xtrace) emitXtrace("for ${cmd.variable} in ${items.joinToString(" ")}", sink = stdio.stderr)
            if (varTable.find(cmd.variable)?.isReadonly == true) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}${cmd.variable}: readonly variable\n")
                return 1
            }
            env[cmd.variable] = item
            try {
                exit = runStatements(cmd.body, stdio.stdin, stdio)
            } catch (
                b: BreakException,
            ) {
                if (b.count > 1) throw BreakException(b.count - 1)
                break
            } catch (
                c: ContinueException,
            ) {
                if (c.count > 1) throw ContinueException(c.count - 1)
                continue
            }
        }
    } finally {
        loopDepth--
    }
    return exit
}

/**
 * `select var in items; do body; done`. Each iteration prints the item
 * menu on stderr followed by `PS3` (default "#? ") and reads a line from
 * stdin. If the line is a 1-based index into [items], the variable is set
 * to that item; otherwise it's set to empty. `REPLY` always holds the
 * raw input. The loop ends on EOF (matching bash) or `break`.
 */
internal suspend fun Interpreter.runSelect(
    cmd: ForCommand,
    items: List<String>,
    stdio: Stdio,
): Int {
    // Bash exits select immediately when the item list is empty — no menu,
    // no prompt. This is how `select i; do …; done` (no `in`, empty `$@`)
    // produces no output at all.
    if (items.isEmpty()) return 0
    var exit = 0
    val ps3 = env["PS3"] ?: "#? "
    val stdin = stdio.stdin
    val width = items.size.toString().length
    loopDepth++
    try {
        while (true) {
            // Reprint the menu before each prompt — bash behavior.
            for ((i, v) in items.withIndex()) {
                stdio.stderr.writeUtf8("${(i + 1).toString().padStart(width)}) $v\n")
            }
            stdio.stderr.writeUtf8(ps3)
            val line = stdin.readUtf8LineOrNull() ?: return exit // EOF -> exit
            env["REPLY"] = line
            val choice = line.trim().toIntOrNull()
            if (varTable.find(cmd.variable)?.isReadonly == true) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}${cmd.variable}: readonly variable\n")
                return 1
            }
            env[cmd.variable] =
                if (choice != null && choice in 1..items.size) {
                    items[choice - 1]
                } else {
                    ""
                }
            try {
                exit = runStatements(cmd.body, stdio.stdin, stdio)
            } catch (b: BreakException) {
                if (b.count > 1) throw BreakException(b.count - 1)
                break
            } catch (c: ContinueException) {
                if (c.count > 1) throw ContinueException(c.count - 1)
                continue
            }
        }
    } finally {
        loopDepth--
    }
    return exit
}

internal suspend fun Interpreter.runArithFor(
    cmd: ArithForCommand,
    stdio: Stdio,
): Int {
    // Bash: an arithmetic error in init/cond/update aborts the whole `for
    // ((...))` command with exit 1. Without this gate the loop would
    // re-evaluate the failing expression on every iteration (safety-capped
    // at 100k), spewing the same diagnostic and never making progress —
    // that's the runaway pattern arith-for.tests was hitting.
    if (cmd.init.isNotBlank()) {
        if (xtrace) emitXtrace("(( ${cmd.init} ))", sink = stdio.stderr)
        arithLastError = false
        evalArithRaw(cmd.init)
        if (arithLastError) return 1
    }
    var exit = 0
    loopDepth++
    try {
        loop@ while (true) {
            val condVal =
                if (cmd.cond.isBlank()) {
                    1L
                } else {
                    if (xtrace) emitXtrace("(( ${cmd.cond} ))", sink = stdio.stderr)
                    arithLastError = false
                    val v = evalArithRaw(cmd.cond)
                    if (arithLastError) return 1
                    v
                }
            if (condVal == 0L) break
            try {
                exit = runStatements(cmd.body, stdio.stdin, stdio)
            } catch (
                b: BreakException,
            ) {
                if (b.count > 1) throw BreakException(b.count - 1)
                break@loop
            } catch (
                c: ContinueException,
            ) {
                if (c.count > 1) throw ContinueException(c.count - 1)
            }
            if (cmd.update.isNotBlank()) {
                if (xtrace) emitXtrace("(( ${cmd.update} ))", sink = stdio.stderr)
                arithLastError = false
                evalArithRaw(cmd.update)
                if (arithLastError) return 1
            }
            yield()
        }
    } finally {
        loopDepth--
    }
    return exit
}

internal suspend fun Interpreter.runArithCommand(cmd: ArithCommand): Int {
    if (xtrace) emitXtrace("(( ${cmd.rawText} ))")
    // Bash's `(( name[\$var] ))` where neither name nor var is set emits
    // `name[]: bad array subscript` directly (NOT wrapped in `((: ... :`).
    // The diagnostic fires TWICE — bash evaluates the subscript twice,
    // once during the implicit read and once during the truthiness test.
    // Pre-scan for this exact shape so the surrounding evalArithRaw
    // catch path (which uses the `((:` framing for all syntax errors)
    // doesn't mask the more specific bash diagnostic.
    val m =
        Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\[\\$([A-Za-z_][A-Za-z0-9_]*)\\]\\s*$")
            .matchEntire(cmd.rawText)
    if (m != null) {
        val arrName = m.groupValues[1]
        val varName = m.groupValues[2]
        val arrUndefined =
            !indexedArrays.containsKey(arrName) &&
                !assocArrays.containsKey(arrName) &&
                varTable.find(arrName) == null
        val varUnset = !env.containsKey(varName)
        if (arrUndefined && varUnset) {
            errSink.writeUtf8("${shellDiagPrefix()}$arrName[]: bad array subscript\n")
            errSink.writeUtf8("${shellDiagPrefix()}$arrName[]: bad array subscript\n")
            return 1
        }
    }
    val v = evalArithRaw(cmd.rawText)
    return if (v != 0L) 0 else 1
}

internal suspend fun Interpreter.evalArithRaw(raw: String): Long {
    // Bash: `(())` (and `(( ))`) evaluates to 0 silently with exit-1 from
    // the surrounding ArithCommand. No diagnostic, no LINENO churn.
    if (raw.isBlank()) return 0L
    // Inside `((…))` and arith-for headers, bash treats the entire body
    // as double-quoted context: `"` and `'` are not delimiters and get
    // stripped. So `for (( ; "i < 3" ; ))` evaluates as `i < 3`. Our
    // ANTLR captures the raw text verbatim, so the strip happens here.
    // POSIX arithmetic identifiers never contain quotes; the only
    // semantic effect of `"X"` in this position is to group `X` as
    // whitespace-bounded — which is identical to bare `X`.
    // `((...))` and arith-for headers also undergo full parameter/command
    // substitution before arithmetic evaluation (POSIX §2.6.4). Run the
    // same `${...}` / `$(...)` / `$$` / `$!` pre-expansion that
    // `$((...))` uses.
    val expanded = preexpandArithmetic(raw)
    // If pre-expansion produced an empty/blank string (e.g. `$(case x
    // in x) esac)` where the case matched with empty action), the
    // section evaluates to 0 — same as a literally empty section.
    // Bash treats `for (( $(empty-cmdsub) ; ; ))` as `for (( ; ; ))`.
    if (expanded.isBlank()) return 0L
    val stripped =
        if (expanded.indexOf('"') >= 0 || expanded.indexOf('\'') >= 0) {
            expanded.filter { it != '"' && it != '\'' }
        } else {
            expanded
        }
    // Seed dynamic specials (LINENO, BASHPID, etc.) into env so bare
    // identifiers in arithmetic resolve to their dynamic values.
    val priorLineno = env["LINENO"]
    env["LINENO"] = (currentLine - linenoOffset).toString()
    return try {
        ArithEval(stripped, env, arithStore, nounset = nounset).evaluate()
    } catch (e: ArithEval.Error) {
        // bash format: `<script>: line N: ((: <expr> : <reason> (error token is "<rest>")`.
        // We approximate the inner detail; the prefix matches bash diagnostics.
        val reason = e.message.orEmpty().removePrefix("arithmetic: ")
        errSink.writeUtf8("${shellDiagPrefix()}((: ${raw.trim()} : $reason (error token is \"0 \")\n")
        arithLastError = true
        0L
    } finally {
        if (priorLineno != null) env["LINENO"] = priorLineno else env.remove("LINENO")
    }
}

internal suspend fun Interpreter.runWhile(
    cmd: WhileCommand,
    stdio: Stdio,
): Int {
    var exit = 0
    var safety = 100_000
    loopDepth++
    try {
        loop@ while (safety-- > 0) {
            // errexit doesn't apply to while/until condition (POSIX §sh).
            errexitSuppressed++
            val cond =
                try {
                    runStatements(cmd.condition, stdio.stdin, stdio)
                } finally {
                    errexitSuppressed--
                }
            val ok = if (cmd.until) cond != 0 else cond == 0
            if (!ok) break
            try {
                exit = runStatements(cmd.body, stdio.stdin, stdio)
            } catch (
                b: BreakException,
            ) {
                if (b.count > 1) throw BreakException(b.count - 1)
                break@loop
            } catch (
                c: ContinueException,
            ) {
                if (c.count > 1) throw ContinueException(c.count - 1)
                continue@loop
            }
            // Loop bodies with no inherent suspension (e.g. `while true; do
            // :; done`, busy arithmetic) would otherwise starve wasm's
            // single JS thread and block Ctrl-C delivery, AND prevent the
            // kotlinx-coroutines-test virtual clock from advancing (a known
            // limitation — see Kotlin/kotlinx.coroutines#2605: `yield()`
            // and `delay(0)` both attempt 0-duration advancement, so a
            // backgrounded `{ sleep 1; kill -USR1 $$; } &` racing this
            // loop never gets to fire its kill). `delay(1)` advances 1ms
            // of virtual time per iteration in tests (so signal-driven
            // exit fires before the safety counter trips), and on real
            // dispatchers acts as yield + 1ms throttle which is fine
            // for tight busy loops that are almost always either bug-
            // shaped or signal-driven anyway.
            delay(1)
        }
    } finally {
        loopDepth--
    }
    if (safety <= 0) stdio.stderr.writeUtf8("kash: loop iteration limit reached\n")
    return exit
}

internal suspend fun Interpreter.runCase(
    cmd: CaseCommand,
    stdio: Stdio,
): Int {
    arithSubstFailedInWordExpansion = false
    val subject = expandSingle(cmd.word)
    if (arithSubstFailedInWordExpansion) {
        arithSubstFailedInWordExpansion = false
        return 1
    }
    if (xtrace) emitXtrace("case $subject in", sink = stdio.stderr)
    var lastExit = 0
    var falling = false
    for ((idx, item) in cmd.items.withIndex()) {
        var arithFailedHere = false
        val matched =
            falling ||
                item.patterns.any {
                    val pat = expandPattern(it)
                    if (arithSubstFailedInWordExpansion) {
                        arithSubstFailedInWordExpansion = false
                        arithFailedHere = true
                        false
                    } else {
                        matchGlob(pat, subject)
                    }
                }
        if (arithFailedHere) return 1
        if (!matched) continue
        if (item.body.isNotEmpty()) {
            lastExit = runStatements(item.body, stdio.stdin, stdio)
        }
        falling = false
        when (item.terminator) {
            com.accucodeai.kash.ast.CaseTerminator.BREAK -> {
                return lastExit
            }

            com.accucodeai.kash.ast.CaseTerminator.FALLTHROUGH -> {
                // Execute the next item regardless of its patterns.
                if (idx == cmd.items.lastIndex) return lastExit
                falling = true
            }

            com.accucodeai.kash.ast.CaseTerminator.CONTINUE_TEST -> {
                // Keep testing remaining items' patterns.
            }
        }
    }
    return lastExit
}

internal suspend fun Interpreter.runCond(cmd: CondCommand): Int {
    if (xtrace) emitXtrace("[[ ${renderCondForXtrace(cmd.expression)} ]]")
    return if (evalCond(cmd.expression)) 0 else 1
}

/**
 * Render a [CondExpr] back to bash xtrace shape: `[[ -r /dev/fd/0 ]]`,
 * `[[ x = y ]]`, `[[ a && b ]]`. Operands are expanded so the trace
 * shows the runtime-substituted values, matching bash's xtrace
 * convention of "what was actually tested".
 */
private suspend fun Interpreter.renderCondForXtrace(e: CondExpr): String =
    when (e) {
        is CondExpr.And -> "${renderCondForXtrace(e.left)} && ${renderCondForXtrace(e.right)}"

        is CondExpr.Or -> "${renderCondForXtrace(e.left)} || ${renderCondForXtrace(e.right)}"

        is CondExpr.Not -> "! ${renderCondForXtrace(e.expr)}"

        is CondExpr.Lone -> expandSingle(e.word)

        is CondExpr.Unary -> "${e.op} ${expandSingle(e.operand)}"

        // Binary operators carry a regex-shape RHS for `=~`; for xtrace
        // we just render the expanded left + op + raw right text.
        is CondExpr.Binary -> "${expandSingle(e.left)} ${e.op} ${expandRegexRhs(e.right)}"
    }

internal suspend fun Interpreter.evalCond(e: CondExpr): Boolean =
    when (e) {
        is CondExpr.And -> evalCond(e.left) && evalCond(e.right)
        is CondExpr.Or -> evalCond(e.left) || evalCond(e.right)
        is CondExpr.Not -> !evalCond(e.expr)
        is CondExpr.Lone -> expandSingle(e.word).isNotEmpty()
        is CondExpr.Unary -> evalCondUnary(e.op, expandSingle(e.operand))
        is CondExpr.Binary -> evalCondBinary(e.op, expandSingle(e.left), e.right)
    }

internal suspend fun Interpreter.evalCondUnary(
    op: String,
    arg: String,
): Boolean {
    val resolved by lazy {
        com.accucodeai.kash.fs.Paths
            .resolve(cwd, arg)
    }
    return when (op) {
        "-z" -> {
            arg.isEmpty()
        }

        "-n" -> {
            arg.isNotEmpty()
        }

        "-e", "-a" -> {
            fs.exists(resolved)
        }

        "-f" -> {
            fs.exists(resolved) && !fs.isDirectory(resolved)
        }

        "-d" -> {
            fs.isDirectory(resolved)
        }

        "-s" -> {
            fs.exists(resolved) && !fs.isDirectory(resolved) &&
                runCatching { fs.readBytes(resolved).isNotEmpty() }.getOrDefault(false)
        }

        "-r", "-w", "-x" -> {
            fs.exists(resolved)
        }

        "-v" -> {
            // `-v NAME[sub]`: check array element existence.
            // `-v NAME[@]` / `-v NAME[*]`: true iff the array has any elements.
            // `-v NAME`: scalar set, OR (for an array) `NAME[0]` is set.
            val lb = arg.indexOf('[')
            val rb = arg.lastIndexOf(']')
            if (lb > 0 && rb == arg.length - 1) {
                val base = arg.substring(0, lb)
                val sub = arg.substring(lb + 1, rb)
                when {
                    sub == "@" || sub == "*" -> {
                        indexedArrays[base]?.isNotEmpty() == true ||
                            assocArrays[base]?.isNotEmpty() == true
                    }

                    base in assocArrays -> {
                        sub in assocArrays.getValue(base)
                    }

                    base in indexedArrays -> {
                        val idx =
                            sub.toIntOrNull() ?: try {
                                evalArithRaw(sub.ifBlank { "0" }).toInt()
                            } catch (_: Throwable) {
                                0
                            }
                        idx in indexedArrays.getValue(base)
                    }

                    // Scalar with subscript: bash treats sub=0 as the scalar.
                    env[base] != null -> {
                        val idx =
                            sub.toIntOrNull() ?: try {
                                evalArithRaw(sub.ifBlank { "0" }).toInt()
                            } catch (_: Throwable) {
                                -1
                            }
                        idx == 0
                    }

                    else -> {
                        false
                    }
                }
            } else {
                env.containsKey(arg) ||
                    indexedArrays[arg]?.containsKey(0) == true ||
                    assocArrays[arg]?.isNotEmpty() == true
            }
        }

        "-o" -> {
            false
        }

        else -> {
            false
        }
    }
}

internal suspend fun Interpreter.evalCondBinary(
    op: String,
    lhs: String,
    rhsWord: Word,
): Boolean {
    if (op == "=~") return evalRegexMatch(lhs, rhsWord)
    // `[[ x == pat ]]` uses pattern expansion: quoted spans match literally.
    // Other ops just want the string value. Expand exactly ONCE here so
    // any cmdsub on the RHS fires once — `[[ x = $(echo y >&2) ]]`
    // emits `y` exactly once on stderr (regression-tested against
    // bash/extglob8.sub's `shopt extglob >&2` inside `[[ ]]`).
    val rhsPat: String?
    val rhs: String
    if (op == "=" || op == "==" || op == "!=") {
        rhsPat = expandPattern(rhsWord)
        rhs = ""
    } else {
        rhsPat = null
        rhs = expandSingle(rhsWord)
    }
    return when (op) {
        "=", "==" -> {
            matchGlob(rhsPat!!, lhs)
        }

        "!=" -> {
            !matchGlob(rhsPat!!, lhs)
        }

        "<" -> {
            lhs < rhs
        }

        ">" -> {
            lhs > rhs
        }

        "-eq" -> {
            (lhs.toLongOrNull() ?: 0L) == (rhs.toLongOrNull() ?: 0L)
        }

        "-ne" -> {
            (lhs.toLongOrNull() ?: 0L) != (rhs.toLongOrNull() ?: 0L)
        }

        "-lt" -> {
            (lhs.toLongOrNull() ?: 0L) < (rhs.toLongOrNull() ?: 0L)
        }

        "-le" -> {
            (lhs.toLongOrNull() ?: 0L) <= (rhs.toLongOrNull() ?: 0L)
        }

        "-gt" -> {
            (lhs.toLongOrNull() ?: 0L) > (rhs.toLongOrNull() ?: 0L)
        }

        "-ge" -> {
            (lhs.toLongOrNull() ?: 0L) >= (rhs.toLongOrNull() ?: 0L)
        }

        // POSIX file-comparison operators. `[[ a -ef b ]]`, `-nt`, `-ot` —
        // bash treats missing files as failure (false). Same-file is
        // decided by normalized-path equality; we don't model inodes, so
        // two distinct paths that happen to refer to the same node aren't
        // counted equivalent. Mtime comparisons go through FileStat.
        "-ef" -> {
            val l =
                com.accucodeai.kash.fs.Paths
                    .resolve(cwd, lhs)
            val r =
                com.accucodeai.kash.fs.Paths
                    .resolve(cwd, rhs)
            fs.exists(l) && fs.exists(r) &&
                com.accucodeai.kash.fs.Paths
                    .normalize(l) ==
                com.accucodeai.kash.fs.Paths
                    .normalize(r)
        }

        "-nt" -> {
            val l =
                com.accucodeai.kash.fs.Paths
                    .resolve(cwd, lhs)
            val r =
                com.accucodeai.kash.fs.Paths
                    .resolve(cwd, rhs)
            when {
                !fs.exists(l) -> false

                !fs.exists(r) -> true

                // bash: -nt is true if rhs doesn't exist
                else -> fs.stat(l).mtimeEpochSeconds > fs.stat(r).mtimeEpochSeconds
            }
        }

        "-ot" -> {
            val l =
                com.accucodeai.kash.fs.Paths
                    .resolve(cwd, lhs)
            val r =
                com.accucodeai.kash.fs.Paths
                    .resolve(cwd, rhs)
            when {
                !fs.exists(r) -> false

                !fs.exists(l) -> true

                // bash: -ot is true if lhs doesn't exist
                else -> fs.stat(l).mtimeEpochSeconds < fs.stat(r).mtimeEpochSeconds
            }
        }

        else -> {
            false
        }
    }
}

/**
 * `[[ str =~ pattern ]]` — bash regex match. Quoted parts of [rhsWord]
 * are regex-escaped (so `[[ x =~ '[[:alpha:]]' ]]` matches the literal
 * 7-char string). POSIX bracket classes `[[:class:]]` in unquoted parts
 * are translated to Java's `\p{Class}` equivalents because Kotlin's
 * Regex backend doesn't accept the POSIX bracket-class form. On a
 * successful match, `BASH_REMATCH` is populated with index 0 = whole
 * match, index N = capture group N.
 */
private suspend fun Interpreter.evalRegexMatch(
    lhs: String,
    rhsWord: Word,
): Boolean {
    val raw = expandRegexRhs(rhsWord)
    val pattern = translatePosixClasses(raw)
    val compiled =
        try {
            Regex(pattern)
        } catch (_: Throwable) {
            // Bash returns exit 2 on regex syntax error; we just return false
            // (no-match) and skip BASH_REMATCH population to match what
            // `[[ … =~ … ]]` does observationally — exit 1 on syntax error,
            // BASH_REMATCH unset.
            indexedArrays.remove("BASH_REMATCH")
            varTable.unset("BASH_REMATCH")
            return false
        }
    val match =
        compiled.find(lhs) ?: run {
            indexedArrays.remove("BASH_REMATCH")
            varTable.unset("BASH_REMATCH")
            return false
        }
    // Populate BASH_REMATCH: [0]=whole match, [1..N]=capture groups.
    val arr = mutableMapOf<Int, String>()
    arr[0] = match.value
    match.groupValues.forEachIndexed { idx, v ->
        if (idx > 0) arr[idx] = v
    }
    indexedArrays["BASH_REMATCH"] = arr
    return true
}

/**
 * Regex-escape every character that has special meaning in Java regex.
 * Used to make quoted spans of a bash `=~` RHS match literally.
 */
internal fun regexEscape(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        when (c) {
            '\\', '.', '+', '*', '?', '(', ')', '[', ']', '{', '}',
            '|', '^', '$', '-',
            -> {
                sb.append('\\').append(c)
            }

            else -> {
                sb.append(c)
            }
        }
    }
    return sb.toString()
}

/**
 * Replace POSIX bracket-class tokens with Java regex equivalents. Java's
 * Pattern doesn't accept `[[:alpha:]]` natively; `\p{Alpha}` is the
 * portable spelling. We rewrite each token in place so a multi-class
 * bracket expression like `[[:alpha:][:blank:]]` becomes `[\p{Alpha}\p{Blank}]`.
 */
internal fun translatePosixClasses(pattern: String): String {
    val map =
        mapOf(
            "alpha" to "\\p{Alpha}",
            "alnum" to "\\p{Alnum}",
            "digit" to "\\p{Digit}",
            "upper" to "\\p{Upper}",
            "lower" to "\\p{Lower}",
            "space" to "\\p{Space}",
            "blank" to "\\p{Blank}",
            "punct" to "\\p{Punct}",
            "xdigit" to "\\p{XDigit}",
            "cntrl" to "\\p{Cntrl}",
            "print" to "\\p{Print}",
            "graph" to "\\p{Graph}",
        )
    return Regex("""\[:([a-z]+):]""").replace(pattern) { m ->
        map[m.groupValues[1]] ?: m.value
    }
}

internal suspend fun Interpreter.runGroup(
    cmd: Group,
    stdio: Stdio,
): Int {
    // bash fires the DEBUG trap before a Group as a whole — separate
    // from the DEBUG fires for each simple command inside. Matters for
    // function-body groups: `f() { echo hi; }` with a DEBUG trap fires
    // once for the `{` (line of the group) and once for `echo`.
    if (!lineFrozenForTrap) currentLine = cmd.line
    fireDebugTrap()
    return runStatements(cmd.body, stdio.stdin, stdio)
}

internal suspend fun Interpreter.runSubshell(
    cmd: Subshell,
    stdio: Stdio,
): Int {
    // Full POSIX subshell isolation: env, cwd, positional, functions,
    // localScopes, readonlyVars, and dollarZero are all forked. The old
    // save/restore dance covered only the first three and leaked the rest
    // (e.g. `(foo() { :; })` redefined the parent's function table).
    val fork =
        try {
            forkSubshell()
        } catch (e: com.accucodeai.kash.api.ForkException) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}fork: ${e.message}\n")
            return 1
        }
    return try {
        fork.runStatementsInFork(cmd.body, stdio)
    } finally {
        signalRouter.unregister(fork.process.pid)
        machine.unregisterProcess(fork.process.pid)
    }
}

/**
 * Entry point for forked-interpreter subshell execution. Sets up the
 * fork's top-level sinks (which `runStatements` swaps for command
 * substitution) and runs the body. Re-raises the same control-flow
 * exceptions [run] catches at the top level so they don't escape the
 * subshell into the parent.
 */
internal suspend fun Interpreter.runStatementsInFork(
    statements: List<Statement>,
    stdio: Stdio,
): Int {
    outSink = stdio.stdout
    errSink = stdio.stderr
    var exit = lastExit
    try {
        exit = runStatements(statements, stdio.stdin, stdio)
    } catch (
        _: BreakException,
    ) {
    } catch (
        _: ContinueException,
    ) {
    } catch (r: ReturnException) {
        exit = r.code
    } catch (e: ScriptAbortException) {
        exit = e.code
    } catch (e: Expander.ParameterError) {
        // POSIX §2.6.2: `${param?word}` with `param` unset/null must
        // write a diagnostic to stderr and, for a non-interactive shell,
        // terminate. In a subshell the termination is local — the
        // parent continues, exit code reflects the subshell failure.
        // Bash's diagnostic format: `<scriptname>: line <N>: <name>: <msg>`.
        stdio.stderr.writeUtf8("${shellDiagPrefix()}${e.name}: ${e.msg.ifEmpty { "parameter null or not set" }}\n")
        exit = 1
    } catch (e: com.accucodeai.kash.parser.ParseException) {
        // Runtime "bad substitution" from ExpanderParameter (see top-
        // level catch in [Interpreter.run] for full rationale). At the
        // subshell boundary the same diagnostic+exit-1 shape applies.
        stdio.stderr.writeUtf8("${shellDiagPrefix()}${e.message ?: "bad substitution"}\n")
        exit = 1
    } finally {
        // POSIX: EXIT trap fires when a subshell terminates too. `exit N`
        // inside the EXIT trap overrides — runTrapHandler sets lastExit.
        lastExit = exit
        runExitTrap()
        exit = lastExit
    }
    return exit
}
