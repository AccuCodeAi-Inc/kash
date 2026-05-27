package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.api.signal.SigDebug
import com.accucodeai.kash.api.signal.SigExit
import com.accucodeai.kash.api.signal.SigReturn
import com.accucodeai.kash.api.util.emptySource
import com.accucodeai.kash.ast.Script
import com.accucodeai.kash.parser.Parser
import com.accucodeai.kash.traps.TrapAction

// Trap-handler runtime — DEBUG / RETURN / ERR / EXIT and async signal
// handlers funnel through here. Extracted from [Interpreter] as part
// of the file-organization cleanup. Pure refactor.

/**
 * Run a trap handler script. Both async signals (INT/TERM/HUP/…) and
 * the EXIT pseudo-signal funnel through here.
 *
 * POSIX/bash: handler commands do not perturb `$?` — saved and
 * restored. Control-flow exceptions (`break`/`continue`/`return`) that
 * escape a handler are swallowed; a `Interpreter.ScriptAbortException` is also
 * swallowed because the script is already terminating. Other
 * exceptions surface to stderr but don't fail the surrounding context.
 *
 * For EXIT, callers pass `clearBeforeRun = true` so a re-entrant EXIT
 * trap in the handler can't loop forever.
 */
internal suspend fun Interpreter.runTrapHandler(
    sig: KashSignal,
    action: TrapAction.Handler,
    clearBeforeRun: Boolean = false,
) {
    if (clearBeforeRun) trapTable.reset(sig)
    val savedExit = lastExit
    val savedPreTrapExit = preTrapExit
    // POSIX interp 1602: bare `return` inside the trap uses $? as it was
    // immediately before the trap fired, not whatever the trap body last
    // produced. Pinned here for the duration of this handler so nested
    // function calls inside the trap don't shadow the value.
    preTrapExit = savedExit
    val savedInTrap = inTrapHandler
    // Preserve `$LINENO` for the surrounding command — bash's DEBUG
    // handler observes the line of the command *about to run*, not the
    // handler's own source line. We freeze currentLine across the
    // trap-action statements; function entry from inside the trap will
    // clear the freeze so callees see their own source lines.
    val savedLine = currentLine
    val savedLineFrozen = lineFrozenForTrap
    // The handler parses its own script — its lines start at 1 in its
    // own source, not relative to the enclosing function frame.
    val savedLinenoOffset = linenoOffset
    linenoOffset = 0
    inTrapHandler = true
    lineFrozenForTrap = true
    // Bash exposes `$BASH_TRAPSIG` to the handler — its value is the
    // numeric signal that fired (1 for HUP, 10 for USR1, etc.). Scripts
    // use it as `kill -l $BASH_TRAPSIG` to recover the symbolic name
    // inside generic trap bodies. Set on entry, restored on exit.
    val savedTrapSig = env["BASH_TRAPSIG"]
    if (sig.number > 0) env["BASH_TRAPSIG"] = sig.number.toString()
    // Track whether a `return N` escaped the handler so we can surface
    // it via [lastDebugTrapExit] — the DEBUG/extdebug skip-command path
    // keys off this value, not the regular `lastExit` (which we restore).
    var handlerReturnCode: Int? = null
    // Track `exit N` from inside the handler. Bash: `exit N` in the EXIT
    // trap overrides the shell's exit status; in other traps it still
    // exits the shell with that code. We capture here and act in finally.
    var handlerAbortCode: Int? = null
    try {
        // Per-statement parse-execute so `set -o posix` inside a trap
        // body affects subsequent statements (e.g. an EXIT-trap that
        // both sets POSIX mode and runs lex-sensitive commands).
        val stream =
            com.accucodeai.kash.parser.StatementStream(
                source = action.script,
                aliasResolver = aliasResolver,
                posixModeProvider = { posixModeRuntime },
                extglobProvider = { isShoptEnabled("extglob") },
                aliasVersionProvider = { aliasVersion },
            )
        loop@ while (true) {
            when (val r = stream.next()) {
                is com.accucodeai.kash.parser.StatementStream.NextResult.Statement -> {
                    runStatement(r.statement, emptySource())
                }

                is com.accucodeai.kash.parser.StatementStream.NextResult.Error -> {
                    errSink.writeUtf8(
                        "kash: trap (${sig.name}): ${r.error.message ?: "parse error"}\n",
                    )
                    break@loop
                }

                is com.accucodeai.kash.parser.StatementStream.NextResult.Eof -> {
                    break@loop
                }
            }
        }
    } catch (
        _: Interpreter.BreakException,
    ) {
    } catch (
        _: Interpreter.ContinueException,
    ) {
    } catch (ret: Interpreter.ReturnException) {
        handlerReturnCode = ret.code
    } catch (e: Interpreter.ScriptAbortException) {
        handlerAbortCode = e.code
    } catch (t: Throwable) {
        errSink.writeUtf8("kash: trap (${sig.name}): ${t.message}\n")
    } finally {
        inTrapHandler = savedInTrap
        lineFrozenForTrap = savedLineFrozen
        currentLine = savedLine
        linenoOffset = savedLinenoOffset
        if (savedTrapSig == null) env.remove("BASH_TRAPSIG") else env["BASH_TRAPSIG"] = savedTrapSig
        preTrapExit = savedPreTrapExit
        // For the DEBUG trap, expose the handler's terminal exit code
        // (from an explicit `return N`, falling back to the last
        // command's exit before we restore $?). Other signals don't
        // observe this — only DEBUG drives extdebug's skip semantics.
        if (sig == SigDebug) {
            lastDebugTrapExit = handlerReturnCode ?: lastExit
        }
        lastExit = savedExit
        // `exit N` semantics from inside the trap:
        //  - EXIT trap: override the shell's exit code with N.
        //  - Other traps: propagate the abort so the surrounding shell
        //    exits with N (matches bash's "exit terminates the shell
        //    immediately" rule, applicable to any trap handler).
        val abortCode = handlerAbortCode
        if (abortCode != null) {
            if (sig == SigExit) {
                lastExit = abortCode
            } else {
                throw Interpreter.ScriptAbortException(abortCode)
            }
        }
        // bash: `return N` inside a signal trap (other than DEBUG/RETURN,
        // which have their own skip-command semantics) returns from the
        // function or sourced script the trap fired inside. Propagate
        // the ReturnException past the handler so the surrounding
        // function frame unwinds. EXIT is excluded too — the trap fires
        // when the shell is already terminating, so a `return` there is
        // observably a no-op in bash.
        val retCode = handlerReturnCode
        if (retCode != null && sig != SigDebug && sig != SigReturn && sig != SigExit) {
            throw Interpreter.ReturnException(retCode)
        }
    }
}

/**
 * Fire the DEBUG trap (bash extension): runs the registered handler
 * before each simple command. No-op when no DEBUG trap is set, when
 * already inside a trap handler (no re-entry), or when the action is
 * [TrapAction.Ignore].
 */
internal suspend fun Interpreter.fireDebugTrap() {
    if (inTrapHandler) return
    if (!inheritsTrapDebugReturn()) return
    val action = trapTable.get(SigDebug) as? TrapAction.Handler ?: return
    runTrapHandler(SigDebug, action)
}

/**
 * Fire the RETURN trap (bash extension): runs the registered handler
 * when a function (or sourced script) returns. Same re-entry guard as
 * [fireDebugTrap].
 */
internal suspend fun Interpreter.fireReturnTrap() {
    if (inTrapHandler) return
    if (!inheritsTrapDebugReturn()) return
    val action = trapTable.get(SigReturn) as? TrapAction.Handler ?: return
    runTrapHandler(SigReturn, action)
}

/**
 * Fire the ERR trap (bash extension): runs the registered handler
 * when a simple command / pipeline returns non-zero, with the same
 * suppression rules as `set -e`. Called from the dispatcher post-exit
 * check. No-op when no ERR trap is set, when already inside a trap
 * handler, or when the active frame doesn't inherit ERR
 * (untraced function with `set +E`).
 */
internal suspend fun Interpreter.fireErrTrap() {
    if (inTrapHandler) return
    if (!inheritsTrapErr()) return
    val action = trapTable.get(com.accucodeai.kash.api.signal.SigErr) as? TrapAction.Handler ?: return
    runTrapHandler(com.accucodeai.kash.api.signal.SigErr, action)
}

/**
 * Whether the currently active execution frame should observe the
 * DEBUG / RETURN traps. At top level (no function frame on the
 * stack) always yes; inside a function, yes iff the frame was marked
 * traced (`declare -ft NAME`) OR `set -T` (functrace) is currently
 * enabled. Live-read of [Interpreter.functrace] so a mid-function
 * `set -T` toggle affects subsequent firings — matches bash's
 * runtime-queried FUNCTRACE semantics.
 */
internal fun Interpreter.inheritsTrapDebugReturn(): Boolean = (traceFramesActive.lastOrNull() ?: true) || functrace

/**
 * Whether the currently active execution frame should observe the
 * ERR trap. Same scoping as [inheritsTrapDebugReturn] but gated on
 * [Interpreter.errtrace] (`set -E`).
 */
internal fun Interpreter.inheritsTrapErr(): Boolean = (traceFramesActive.lastOrNull() ?: true) || errtrace

internal suspend fun Interpreter.runExitTrap() {
    val a = trapTable.get(SigExit) as? TrapAction.Handler ?: return
    runTrapHandler(SigExit, a, clearBeforeRun = true)
}
