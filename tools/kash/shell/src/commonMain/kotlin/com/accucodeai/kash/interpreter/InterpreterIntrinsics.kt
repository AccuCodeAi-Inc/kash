package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.BreakException
import com.accucodeai.kash.interpreter.Interpreter.ContinueException
import com.accucodeai.kash.interpreter.Interpreter.ReturnException
import com.accucodeai.kash.interpreter.Interpreter.ScriptAbortException
import com.accucodeai.kash.interpreter.Interpreter.Stdio

// Builtin / intrinsic dispatch extracted from Interpreter.

// -------- Intrinsic builtins --------

internal suspend fun Interpreter.runIntrinsic(
    name: String,
    args: List<String>,
    stdio: Stdio,
): Int? =
    when (name) {
        ":" -> {
            0
        }

        "set" -> {
            runSetIntrinsic(args, stdio)
        }

        "shift" -> {
            runShiftIntrinsic(args, stdio)
        }

        "unset" -> {
            runUnsetIntrinsic(args, stdio)
        }

        "export" -> {
            runDeclareIntrinsic(
                listOf("-x") + args,
                forceLocal = false,
                stdio,
                forceGlobal = true,
                callerName = "export",
            )
        }

        "return" -> {
            runReturnIntrinsic(args, stdio)
        }

        "exit" -> {
            runExitIntrinsic(args, stdio)
        }

        "break" -> {
            runBreakIntrinsic(args, stdio)
        }

        "continue" -> {
            runContinueIntrinsic(args, stdio)
        }

        "eval" -> {
            runEvalIntrinsic(args, stdio)
        }

        "declare", "typeset" -> {
            // Pass the actual caller name through so error messages use
            // the right prefix (`typeset: NAME: ...` when invoked as
            // `typeset`, `declare: ...` otherwise). Verified bash 5.3.
            runDeclareIntrinsic(args, forceLocal = false, stdio, callerName = name)
        }

        "local" -> {
            runDeclareIntrinsic(args, forceLocal = true, stdio)
        }

        "readonly" -> {
            runDeclareIntrinsic(
                listOf("-r") + args,
                forceLocal = false,
                stdio,
                forceGlobal = true,
                callerName = "readonly",
            )
        }

        "type" -> {
            runTypeIntrinsic(args, stdio)
        }

        "command" -> {
            runCommandIntrinsic(args, stdio)
        }

        "hash" -> {
            runHashIntrinsic(args, stdio)
        }

        "wait" -> {
            runWaitIntrinsic(args, stdio)
        }

        "jobs" -> {
            runJobsIntrinsic(args, stdio)
        }

        "trap" -> {
            runTrapIntrinsic(args, stdio)
        }

        "kill" -> {
            runKillIntrinsic(args, stdio)
        }

        "fg" -> {
            runFgIntrinsic(args, stdio)
        }

        "bg" -> {
            runBgIntrinsic(args, stdio)
        }

        "disown" -> {
            runDisownIntrinsic(args, stdio)
        }

        "suspend" -> {
            runSuspendIntrinsic(args, stdio)
        }

        "alias" -> {
            runAliasIntrinsic(args, stdio)
        }

        "unalias" -> {
            runUnaliasIntrinsic(args, stdio)
        }

        "shopt" -> {
            runShoptIntrinsic(args, stdio)
        }

        "compgen" -> {
            runCompgenIntrinsic(args, stdio)
        }

        "complete" -> {
            runCompleteIntrinsic(args, stdio)
        }

        "compopt" -> {
            runCompoptIntrinsic(args, stdio)
        }

        "builtin" -> {
            runBuiltinIntrinsic(args, stdio)
        }

        "caller" -> {
            runCallerIntrinsic(args, stdio)
        }

        "let" -> {
            runLetIntrinsic(args, stdio)
        }

        "logout" -> {
            runLogoutIntrinsic(args, stdio)
        }

        "enable" -> {
            runEnableIntrinsic(args, stdio)
        }

        "mapfile", "readarray" -> {
            runMapfileIntrinsic(args, stdio)
        }

        "dirs" -> {
            runDirsIntrinsic(args, stdio)
        }

        "pushd" -> {
            runPushdIntrinsic(args, stdio)
        }

        "popd" -> {
            runPopdIntrinsic(args, stdio)
        }

        "history" -> {
            runHistoryIntrinsic(args, stdio)
        }

        "fc" -> {
            runFcIntrinsic(args, stdio)
        }

        "help" -> {
            runHelpIntrinsic(args, stdio)
        }

        "bind" -> {
            runBindIntrinsic(args, stdio)
        }

        "." -> {
            runDotIntrinsic(args, stdio)
        }

        "source" -> {
            runDotIntrinsic(args, stdio)
        }

        "exec" -> {
            runExecIntrinsic(args, stdio)
        }

        "times" -> {
            runTimesIntrinsic(stdio)
        }

        "umask" -> {
            runUmaskIntrinsic(args, stdio)
        }

        "ulimit" -> {
            runUlimitIntrinsic(args, stdio)
        }

        "getopts" -> {
            runGetoptsIntrinsic(args, stdio)
        }

        "read" -> {
            runReadIntrinsic(args, stdio)
        }

        else -> {
            null
        }
    }

// `return` / `exit` / `break` / `continue` are extracted into their own
// suspend functions to dodge a Kotlin/Wasm code-gen bug: the giant `when`
// in [runIntrinsic] otherwise generates an invalid state machine where
// a suspending `writeUtf8(...)` followed by an Int literal in the same
// branch stores `kotlin.Unit_instance` into the `WHEN_RESULT0` struct
// slot (typed as `Int?`), producing a `wasm validation error: at offset
// N: type mismatch: expression has type (ref null X) but expected
// (ref null Y)` at browser load time. Moving the inline logic into
// helper functions removes the suspend-then-int pattern from the
// dispatch `when` and the bug doesn't trip.

internal suspend fun Interpreter.runReturnIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (functionNameStack.isEmpty() && sourcingDepth == 0) {
        stdio.stderr.writeUtf8(
            "${shellDiagPrefix()}return: can only `return' from a function or sourced script\n",
        )
        return 1
    }
    // POSIX interp 1602: bare `return` inside a trap action exits with
    // the exit status of the command immediately *preceding* the trap,
    // pinned in [preTrapExit] by runTrapHandler. Outside a trap, falls
    // back to current $?.
    val defaultCode = preTrapExit ?: lastExit
    val a = args.firstOrNull() ?: throw ReturnException(defaultCode and 0xFF)
    val n = a.toIntOrNull()
    if (n == null) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}return: $a: numeric argument required\n")
        throw ReturnException(2)
    }
    throw ReturnException(n and 0xFF)
}

internal suspend fun Interpreter.runExitIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    val a = args.firstOrNull() ?: throw ScriptAbortException(lastExit and 0xFF)
    val n = a.toIntOrNull()
    if (n == null) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}exit: $a: numeric argument required\n")
        throw ScriptAbortException(2)
    }
    throw ScriptAbortException(n and 0xFF)
}

internal suspend fun Interpreter.runBreakIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (loopDepth == 0) {
        // Bash: `break` outside a loop prints a diagnostic and is a
        // no-op (exit 0); the script continues. Without this gate
        // the BreakException escapes the loop frames and unwinds
        // out of `runScript` entirely, aborting the rest of the
        // file — visible as a missing trailing `Y` in
        // arith-for.tests. POSIX §break specifies silent no-op
        // behavior, so suppress the diagnostic under posix mode.
        if (!posixModeRuntime) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}break: only meaningful in a `for', `while', or `until' loop\n",
            )
        }
        return 0
    }
    val a = args.firstOrNull() ?: throw BreakException(1)
    val n = a.toIntOrNull()
    if (n == null) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}break: $a: numeric argument required\n")
        return 2
    }
    if (n <= 0) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}break: $a: loop count out of range\n")
        return 1
    }
    throw BreakException(n)
}

internal suspend fun Interpreter.runContinueIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (loopDepth == 0) {
        stdio.stderr.writeUtf8(
            "${shellDiagPrefix()}continue: only meaningful in a `for', `while', or `until' loop\n",
        )
        return 0
    }
    val a = args.firstOrNull() ?: throw ContinueException(1)
    val n = a.toIntOrNull()
    if (n == null) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}continue: $a: numeric argument required\n")
        return 2
    }
    if (n <= 0) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}continue: $a: loop count out of range\n")
        return 1
    }
    throw ContinueException(n)
}

/**
 * POSIX `getopts optstring name arg...`. Walks the positional parameters
 * (or supplied `arg...`) one option character at a time, updating `OPTIND`,
 * `OPTARG`, and the named variable. Returns 0 on a successful match,
 * 1 when all options are consumed, 2 on error (mirrors bash).
 *
 * Supports clustered short options (`-abc` equivalent to `-a -b -c`) and
 * the `:` leading-character "silent" mode: with a leading `:` in optstring,
 * unknown options yield `name='?' OPTARG=<char>` and missing arguments
 * yield `name=':' OPTARG=<char>`. Without leading `:`, unknown opts emit a
 * diagnostic to stderr and set `name='?'`, missing args either yield `'?'`
 * (loud) or `':'` (silent) per POSIX.
 */
internal suspend fun Interpreter.runGetoptsIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    // Bash prints the builtin usage banner WITHOUT the `script: line N:`
    // location prefix (that prefix is reserved for actual error lines).
    val usageBanner = "getopts: usage: getopts optstring name [arg ...]\n"
    if (args.size < 2) {
        stdio.stderr.writeUtf8(usageBanner)
        return 2
    }
    // getopts takes no options of its own, so a leading `-X` first argument
    // is an invalid option to getopts itself (bash), not the optstring.
    if (args[0].length > 1 && args[0][0] == '-' && args[0] != "--") {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}getopts: ${args[0]}: invalid option\n")
        stdio.stderr.writeUtf8(usageBanner)
        return 2
    }
    val optstring = args[0]
    val name = args[1]
    // The result variable must be a valid identifier (an array-element
    // target `name[sub]` is allowed). bash rejects e.g. `opt-var` — but only
    // AFTER reading the current option (so OPTIND still advances), and getopts
    // then returns 1. The diagnostic is emitted by [assignGetoptsVar] when it
    // goes to store the option into the bad name.
    val nameValid =
        com.accucodeai.kash.parser
            .isShellIdentifier(name.substringBefore('['))
    val operands: List<String> = if (args.size > 2) args.drop(2) else positional
    val silent = optstring.startsWith(":")
    val effectiveOpts = if (silent) optstring.substring(1) else optstring
    // bash suppresses getopts' own diagnostics when `OPTERR=0` — distinct
    // from `:`-silent mode (which also changes the missing-arg result to
    // `:`). So a non-colon optstring with OPTERR=0 still reports `?`, just
    // without printing the message.
    val printErrors = !silent && (env["OPTERR"]?.toIntOrNull() ?: 1) != 0

    var optind = env["OPTIND"]?.toIntOrNull() ?: 1
    // Scan offset within the current clustered-option arg (`-xyz` → x/y/z).
    // Kept ON the OPTIND variable ([Variable.getoptsSubIndex]) so it shares
    // OPTIND's scope: `local`/`typeset OPTIND` gives a recursive frame a
    // fresh scan and restores the caller's offset on return — so a recursive
    // `getopts` cannot corrupt the caller's position. Defaults to 1 before
    // OPTIND becomes a table variable (very first call / process-env-only).
    var subIndex = varTable.find("OPTIND")?.getoptsSubIndex ?: 1

    // Persist the offset onto the (possibly local) OPTIND variable. The
    // preceding `env["OPTIND"] = ...` write ensures that variable exists.
    fun setOptindSub(v: Int) {
        varTable.find("OPTIND")?.getoptsSubIndex = v
    }

    // Write a getopts result variable (the name var or OPTARG). Two ways it
    // can fail without writing, matching bash: (1) the result NAME is not a
    // valid identifier → `<loc>getopts: \`NAME': not a valid identifier`;
    // (2) the (possibly nameref) target is readonly →
    // `<loc>NAME: readonly variable`.
    suspend fun assignGetoptsVar(
        v: String,
        value: String,
    ) {
        if (v == name && !nameValid) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}getopts: `$name': not a valid identifier\n")
            return
        }
        val tgt = varTable.find(v)
        val real = if (tgt != null && VarAttr.NameRef in tgt.attrs) varTable.resolveRef(tgt) else tgt
        if (real?.isReadonly == true) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}$v: readonly variable\n")
            return
        }
        env[v] = value
    }

    // Result of a successful option read: 0 normally, but 1 when the result
    // NAME is invalid (bash still consumes the option / advances OPTIND, then
    // fails). finishEnd returns 1 regardless (no more options).
    fun optResult(): Int = if (nameValid) 0 else 1

    suspend fun finishEnd(): Int {
        varTable.unset("OPTARG")
        assignGetoptsVar(name, "?")
        env["OPTIND"] = optind.toString()
        setOptindSub(1)
        return 1
    }

    while (true) {
        if (optind - 1 >= operands.size) return finishEnd()
        val current = operands[optind - 1]
        if (current.length < 2 || current[0] != '-') return finishEnd()
        if (current == "--") {
            env["OPTIND"] = (optind + 1).toString()
            setOptindSub(1)
            assignGetoptsVar(name, "?")
            return 1
        }
        if (subIndex >= current.length) {
            optind++
            subIndex = 1
            continue
        }
        val ch = current[subIndex]
        val idx = effectiveOpts.indexOf(ch)
        if (ch == ':' || idx < 0) {
            if (silent) {
                assignGetoptsVar(name, "?")
                assignGetoptsVar("OPTARG", ch.toString())
            } else {
                if (printErrors) stdio.stderr.writeUtf8("$dollarZero: illegal option -- $ch\n")
                assignGetoptsVar(name, "?")
                varTable.unset("OPTARG")
            }
            subIndex++
            if (subIndex >= current.length) {
                optind++
                subIndex = 1
            }
            env["OPTIND"] = optind.toString()
            setOptindSub(subIndex)
            return optResult()
        }
        val wantsArg = idx + 1 < effectiveOpts.length && effectiveOpts[idx + 1] == ':'
        if (!wantsArg) {
            assignGetoptsVar(name, ch.toString())
            varTable.unset("OPTARG")
            subIndex++
            if (subIndex >= current.length) {
                optind++
                subIndex = 1
            }
            env["OPTIND"] = optind.toString()
            setOptindSub(subIndex)
            return optResult()
        }
        // Option requires an argument: it's the remainder of the current
        // token (`-aVALUE`) or the next operand.
        val argInline = current.length > subIndex + 1
        if (argInline) {
            assignGetoptsVar("OPTARG", current.substring(subIndex + 1))
            assignGetoptsVar(name, ch.toString())
            optind++
            env["OPTIND"] = optind.toString()
            setOptindSub(1)
            return optResult()
        }
        if (optind >= operands.size) {
            // Missing required arg.
            if (silent) {
                assignGetoptsVar(name, ":")
                assignGetoptsVar("OPTARG", ch.toString())
            } else {
                if (printErrors) stdio.stderr.writeUtf8("$dollarZero: option requires an argument -- $ch\n")
                assignGetoptsVar(name, "?")
                varTable.unset("OPTARG")
            }
            optind++
            env["OPTIND"] = optind.toString()
            setOptindSub(1)
            return optResult()
        }
        assignGetoptsVar("OPTARG", operands[optind])
        assignGetoptsVar(name, ch.toString())
        optind += 2
        env["OPTIND"] = optind.toString()
        setOptindSub(1)
        return optResult()
    }
}
