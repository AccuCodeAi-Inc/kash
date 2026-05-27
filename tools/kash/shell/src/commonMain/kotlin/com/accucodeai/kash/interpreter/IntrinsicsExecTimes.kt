package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import kotlin.time.Duration

// POSIX `exec` (redirection-only form) and `times` intrinsics.

/**
 * POSIX `exec`. With no command word, the redirections that were already
 * applied for this SimpleCommand are persisted onto the shell's
 * [Interpreter.outSink] / [Interpreter.errSink]. This is the canonical
 * `exec >logfile`, `exec 2>&1`, `exec 2>>err` form.
 *
 * With a command word: real bash calls `execve()` to replace the shell
 * process. kash runs in-process — there's no separate OS process to
 * replace — but the *semantic* (run CMD, then never return to the
 * surrounding script, propagating CMD's exit) is implementable as a
 * tail-call: run synchronously, throw [Interpreter.ScriptAbortException]
 * with the child's exit code. The current [com.accucodeai.kash.api.KashProcess]
 * slot reports that status when the shell coroutine unwinds.
 *
 * Flags supported:
 *  - `-a NAME`  — override argv[0] (CMD's `$0`).
 *  - `-l`       — prefix argv[0] with `-` (login-shell convention).
 *  - `-c`       — clear env before invoking CMD (only exports stripped;
 *                 since we're about to abort the script, no restore needed).
 *  - `--`       — end-of-options.
 *
 * Input redirection (`exec <FILE`) is accepted at parse-time (the
 * SimpleCommand carries the redirection) but the stdin source used by
 * subsequent statements lives as a local in [Interpreter.run]'s loop — so
 * the input side persists only until the next statement reads it. That
 * matches bash for the common case of `exec <FILE; while read line; do …`
 * because the read happens in the same outer statement context. Multi-
 * statement input persistence after `exec <FILE` is a known gap.
 */
internal suspend fun Interpreter.runExecIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (args.isNotEmpty()) {
        if (restricted) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}exec: restricted\n")
            return 1
        }
        var i = 0
        var aName: String? = null
        var loginShell = false
        var clearEnv = false
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--" -> {
                    i++
                    break
                }

                a == "-a" -> {
                    if (i + 1 >= args.size) {
                        stdio.stderr.writeUtf8(
                            "${shellDiagPrefix()}exec: -a: option requires an argument\n",
                        )
                        return 2
                    }
                    aName = args[i + 1]
                    i += 2
                }

                a == "-l" -> {
                    loginShell = true
                    i++
                }

                a == "-c" -> {
                    clearEnv = true
                    i++
                }

                a.startsWith("-") && a.length > 1 -> {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}exec: $a: invalid option\n")
                    stdio.stderr.writeUtf8(
                        "exec: usage: exec [-cl] [-a name] [command [argument ...]] [redirection ...]\n",
                    )
                    return 2
                }

                else -> {
                    break
                }
            }
        }
        if (i >= args.size) {
            // Only flags given (e.g. `exec -l`) — bash treats this as the
            // redirections-only form, persisting and returning 0.
            adoptPersistentRedirections(stdio)
            return 0
        }
        val cmd = args[i]
        val cmdArgs = args.drop(i + 1)
        // Effective name CMD sees as $0:
        //   -a NAME wins (bash sets argv0 = NAME);
        //   else, the path/word as-typed.
        // -l prefixes with `-` so login shells can detect themselves.
        val displayName =
            buildString {
                if (loginShell) append('-')
                append(aName ?: cmd)
            }
        // -c: bash clears the environment before exec. In-process we don't
        // un-set our process-internal mirror (other coroutines might depend
        // on the static env shape), but we DO drop all exports so the
        // child-utility view (process.env) reflects an empty inherited env.
        // Since we ScriptAbortException out immediately after the call,
        // there's no restore — exactly matching real `exec`'s "never returns"
        // contract.
        if (clearEnv) {
            // Strip every export bit; everything that was visible to a
            // forked utility via `pruneToExportedEnv` now isn't.
            for (name in varTable.visibleNames()) {
                varTable.find(name)?.attrs?.remove(VarAttr.Export)
            }
            // Also clear the process-level env mirror so any new TOOL fork
            // started by `cmd` starts from a blank inherited environment.
            process.env.clear()
        }
        // Resolve and dispatch via the standard command path. We deliberately
        // bypass [runSimple]: we don't want re-expansion (the caller's
        // expansion already produced these args), and we don't want the
        // inline-env / arg-assignment machinery applied to `exec` itself.
        val resolved = resolveCommand(cmd)
        val exit =
            when (resolved) {
                is Resolved.Builtin -> {
                    runResolvedSpec(resolved.spec, displayName, cmdArgs, emptyMap(), stdio)
                }

                is Resolved.Intrinsic -> {
                    runIntrinsic(displayName, cmdArgs, stdio)
                        ?: error("intrinsic $displayName missing from runIntrinsic dispatch")
                }

                is Resolved.Function -> {
                    runFunctionCall(displayName, resolved.body, cmdArgs, stdio, emptyMap())
                }

                is Resolved.Script -> {
                    runScript(resolved.path, cmdArgs, emptyMap(), stdio)
                }

                null -> {
                    // Match bash's exec-not-found phrasing exactly.
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}$cmd: not found\n")
                    127
                }
            }
        // Tail-call: never return to the surrounding script. Bash exits
        // the shell with the exec'd command's status (unless `execfail`
        // shopt is set AND the command was not found — kash doesn't model
        // execfail, but the common case is "exec succeeded; shell exits").
        throw Interpreter.ScriptAbortException(exit)
    }
    adoptPersistentRedirections(stdio)
    return 0
}

private fun Interpreter.adoptPersistentRedirections(stdio: Stdio) {
    // The current [stdio] is the post-applyRedirections sink set. Adopt
    // those into the persistent script-level sinks; flag executeWithStdio
    // to skip the matching close in its finally so the file handles
    // outlive this SimpleCommand.
    outSink = stdio.stdout
    errSink = stdio.stderr
    outSinkIsTty = stdio.stdoutIsTty
    errSinkIsTty = stdio.stderrIsTty
    execPersistedRedirections = true
}

/**
 * POSIX `times`. Output format:
 * ```
 * <m>m<s>.<ms>s <m>m<s>.<ms>s
 * <m>m<s>.<ms>s <m>m<s>.<ms>s
 * ```
 * Line 1 = shell (user, system). Line 2 = children (user, system). kash
 * has no child accounting, so children are `0m0.000s`. Shell "user" is
 * wall-clock elapsed since shell start; "system" is `0m0.000s` — JVM-side
 * user/system split isn't worth a per-platform expect for a builtin
 * scripts rarely use outside benchmarks.
 */
internal suspend fun Interpreter.runTimesIntrinsic(stdio: Stdio): Int {
    val shellUser = clock.elapsedSinceShellStart()
    val zero = Duration.ZERO
    val out =
        buildString {
            append(formatMmSs(shellUser)).append(' ').append(formatMmSs(zero)).append('\n')
            append(formatMmSs(zero)).append(' ').append(formatMmSs(zero)).append('\n')
        }
    stdio.stdout.writeUtf8(out)
    return 0
}

private fun formatMmSs(d: Duration): String {
    val totalMillis = d.inWholeMilliseconds.coerceAtLeast(0)
    val minutes = totalMillis / 60_000
    val secMillis = totalMillis - minutes * 60_000
    val seconds = secMillis / 1000
    val millis = (secMillis - seconds * 1000).toInt()
    val ms = millis.toString().padStart(3, '0')
    return "${minutes}m$seconds.${ms}s"
}
