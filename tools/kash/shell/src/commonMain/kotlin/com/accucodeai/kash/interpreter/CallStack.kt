package com.accucodeai.kash.interpreter

/**
 * Call-stack and trap-bookkeeping cluster — function-name stack
 * (drives `FUNCNAME[@]`), caller-line stack (drives `BASH_LINENO[@]`),
 * `$LINENO` source-line tracker and its function-relative offset,
 * loop nesting depth, and the trap-inheritance flags per frame.
 *
 * Held cohesively because every function-call boundary touches all
 * of these together — `runFunctionCall` push/pop sequence, the DEBUG
 * trap firing rules, `break`/`continue` count clamping, etc.
 */
internal class CallStack {
    /**
     * Names of currently-executing functions, innermost last. Used by
     * `readonly -a`/`export -a` diagnostics, which bash prefixes with
     * the caller's function name when the assignment surfaced through
     * the assignment-builtin path.
     *
     * Drives `${FUNCNAME[@]}` synthesis in the expander: FUNCNAME[0]
     * is the innermost frame, so the bash-visible array is this list
     * reversed with `"main"` appended as the synthetic outer frame.
     */
    val functionNameStack: ArrayDeque<String> = ArrayDeque()

    /**
     * Caller line numbers parallel to [functionNameStack]. Each entry
     * is the source line at which the *next-inner* function was
     * invoked. Drives `${BASH_LINENO[@]}`.
     */
    val callerLineStack: ArrayDeque<Int> = ArrayDeque()

    /**
     * Source line of the command currently being executed — backs
     * `$LINENO`. Updated at the entry of `executeWithStdio` for every
     * command kind. The raw value is the lexer line; bash semantics
     * report it relative to the enclosing scope via [linenoOffset].
     */
    var currentLine: Int = 0

    /**
     * Amount subtracted from [currentLine] before it's reported as
     * `$LINENO`. Top-level scripts use 0; pushed/popped at function-call
     * boundaries.
     */
    var linenoOffset: Int = 0

    /** `break N` / `continue N` clamp target. Bumped by every active loop. */
    var loopDepth: Int = 0

    /**
     * Function names that opted into trap inheritance via `declare -ft
     * NAME`. Marked functions inherit DEBUG/RETURN/ERR traps from the
     * calling scope.
     */
    val tracedFunctions: MutableSet<String> = mutableSetOf()

    /**
     * Per-frame stack of "is this frame trap-inheriting?". Empty stack
     * == top-level (traps always apply). One push per `runFunctionCall`.
     */
    val traceFramesActive: ArrayDeque<Boolean> = ArrayDeque()

    fun copyFrom(other: CallStack) {
        functionNameStack.clear()
        functionNameStack.addAll(other.functionNameStack)
        callerLineStack.clear()
        callerLineStack.addAll(other.callerLineStack)
        currentLine = other.currentLine
        linenoOffset = other.linenoOffset
        loopDepth = other.loopDepth
        tracedFunctions.clear()
        tracedFunctions.addAll(other.tracedFunctions)
        traceFramesActive.clear()
        traceFramesActive.addAll(other.traceFramesActive)
    }
}
