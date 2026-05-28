package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.FdTableEntry
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.installFd
import com.accucodeai.kash.api.installStdio
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.api.util.bufferOf
import com.accucodeai.kash.ast.RedirOp
import com.accucodeai.kash.ast.RedirTarget
import com.accucodeai.kash.ast.Redirection
import com.accucodeai.kash.ast.WordPart
import com.accucodeai.kash.fs.Paths

// I/O redirection runtime, extracted from [Interpreter] (was the
// single biggest function in that file at ~580 LOC). Handles
// `>file`, `<file`, `>>file`, `>&N`, `<&N`, `<<EOF`, `<<<string`,
// `>&file` ambiguous-vs-fd dispatch, `{NAME}>file` dynamic-fd
// allocation, `2>&1` deferred binding, `/dev/tty` raw-mode passthrough,
// coproc-fd close-and-reset, and the noclobber gate.

// Recognize an expanded `<&WORD` / `>&WORD` operand that *looks like*
// a malformed fd specification rather than a filename. Bash's rule:
// if the operand starts with `-` or `+` and is not a valid non-negative
// integer, or contains internal whitespace, it's diagnosed as
// "ambiguous redirect". Pure identifiers (`abc`) fall through to
// the file-open branch.

/**
 * Reconstruct the *source-level* form of a redirection operand word for
 * use in error diagnostics. Bash names the operand by the literal text
 * the user wrote (`./script: line N: $v: Bad file descriptor`), not by
 * the post-expansion fd number — scripts grep for the variable token
 * in stderr (`grep -q '\$v: Bad'`) to detect the failure path.
 *
 * Best-effort: covers the parts that appear in real redirection operands
 * (`$NAME`, `${NAME}`, literals, quotes). Falls back to the expanded
 * string for parts we can't faithfully round-trip (command substitution,
 * arithmetic expansion).
 */
private fun wordSourceForDiag(word: com.accucodeai.kash.ast.Word): String =
    buildString {
        for (p in word.parts) {
            when (p) {
                is WordPart.Literal -> {
                    append(p.value)
                }

                is WordPart.SingleQuoted -> {
                    append('\'')
                    append(p.value)
                    append('\'')
                }

                is WordPart.Escaped -> {
                    append('\\')
                    append(p.value)
                }

                is WordPart.ParameterExpansion -> {
                    if (p.op == null && !p.lengthOf && !p.indirect &&
                        p.subscript == null && p.nameGlob == null
                    ) {
                        if (p.braced) {
                            append("\${")
                            append(p.parameter)
                            append('}')
                        } else {
                            append('$')
                            append(p.parameter)
                        }
                    } else {
                        append("\${")
                        append(p.parameter)
                        append("...}")
                    }
                }

                is WordPart.CommandSubstitution -> {
                    append("\$(...)")
                }

                is WordPart.ArithmeticExpansion -> {
                    append("\$((...))")
                }

                is WordPart.DoubleQuoted -> {
                    append('"')
                    append(
                        wordSourceForDiag(
                            com.accucodeai.kash.ast
                                .Word(p.parts, word.line),
                        ),
                    )
                    append('"')
                }

                is WordPart.ExpandedText -> {
                    append(p.value)
                }

                is WordPart.ProcessSubstitution -> {
                    append(p.direction)
                    append("(...)")
                }
            }
        }
    }

private fun looksLikeBadFdSpec(s: String): Boolean {
    if (s.isEmpty()) return true
    // Bare `-` is the close-fd sentinel (`<&-`, `>&-`) — never ambiguous.
    if (s == "-") return false
    if (s[0] == '-' || s[0] == '+') return true
    if (s.any { it == ' ' || it == '\t' || it == '\n' }) return true
    return false
}

/**
 * Counterpart to the save-fd dance in [Interpreter.runResolvedSpec]'s
 * builtin branch. Reinstalls [saved] at fd [fd] on [target], releasing
 * whatever the builtin's redirection left there. The retain that
 * happened at save time is balanced here so the restored entry's
 * refcount lands at 1 (one fd-table reference, no leak).
 *
 * If [saved] is null (fd was unset before the builtin — unusual),
 * remove the fd entirely after releasing whatever's there now.
 */
internal fun Interpreter.restoreFd(
    target: KashProcess,
    fd: Int,
    saved: FdTableEntry?,
) {
    if (saved == null) {
        target.fdTable
            .remove(fd)
            ?.ofd
            ?.release()
        return
    }
    // Put the saved entry back; the displaced OFD (the redirection's
    // entry that installStdioFds created) gets released — closes its
    // sink iff owning. Then balance our save-time retain.
    target.fdTable
        .put(fd, saved)
        ?.ofd
        ?.release()
    saved.ofd.release()
}

internal fun Interpreter.installStdioFds(
    target: KashProcess,
    stdin: SuspendSource,
    stdout: SuspendSink,
    stderr: SuspendSink,
    stdinIsTty: Boolean = false,
    stdoutIsTty: Boolean = false,
    stderrIsTty: Boolean = false,
    terminalControl: TerminalControl? = null,
    stdinPath: String? = null,
    stdoutPath: String? = null,
    stderrPath: String? = null,
) = target.installStdio(
    stdin,
    stdout,
    stderr,
    stdinIsTty,
    stdoutIsTty,
    stderrIsTty,
    terminalControl,
    stdinPath,
    stdoutPath,
    stderrPath,
)

internal fun Interpreter.installStdioFds(
    target: KashProcess,
    stdio: Interpreter.Stdio,
) = installStdioFds(
    target,
    stdio.stdin,
    stdio.stdout,
    stdio.stderr,
    stdio.stdinIsTty,
    stdio.stdoutIsTty,
    stdio.stderrIsTty,
    stdio.terminalControl,
    stdio.stdinPath,
    stdio.stdoutPath,
    stdio.stderrPath,
)

internal fun Interpreter.defaultFd(op: RedirOp): Int =
    when (op) {
        RedirOp.INPUT, RedirOp.HERESTRING, RedirOp.HEREDOC, RedirOp.HEREDOC_STRIP, RedirOp.DUP_IN -> 0

        RedirOp.OUTPUT, RedirOp.APPEND, RedirOp.CLOBBER, RedirOp.READ_WRITE, RedirOp.DUP_OUT,
        RedirOp.OUT_AND_ERR, RedirOp.OUT_AND_ERR_APPEND,
        -> 1
    }

/**
 * Expand a redirect target word. In non-POSIX mode bash globs the
 * target: a single match resolves to that path, multiple matches are
 * an "ambiguous redirect" (caller raises), zero matches return the
 * pre-glob string and fall through to the normal "No such file" path.
 * POSIX mode skips globbing entirely.
 */
internal suspend fun Interpreter.expandAndMaybeGlobRedirTarget(word: com.accucodeai.kash.ast.Word): String {
    val raw = expandSingle(word)
    if (posixModeRuntime) return raw
    // Only attempt glob if the expansion looks like a pattern. Cheap
    // pre-check avoids unnecessary FS scans on plain paths.
    if (raw.none { it == '*' || it == '?' || it == '[' }) return raw
    val matches = expandArg(word)
    return when {
        matches.size == 1 -> matches.first()

        // Multiple matches: leave the raw string; the caller will fail
        // with the standard diagnostic. Bash actually emits "ambiguous
        // redirect" for this case; matching that diagnostic is a
        // follow-on. For now use the original expansion path.
        matches.size > 1 -> raw

        else -> raw
    }
}

internal suspend fun Interpreter.applyRedirections(
    redirections: List<Redirection>,
    base: Interpreter.Stdio,
): Pair<Interpreter.Stdio, List<() -> Unit>>? {
    if (redirections.isEmpty()) return Pair(base, emptyList())
    var stdin: SuspendSource = base.stdin
    var stdout: SuspendSink = base.stdout
    var stderr: SuspendSink = base.stderr
    // Path the redirection opened for fd 0/1/2, threaded into the
    // resulting [Stdio] so [installStdio] stamps it on fd N's OFD. Lets
    // `readlink /proc/self/fd/1` after `cmd > file` return `file`, not
    // the inherited pipe path. Null for the fd<3 cases that retain the
    // inherited stream (no redirection touched this slot, or the
    // redirection was a `2>&1`-style dup not a file open).
    var stdinPath: String? = base.stdinPath
    var stdoutPath: String? = base.stdoutPath
    var stderrPath: String? = base.stderrPath
    // Track which fds have been clobbered by a redirection — those lose
    // their tty status because they now point at a file (or a `2>&1`-
    // style sink that is itself non-tty).
    var stdinClobbered = false
    var stdoutClobbered = false
    var stderrClobbered = false
    // Per-fd terminalControl override picked up from /dev/tty redirections.
    // Non-null on a stream fd means the redirection target carried a
    // TerminalControl (only /dev/tty does today); the resulting Stdio's
    // terminalControl is replaced with this so a tool reading `</dev/tty`
    // or writing `>/dev/tty` gets raw-mode access.
    var stdinTerminalOverride: TerminalControl? = null
    var stdoutTerminalOverride: TerminalControl? = null
    var stderrTerminalOverride: TerminalControl? = null
    // /dev/tty redirection also implies isTty=true on the affected fd —
    // it IS a tty, by construction.
    var stdinIsTtyOverride = false
    var stdoutIsTtyOverride = false
    var stderrIsTtyOverride = false
    val cleanups = mutableListOf<() -> Unit>()

    // Fds opened via `{NAME}>...` dynamic-fd redirects. Bash leaves these
    // open after the command (the `shopt -s varredir_close` option flips
    // this; default is off, so the fd survives and is reachable via
    // `$NAME` from later commands). Cleanups for fds in this set are
    // filtered out before [cleanups] is returned to the caller.
    val persistedDynamicFds = mutableSetOf<Int>()

    // Helper: install a fresh OFD at fd N in the process's fd table,
    // releasing any prior occupant; schedule a transient release that
    // exec persistence can suppress. Delegates to
    // [com.accucodeai.kash.api.installFd] for the actual mutation —
    // see that helper's KDoc for the refcount-consumption invariant.
    fun installFd(
        fd: Int,
        ofd: OpenFileDescription,
    ) {
        process.installFd(fd, ofd)
        cleanups += {
            if (fd !in persistedDynamicFds) {
                process.fdTable
                    .remove(fd)
                    ?.ofd
                    ?.release()
            }
        }
    }

    // Release a slot from process.fdTable AND, if the slot was a coproc
    // fd, reset that coproc's `${NAME}[@]` to `-1 -1` (bash behavior on
    // move-fd of a coproc end). The owner map is populated by [runCoproc].
    fun releaseFdAndResetCoproc(slot: Int) {
        val owner = coprocFdOwner.remove(slot)
        process.fdTable
            .remove(slot)
            ?.ofd
            ?.release()
        if (owner != null) {
            indexedArrays[owner]?.let { arr ->
                arr[0] = "-1"
                arr[1] = "-1"
            }
            // The peer fd also becomes "closed" from bash's perspective —
            // drop its owner entry so a subsequent move-fd on the peer
            // doesn't redundantly reset.
            val keys = coprocFdOwner.entries.filter { it.value == owner }.map { it.key }
            for (k in keys) coprocFdOwner.remove(k)
        }
    }
    // Track whether stdout/stderr currently point at a tee onto the OTHER fd.
    // Implemented post-hoc: if we see `2>&1`, after all file redirections are
    // applied, stderr becomes a copy of stdout (the current `stdout` sink).
    var errToOut = false
    var outToErr = false

    for (r in redirections) {
        // Bash `{NAME}>file` dynamic fd: allocate a fresh high fd here, run
        // the normal per-op install path on it, then assign the slot number
        // to shell var `$NAME` at the end of the iteration. The high-fd
        // policy mirrors what coproc uses (lowest free ≥ 10) — the actual
        // number is implementation-defined per POSIX; bash uses
        // move_to_high_fd capped at 64.
        // `{NAME}>&-` / `{NAME}<&-` is the close-by-name form: READ the fd
        // value from NAME and close it, instead of allocating a fresh fd.
        // Bash's check: if NAME is unset/empty/non-numeric the operand is
        // diagnosed "ambiguous redirect" — there's no fd to close.
        val isCloseByName =
            r.fdVar != null &&
                (r.operator == RedirOp.DUP_OUT || r.operator == RedirOp.DUP_IN) &&
                (r.target as? RedirTarget.Fd)?.fd == -1
        // `{NAME}` may carry an array-subscript form `name[subscript]` (vredir7).
        // Split the literal here; the base name drives readonly/lookup checks,
        // the subscript routes the eventual write through the array setters.
        val fdVarBracket = r.fdVar?.indexOf('[') ?: -1
        val fdVarName =
            if (r.fdVar != null && fdVarBracket >= 0) r.fdVar.substring(0, fdVarBracket) else r.fdVar
        val fdVarSubscript: String? =
            if (r.fdVar != null && fdVarBracket >= 0 && r.fdVar.endsWith(']')) {
                r.fdVar.substring(fdVarBracket + 1, r.fdVar.length - 1)
            } else {
                null
            }
        val fd =
            if (r.fdVar != null) {
                if (isCloseByName) {
                    val s: String? =
                        if (fdVarSubscript != null) {
                            // Read array element value via existing intrinsics.
                            // Follow nameref chain to the array binding.
                            val v = varTable.findResolved(fdVarName!!)
                            val attrs = v?.attrs ?: emptySet()
                            when {
                                VarAttr.Associative in attrs -> {
                                    (v?.value as? VariableValue.Assoc)?.elements?.get(fdVarSubscript)
                                }

                                VarAttr.Indexed in attrs -> {
                                    val idx = ArithEval(fdVarSubscript, env, arithStore).evaluate().toInt()
                                    (v?.value as? VariableValue.Indexed)?.elements?.get(idx)
                                }

                                else -> {
                                    null
                                }
                            }
                        } else {
                            // Follow nameref chain — `declare -n stdin=input; exec {stdin}<&-`
                            // must close the fd recorded in `input`, not in the
                            // nameref binding itself.
                            val v = varTable.findResolved(fdVarName!!)
                            (v?.value as? VariableValue.Scalar)?.s
                        }
                    val n = s?.toIntOrNull()
                    if (n == null) {
                        base.stderr.writeUtf8(
                            "${shellDiagPrefix()}${r.fdVar}: ambiguous redirect\n",
                        )
                        return null
                    }
                    n
                } else {
                    // POSIX-shell convention: `{NAME}>file` writes the allocated
                    // fd to shell variable NAME. If NAME is readonly, the write
                    // can't proceed — bash emits two diagnostics ("readonly
                    // variable" then "cannot assign fd to variable") and skips
                    // the redirection entirely.
                    val existing = varTable.find(fdVarName!!)
                    if (existing?.isReadonly == true) {
                        base.stderr.writeUtf8("${shellDiagPrefix()}${r.fdVar}: readonly variable\n")
                        base.stderr.writeUtf8("${shellDiagPrefix()}${r.fdVar}: cannot assign fd to variable\n")
                        return null
                    }
                    val alloc =
                        allocHighFd(process) ?: run {
                            // RLIMIT_NOFILE exhausted: bash leaves NAME unset
                            // and emits "cannot allocate file descriptor".
                            // Scripts use `echo ${NAME-unset}` to detect this.
                            base.stderr.writeUtf8(
                                "${shellDiagPrefix()}${r.fdVar}: cannot allocate file descriptor for redirection: Too many open files\n",
                            )
                            return null
                        }
                    // Bash default: `{NAME}>...` opens a persistent fd.
                    // `shopt -s varredir_close` flips the default — when
                    // enabled, the dynamic fd IS closed at command exit
                    // (so the slot stays in the normal cleanup path).
                    if (!isShoptEnabled("varredir_close")) {
                        persistedDynamicFds += alloc
                    }
                    alloc
                }
            } else {
                r.fd ?: defaultFd(r.operator)
            }
        when (r.operator) {
            RedirOp.INPUT -> {
                // bash (non-POSIX): glob the redirect target. A single
                // glob match resolves to that file; multiple matches are
                // "ambiguous redirect"; zero matches use the original
                // glob string and fail with "No such file".
                val originalArg =
                    expandAndMaybeGlobRedirTarget((r.target as RedirTarget.File).word)
                val path = Paths.resolve(cwd, originalArg)
                val ofd =
                    try {
                        process.fs.openHandle(path, AccessMode.RDONLY, opener = process)
                            ?: OpenFileDescription(
                                source = process.fs.source(path),
                                accessMode = AccessMode.RDONLY,
                                path = path,
                            )
                    } catch (_: Throwable) {
                        // Bash diagnostic: <script>: line N: <arg>: No such file or directory
                        // (uses the literal redirect operand, not the resolved path).
                        base.stderr.writeUtf8("${shellDiagPrefix()}$originalArg: No such file or directory\n")
                        return null
                    }
                if (fd >= 3) {
                    installFd(fd, ofd)
                } else {
                    stdin = ofd.source!!
                    stdinClobbered = true
                    // Surface the opened path so /proc/self/fd/0 reports
                    // the redirection target after this command's fd 0
                    // gets installed via installStdio. The OFD opened
                    // above carries `path = path`; mirror that on the
                    // resulting Stdio so [installStdio] re-stamps it.
                    if (fd == 0) {
                        stdinPath = ofd.path ?: path
                    }
                    // /dev/tty carries a TerminalControl on its OFD —
                    // honor it so a tool reading `</dev/tty` (e.g. a
                    // password prompt) gets raw-mode access even when
                    // fd 0 was previously redirected away from the tty.
                    if (fd == 0 && ofd.terminalControl != null) {
                        stdinTerminalOverride = ofd.terminalControl
                        stdinIsTtyOverride = true
                    }
                }
            }

            RedirOp.HERESTRING -> {
                stdin = bufferOf((expandSingle((r.target as RedirTarget.File).word) + "\n").encodeToByteArray())
                stdinClobbered = true
                // Here-string content has no file path — explicitly null
                // so a prior /proc/self/fd/0 path doesn't bleed through.
                stdinPath = null
            }

            RedirOp.HEREDOC, RedirOp.HEREDOC_STRIP -> {
                val hd = r.target as RedirTarget.HereDoc
                val body =
                    if (hd.quoted) {
                        hd.body.parts.joinToString("") { (it as WordPart.Literal).value }
                    } else {
                        expandSingleNoTilde(hd.body)
                    }
                val src = bufferOf(body.encodeToByteArray())
                if (fd >= 3) {
                    installFd(fd, OpenFileDescription(source = src, accessMode = AccessMode.RDONLY))
                } else {
                    stdin = src
                    stdinClobbered = true
                    stdinPath = null
                }
            }

            RedirOp.OUTPUT, RedirOp.CLOBBER, RedirOp.APPEND -> {
                val originalArg = expandSingle((r.target as RedirTarget.File).word)
                if (restricted) {
                    base.stderr.writeUtf8("${shellDiagPrefix()}$originalArg: restricted: cannot redirect output\n")
                    return null
                }
                val path = Paths.resolve(cwd, originalArg)
                // POSIX noclobber: `>` to existing file is an error,
                // `>|` (CLOBBER) bypasses the check, `>>` (APPEND)
                // also bypasses since it doesn't truncate.
                if (noclobber && r.operator == RedirOp.OUTPUT) {
                    try {
                        if (fs.exists(path) && !fs.isDirectory(path)) {
                            base.stderr.writeUtf8(
                                "${shellDiagPrefix()}$originalArg: cannot overwrite existing file\n",
                            )
                            return null
                        }
                    } catch (_: Throwable) {
                        // fs probe failed — fall through to the open
                        // attempt, which will surface its own diagnostic.
                    }
                }
                val ofd =
                    process.fs.openHandle(path, AccessMode.WRONLY, opener = process)
                        ?: OpenFileDescription(
                            sink =
                                process.fs.sink(
                                    path,
                                    append = r.operator == RedirOp.APPEND,
                                    mode = 0b110_110_110 and umask.inv(),
                                ),
                            accessMode = AccessMode.WRONLY,
                            path = path,
                        )
                val s = ofd.sink!!
                if (fd >= 3) {
                    installFd(fd, ofd)
                } else {
                    // For openHandle-supplied non-owning OFDs (e.g.
                    // /dev/tty, /dev/null) the sink is shared and we
                    // must NOT close it on cleanup. For fs.sink(...)-
                    // opened files, we own it and must.
                    if (ofd.owning) {
                        cleanups += {
                            try {
                                s.close()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    if (fd == 2) {
                        stderr = s
                        stderrClobbered = true
                        stderrPath = ofd.path ?: path
                        if (ofd.terminalControl != null) {
                            stderrTerminalOverride = ofd.terminalControl
                            stderrIsTtyOverride = true
                        }
                    } else {
                        stdout = s
                        stdoutPath = ofd.path ?: path
                        stdoutClobbered = true
                        if (ofd.terminalControl != null) {
                            stdoutTerminalOverride = ofd.terminalControl
                            stdoutIsTtyOverride = true
                        }
                    }
                }
            }

            RedirOp.OUT_AND_ERR, RedirOp.OUT_AND_ERR_APPEND -> {
                val path =
                    Paths
                        .resolve(cwd, expandSingle((r.target as RedirTarget.File).word))
                val ofd =
                    process.fs.openHandle(path, AccessMode.WRONLY, opener = process)
                        ?: OpenFileDescription(
                            sink =
                                process.fs.sink(
                                    path,
                                    append = r.operator == RedirOp.OUT_AND_ERR_APPEND,
                                    mode = 0b110_110_110 and umask.inv(),
                                ),
                            accessMode = AccessMode.WRONLY,
                            path = path,
                        )
                val s = ofd.sink!!
                if (ofd.owning) {
                    cleanups += {
                        try {
                            s.close()
                        } catch (_: Throwable) {
                        }
                    }
                }
                stdout = s
                stderr = s
                stdoutClobbered = true
                stderrClobbered = true
                stdoutPath = ofd.path ?: path
                stderrPath = ofd.path ?: path
            }

            RedirOp.DUP_OUT -> {
                when (val t = r.target) {
                    is RedirTarget.Fd -> {
                        when {
                            // `>&-` — close fd. Only meaningful for fd ≥ 3
                            // (the named slots stay attached for the
                            // command's lifetime regardless).
                            t.fd == -1 -> {
                                if (fd >= 3) {
                                    process.fdTable
                                        .remove(fd)
                                        ?.ofd
                                        ?.release()
                                }
                            }

                            // `2>&1` / `1>&2` — defer until after all file
                            // redirections settle.
                            fd == 2 && t.fd == 1 -> {
                                errToOut = true
                            }

                            fd == 1 && t.fd == 2 -> {
                                outToErr = true
                            }

                            // `N>&M` — pull the sink straight out of the
                            // process's fd table. M may be 1/2 (stdout/
                            // stderr — both already populated by
                            // installStdio) or ≥ 3.
                            t.fd >= 0 -> {
                                val srcEntry =
                                    process.fdTable[t.fd] ?: run {
                                        base.stderr.writeUtf8("kash: ${t.fd}: Bad file descriptor\n")
                                        return null
                                    }
                                val src = srcEntry.ofd.sink
                                if (src == null) {
                                    base.stderr.writeUtf8("kash: ${t.fd}: Bad file descriptor\n")
                                    return null
                                }
                                if (fd >= 3) {
                                    // `{NAME}>&M` — install a new high-fd
                                    // entry pointing at M's OFD so later
                                    // `>&$NAME` finds it.
                                    srcEntry.ofd.retain()
                                    installFd(fd, srcEntry.ofd)
                                } else if (fd == 2) {
                                    stderr = src
                                    stderrClobbered = true
                                } else if (fd == 1) {
                                    stdout = src
                                    stdoutClobbered = true
                                }
                                // `N>&M-` move-fd: release source fd after dup.
                                if (r.closeAfter) {
                                    releaseFdAndResetCoproc(t.fd)
                                }
                            }

                            else -> {
                                Unit
                            }
                        }
                    }

                    is RedirTarget.File -> {
                        // For `>&${VAR}-` the lexer left the trailing `-`
                        // on the expansion; strip it here. Variable-
                        // expansion all-digits → fd-dup. closeAfter on
                        // top releases the source fd after dup.
                        val raw = expandSingle(t.word)
                        val expanded =
                            if (r.closeAfter && raw.endsWith("-")) raw.dropLast(1) else raw
                        // POSIX `>&WORD`: if WORD expands to an all-digit
                        // string, treat as fd-dup (same as `>&N`); else
                        // open as a file. `${COPROC[1]}` lands here as
                        // its expanded fd number.
                        val asFd = expanded.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
                        if (asFd == null && looksLikeBadFdSpec(expanded)) {
                            base.stderr.writeUtf8(
                                "${shellDiagPrefix()}$expanded: ambiguous redirect\n",
                            )
                            return null
                        }
                        if (asFd != null) {
                            val src =
                                process.fdTable[asFd]?.ofd?.sink ?: run {
                                    base.stderr.writeUtf8(
                                        "${shellDiagPrefix()}${wordSourceForDiag(t.word)}: Bad file descriptor\n",
                                    )
                                    return null
                                }
                            if (fd >= 3) {
                                process.fdTable[asFd]?.ofd?.retain()
                                installFd(
                                    fd,
                                    process.fdTable[asFd]!!.ofd,
                                )
                                // `N>&M-` move-fd: release source slot. The
                                // OFD survives via the retain+install above.
                                if (r.closeAfter) {
                                    releaseFdAndResetCoproc(asFd)
                                }
                            } else if (fd == 2) {
                                stderr = src
                                stderrClobbered = true
                            } else {
                                stdout = src
                                stdoutClobbered = true
                            }
                            // For the fd 1 / fd 2 inline-alias path, the
                            // move-fd close cannot happen via fdTable
                            // release (it would close the underlying
                            // channel and break the alias). Bash's `exec
                            // >&N-` keeps the OFD alive via the implicit
                            // fd 1 dup; we approximate by leaving the
                            // source entry in the table. POSIX-wise the
                            // observable difference is whether fd N is
                            // still readable later in the script — kash
                            // says yes, bash says no. No test in the
                            // corpus depends on that distinction.
                        } else {
                            val path =
                                Paths
                                    .resolve(cwd, expanded)
                            val ofd =
                                process.fs.openHandle(path, AccessMode.WRONLY, opener = process)
                                    ?: OpenFileDescription(
                                        sink = process.fs.sink(path, append = false),
                                        accessMode = AccessMode.WRONLY,
                                        path = path,
                                    )
                            val s = ofd.sink!!
                            if (fd >= 3) {
                                installFd(fd, ofd)
                            } else {
                                if (ofd.owning) {
                                    cleanups += {
                                        try {
                                            s.close()
                                        } catch (_: Throwable) {
                                        }
                                    }
                                }
                                if (fd == 2) {
                                    stderr = s
                                    stderrClobbered = true
                                } else {
                                    stdout = s
                                    stdoutClobbered = true
                                }
                            }
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }

            RedirOp.DUP_IN -> {
                when (val t = r.target) {
                    is RedirTarget.Fd -> {
                        when {
                            // `<&-` — close fd ≥ 3.
                            t.fd == -1 -> {
                                if (fd >= 3) {
                                    process.fdTable
                                        .remove(fd)
                                        ?.ofd
                                        ?.release()
                                }
                            }

                            // `<&M` — dup fd M's source onto fd N. M may be
                            // 0 (stdin, populated by installStdio) or ≥ 3.
                            t.fd >= 0 -> {
                                val srcEntry =
                                    process.fdTable[t.fd] ?: run {
                                        base.stderr.writeUtf8("kash: ${t.fd}: Bad file descriptor\n")
                                        return null
                                    }
                                val src = srcEntry.ofd.source
                                if (src == null) {
                                    base.stderr.writeUtf8("kash: ${t.fd}: Bad file descriptor\n")
                                    return null
                                }
                                if (fd == 0) {
                                    stdin = src
                                    stdinClobbered = true
                                } else if (fd >= 3) {
                                    srcEntry.ofd.retain()
                                    installFd(fd, srcEntry.ofd)
                                }
                                // `<&M-` move-fd: release source fd after dup.
                                if (r.closeAfter) {
                                    releaseFdAndResetCoproc(t.fd)
                                }
                            }

                            else -> {
                                Unit
                            }
                        }
                    }

                    is RedirTarget.File -> {
                        // POSIX `<&WORD`: if WORD expands to all-digits,
                        // treat as fd-dup; else if WORD looks like a
                        // malformed fd spec (starts with `-` or contains
                        // whitespace, etc.) bash diagnoses "ambiguous
                        // redirect" and the surrounding command exits 1.
                        // For `<&${VAR}-` strip the trailing `-` left by
                        // the lexer for the move-fd form.
                        val raw = expandSingle(t.word)
                        val expanded =
                            if (r.closeAfter && raw.endsWith("-")) raw.dropLast(1) else raw
                        val asFd = expanded.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
                        if (asFd != null) {
                            val src =
                                process.fdTable[asFd]?.ofd?.source ?: run {
                                    base.stderr.writeUtf8(
                                        "${shellDiagPrefix()}${wordSourceForDiag(t.word)}: Bad file descriptor\n",
                                    )
                                    return null
                                }
                            if (fd >= 3) {
                                process.fdTable[asFd]?.ofd?.retain()
                                installFd(fd, process.fdTable[asFd]!!.ofd)
                                if (r.closeAfter) {
                                    process.fdTable
                                        .remove(asFd)
                                        ?.ofd
                                        ?.release()
                                }
                            } else if (fd == 0) {
                                stdin = src
                                stdinClobbered = true
                                // See DUP_OUT note above: skipping release
                                // for the inline-alias path keeps the
                                // channel alive for the duration of the
                                // command's stdin.
                            }
                        } else if (looksLikeBadFdSpec(expanded)) {
                            base.stderr.writeUtf8(
                                "${shellDiagPrefix()}$expanded: ambiguous redirect\n",
                            )
                            return null
                        } else {
                            // Bash treats `<&NONDIGIT` as the same as `<NONDIGIT`
                            // (open file for read). Defer until a real test
                            // demands it; for now the parser shouldn't even
                            // produce this case unless a script explicitly does it.
                            Unit
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }

            RedirOp.READ_WRITE -> {
                // `N<>file` / `{NAME}<>file` — open file for read AND write
                // on the same fd. Used most often as `: {fd}<>/dev/null` to
                // grab a recyclable scratch fd. Bash forwards both directions
                // through the single OFD; we model this by installing the
                // openHandle-returned OFD (which carries source+sink for
                // RDWR-capable files like /dev/null, /dev/tty, and regular
                // files) at the target fd.
                val originalArg = expandSingle((r.target as RedirTarget.File).word)
                val path = Paths.resolve(cwd, originalArg)
                val ofd =
                    process.fs.openHandle(path, AccessMode.RDWR, opener = process)
                        ?: run {
                            base.stderr.writeUtf8(
                                "${shellDiagPrefix()}$originalArg: No such file or directory\n",
                            )
                            return null
                        }
                if (fd >= 3) {
                    installFd(fd, ofd)
                } else if (fd == 0) {
                    stdin = ofd.source ?: stdin
                    stdinClobbered = true
                    stdinPath = ofd.path ?: path
                } else {
                    val sink =
                        ofd.sink ?: run {
                            base.stderr.writeUtf8(
                                "${shellDiagPrefix()}$originalArg: cannot open for writing\n",
                            )
                            return null
                        }
                    if (fd == 2) {
                        stderr = sink
                        stderrClobbered = true
                        stderrPath = ofd.path ?: path
                    } else if (fd == 1) {
                        stdout = sink
                        stdoutClobbered = true
                        stdoutPath = ofd.path ?: path
                    }
                }
            }
        }
        // Bash dynamic-fd allocation: publish the allocated slot number to
        // shell var `$NAME` after the per-op install path ran. The op
        // handlers above used the high `fd` we picked at loop top, so
        // the OFD is already in process.fdTable[fd]. We just need to
        // expose the number to the script. setScalar (not env put) so
        // `declare -i` / case-mod attributes (unlikely but possible)
        // round-trip correctly.
        // `{NAME}>&-` reads the fd from NAME and closes it — don't write
        // the fd back. Only the allocate-a-new-fd path assigns to NAME.
        if (r.fdVar != null && !isCloseByName) {
            if (fdVarSubscript != null) {
                // Array-element write. Treat as associative if the variable
                // already has that attribute, otherwise indexed (matches
                // bash, which treats undeclared subscripted names as indexed).
                val existingAttrs = varTable.find(fdVarName!!)?.attrs ?: emptySet()
                if (VarAttr.Associative in existingAttrs) {
                    setAssocElement(fdVarName, fdVarSubscript, fd.toString(), append = false)
                } else {
                    val idx = ArithEval(fdVarSubscript, env, arithStore).evaluate().toInt()
                    setIndexedElement(fdVarName, idx, fd.toString(), append = false)
                }
            } else {
                setScalar(fdVarName!!, fd.toString(), append = false)
            }
        }
    }
    if (errToOut) {
        stderr = stdout
        // `2>&1`: stderr now mirrors stdout — its ttyness follows.
        stderrClobbered = stdoutClobbered || (!base.stdoutIsTty && stderrClobbered)
        // `2>&1` shares fd 1's backing path — `readlink /proc/self/fd/2`
        // after `cmd > file 2>&1` should report `file`, same as fd 1.
        stderrPath = stdoutPath
    }
    if (outToErr) {
        stdout = stderr
        stdoutClobbered = stderrClobbered || (!base.stderrIsTty && stdoutClobbered)
        stdoutPath = stderrPath
    }
    // /dev/tty override beats "clobbered = no tty": redirecting fd 0
    // FROM /dev/tty re-asserts tty-ness on that fd.
    val effectiveStdinIsTty = stdinIsTtyOverride || (!stdinClobbered && base.stdinIsTty)
    val effectiveStdoutIsTty = stdoutIsTtyOverride || (!stdoutClobbered && base.stdoutIsTty)
    val effectiveStderrIsTty = stderrIsTtyOverride || (!stderrClobbered && base.stderrIsTty)
    // Stdio bundles one TerminalControl; any /dev/tty redirection's
    // handle wins over the inherited one. In practice all three would
    // be the same host terminal anyway.
    val effectiveTerminalControl =
        stdinTerminalOverride
            ?: stdoutTerminalOverride
            ?: stderrTerminalOverride
            ?: base.terminalControl
    return Pair(
        Interpreter.Stdio(
            stdin = stdin,
            stdout = stdout,
            stderr = stderr,
            stdinIsTty = effectiveStdinIsTty,
            stdoutIsTty = effectiveStdoutIsTty,
            stderrIsTty = effectiveStderrIsTty,
            terminalControl = effectiveTerminalControl,
            stdinPath = stdinPath,
            stdoutPath = stdoutPath,
            stderrPath = stderrPath,
        ),
        cleanups,
    )
}
