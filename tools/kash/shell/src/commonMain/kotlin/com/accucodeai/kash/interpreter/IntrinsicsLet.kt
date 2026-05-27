package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio

/**
 * Bash `let expr [expr ...]`. Evaluates each arithmetic expression in
 * order, mutating `env` via assignment operators (`x=1`, `i+=2`, etc.).
 *
 * Exit status mirrors `(( expr ))`: the value of the LAST expression —
 * exit 0 if that value is non-zero, exit 1 if zero. Per bash, this lets
 * `let "x = 0" && ...` short-circuit on a zero result.
 *
 * No args → "expression expected" diagnostic, exit 2 (bash returns 1 in
 * some versions; we match the `2 = usage error` convention used elsewhere
 * in kash intrinsics for consistency).
 */
internal suspend fun Interpreter.runLetIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    // POSIX §2.10.2 / Utility Syntax Guideline 10: `--` ends option
    // parsing; subsequent operands are taken as positional even if they
    // begin with `-`. For `let`, that means everything after `--` is an
    // arithmetic expression, not a flag.
    val effective = if (args.firstOrNull() == "--") args.drop(1) else args
    if (effective.isEmpty()) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}let: expression expected\n")
        return 2
    }
    var last = 0L
    for (expr in effective) {
        // Bash arith context is implicitly double-quoted; `"`/`'`
        // outside a bracketed subscript are stripped before eval. INSIDE
        // a `[...]` subscript, `let` preserves the quotes so an assoc
        // lookup `let "a[\"k\"]=v"` lands `"k"` as the literal key
        // (with quotes). The `(( ))` form does NOT do this preservation
        // — see [evalArithRaw], which strips quotes everywhere — but
        // bash treats the two contexts differently here.
        val stripped =
            if (expr.indexOf('"') >= 0 || expr.indexOf('\'') >= 0) {
                val sb = StringBuilder(expr.length)
                var depth = 0
                for (c in expr) {
                    when {
                        c == '[' -> {
                            depth++
                            sb.append(c)
                        }

                        c == ']' -> {
                            if (depth > 0) depth--
                            sb.append(c)
                        }

                        (c == '"' || c == '\'') && depth == 0 -> { /* strip */ }

                        else -> {
                            sb.append(c)
                        }
                    }
                }
                sb.toString()
            } else {
                expr
            }
        last =
            try {
                ArithEval(stripped, env, arithStore, nounset = nounset).evaluate()
            } catch (e: ArithEval.Error) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}let: ${e.message ?: "arithmetic error"}\n")
                return 1
            }
    }
    return if (last == 0L) 1 else 0
}
