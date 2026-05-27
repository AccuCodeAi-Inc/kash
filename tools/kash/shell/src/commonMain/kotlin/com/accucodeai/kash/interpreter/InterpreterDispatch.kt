package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.ExitStatus
import com.accucodeai.kash.api.ShellRunner
import com.accucodeai.kash.api.UtilityRunner
import com.accucodeai.kash.api.installFd
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.emptySource
import com.accucodeai.kash.ast.Connector
import com.accucodeai.kash.ast.InlineAssignment
import com.accucodeai.kash.ast.Script
import com.accucodeai.kash.ast.SimpleCommand
import com.accucodeai.kash.ast.Statement
import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.parser.isShellIdentifier
import kotlinx.coroutines.async
import kotlinx.io.readString

/**
 * AST execution dispatch — extracted from [Interpreter].
 *
 * Contains the three core executor entry points:
 *   - [runStatement]: top-level statement dispatcher (pipelines,
 *     `&` background, `&&`/`||`, compound forms).
 *   - [runSimple]: simple-command path — inline-env application,
 *     expansion, resolution, and dispatch.
 *   - [runResolvedSpec]: builtin / function / tool / fork-and-exec
 *     dispatch once command resolution has chosen a target.
 *
 * Pure refactor — every behaviour is identical to before the move.
 */

internal suspend fun Interpreter.runStatement(
    stmt: Statement,
    initialStdin: SuspendSource,
): Int {
    // POSIX `cmd &`: launch the first pipeline as an async job, return 0,
    // skip any `&&`/`||` chain (background is restricted to a single
    // pipeline statement). Only meaningful at the top level — see
    // [dispatchBackground] for the snapshot-and-launch dance.
    if (stmt.background) {
        dispatchBackground(stmt.pipelines.first(), initialStdin)
        return 0
    }
    // errexit suppression for non-last members of an `&&`/`||` chain:
    // POSIX §2.11 says only the LAST command in the AND/OR list counts
    // for `set -e`. Wrap the non-last pipelines in a suppression bump
    // so a `false &&` doesn't abort the script while a trailing
    // `false` after a successful chain still does.
    val pipelines = stmt.pipelines
    val isChain = pipelines.size > 1
    var exit: Int
    // For an `&&`/`||` chain, POSIX §sh:
    //   "errexit shall not apply if the command that fails is part of
    //    the command list immediately following a while or until
    //    keyword, part of the test in an if statement, part of any
    //    command executed in a && or || list except the command
    //    following the final && or ||, or any command in a pipeline
    //    but the last."
    // We suppress errexit during the entire chain and only unsuppress
    // for the *actually-executed* final pipeline (i.e. if the chain
    // short-circuited before reaching it, errexit stays suppressed).
    // Tracks whether the chain's final clause actually ran. If it did,
    // its exit status is the chain's status and errexit applies normally.
    // If it didn't (the chain short-circuited earlier), errexit must NOT
    // fire — bash/POSIX §2.11: errexit ignores intermediate `&&`/`||`
    // failures — but the chain's exit status is still the last actually-
    // executed command's exit (matches bash: `true && false && echo X`
    // exits with 1, not 0).
    var finalClauseRan = false
    if (isChain) {
        errexitSuppressed++
        try {
            exit = runPipeline(pipelines[0], initialStdin)
            for ((idx, op) in stmt.operators.withIndex()) {
                val isLast = idx == stmt.operators.lastIndex
                val shouldRun = (op == Connector.AND && exit == 0) || (op == Connector.OR && exit != 0)
                if (shouldRun) {
                    if (isLast) {
                        errexitSuppressed--
                        finalClauseRan = true
                    }
                    try {
                        exit = runPipeline(pipelines[idx + 1], emptySource())
                    } finally {
                        if (isLast) errexitSuppressed++
                    }
                }
            }
        } finally {
            errexitSuppressed--
        }
    } else {
        exit = runPipeline(pipelines[0], initialStdin)
    }
    lastExit = exit
    // ERR trap (bash extension): fires after any non-zero command under
    // the SAME suppression rules as errexit — bash documents the two as
    // identically gated. Runs before the errexit abort marker so a `trap
    // '…' ERR` handler can observe `$?` and the un-aborted state. The
    // `inheritsTrapErr()` check inside [fireErrTrap] gates further on
    // `set -E` (errtrace) / `declare -ft` per-frame trace bits.
    val suppressedByContext =
        stmt.pipelines.first().negated || (isChain && finalClauseRan.not())
    if (exit != 0 && errexitSuppressed == 0 && !suppressedByContext) {
        fireErrTrap()
    }
    // POSIX errexit: abort the script after a non-zero simple command /
    // pipeline that isn't suppressed (conditions, &&/|| non-last, `!`
    // pipelines). We mark pendingAbort so the runStatements loop bails
    // out at the next top-of-loop check — same mechanism POSIX special
    // builtins use for §2.8.1 abort.
    if (errexit && errexitSuppressed == 0 && exit != 0 &&
        // `!` inverts both exit and errexit semantics — bash explicitly
        // says `! cmd` is NEVER subject to errexit.
        !stmt.pipelines.first().negated &&
        // For `&&`/`||` chains: errexit fires only when the failure is in
        // the FINAL clause. Intermediate failures (e.g. the `false` in
        // `true && false && echo`) don't trigger abort even though they
        // determine the chain's exit code.
        (!isChain || finalClauseRan)
    ) {
        pendingAbort = true
        pendingAbortCode = exit
    }
    return exit
}

// -------- Simple commands --------

/**
 * Builtins that take an array-element argument (a name with a bracketed
 * subscript) and, under `assoc_expand_once`, treat an *unquoted*
 * array-reference word's subscript as a single literal key spanning to the
 * closing bracket. Their args get per-position array-reference flags
 * computed before expansion.
 */
internal val ARRAYREF_ARG_BUILTINS = setOf("read", "printf", "wait")

/**
 * True when [word] is, structurally and BEFORE expansion, a valid
 * array-reference target — a leading literal identifier, an unquoted open
 * bracket, a bracket-balanced subscript, and the close bracket as the final
 * character. Quoted brackets and dynamic expansions are opaque (they never
 * contribute subscript syntax), so an unquoted name-with-expansion-subscript
 * qualifies but a fully double-quoted word, or a literal word whose first
 * bracket pair is empty with a trailing close bracket, does not.
 *
 * This is the signal that, under `assoc_expand_once`, lets a builtin parse
 * the post-expansion subscript greedily to the last close bracket (so a
 * subscript that expands to a single right-bracket becomes the key), while a
 * quoted or non-reference word keeps the strict "first close bracket ends the
 * subscript" parse and is rejected.
 */
internal fun isArrayRefWord(word: Word): Boolean {
    if (word.parts.isEmpty()) return false
    // Skeleton: unquoted literal text verbatim; every quoted/expansion part
    // collapses to one opaque placeholder so no bracket syntax leaks from it.
    val sb = StringBuilder()
    for (p in word.parts) {
        when (p) {
            is WordPart.Literal -> sb.append(p.value)
            else -> sb.append('\u0001')
        }
    }
    val s = sb.toString()
    val lb = s.indexOf('[')
    if (lb <= 0) return false
    if (!isShellIdentifier(s.substring(0, lb))) return false
    var depth = 0
    var close = -1
    var i = lb
    while (i < s.length) {
        when (s[i]) {
            '[' -> {
                depth++
            }

            ']' -> {
                depth--
                if (depth == 0) {
                    close = i
                    break
                }
            }
        }
        i++
    }
    if (close < 0) return false
    // Reject empty subscript `name[]` and any trailing text after the close.
    if (close == lb + 1) return false
    return close == s.length - 1
}

/** A builtin argument of the shape `base[sub]` (the closing `]` is the last char). */
internal data class BracketTarget(
    val base: String,
    val sub: String,
)

/**
 * Split a builtin target `NAME[sub]` into base and (greedy, to the last `]`)
 * subscript, or null when the word isn't of that shape. Shared by the
 * builtins that write an array element (read/wait/declare/unset); each then
 * applies its own validity rules. `printf` open-codes the same split because
 * it lives in a separate module with no access to interpreter helpers.
 */
internal fun parseBracketTarget(name: String): BracketTarget? {
    val lb = name.indexOf('[')
    val rb = name.lastIndexOf(']')
    if (lb <= 0 || rb != name.length - 1) return null
    return BracketTarget(name.substring(0, lb), name.substring(lb + 1, rb))
}

/**
 * The greedy-key gate: the arg at [subArgIdx] was an unquoted array-reference
 * word, `assoc_expand_once` is set, and [base] is an associative array — so
 * its post-expansion subscript is one literal key spanning to the closing
 * `]`. Shared by `read` and `wait`; `printf` uses the equivalent
 * `CommandContext` fields (arrayRefArgs / isAssocArray / assocExpandOnce).
 */
internal fun Interpreter.isGreedyAssocRef(
    base: String,
    subArgIdx: Int,
): Boolean =
    shoptOptions["assoc_expand_once"] == true &&
        subArgIdx in currentArrayRefArgs &&
        varTable.find(base)?.isAssoc == true

internal suspend fun Interpreter.runSimple(
    cmd: SimpleCommand,
    stdio: Interpreter.Stdio,
): Int {
    // Bash fires the DEBUG trap before each simple command, after any
    // command substitution within the command line would normally happen
    // — but practical implementations vary. We fire it *before* word
    // expansion, which is the simpler and most common observable order.
    fireDebugTrap()
    // extdebug + `return 2` from DEBUG trap → skip this command. Bash's
    // dbg-support2 test relies on this: a DEBUG handler that returns 2
    // suppresses the next assignment/command so program state stays
    // unchanged. Reset after consumption so the gate is one-shot.
    if (extdebugEnabled && lastDebugTrapExit == 2) {
        lastDebugTrapExit = 0
        return 0
    }
    // Reset the "arith substitution failed" guard at the START of the
    // command boundary — it spans inline-assignment evaluation AND the
    // subsequent argv expansion (both of which can trigger arith), and
    // any failure in either should be deduplicated to a single
    // diagnostic.
    arithSubstFailedInWordExpansion = false
    val inlineEnv = linkedMapOf<String, String>()
    val bareAssignments = mutableListOf<InlineAssignment>()
    // For pure assignment-only commands (no command word), the inlineEnv
    // map is unused — `applyBareAssignment` re-expands each RHS into the
    // shell variable table. Skipping the eager expand here is required for
    // bash correctness: expanding the RHS twice would re-fire any cmdsub
    // side effects (e.g. `LINES3=$(< /tmp/nope)` would print the
    // "No such file" diagnostic twice).
    val needsInlineEnv = cmd.name != null
    for (a in cmd.assignments) {
        when (a) {
            is InlineAssignment.Scalar -> {
                if (needsInlineEnv) {
                    val v = expandAssignmentValue(a.value)
                    // Inline-env values are subject to case attributes — applying
                    // them here keeps the prefix-only env in sync with what a
                    // child or builtin would see if it read the variable back.
                    // For typeset -i targets, `+=` is arithmetic add, not
                    // string concat — bash applies the integer attribute even
                    // to prefix assignments (POSIXLY_CORRECT or not).
                    val combined =
                        applyIntegerAttr(a.name, v, env[a.name], a.append)
                            ?: if (a.append) (env[a.name] ?: "") + v else v
                    inlineEnv[a.name] = combined
                }
                bareAssignments += a
            }

            is InlineAssignment.Array -> {
                // Array literals can't be carried in a child-process env;
                // they only apply when there's no command word (bare).
                bareAssignments += a
            }

            is InlineAssignment.Indexed -> {
                bareAssignments += a
            }
        }
    }
    if (cmd.name == null) {
        // bash special: `$(<file)` (cmdsub with only an INPUT redir,
        // no command word) reads the now-installed stdin and emits
        // it on stdout — equivalent to `$(cat file)`. [applyRedirections]
        // has already opened the redirect and installed it on stdio.stdin
        // (or already emitted "No such file" and returned null upstream),
        // so we read what's already there. Empty stdin → empty output.
        if (bareAssignments.isEmpty() && cmd.argAssignments.isEmpty() &&
            cmd.redirections.any {
                it.operator == com.accucodeai.kash.ast.RedirOp.INPUT ||
                    it.operator == com.accucodeai.kash.ast.RedirOp.HERESTRING ||
                    it.operator == com.accucodeai.kash.ast.RedirOp.HEREDOC ||
                    it.operator == com.accucodeai.kash.ast.RedirOp.HEREDOC_STRIP
            }
        ) {
            try {
                val buf = kotlinx.io.Buffer()
                stdio.stdin.readAtMostTo(buf, Long.MAX_VALUE)
                stdio.stdout.writeUtf8(buf.readString())
            } catch (_: Throwable) {
                // Stdin already drained or unreadable — silent empty.
            }
            return 0
        }
        // Assignment-only simple command (no command word). xtrace
        // emits each `NAME=value` on a separate line, matching bash's
        // `set -x` behavior for bare assignments.
        if (xtrace) {
            for (a in bareAssignments) {
                when (a) {
                    is InlineAssignment.Scalar -> {
                        val v = expandAssignmentValue(a.value)
                        // Use xtraceQuote so control chars (tab, newline, etc.) render
                        // as ANSI-C `$'…'` — bash's xtrace form for special bytes in
                        // assignments (`+ DEFAULT_IFS=$' \t\n'`).
                        emitXtrace(
                            sink = stdio.stderr,
                            rendered = "${a.name}${if (a.append) "+=" else "="}${xtraceQuote(v)}",
                        )
                    }

                    is InlineAssignment.Array, is InlineAssignment.Indexed -> {
                        // Array literals: bash emits the source form
                        // (`metas=(\| \& ...)`), not post-expansion values —
                        // the test comment in set-x2.sub even calls out
                        // "some time we will have better compound assignment
                        // printing (after expansion)". Use AstPrinter to
                        // deparse the AST back to source-ish syntax.
                        emitXtrace(sink = stdio.stderr, rendered = AstPrinter.formatAssignment(a))
                    }
                }
            }
        }
        // Bash: `var=$(cmd)` reports cmd's exit in `$?`; pure literal
        // assignment `var=value` is always 0. Track whether any
        // command substitution fired during the assignment's RHS
        // expansion (`cmdsubExitSeen` is set by evalCommandSubstitution)
        // and surface it; otherwise default to 0.
        val priorSeen = cmdsubExitSeen
        cmdsubExitSeen = false
        val priorLastExit = lastExit
        for (a in bareAssignments) applyBareAssignment(a)
        val result = if (cmdsubExitSeen) lastExit else 0
        cmdsubExitSeen = priorSeen
        if (!cmdsubExitSeen && result == 0) lastExit = priorLastExit
        return result
    }
    // Brace-expand the command word too so `{cat,echo} foo` works
    // (bash treats brace expansion as the very first step on every word).
    val braceOn = shoptOptions["braceexpand"] != false
    val nameWords = if (braceOn) expandBraces(cmd.name, enabled = true) else listOf(cmd.name)
    val nameArgs = nameWords.flatMap { expandArg(it) }
    if (nameArgs.isEmpty()) return 0
    val name = nameArgs.first()
    // For builtins that re-parse a `NAME[sub]` target, track which expanded
    // args came from unquoted array-reference words (computed during the
    // single expansion to avoid double command-substitution). The flags are
    // offset by the name-derived prefix so they index into the final `args`.
    val arrayRefArgIndices: Set<Int>
    val args: List<String>
    if (name in ARRAYREF_ARG_BUILTINS) {
        val flags = mutableListOf<Boolean>()
        val cmdArgs = flatExpandArgsTracked(cmd.args, flags)
        val prefix = nameArgs.drop(1)
        args = prefix + cmdArgs
        arrayRefArgIndices =
            flags
                .withIndex()
                .filter { it.value }
                .map { prefix.size + it.index }
                .toSet()
    } else {
        args = nameArgs.drop(1) + flatExpandArgs(cmd.args)
        arrayRefArgIndices = emptySet()
    }
    if (xtrace) {
        // Bash xtrace splits inline env from the command: `foo=one echo $foo`
        // emits `+ foo=one` then `+ echo onetwo` — each prefix assignment is
        // its own trace line, with the command standing alone after.
        for ((n, v) in inlineEnv) {
            emitXtrace(sink = stdio.stderr, rendered = "$n=${xtraceQuote(v)}")
        }
        emitXtrace(sink = stdio.stderr, rendered = renderSimpleForXtrace(emptyMap(), listOf(name) + args))
    }
    // Restricted shell (set -r): forbid `/' in command names. Bash emits
    // this diagnostic and returns 1 before any resolution attempt.
    if (restricted) {
        if ('/' in name) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}$name: restricted: cannot specify `/' in command names\n")
            return 1
        }
        if (name == "cd") {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}cd: restricted\n")
            return 1
        }
    }
    // Bash: a failed `$((expr))` substitution anywhere in argv aborts
    // the simple command after word expansion — the diagnostic has
    // already been printed by [evalArithmetic]. Exit 1 mirrors the
    // status bash sets for "word expansion error in non-special
    // builtin invocation."
    if (arithSubstFailedInWordExpansion) {
        arithSubstFailedInWordExpansion = false
        return 1
    }
    // Stash argAssignments for the assignment-aware builtins. Saved &
    // restored so nested command invocations (e.g. `eval declare a=1`)
    // see their own assignments, not the parent's.
    val savedArgAssignments = currentArgAssignments
    currentArgAssignments = cmd.argAssignments
    val savedArrayRefArgs = currentArrayRefArgs
    currentArrayRefArgs = arrayRefArgIndices

    // POSIX [XCU §2.9.1](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_09_01):
    // variable assignments precede command-word processing — so a
    // `PATH=/foo cmd` must walk `/foo`, not the surrounding PATH.
    // We mutate env for the resolve, then restore (special-builtin
    // assignment persistence is handled later by runResolvedSpec).
    val savedForResolve =
        if (inlineEnv.isEmpty()) {
            null
        } else {
            val before = inlineEnv.keys.associateWith { env[it] }
            for ((k, v) in inlineEnv) env[k] = v
            before
        }
    val resolved =
        try {
            resolveCommand(name)
        } finally {
            if (savedForResolve != null) {
                for ((k, prior) in savedForResolve) {
                    if (prior == null) env.remove(k) else env[k] = prior
                }
            }
        }
    return try {
        when (resolved) {
            is Resolved.Builtin -> {
                runResolvedSpec(resolved.spec, name, args, inlineEnv, stdio)
            }

            is Resolved.Intrinsic -> {
                // POSIX: special builtins persist inline assignments;
                // regular builtins/intrinsics see them only for the
                // duration of the call. Either way the assignments
                // must be visible inside the intrinsic so e.g.
                // `IFS=$'\001' read x` runs with the temp IFS.
                val priorForRevert =
                    if (!resolved.entry.isSpecial && inlineEnv.isNotEmpty()) {
                        val before = inlineEnv.keys.associateWith { env[it] }
                        for ((k, v) in inlineEnv) env[k] = v
                        before
                    } else {
                        if (resolved.entry.isSpecial) {
                            for ((k, v) in inlineEnv) {
                                applySpecialBuiltinPrefix(k, v)
                            }
                        }
                        null
                    }
                val rc =
                    try {
                        runIntrinsic(name, args, stdio)
                            ?: error("intrinsic $name in catalog but missing from runIntrinsic dispatch")
                    } finally {
                        if (priorForRevert != null) {
                            for ((k, prior) in priorForRevert) {
                                if (prior == null) env.remove(k) else env[k] = prior
                            }
                        }
                    }
                if (resolved.entry.isSpecial && !interactive && rc != 0 &&
                    name !in Interpreter.NON_ABORTING_SPECIAL_BUILTINS
                ) {
                    pendingAbort = true
                    pendingAbortCode = rc
                }
                rc
            }

            is Resolved.Function -> {
                runFunctionCall(name, resolved.body, args, stdio, inlineEnv)
            }

            is Resolved.Script -> {
                runScript(resolved.path, args, inlineEnv, stdio)
            }

            null -> {
                // Mirror POSIX [XCU §2.9.1.1](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_09_01_01):
                // path-qualified names invoke execl() and surface its errno
                // (ENOENT / EISDIR / EACCES → 127 / 126 / 126). Bare names
                // that PATH-search misses get the canonical "command not found".
                if ('/' in name) {
                    val resolvedPath = Paths.resolve(cwd, name)
                    val exists =
                        try {
                            fs.exists(resolvedPath)
                        } catch (_: Throwable) {
                            false
                        }
                    // Bash keeps the leading `./` in path-qualified
                    // diagnostics: `bash: ./bad: No such file or directory`.
                    // (An earlier comment here claimed bash *strips* the
                    // `./` — incorrect; bash 5.x keeps it. PathResolutionTest
                    // pins the un-stripped form.)
                    if (!exists) {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}$name: No such file or directory\n")
                        127
                    } else if (fs.isDirectory(resolvedPath)) {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}$name: Is a directory\n")
                        126
                    } else {
                        // File exists but resolveFsPath returned null — implies the
                        // body couldn't be read or it's some non-executable entry
                        // we don't recognize. POSIX EACCES → 126.
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}$name: Permission denied\n")
                        126
                    }
                } else if (name.startsWith("%") && monitor) {
                    // Bash: a bare `%N` / `%+` / `%foo` token at command
                    // position is shorthand for `fg %N` (POSIX §job-control:
                    // "If %N is given as a command name, the implementation
                    // shall treat it as `fg %N`"). Only honored when monitor
                    // mode is on; otherwise it stays unknown.
                    runFgIntrinsic(listOf(name), stdio)
                } else {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}$name: command not found\n")
                    127
                }
            }
        }
    } finally {
        currentArgAssignments = savedArgAssignments
        currentArrayRefArgs = savedArrayRefArgs
    }
}

// -------- Command resolution (POSIX XCU §2.9.1.1) --------

internal suspend fun Interpreter.runResolvedSpec(
    spec: CommandSpec,
    name: String,
    args: List<String>,
    inlineEnv: Map<String, String>,
    stdio: Interpreter.Stdio,
): Int {
    if (spec.isSpecial) {
        for ((k, v) in inlineEnv) applySpecialBuiltinPrefix(k, v)
    }

    val exitCode: Int =
        if (spec.kind == CommandKind.TOOL) {
            // POSIX external utility — fork the process via the VM's
            // spawn primitive so env / cwd / fdTable mutations the tool
            // performs are isolated to the child invocation and die when
            // it returns. Inline assignments (`PATH=foo cmd`) apply only
            // to the child's env — the fork already gives us that
            // isolation for free. [BUILTIN] and [SPECIAL_BUILTIN] still
            // run in-process below (bash semantics for regulars; POSIX
            // semantics for specials).
            val sr =
                machine.spawn(process) { child ->
                    // POSIX exec: only exports cross the boundary.
                    pruneToExportedEnv(child)
                    if (inlineEnv.isNotEmpty()) child.env.putAll(inlineEnv)
                    child.commandName = name
                    child.argv = listOf(name) + args
                    installStdioFds(child, stdio)
                    val ctx =
                        CommandContext(
                            process = child,
                            shellRunner = makeShellRunner(callerStderr = stdio.stderr),
                            // Nested utility-runner calls (xargs UTIL,
                            // find -exec UTIL ;) inside this tool fork
                            // off the *child*, not the shell — that
                            // makes the invocation a grandchild and
                            // preserves env/cwd mutations xargs may
                            // have made before invoking UTIL.
                            utilityRunner = makeUtilityRunner(child),
                            isInteractive = interactive,
                            userDb = userDb,
                            sandbox = sandbox,
                            shellDiagPrefix = shellDiagPrefix(),
                            assocExpandOnce = shoptOptions["assoc_expand_once"] == true,
                        )
                    spec.command?.run(args, ctx)?.exitCode ?: run {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}$name: command not found\n")
                        127
                    }
                }
            // Foreground TOOL: wait + reap right away. Background
            // dispatch (`&`) wraps this whole branch in a session-scope
            // coroutine elsewhere, so reaping there is the bg's call.
            //
            // RLIMIT_NPROC: spawn failure surfaces as pid=-1 with the
            // exit deferred pre-completed to status 1. Emit the
            // bash-style fork error and skip the wait.
            if (sr.pid < 0) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}fork: retry: Resource temporarily unavailable\n")
                1
            } else {
                val code = (machine.wait(sr.pid) as? ExitStatus.Exited)?.code ?: 0
                // bash fires the SIGCHLD trap for every child that exits,
                // including foreground ones. Foreground TOOLs write
                // directly to the parent's stdout (no job-buffer drain
                // step), so the trap can fire as soon as wait returns.
                deliverSignal(com.accucodeai.kash.api.signal.SigChld)
                code
            }
        } else {
            // SPECIAL_BUILTIN / BUILTIN — run on the shell's own process
            // so mutations stick (cd's newCwd, export's env, etc.).
            //
            // Non-special builtin with inline env (`PATH=foo cmd`):
            // POSIX says the assignments are scoped to that one call.
            // Since the tool reads `ctx.process.env` (which IS the
            // shell's env), temp-mutate then restore in finally. For
            // special builtins, mutations stick (already applied above
            // via `for ((k, v) in inlineEnv) env[k] = v`).
            val savedEnv: Map<String, String?>? =
                if (!spec.isSpecial && inlineEnv.isNotEmpty()) {
                    val snap = inlineEnv.keys.associateWith { env[it] }
                    for ((k, v) in inlineEnv) {
                        env[k] = v
                    }
                    snap
                } else {
                    null
                }
            val savedProcessEnv: Map<String, String?>? =
                if (!spec.isSpecial && inlineEnv.isNotEmpty()) {
                    val snap = inlineEnv.keys.associateWith { process.env[it] }
                    // `VAR=val cmd`: bash exports VAR to cmd's env for
                    // the duration of the call. Builtins read
                    // `ctx.process.env` directly, so force the mirror —
                    // the ProcessEnvAdapter's "only mirror exported"
                    // rule would otherwise hide the inline assignment
                    // from `printenv` et al.
                    for ((k, v) in inlineEnv) process.env[k] = v
                    snap
                } else {
                    null
                }
            // Save fd 0/1/2 before clobbering with the redirected stdio,
            // so we can restore on exit. POSIX semantics: redirections
            // on a builtin (`echo > f`) are per-command — the shell's
            // own fd 0/1/2 must look the same after the builtin returns.
            //
            // Without this, an interactive REPL whose `ctx.stdout` is
            // computed lazily from `process.fdTable[1].ofd.sink` will
            // see the redirected sink on every subsequent line and
            // emit invisible output.
            //
            // Retain bumps the OFD's refcount so [installStdioFds]'s
            // implicit release (via [installFd]) doesn't bring our
            // saved entry to zero refs. On restore we put the entry
            // back, releasing the now-displaced redirected OFD; the
            // explicit release after that compensates for our retain.
            val savedFd0 = process.fdTable[0]?.also { it.ofd.retain() }
            val savedFd1 = process.fdTable[1]?.also { it.ofd.retain() }
            val savedFd2 = process.fdTable[2]?.also { it.ofd.retain() }
            installStdioFds(process, stdio)
            val ctx =
                CommandContext(
                    process = process,
                    shellRunner = makeShellRunner(callerStderr = stdio.stderr),
                    utilityRunner = makeUtilityRunner(),
                    isInteractive = interactive,
                    userDb = userDb,
                    sandbox = sandbox,
                    shellDiagPrefix = shellDiagPrefix(),
                    assocExpandOnce = shoptOptions["assoc_expand_once"] == true,
                    setArrayElement = { name, sub, value -> setBuiltinArrayElementTarget(name, sub, value) },
                    arrayRefArgs = currentArrayRefArgs,
                    isAssocArray = { n -> varTable.find(n)?.isAssoc == true },
                )
            val r =
                try {
                    spec.command?.run(args, ctx) ?: run {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}$name: command not found\n")
                        return 127
                    }
                } catch (
                    _: com.accucodeai.kash.api.io.BrokenPipeException,
                ) {
                    // POSIX SIGPIPE behavior: writes to a closed pipe
                    // cause the writer to exit. Bash terminates the
                    // command with 128+13 = 141. Kash has no real
                    // signal delivery; we synthesize the same exit
                    // code so the surrounding script keeps going (as
                    // bash does for `cmd | head` where cmd writes
                    // past head's read close).
                    com.accucodeai.kash.api
                        .CommandResult(exitCode = 141)
                } finally {
                    if (savedEnv != null) {
                        for ((k, v) in savedEnv) {
                            if (v == null) env.remove(k) else env[k] = v
                        }
                    }
                    if (savedProcessEnv != null) {
                        for ((k, v) in savedProcessEnv) {
                            if (v == null) process.env.remove(k) else process.env[k] = v
                        }
                    }
                    // Restore fd 0/1/2 to their pre-redirection entries.
                    // Each `put` displaces the redirected OFD, releasing
                    // it (closing the sink iff owning); the explicit
                    // release below balances the retain above so the
                    // restored entry's refcount lands at 1 (just the
                    // fd-table reference).
                    restoreFd(process, 0, savedFd0)
                    restoreFd(process, 1, savedFd1)
                    restoreFd(process, 2, savedFd2)
                }
            r.newCwd?.let {
                cwd = it
                env["PWD"] = it
            }
            // Tools that pass through `ctx.process.env` (printf -v with a
            // bracket-form name, etc.) can land a `NAME[sub]` entry in
            // the flat env map. Route it back through the typed
            // [ProcessEnvAdapter] so the varTable's array storage gets
            // the value and `declare -p NAME` reflects it.
            val bracketKeys = process.env.keys.filter { '[' in it && it.endsWith(']') }
            for (key in bracketKeys) {
                val v = process.env[key] ?: continue
                process.env.remove(key)
                env[key] = v
            }
            r.exitCode
        }

    if (spec.isSpecial && !interactive && exitCode != 0 && name !in Interpreter.NON_ABORTING_SPECIAL_BUILTINS) {
        pendingAbort = true
        pendingAbortCode = exitCode
    }
    return exitCode
}

/**
 * Apply a `VAR=val` prefix on a special-builtin call. POSIX §2.9.1.4
 * specifies that the assignment "shall affect the current execution
 * environment", but bash's posix-mode behavior for special builtins
 * invoked INSIDE a function unwinds past any function-local shadow
 * binding so the value lands in the caller's scope — visible to
 * `bash/func3.sub`'s `func() { var=20 return 5 }; var=30 func`
 * sequence, where the expected outer var after the call is 20 (the
 * special-builtin prefix) not 30 (the function-call prefix).
 *
 * Outside posix mode kash keeps the simpler "write through the
 * current scope" semantics — matches bash's default behavior.
 */
internal suspend fun Interpreter.applySpecialBuiltinPrefix(
    name: String,
    value: String,
) {
    if (posixModeRuntime && varTable.scopeDepth > 0) {
        // Temporarily remove the function-local shadow (if any) so
        // `env[name] = value` walks past it and lands in the global
        // binding, then re-shadow so reads inside the rest of the
        // function still see the temp-env value of `name`.
        val shadow = varTable.detachLocalShadow(name)
        env[name] = value
        if (shadow != null) varTable.reattachLocalShadow(name, shadow)
        return
    }
    env[name] = value
}
