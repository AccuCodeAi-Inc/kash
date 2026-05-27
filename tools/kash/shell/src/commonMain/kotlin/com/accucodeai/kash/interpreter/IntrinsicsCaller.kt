package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio

/**
 * Bash `caller [expr]`. Reports the call-stack frame above the current
 * function (or the current `.`-sourced script).
 *
 *  - `caller` (no arg): prints `<line> <subroutine>` for the immediate
 *    caller and exits 0. Exits 1 silently when not inside a function or
 *    sourced script (i.e. no call frame to report).
 *  - `caller N`: prints `<line> <subroutine> <source>` for frame N
 *    (0-indexed, innermost first) and exits 0. Out-of-range N → exit 1.
 *
 * Backed by [functionNameStack] / [callerLineStack] — both already
 * populated by [runFunctionCall] and the dot/source intrinsic.
 */
internal suspend fun Interpreter.runCallerIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    val depth = functionNameStack.size
    if (depth == 0) return 1
    val frame = args.firstOrNull()?.toIntOrNull() ?: 0
    if (frame < 0 || frame >= depth) return 1
    // functionNameStack is innermost-last; frame 0 = innermost.
    val name = functionNameStack[depth - 1 - frame]
    val line =
        if (frame < callerLineStack.size) {
            callerLineStack[callerLineStack.size - 1 - frame]
        } else {
            0
        }
    // BASH_SOURCE is a hook-backed synthesized array — read it through
    // the live [Variable.indexedView] rather than the stored-only view.
    val sourceArr = varTable.find("BASH_SOURCE")?.indexedView
    val source = sourceArr?.get(frame) ?: "main"
    return if (args.isEmpty()) {
        stdio.stdout.writeUtf8("$line $name\n")
        0
    } else {
        stdio.stdout.writeUtf8("$line $name $source\n")
        0
    }
}
