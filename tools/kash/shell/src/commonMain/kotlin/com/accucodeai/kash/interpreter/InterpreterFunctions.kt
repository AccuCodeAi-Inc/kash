package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.ast.FunctionDef

/**
 * Function-call runtime — push/pop local scope frames, positional
 * args, FUNCNAME/BASH_LINENO call-stack push, RETURN trap. Extracted
 * from [Interpreter] as part of the file-organization cleanup. Pure
 * refactor.
 */

internal suspend fun Interpreter.runFunctionCall(
    name: String,
    fn: FunctionDef,
    args: List<String>,
    stdio: Interpreter.Stdio,
    inlineEnv: Map<String, String> = emptyMap(),
): Int {
    // FUNCNEST enforcement (bash 4.2+). A positive integer caps the
    // function call-stack depth; recursing past the cap prints
    // `maximum function nesting level exceeded (N)` and returns 1
    // without entering the body. `FUNCNEST=0`, unset, or a
    // non-positive / non-numeric value disables the limit.
    val funcnestRaw = env["FUNCNEST"]
    val funcnest = funcnestRaw?.toIntOrNull()
    if (funcnest != null && funcnest > 0 && functionNameStack.size >= funcnest) {
        stdio.stderr.writeUtf8(
            "${shellDiagPrefix()}$name: maximum function nesting level exceeded ($funcnest)\n",
        )
        return 1
    }
    val savedPositional = positional
    positional = args
    pushLocalScope()
    functionNameStack.addLast(name)
    // Record the caller's source line so `${BASH_LINENO[0]}` inside the
    // function reports the line at which we were invoked. Snapshot
    // *before* the body's first statement updates [currentLine].
    callerLineStack.addLast(currentLine)
    // Apply transient prefix assignments (`X=1 fn`) as locals so
    // they're visible inside the function body and auto-restored on
    // return. POSIX §2.9.1.4: assignments preceding a function call
    // affect ONLY that command's execution environment — they don't
    // persist into the calling scope (that persistence applies only
    // to special builtins). Bash also exports them for the function's
    // lifetime so externals / builtins invoked from inside see them in
    // their environment (`AVAR=foo f1` → `printenv AVAR` inside f1
    // prints `foo`).
    for ((k, v) in inlineEnv) {
        shadowLocal(k)
        varTable.findOrCreate(k).attrs += com.accucodeai.kash.interpreter.VarAttr.Export
        env[k] = v
    }
    // Trap inheritance: per-function trace bit set by `declare -ft NAME`.
    // The frame entry is the *per-function* bit only; the global flags
    // `set -T` (functrace) and `set -E` (errtrace) are OR'd in
    // separately at fire time (see [inheritsTrapDebugReturn] /
    // [inheritsTrapErr]) so a mid-function `set -T` toggle is honored
    // for the rest of the function — matching bash's runtime-queried
    // FUNCTRACE / ERRTRACE semantics.
    traceFramesActive.addLast(name in tracedFunctions)
    // `$LINENO` inside a function = absolute script line of the command
    // (bash 5.x semantics). Earlier we pushed `linenoOffset = fn.line`
    // here to report function-body-relative line numbers (matching
    // bash 4.x), but the modern reference and the dbg-support tests
    // both want absolute. Leave the offset alone; trap handlers still
    // reset it to 0 inside [runTrapHandler] because their action script
    // is reparsed standalone.
    val savedLinenoOffset = linenoOffset
    // If we got here from a DEBUG/RETURN/ERR trap action that froze
    // currentLine to the outer command's line, release the freeze so
    // the called function's body sees its own source lines via the
    // normal per-statement [currentLine] update. Restored on return.
    val savedLineFrozen = lineFrozenForTrap
    lineFrozenForTrap = false
    // POSIX interp 1602: the pre-trap `$?` override for bare `return`
    // applies only at the trap-action's *outermost* scope. A function
    // called from inside the trap (`trap 'handle' USR1` → `handle()`)
    // sees normal `return` semantics — its body's `return` uses the
    // current `$?`, not the trap's saved one. Clear here and restore
    // on the way out.
    val savedPreTrapExit = preTrapExit
    preTrapExit = null
    // Snapshot the procsub fd set on function entry so that any procsub
    // allocated by an assignment-only command inside the body — which
    // [executeWithStdio]'s per-command pass deliberately doesn't reclaim,
    // so the variable can be dereferenced later in the function — gets
    // released when the function returns. Procsubs that already existed
    // before the call (passed in via the caller's scope) are preserved.
    val procsubEntrySnapshot = procsubFds.toSet()
    val exit =
        try {
            try {
                executeWithStdio(fn.body, stdio)
            } catch (ret: Interpreter.ReturnException) {
                ret.code
            }
        } finally {
            // RETURN trap fires after the function body completes but
            // before locals are torn down, mirroring bash so the handler
            // can still observe the function's locals via inspection.
            // Wrapped so a handler error never leaks past the function
            // boundary.
            try {
                fireReturnTrap()
            } catch (_: Throwable) {
            }
            traceFramesActive.removeLast()
            popLocalScope()
            functionNameStack.removeLast()
            callerLineStack.removeLast()
            positional = savedPositional
            linenoOffset = savedLinenoOffset
            lineFrozenForTrap = savedLineFrozen
            preTrapExit = savedPreTrapExit
            reclaimProcsubsSince(procsubEntrySnapshot)
        }
    return exit
}

internal fun Interpreter.pushLocalScope() {
    varTable.pushScope()
}

/**
 * Pop the topmost function-local scope. Variables created in that
 * frame are discarded automatically by [VarTable.popScope]; we still
 * need to re-sync [KashProcess.env] for names that were shadowed so
 * the OS-process env block reflects the now-revealed outer value
 * (external programs spawned inside the function saw the shadow;
 * external programs spawned after return must see the outer scalar).
 */
internal fun Interpreter.popLocalScope() {
    val popped = varTable.popScope()
    for (name in popped.keys) {
        val outer = varTable.find(name)?.scalarOrNull
        if (outer != null) {
            process.env[name] = outer
        } else {
            process.env.remove(name)
        }
    }
}

internal fun Interpreter.shadowLocal(name: String) {
    varTable.shadowLocal(name)
}
