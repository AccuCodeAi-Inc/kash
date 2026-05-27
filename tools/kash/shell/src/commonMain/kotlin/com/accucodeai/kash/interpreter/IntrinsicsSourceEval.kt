package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.emptySource
import com.accucodeai.kash.api.util.splitPath
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.interpreter.Interpreter.Stdio

// POSIX `.` (source) and `eval` intrinsics.

/**
 * POSIX `.` (and bash alias `source`): read FILE and execute its contents
 * in the *current* shell environment. Differs from `sh FILE` in that env,
 * cwd, locals, aliases, and function defs mutated by the file persist.
 *
 * Resolution: an absolute or `/`-containing path resolves against [cwd];
 * a bare name searches `$PATH` (POSIX §dot). Optional positional
 * arguments after FILE replace `$1..$N` for the duration of the file;
 * the caller's positionals are restored on exit.
 */

internal suspend fun Interpreter.runDotIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (args.isEmpty()) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}.: filename argument required\n")
        return 2
    }
    val name = args[0]
    if (restricted && '/' in name) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}.: $name: restricted\n")
        return 1
    }
    val resolved =
        if ('/' in name) {
            Paths.resolve(cwd, name).takeIf { fs.exists(it) && !fs.isDirectory(it) }
        } else {
            // Bash (non-POSIX mode): walk PATH first, then fall back to cwd.
            // POSIX `sh` only walks PATH. We're closer to bash here so the
            // common script idiom `. test-helpers` (no leading ./) works.
            val pathHit =
                splitPath(env["PATH"])
                    .asSequence()
                    .map { dir -> if (dir.isEmpty()) Paths.resolve(cwd, name) else "$dir/$name" }
                    .firstOrNull { fs.exists(it) && !fs.isDirectory(it) }
            pathHit ?: Paths.resolve(cwd, name).takeIf {
                !posixModeRuntime && fs.exists(it) && !fs.isDirectory(it)
            }
        }
    if (resolved == null) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}.: $name: No such file or directory\n")
        return 127
    }
    val text =
        try {
            fs.readBytes(resolved).decodeToString()
        } catch (t: Throwable) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}.: $name: ${t.message ?: "read error"}\n")
            return 1
        }
    // Per-statement read-parse-execute — bash sources files line-by-line
    // and executes pre-error statements before emitting any diagnostic.
    // Each statement is re-parsed with the LIVE `posixModeRuntime` so a
    // sourced file that runs `set -o posix` affects subsequent statements'
    // lex (POSIX shell reader-loop semantics).
    val stream =
        try {
            com.accucodeai.kash.parser.StatementStream(
                source = text,
                aliasResolver = aliasResolver,
                posixModeProvider = { posixModeRuntime },
                extglobProvider = { isShoptEnabled("extglob") },
                aliasVersionProvider = { aliasVersion },
            )
        } catch (t: Throwable) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}.: $name: ${t.message ?: "parse error"}\n")
            return 2
        }
    val extraPositional = args.drop(1)
    val savedPositional = positional
    val savedDollarZero = dollarZero
    if (extraPositional.isNotEmpty()) positional = extraPositional
    // `.` runs in the current shell — outSink/errSink already point at
    // the caller; just route stdio's sinks through for this call's scope.
    val savedOut = outSink
    val savedErr = errSink
    outSink = stdio.stdout
    errSink = stdio.stderr
    var exit = 0
    sourcingDepth++
    try {
        var s = stdio.stdin
        loop@ while (true) {
            try {
                when (val r = stream.next()) {
                    is com.accucodeai.kash.parser.StatementStream.NextResult.Statement -> {
                        exit = runStatement(r.statement, s)
                        s = emptySource()
                    }

                    is com.accucodeai.kash.parser.StatementStream.NextResult.Error -> {
                        val ln = r.error.line
                        val prefix = if (ln > 0) "$name: line $ln" else "."
                        stdio.stderr.writeUtf8("$prefix: ${r.error.message ?: "parse error"}\n")
                        exit = 2
                        break@loop
                    }

                    is com.accucodeai.kash.parser.StatementStream.NextResult.Eof -> {
                        break@loop
                    }
                }
            } catch (ret: Interpreter.ReturnException) {
                // `return N` from a sourced script terminates the
                // sourcing only — caller continues with the
                // returned exit status. Without this catch the
                // ReturnException unwinds past the source loop and
                // aborts the outer script (bash/func3.sub
                // `var=40 return 2` test).
                exit = ret.code
                break@loop
            }
        }
        return exit
    } finally {
        sourcingDepth--
        outSink = savedOut
        errSink = savedErr
        if (extraPositional.isNotEmpty()) positional = savedPositional
        dollarZero = savedDollarZero
    }
}

/**
 * `eval ARGS...` — parse joined args as a script and run it through the
 * caller's stdio. No capture: eval's stdout/stderr go wherever the caller's
 * went.
 */
internal suspend fun Interpreter.runEvalIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    val text = args.joinToString(" ")
    if (text.isBlank()) return 0
    // Statement-streaming parse — bash's eval executes valid statements
    // before reporting a later syntax error.
    // Bash's eval diagnostic format is `<script>: eval: line <call>: <msg>`
    // — *without* the usual `line N:` from shellDiagPrefix, since `eval`
    // substitutes itself for the script-name slot and supplies its own
    // line marker.
    val evalPrefix =
        if (dollarZero == "kash") "eval: line $currentLine: " else "$dollarZero: eval: line $currentLine: "

    val stream =
        try {
            com.accucodeai.kash.parser.StatementStream(
                source = text,
                aliasResolver = aliasResolver,
                posixModeProvider = { posixModeRuntime },
                // Pin eval's internal line numbering to the caller's
                // current line so diagnostics like "syntax error: unexpected
                // end of file from `{' command on line N" report the
                // script-level N where the eval was invoked, matching bash.
                startLine = currentLine,
                extglobProvider = { isShoptEnabled("extglob") },
                aliasVersionProvider = { aliasVersion },
            )
        } catch (t: Throwable) {
            stdio.stderr.writeUtf8("${evalPrefix}${t.message}\n")
            stdio.stderr.writeUtf8("$evalPrefix`$text'\n")
            return 2
        }
    val savedOut = outSink
    val savedErr = errSink
    outSink = stdio.stdout
    errSink = stdio.stderr
    var exit = 0
    try {
        var s = stdio.stdin
        loop@ while (true) {
            when (val r = stream.next()) {
                is com.accucodeai.kash.parser.StatementStream.NextResult.Statement -> {
                    exit = runStatement(r.statement, s)
                    s = emptySource()
                }

                is com.accucodeai.kash.parser.StatementStream.NextResult.Error -> {
                    // Bash's eval diagnostic shape depends on the error:
                    //   - "syntax error near unexpected token <tok>"    → 2 lines (msg + source echo)
                    //   - "syntax error: unexpected end of file ..."    → 1 line
                    //   - "unexpected EOF while looking for matching X" → 1 line
                    // The source-echo line gets dropped for the EOF
                    // shapes because the offending source is incomplete
                    // (bash has nothing useful to echo); the "near
                    // token" shape still echoes it for the pre-EOF
                    // cases.
                    val msg = r.error.message ?: "parse error"
                    stdio.stderr.writeUtf8("${evalPrefix}$msg\n")
                    val isEofShape =
                        msg.contains("unexpected end of file") ||
                            msg.contains("unexpected EOF") ||
                            msg.contains("unexpected `EOF'") ||
                            msg.contains("unexpected token `EOF'")
                    if (!isEofShape) {
                        stdio.stderr.writeUtf8("$evalPrefix`$text'\n")
                    }
                    exit = 2
                    break@loop
                }

                is com.accucodeai.kash.parser.StatementStream.NextResult.Eof -> {
                    break@loop
                }
            }
        }
        return exit
    } finally {
        outSink = savedOut
        errSink = savedErr
    }
}
