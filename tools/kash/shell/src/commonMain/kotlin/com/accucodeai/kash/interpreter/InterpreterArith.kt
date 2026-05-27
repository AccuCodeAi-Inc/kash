package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.ast.Script
import com.accucodeai.kash.parser.Parser
import com.accucodeai.kash.parser.findCmdSubClose

/**
 * Arithmetic-context runtime — `$((...))`, `((...))`, the integer-attr
 * coercion path, and the parameter pre-expansion that lets ArithEval
 * see `${#arr[@]}` as a literal number. Extracted from [Interpreter]
 * as part of the file-organization cleanup. Pure refactor.
 */

internal suspend fun Interpreter.evalArithmetic(raw: String): String {
    // Bash arithmetic context (`$((...))`) first performs parameter,
    // command, and arithmetic substitution on the expression, then
    // evaluates the result. Our [ArithEval] only understands plain
    // identifiers, so pre-expand `${...}` blocks here — that's how
    // `${#NAME[@]}` reaches the evaluator as a literal number.
    //
    // Bash also treats the entire arith body as double-quoted, so
    // `"`/`'` aren't delimiters and get stripped (see `evalArithRaw`).
    val expanded =
        preexpandArithmetic(raw).let {
            if (it.indexOf('"') >= 0 || it.indexOf('\'') >= 0) {
                it.filter { c -> c != '"' && c != '\'' }
            } else {
                it
            }
        }
    // ArithEval reads bare identifiers via env[name]. Dynamic specials
    // (LINENO, BASHPID, etc.) aren't real env entries, so seed them
    // for this eval so `$((LINENO))` / `$((BASHPID + 1))` work.
    val priorLineno = env["LINENO"]
    env["LINENO"] = effectiveLineno().toString()
    return try {
        ArithEval(expanded, env, arithStore, nounset = nounset).evaluate().toString()
    } catch (e: ArithEval.Error) {
        // Suppress duplicate diagnostics within one simple-command
        // boundary: bash prints the failure once even when the same
        // assignment is processed twice (once into the prefix-env
        // collector, once during the bare-assignment apply pass).
        val msg = e.message.orEmpty().removePrefix("arithmetic: ")
        val isSyntaxError =
            !msg.endsWith(": unbound variable") && !msg.endsWith(": readonly variable")
        if (!arithSubstFailedInWordExpansion) {
            // bash prints `<name>: unbound variable` without the `((:`
            // prefix it uses for syntax errors — match that shape so
            // the conformance normalize-rule catches the line.
            if (isSyntaxError) {
                errSink.writeUtf8("${shellDiagPrefix()}((: $raw: $msg\n")
            } else {
                errSink.writeUtf8("${shellDiagPrefix()}$msg\n")
            }
        }
        // Bash semantics: a failed arithmetic substitution aborts the
        // enclosing simple command — `echo $((bogus))` writes the
        // diagnostic and produces no stdout from the (skipped) echo.
        // Set a flag the simple-command driver checks after argv
        // expansion; the substitution value itself is irrelevant
        // because the command won't run.
        arithSubstFailedInWordExpansion = true
        // Bash's `-c` mode treats the whole inline-script as one parse
        // unit, so an arith syntax error inside `$(( ))` aborts the
        // entire unit — the rest of the script never runs. Script-file
        // mode keeps running (see arith.tests). posixexp2.sub test 15
        // verifies: `sh -c $'echo $(( x+ )) \\n exit 0'` must NOT reach
        // `exit 0`.
        if (isSyntaxError && currentOuterIsCLine) {
            throw Interpreter.ScriptAbortException(1)
        }
        ""
    } finally {
        if (priorLineno != null) env["LINENO"] = priorLineno else env.remove("LINENO")
    }
}

/** Effective source line — `$LINENO` value, accounting for function-call frame. */
private fun Interpreter.effectiveLineno(): Int = currentLine - linenoOffset

/**
 * Scan [raw] for `${...}` and replace each with its parameter-expanded
 * value. Nested braces inside the block are tracked. Other `$`
 * substitutions (`$NAME`, `$(...)`) are left to [ArithEval] / the
 * caller — for the dstack2-class motivator (`${#A[@]}`) the brace
 * form is sufficient. The replacement is a numeric or string literal
 * that the arithmetic parser can then consume normally.
 */
internal suspend fun Interpreter.preexpandArithmetic(raw: String): String {
    // Bash performs parameter expansion on the WHOLE arithmetic string
    // before evaluation. Inline the special-name expansions ArithEval
    // can't parse on its own:
    //   - `${$}` → the PID (bash extension; we rewrite to `$$` for the
    //     loop below to handle uniformly)
    //   - `$$` → the PID literal
    //   - `$!` → most recent background pid (or empty)
    // ArithEval handles `$NAME` and `${NAME}` already, so we don't
    // need to special-case alphanumerics.
    val pid = shellPid.toString()
    // `$@` and `$*` in arith get pre-expanded to the joined positional
    // args; bash treats `$(( $@ ))` as `$(( 1 + 2 ))` for positional
    // args `1 + 2`. The join uses IFS's first char (matches bash and
    // `$*` expansion in non-arith contexts).
    val positionalJoined = positional.joinToString(" ")
    val preExpanded =
        raw
            .replace("\${\$}", pid)
            .replace("\$\$", pid)
            .replace("\$!", lastBgPidMasked()?.toString() ?: "0")
            .replace("\$#", positional.size.toString())
            .replace("\$?", lastExit.toString())
            .replace("\$@", positionalJoined)
            .replace("\$*", positionalJoined)
    if (!preExpanded.contains("\${") && !preExpanded.contains("\$(") &&
        !preExpanded.contains('`')
    ) {
        return preExpanded
    }
    val src = preExpanded
    val sb = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        val c = src[i]
        // Pre-expand `$(cmd)` substitutions: bash runs them as full
        // shell commands before arith eval, so kash needs to do the
        // same to keep parity with idioms like
        // `for (( $(case x in x) esac);; ))` that the test corpus
        // relies on. We track paren depth (NOT brace depth) so the
        // closing `)` of nested `$(...)` matches correctly.
        // Backtick command-substitution inside arithmetic: bash runs
        // these like `$(...)`. Find the matching backtick (no nesting
        // — bash backticks don't nest without backslash escapes, and
        // the arith corpus only uses simple `cmd` forms).
        if (c == '`') {
            val end = src.indexOf('`', i + 1)
            if (end < 0) {
                sb.append(src, i, src.length)
                return sb.toString()
            }
            val cmd = src.substring(i + 1, end)
            val expanded =
                try {
                    val ast =
                        com.accucodeai.kash.parser
                            .Parser("echo \$($cmd)", aliasResolver)
                            .parseScript()
                    val word =
                        ast.statements
                            .firstOrNull()
                            ?.pipelines
                            ?.firstOrNull()
                            ?.commands
                            ?.firstOrNull()
                            ?.let { it as? com.accucodeai.kash.ast.SimpleCommand }
                            ?.args
                            ?.firstOrNull()
                    if (word != null) expandSingle(word) else cmd
                } catch (_: Throwable) {
                    ""
                }
            sb.append(expanded)
            i = end + 1
            continue
        }
        // Nested `$((...))` — recursively evaluate. Bash treats the
        // outer arith body as one expansion pass, so `$((10-$((5+0))))`
        // resolves the inner arith to its number before the outer
        // evaluator sees it. Find the matching `))` (paren-balanced) and
        // replace the whole `$((...))` block with its numeric result.
        if (c == '$' && i + 2 < src.length && src[i + 1] == '(' && src[i + 2] == '(') {
            var depth = 1
            var j = i + 3
            while (j < src.length && depth > 0) {
                when (src[j]) {
                    '(' -> {
                        depth++
                    }

                    ')' -> {
                        // `))` closes only when at depth 1 AND the next
                        // char is also `)`. Otherwise it's a single `)`
                        // matching an interior `(`.
                        if (depth == 1 && j + 1 < src.length && src[j + 1] == ')') {
                            depth = 0
                            break
                        }
                        depth--
                    }
                }
                if (depth > 0) j++
            }
            if (depth == 0 && j + 1 < src.length) {
                val innerExpr = src.substring(i + 3, j)
                val result =
                    try {
                        evalArithmetic(innerExpr)
                    } catch (_: Throwable) {
                        innerExpr
                    }
                sb.append(result)
                i = j + 2 // skip the closing `))`
                continue
            }
            // Unbalanced — fall through to the `$(` handling below, which
            // copies the rest verbatim.
        }
        if (c == '$' && i + 1 < src.length && src[i + 1] == '(' && (i + 2 >= src.length || src[i + 2] != '(')) {
            // Case-aware boundary scan — mirror of the A1 fix in
            // Lexer.readCommandSubstitution. A `)` closing a case
            // pattern inside the cmdsub body must not be treated as
            // the cmdsub terminator. Reference: POSIX §2.6.3.
            val j = findCmdSubClose(src, i + 2)
            if (j < 0) {
                sb.append(src, i, src.length)
                return sb.toString()
            }
            val block = src.substring(i, j + 1)
            val expanded =
                try {
                    val ast =
                        com.accucodeai.kash.parser
                            .Parser("echo $block", aliasResolver)
                            .parseScript()
                    val word =
                        ast.statements
                            .firstOrNull()
                            ?.pipelines
                            ?.firstOrNull()
                            ?.commands
                            ?.firstOrNull()
                            ?.let { it as? com.accucodeai.kash.ast.SimpleCommand }
                            ?.args
                            ?.firstOrNull()
                    if (word != null) expandSingle(word) else block
                } catch (_: Throwable) {
                    ""
                }
            sb.append(expanded)
            i = j + 1
            continue
        }
        if (c == '$' && i + 1 < src.length && src[i + 1] == '{') {
            var depth = 1
            var j = i + 2
            while (j < src.length && depth > 0) {
                when (src[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                if (depth > 0) j++
            }
            if (depth != 0 || j >= src.length) {
                // Unbalanced — leave verbatim; ArithEval will complain.
                sb.append(src, i, src.length)
                return sb.toString()
            }
            val block = src.substring(i, j + 1) // includes ${ and }
            val expanded =
                try {
                    val ast =
                        com.accucodeai.kash.parser
                            .Parser("echo $block", aliasResolver)
                            .parseScript()
                    val word =
                        ast.statements
                            .firstOrNull()
                            ?.pipelines
                            ?.firstOrNull()
                            ?.commands
                            ?.firstOrNull()
                            ?.let { it as? com.accucodeai.kash.ast.SimpleCommand }
                            ?.args
                            ?.firstOrNull()
                    if (word != null) expandSingle(word) else block
                } catch (_: Throwable) {
                    block
                }
            sb.append(expanded)
            i = j + 1
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}
