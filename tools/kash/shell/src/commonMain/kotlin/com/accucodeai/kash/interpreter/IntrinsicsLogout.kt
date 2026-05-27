package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio

/**
 * Bash `logout [n]`. Exits the shell — but ONLY if it's a login shell;
 * otherwise prints the canonical "not login shell: use \`exit'" diagnostic
 * and returns 1 without exiting. Login-shell status is determined at
 * startup by `KashShellCommand` (the `-l`/`--login` flag) and exposed
 * via [isLoginShell].
 *
 * The optional exit-code argument follows the same convention as `exit`:
 * numeric → use that code; absent → use `$?`.
 */
internal suspend fun Interpreter.runLogoutIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (!isLoginShell) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}logout: not login shell: use `exit'\n")
        return 1
    }
    val code = args.firstOrNull()?.toIntOrNull() ?: lastExit
    throw Interpreter.ScriptAbortException(code)
}
