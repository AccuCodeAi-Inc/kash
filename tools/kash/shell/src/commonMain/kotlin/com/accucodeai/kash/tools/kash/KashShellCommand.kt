package com.accucodeai.kash.tools.kash

import com.accucodeai.kash.api.BasicLineEditor
import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.Snapshottable
import com.accucodeai.kash.api.io.EmptySuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.terminal.LineEditor
import com.accucodeai.kash.api.terminal.LineEditorResult
import com.accucodeai.kash.completion.ShellCompleter
import com.accucodeai.kash.interpreter.Interpreter
import com.accucodeai.kash.interpreter.notifyCompletedBackgroundJobs
import com.accucodeai.kash.parser.ParseResult
import com.accucodeai.kash.parser.Parser
import com.accucodeai.kash.snapshot.InterpreterSnapshot
import com.accucodeai.kash.snapshot.SnapshotJson
import kotlinx.coroutines.currentCoroutineContext

/**
 * The shell itself, modeled as a [Command]. Registered in the default
 * catalog at `/usr/bin/kash` so typing `kash …` at the prompt resolves
 * just like any other tool — dispatched as [CommandKind.TOOL], forked
 * via [com.accucodeai.kash.api.KashMachine.spawn], reaped on exit.
 *
 * Handles `-c <script>`, no-args + non-tty stdin (read all and execute),
 * and bare `kash` on a tty falling into the interactive REPL.
 *
 * Recursive `kash`-in-kash works for free: the child invocation is just
 * another spawned process on the same [com.accucodeai.kash.api.KashMachine],
 * inheriting env / cwd / fds / terminal exactly like POSIX `execve` of
 * `/bin/sh` would inherit them.
 *
 * Registry lookup: the sub-script's [Interpreter] reads its command catalog
 * from `ctx.process.machine.registry` — the same registry the parent shell
 * uses. No constructor-time registry capture, so the Koin scan that picks
 * this `@Single` up has no chicken-and-egg with the registry that will
 * end up containing this very instance.
 */
public class KashShellCommand :
    Command,
    CommandSpec,
    Snapshottable {
    override val name: String = "kash"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    // `bash` resolves to kash so agent harnesses that probe with
    // `command -v bash` or invoke `/usr/bin/env bash` find the shell.
    // `sh` is owned by [com.accucodeai.kash.tools.sh.ShCommand] (POSIX
    // subset), not aliased here. Behavior is identical regardless of name;
    // kash already speaks bash's CLI surface (`-c`, `-l`, `--norc`, …).
    override val aliases: List<String> = listOf("bash")

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        // Argv parsing:
        //   kash -c "script" [arg0 [arg1 …]]   → run inline script
        //   kash (no args, tty stdin)          → interactive REPL
        //   kash (no args, non-tty stdin)      → read all stdin, run
        //   kash script.sh [arg1 …]            → run script file with positional args
        //
        // --norc skips sourcing $HOME/.kashrc in interactive mode (bash parity).
        val parsed = parseArgs(args, stdinIsTty = ctx.process.isTty(0))
        if (parsed.error != null || parsed.mode == null) {
            ctx.stderr.writeUtf8("kash: ${parsed.error ?: "internal: no mode"}\n")
            return CommandResult(exitCode = 2)
        }

        val mode = parsed.mode
        return when (mode) {
            is Mode.DashC -> {
                runScriptText(
                    mode.script,
                    mode.scriptName,
                    mode.positional,
                    ctx,
                    parsed.loginShell,
                    parsed.shellOpts,
                    noProfile = parsed.noProfile,
                )
            }

            is Mode.StdinScript -> {
                runStdinStream(
                    ctx,
                    parsed.loginShell,
                    parsed.shellOpts,
                    noProfile = parsed.noProfile,
                )
            }

            is Mode.ScriptFile -> {
                runScriptFile(
                    mode.path,
                    mode.positional,
                    ctx,
                    parsed.loginShell,
                    parsed.shellOpts,
                    noProfile = parsed.noProfile,
                )
            }

            is Mode.Interactive -> {
                runInteractive(
                    ctx,
                    noRc = parsed.noRc,
                    loginShell = parsed.loginShell,
                    noProfile = parsed.noProfile,
                    posixMode = parsed.posixMode,
                    rcFileOverride = parsed.rcFileOverride,
                )
            }
        }
    }

    /**
     * Apply command-line shell option flags (`-u`, `-e`, …) to [interp]'s
     * [com.accucodeai.kash.interpreter.ShellOptions]. POSIX §1.6: invoking
     * the shell with these flags is equivalent to running `set -<ch>` as
     * the first command — and unlike the `set` intrinsic the changes
     * persist across the rest of the script (they're not reset on
     * fork). Unknown letters that we accepted in [parseArgs] but don't
     * map to an option here (`-h` hashall — irrelevant since kash always
     * hashes) silently no-op, matching bash for flags whose behavior is
     * unobservable in our model.
     */
    private fun applyShellOpts(
        interp: Interpreter,
        opts: Set<Char>,
    ) {
        for (ch in opts) {
            when (ch) {
                'a' -> {
                    interp.options.allexport = true
                }

                // POSIX §2.14.1 allexport
                'b' -> {}

                // notify — bg-job report timing; not modeled (kash is in-process)
                'e' -> {
                    interp.options.errexit = true
                }

                'f' -> {
                    interp.options.noglob = true
                }

                // POSIX §2.14.1 noglob
                'h' -> {}

                // hashall — always on in kash
                'm' -> {
                    interp.options.monitor = true
                }

                'n' -> {
                    interp.options.noexec = true
                }

                // POSIX §2.14.1 noexec
                'u' -> {
                    interp.options.nounset = true
                }

                'v' -> {
                    interp.options.verbose = true
                }

                // POSIX §2.14.1 verbose
                'x' -> {
                    interp.options.xtrace = true
                }

                'C' -> {
                    interp.options.noclobber = true
                }

                'E' -> {
                    interp.options.errtrace = true
                }

                'P' -> {}

                // physical — symlink-handling in cd; no-op for kash
                'T' -> {
                    interp.options.functrace = true
                }
            }
        }
    }

    /**
     * POSIX `sh SCRIPT [ARG…]`: resolve [path] against `ctx.process.cwd` (if
     * relative), read it, execute. The script's `$0` is [path] verbatim;
     * `$1..$N` are [positional]. Diagnostics use the basename, mirroring
     * bash's `<script>: line N: <msg>` prefix.
     */
    private suspend fun runScriptFile(
        path: String,
        positional: List<String>,
        ctx: CommandContext,
        loginShell: Boolean = false,
        shellOpts: Set<Char> = emptySet(),
        noProfile: Boolean = false,
    ): CommandResult {
        val fs = ctx.process.fs
        val resolved =
            if (path.startsWith("/")) {
                path
            } else {
                val base =
                    ctx.process.cwd
                        .trimEnd('/')
                        .ifEmpty { "/" }
                if (base == "/") "/$path" else "$base/$path"
            }
        if (!fs.exists(resolved)) {
            ctx.stderr.writeUtf8("kash: $path: No such file or directory\n")
            return CommandResult(exitCode = 127)
        }
        if (fs.isDirectory(resolved)) {
            ctx.stderr.writeUtf8("kash: $path: is a directory\n")
            return CommandResult(exitCode = 126)
        }
        val source =
            try {
                fs.readBytes(resolved).decodeToString()
            } catch (e: Throwable) {
                ctx.stderr.writeUtf8("kash: $path: ${e.message ?: "read error"}\n")
                return CommandResult(exitCode = 1)
            }
        return runScriptText(source, scriptName = path, positional = positional, ctx, loginShell, shellOpts, noProfile)
    }

    private suspend fun runScriptText(
        script: String,
        scriptName: String,
        positional: List<String>,
        ctx: CommandContext,
        loginShell: Boolean = false,
        shellOpts: Set<Char> = emptySet(),
        noProfile: Boolean = false,
    ): CommandResult {
        // `kash -c '...'` and `kash script.sh` are one-shot, non-interactive
        // invocations. They MUST NOT touch `machine.snapshotSlots` — slot
        // restore is for resuming an interactive REPL session, not for
        // implicit state sharing across unrelated `-c` runs. The slot map
        // is persisted to disk and keyed by virtual pid; two unrelated
        // top-level `-c` invocations that happen to allocate the same pid
        // would otherwise inherit each other's aliases / functions / env,
        // producing flaky, order-dependent behavior. See KashShellCommand
        // history comment: prior code restored on any ppid==1 shell which
        // is exactly the leak path.
        val interp =
            Interpreter(
                machine = ctx.process.machine,
                registry = ctx.process.machine.registry,
                process = ctx.process,
                parentContext = currentCoroutineContext(),
                userDb = ctx.userDb,
                sandbox = ctx.sandbox,
            )
        interp.setScriptName(scriptName)
        if (positional.isNotEmpty()) interp.positional = positional
        interp.isLoginShell = loginShell
        applyShellOpts(interp, shellOpts)
        // Track outer-script source so deferred cmdsub parse errors
        // (Part B) emit with the same prefix/source-line shape as outer
        // parse errors. `-c` mode is identified by the default scriptName
        // — KashShellCommand.parseArgs uses "kash" (or "bash" via alias)
        // when no explicit name was passed after the `-c '...'` arg.
        interp.currentOuterScript = script
        interp.currentOuterIsCLine = scriptName == "kash" || scriptName == "bash"

        // Bash startup-file cascade for non-interactive entry. A login
        // non-interactive shell (`kash -l -c '…'`, `kash -l script.sh`)
        // sources the same `/etc/profile` + personal-profile cascade as
        // an interactive login shell. All non-interactive invocations
        // then honor `$KASH_ENV` / `$BASH_ENV`.
        if (loginShell && !noProfile) {
            StartupFiles.sourceLoginProfiles(interp, ctx)
        }
        StartupFiles.sourceNonInteractiveEnv(interp, ctx)

        // Per-statement parse-execute loop — see [Kash.execWithOptions]
        // for the rationale. The stream re-parses each statement with the
        // live `interp.posixModeRuntime` / alias state.
        val stream =
            try {
                com.accucodeai.kash.parser.StatementStream(
                    source = script,
                    aliasResolver = interp.aliasResolver,
                    posixModeProvider = { interp.posixModeRuntime },
                    extglobProvider = { interp.isShoptEnabled("extglob") },
                    aliasVersionProvider = { interp.aliasVersion },
                    // `bash -c` aborts on first top-level syntax error
                    // (status 2). File-script invocations keep the in-
                    // command recovery path so `a=(x & y); echo $?` works.
                    abortOnSyntaxError = interp.currentOuterIsCLine,
                )
            } catch (e: IllegalStateException) {
                ctx.stderr.writeUtf8("kash: ${e.message ?: "lex error"}\n")
                return CommandResult(exitCode = 2)
            }
        val source =
            com.accucodeai.kash.interpreter
                .StreamStatementSource(stream)
        val code =
            interp.runStreaming(
                source = source,
                initialStdin = ctx.stdin,
                stdout = ctx.stdout,
                stderr = ctx.stderr,
                stdinIsTty = ctx.process.isTty(0),
                terminalControl =
                    ctx.process.fdTable[0]
                        ?.ofd
                        ?.terminalControl,
            )
        return CommandResult(exitCode = code)
    }

    /**
     * Decode `machine.snapshotSlots[pid]` (if present) into an
     * [InterpreterSnapshot] and apply it to [interp]. Silent no-op on
     * decode failure — a corrupt slot shouldn't prevent the shell from
     * booting; the script just runs against default state.
     *
     * Slot restore is reserved for the **interactive REPL** path. The
     * `-c` and stdin-script paths intentionally do NOT call this — they're
     * one-shot, non-interactive invocations and the slot is keyed by
     * virtual pid, so two unrelated `-c` runs that allocate the same pid
     * would otherwise inherit each other's aliases / functions / env.
     * The legacy `ppid != 1` guard remains as a belt-and-braces check
     * against accidental subshell restores from this same call path.
     */
    private fun restoreSlotIfPresent(
        ctx: CommandContext,
        interp: Interpreter,
    ) {
        val proc = ctx.process
        if (proc.ppid != 1) return
        val slot = proc.machine.snapshotSlots[proc.pid] ?: return
        val snap =
            try {
                SnapshotJson.decodeFromJsonElement(InterpreterSnapshot.serializer(), slot)
            } catch (_: Throwable) {
                return
            }
        try {
            interp.restore(snap)
        } catch (_: Throwable) {
            // Best effort — partial restore is OK; the script will run
            // against whatever state survived.
        }
    }

    /**
     * Capture [interp]'s state and write it to `machine.snapshotSlots`.
     * Called in a `finally` so the slot is populated on both normal exit
     * and exception — the on-disk snapshot is the persisted projection
     * of whatever the shell last did.
     *
     * Symmetric with [restoreSlotIfPresent]: only the interactive REPL
     * calls this. `-c` / stdin-script paths intentionally don't write a
     * slot — they're one-shot and shouldn't leave state behind for the
     * next pid-colliding invocation to pick up.
     */
    private fun writeSlot(
        ctx: CommandContext,
        interp: Interpreter,
    ) {
        val proc = ctx.process
        if (proc.ppid != 1) return
        try {
            val snap = interp.snapshot()
            proc.machine.snapshotSlots[proc.pid] =
                SnapshotJson.encodeToJsonElement(InterpreterSnapshot.serializer(), snap)
        } catch (_: Throwable) {
            // Best-effort — a slot is pure shell state and capture shouldn't
            // throw, but skip silently if it ever does: no snapshot is better
            // than crashing on shutdown.
        }
    }

    /**
     * Non-interactive stdin driver: reads commands incrementally from
     * `ctx.stdin` and dispatches each complete statement through a single
     * long-lived [Interpreter], so env / cwd / functions / `$?` persist
     * across statements — matching real bash's behavior when stdin is a
     * pipe.
     *
     * The old implementation `readAllBytes()`-then-`runScriptText` deadlocked
     * agent harnesses (Claude Code, Aider, …) that keep one shell process
     * alive and feed it commands over stdin, expecting each to execute and
     * flush before the next is sent.
     *
     * Incomplete-statement handling mirrors [runReplLoop]: if [Parser.parse]
     * returns [ParseResult.Incomplete], we accumulate more lines before
     * trying again (covers heredocs, unclosed quotes, multi-line `if`/`for`/
     * `while`/`case`/function bodies, line-continuation `\`).
     */
    private suspend fun runStdinStream(
        ctx: CommandContext,
        loginShell: Boolean = false,
        shellOpts: Set<Char> = emptySet(),
        noProfile: Boolean = false,
    ): CommandResult {
        val machine = ctx.process.machine
        val interp =
            Interpreter(
                machine = machine,
                registry = machine.registry,
                process = ctx.process,
                parentContext = currentCoroutineContext(),
                userDb = ctx.userDb,
                sandbox = ctx.sandbox,
            )
        // Non-interactive pipe-fed shell: do NOT touch snapshotSlots. The
        // in-memory [Interpreter] persists state across statements within
        // this invocation already; restoring/writing a disk-backed slot
        // would leak state between unrelated invocations sharing a pid.
        // Slot resume is reserved for the interactive REPL path.
        interp.setScriptName("kash")
        interp.isLoginShell = loginShell
        applyShellOpts(interp, shellOpts)

        // Same non-interactive startup-file cascade as [runScriptText]:
        // login profiles when `-l`, then `$KASH_ENV` / `$BASH_ENV`.
        if (loginShell && !noProfile) {
            StartupFiles.sourceLoginProfiles(interp, ctx)
        }
        StartupFiles.sourceNonInteractiveEnv(interp, ctx)

        // Foreground signal routing: same install/uninstall dance as
        // [runReplLoop] so a SIGINT forwarded via the machine-wide slot
        // aborts the running statement and we resume reading more.
        // Also register under our session id so per-tab terminals (web
        // UI) can route Ctrl-C to *this* shell rather than to whichever
        // shell last touched the global slot.
        val previousReceiver = machine.foregroundSignalReceiver
        machine.foregroundSignalReceiver = interp::deliverSignal
        val sessionKey = ctx.process.sid
        machine.sessionSignalReceivers[sessionKey] = interp::deliverSignal
        try {
            val pending = StringBuilder()
            eof@ while (true) {
                val line = ctx.stdin.readUtf8LineOrNull() ?: break@eof
                if (pending.isNotEmpty()) pending.append('\n')
                pending.append(line)

                // Skip whitespace-only buffers — same empty-line skip
                // [runReplLoop] does for blank prompts.
                if (pending.isBlank()) {
                    pending.clear()
                    continue@eof
                }

                val src = pending.toString()
                val r =
                    try {
                        Parser(src, interp.aliasResolver).parse()
                    } catch (e: IllegalStateException) {
                        ctx.stderr.writeUtf8("kash: ${e.message ?: "lex error"}\n")
                        pending.clear()
                        continue@eof
                    }
                when (r) {
                    is ParseResult.Incomplete -> {
                        continue@eof
                    }

                    is ParseResult.Error -> {
                        // Real bash exits on syntax error in non-interactive
                        // stdin mode; we intentionally continue (matching
                        // [runReplLoop]) so an agent harness doesn't lose
                        // its session over one bad statement.
                        val ln = r.line.coerceAtLeast(1)
                        ctx.stderr.writeUtf8("kash: line $ln: ${r.message}\n")
                        pending.clear()
                    }

                    is ParseResult.Ok -> {
                        pending.clear()
                        try {
                            // EmptySuspendSource for the child stdin: the
                            // script body itself is being consumed from
                            // stdin, so a `read` builtin can't safely share
                            // that stream without losing parser lookahead.
                            // Matches [runReplLoop].
                            interp.runStreaming(
                                source =
                                    com.accucodeai.kash.interpreter.ScriptStatementSource(
                                        r.script.statements,
                                    ),
                                initialStdin = EmptySuspendSource,
                                stdout = ctx.stdout,
                                stderr = ctx.stderr,
                                stdinIsTty = false,
                            )
                        } catch (t: Throwable) {
                            ctx.stderr.writeUtf8("kash: ${t.message ?: t::class.simpleName}\n")
                        }
                    }
                }
            }
        } finally {
            machine.foregroundSignalReceiver = previousReceiver
            machine.sessionSignalReceivers.remove(sessionKey)
        }
        return CommandResult(exitCode = interp.lastExit)
    }

    /**
     * Interactive read-eval-print loop. Builds a [BasicLineEditor] on the
     * terminal handle attached to fd 0's OFD. Loops reading complete
     * statements, dispatches each through a long-lived [Interpreter]
     * bound to `ctx.process` so cwd / env / functions / aliases persist
     * across lines.
     */
    private suspend fun runInteractive(
        ctx: CommandContext,
        noRc: Boolean,
        loginShell: Boolean = false,
        noProfile: Boolean = false,
        posixMode: Boolean = false,
        rcFileOverride: String? = null,
    ): CommandResult {
        val machine = ctx.process.machine
        val term =
            ctx.process.fdTable[0]
                ?.ofd
                ?.terminalControl ?: run {
                ctx.stderr.writeUtf8("kash: no terminal available for interactive mode\n")
                return CommandResult(exitCode = 1)
            }
        val interp =
            Interpreter(
                machine = machine,
                registry = machine.registry,
                process = ctx.process,
                interactive = true,
                parentContext = currentCoroutineContext(),
                userDb = ctx.userDb,
                sandbox = ctx.sandbox,
            )
        restoreSlotIfPresent(ctx, interp)
        interp.isLoginShell = loginShell
        if (posixMode) interp.posixModeRuntime = true
        // Bash default: interactive shells start with `set -m` (monitor
        // mode) on; scripts and `-c` invocations leave it off. Without
        // this, `fg` / `bg` print `no job control` even after the user
        // backgrounds a job — which is exactly the bash error message
        // for non-monitor mode but the wrong default for an interactive
        // session.
        interp.monitor = true

        // Bash-style tab completion wired through the interpreter so
        // completions reflect live env / functions / aliases.
        val editor: LineEditor =
            BasicLineEditor(
                terminal = term,
                completer = ShellCompleter(interp, machine.registry, machine.fs),
            )

        // Foreground signal routing: the host (kash-app Main) catches
        // SIGINT/SIGTSTP from the JVM and forwards via this machine-wide
        // slot. We point it at our Interpreter for the duration of the
        // REPL, and clear on exit so a recursive `kash` in a tool can
        // re-install its own handler without leaking ours.
        val previousReceiver = machine.foregroundSignalReceiver
        machine.foregroundSignalReceiver = interp::deliverSignal
        val sessionKey = ctx.process.sid
        machine.sessionSignalReceivers[sessionKey] = interp::deliverSignal
        // Register a live-snapshot provider so a workspace "Save"
        // invoked while we're sitting at the prompt can capture the
        // interpreter's current state (functions, aliases, vars, cwd,
        // history) into the machine's snapshot slot.
        machine.liveSnapshotProviders[ctx.process.pid] = { writeSlot(ctx, interp) }
        try {
            // Bash interactive startup-file cascade. Three branches per
            // `man bash` § INVOCATION:
            //   1. Login shell → /etc/profile + first of
            //      ~/.kash_profile|~/.kash_login|~/.profile.
            //   2. --posix → only $ENV.
            //   3. Otherwise → /etc/kashrc + ~/.kashrc (or --rcfile).
            // The wrapper layer (kash-app) materializes $HOME/.kashrc on
            // the VFS before spawning us; everything else is opt-in.
            when {
                loginShell && !noProfile -> {
                    StartupFiles.sourceLoginProfiles(interp, ctx)
                }

                posixMode -> {
                    StartupFiles.sourcePosixEnv(interp, ctx)
                }

                !noRc -> {
                    StartupFiles.sourceInteractiveRc(interp, ctx, rcFileOverride)
                }
            }

            return runReplLoop(ctx, interp, editor)
        } finally {
            // Login-shell exit hook (`~/.kash_logout`) — fires for both
            // the `logout` builtin AND a plain EOF, matching bash. Must
            // run before signal-receiver teardown so the script still
            // sees a live foreground slot.
            if (loginShell) {
                try {
                    StartupFiles.sourceLogout(interp, ctx)
                } catch (_: Throwable) {
                    // The cascade already logs its own errors; swallow
                    // anything that escapes so the REPL teardown path
                    // never deadlocks on a broken `.kash_logout`.
                }
            }
            machine.foregroundSignalReceiver = previousReceiver
            machine.sessionSignalReceivers.remove(sessionKey)
            machine.liveSnapshotProviders.remove(ctx.process.pid)
            writeSlot(ctx, interp)
        }
    }

    private suspend fun runReplLoop(
        ctx: CommandContext,
        interp: Interpreter,
        editor: LineEditor,
    ): CommandResult {
        val isComplete: (String) -> Boolean = { src ->
            try {
                Parser(src, interp.aliasResolver).parse() !is ParseResult.Incomplete
            } catch (_: IllegalStateException) {
                false
            }
        }

        replLoop@ while (true) {
            // Bash interactive synchronization point: before each
            // prompt, flush completed-but-not-yet-reported background
            // jobs as `[1]+  Done   sleep 10` lines on stderr. The
            // helper drops reaped entries from the job table so they
            // don't double-fire.
            interp.notifyCompletedBackgroundJobs(ctx.stderr)
            val prompt = "kash:${ctx.process.cwd}$ "
            when (val r = editor.readLine(prompt, "> ", isComplete)) {
                LineEditorResult.Eof -> {
                    break@replLoop
                }

                LineEditorResult.Interrupted -> {
                    continue@replLoop
                }

                is LineEditorResult.Line -> {
                    val trimmed = r.text.trim()
                    if (trimmed.isEmpty()) continue@replLoop
                    if (trimmed == "exit" || trimmed == "quit") break@replLoop
                    try {
                        val ast =
                            try {
                                Parser(r.text, interp.aliasResolver).parseScript()
                            } catch (e: IllegalStateException) {
                                ctx.stderr.writeUtf8("kash: ${e.message ?: "parse error"}\n")
                                continue@replLoop
                            }
                        interp.runStreaming(
                            source =
                                com.accucodeai.kash.interpreter.ScriptStatementSource(
                                    ast.statements,
                                ),
                            // Pass the real terminal stdin so interactive
                            // tools (cat with no args, python3, less, …) can
                            // read user input. PosixTerminalControl's byte
                            // router separates raw-mode (line editor) from
                            // cooked-mode (this) bytes, so there's no race —
                            // when this tool is running the line editor has
                            // exited raw mode (rawCount=0) and bytes flow
                            // to the cooked source.
                            initialStdin = ctx.stdin,
                            stdout = ctx.stdout,
                            stderr = ctx.stderr,
                            stdinIsTty = ctx.process.isTty(0),
                            // Forward the same terminal handle BasicLineEditor
                            // uses, so a tool we dispatch (`kash`, `nano`,
                            // `reset`) inherits the device on its fd 0 OFD
                            // and can flip raw mode. Without this, nested
                            // interactive tools fail with "no terminal
                            // available" even though isTty=true propagates.
                            terminalControl =
                                ctx.process.fdTable[0]
                                    ?.ofd
                                    ?.terminalControl,
                        )
                        editor.addHistory(r.text)
                    } catch (t: Throwable) {
                        ctx.stderr.writeUtf8("kash: ${t.message ?: t::class.simpleName}\n")
                    }
                }
            }
        }
        return CommandResult(exitCode = interp.lastExit)
    }

    private sealed interface Mode {
        data class DashC(
            val script: String,
            val scriptName: String,
            val positional: List<String>,
        ) : Mode

        data object StdinScript : Mode

        data class ScriptFile(
            val path: String,
            val positional: List<String>,
        ) : Mode

        data object Interactive : Mode
    }

    private data class ParsedArgs(
        val mode: Mode?,
        val error: String?,
        val noRc: Boolean = false,
        val loginShell: Boolean = false,
        /** Shell option flags from `-u`/`-e`/`-x`/`-f`/`-v`/`-n`/`-a`
         *  on the command line. Applied to the new interpreter's
         *  [com.accucodeai.kash.interpreter.ShellOptions] before the
         *  script runs. */
        val shellOpts: Set<Char> = emptySet(),
        /** `--noprofile` — suppress the login-shell profile cascade
         *  (`/etc/profile` + `~/.kash_profile|~/.kash_login|~/.profile`).
         *  No effect on non-login shells. */
        val noProfile: Boolean = false,
        /** `--posix` — POSIX-compliant mode. Interactive shells in
         *  this mode source `$ENV` and nothing else; the `~/.kashrc`
         *  block is skipped. */
        val posixMode: Boolean = false,
        /** `--rcfile FILE` / `--init-file FILE` — override the
         *  default `~/.kashrc` path used for interactive non-login
         *  shells. Null means "use the default". */
        val rcFileOverride: String? = null,
    )

    private fun parseArgs(
        args: List<String>,
        stdinIsTty: Boolean,
    ): ParsedArgs {
        var noRc = false
        // `-c` is a flag, not an option that takes an argument inline. Bash
        // takes the command string from the FIRST NON-OPTION argument after
        // all flags are parsed (`bash -c -l 'echo hi'` runs `echo hi` as a
        // login shell, not the script string `-l`). Earlier kash treated -c
        // as a Linux-getopt-style "consume next argv", which is what produces
        // the canonical `<wrapper>: line 1: -l: command not found` failure
        // mode for Claude Code's `bash -c -l '<wrapper>'` invocation.
        var wantDashC = false
        // Login-shell detection: bash treats `kash -l`, `kash --login`, or
        // argv[0] beginning with `-` (i.e. invoked as `-kash`, the
        // historical login-shell exec convention) as a login shell. We
        // don't see argv[0] here (the CommandContext doesn't carry it), so
        // we infer from the flags only — the rare "exec'd as -kash" case
        // can be addressed if/when needed.
        var loginShell = false
        var noProfile = false
        var posixMode = false
        var rcFileOverride: String? = null
        val shellOpts = mutableSetOf<Char>()
        var i = 0

        // Consume leading options. Accept the common bash long-flags
        // harnesses pass — `-l`, `-i`, `-s`, `--login`, `--noediting`,
        // `--noprofile`, `--norc`, `--posix`, `--rcfile`, `--init-file`
        // — so we don't mistake them for a script path. See
        // [StartupFiles] for what `--noprofile` / `--posix` / `--rcfile`
        // actually drive.
        loop@ while (i < args.size) {
            val a = args[i]
            when {
                a == "--norc" -> {
                    noRc = true
                    i++
                }

                a == "--login" -> {
                    loginShell = true
                    i++
                }

                a == "--noprofile" -> {
                    noProfile = true
                    i++
                }

                a == "--posix" -> {
                    posixMode = true
                    i++
                }

                a == "--rcfile" || a == "--init-file" -> {
                    // Consume the next arg as the rc file path. Bash
                    // exits 2 with "option requires an argument" when
                    // it's missing.
                    if (i + 1 >= args.size) {
                        return ParsedArgs(null, "$a: option requires an argument", noRc)
                    }
                    rcFileOverride = args[i + 1]
                    i += 2
                }

                a == "--noediting" -> {
                    i++
                }

                a == "--" -> {
                    i++
                    break@loop
                }

                a == "-c" -> {
                    wantDashC = true
                    i++
                }

                a.startsWith("--") -> {
                    return ParsedArgs(null, "$a: option not yet supported", noRc)
                }

                a.startsWith("-") && a.length >= 2 -> {
                    // Short-option cluster: -lc, -li, -l, etc.
                    for (ch in a.drop(1)) {
                        when (ch) {
                            'l' -> {
                                loginShell = true
                            }

                            'i', 's', 'r', 'B', 'D', 'H' -> {}

                            'c' -> {
                                wantDashC = true
                            }

                            // POSIX shell-option flags acceptable on the
                            // command line — equivalent to `set -<ch>` run
                            // before the first statement. Mirrors bash
                            // builtins.def's "options accepted on shell
                            // invocation": -a/-b/-e/-f/-h/-m/-n/-u/-v/-x.
                            // The capital -E (errtrace) / -T (functrace) /
                            // -P (physical) variants likewise pass through.
                            'a', 'e', 'f', 'h', 'm', 'n', 'u', 'v', 'x',
                            'C', 'E', 'P', 'T',
                            -> {
                                shellOpts += ch
                            }

                            else -> {
                                return ParsedArgs(null, "-$ch: option not yet supported", noRc)
                            }
                        }
                    }
                    i++
                }

                else -> {
                    break@loop
                }
            }
        }

        val rest = args.drop(i)

        if (wantDashC) {
            // bash: `sh -c COMMAND_STRING [arg0 [arg1 …]]`. The first
            // non-option arg is the script body; the next becomes `$0`, the
            // rest become `$1..$N`. POSIX XCU §2 Section "sh".
            if (rest.isEmpty()) {
                return ParsedArgs(null, "-c: option requires an argument", noRc)
            }
            val scriptText = rest[0]
            val scriptName = rest.getOrNull(1) ?: "kash"
            val positional = if (rest.size > 2) rest.drop(2) else emptyList()
            return ParsedArgs(
                mode = Mode.DashC(scriptText, scriptName, positional),
                error = null,
                noRc = noRc,
                loginShell = loginShell,
                shellOpts = shellOpts,
                noProfile = noProfile,
                posixMode = posixMode,
                rcFileOverride = rcFileOverride,
            )
        }

        if (rest.isEmpty()) {
            val mode = if (stdinIsTty) Mode.Interactive else Mode.StdinScript
            return ParsedArgs(
                mode = mode,
                error = null,
                noRc = noRc,
                loginShell = loginShell,
                shellOpts = shellOpts,
                noProfile = noProfile,
                posixMode = posixMode,
                rcFileOverride = rcFileOverride,
            )
        }
        // bash: `kash SCRIPT [ARG...]` — argv[0] is the script (becomes $0),
        // argv[1..] are positional ($1..$N).
        val positional = if (rest.size > 1) rest.drop(1) else emptyList()
        return ParsedArgs(
            mode = Mode.ScriptFile(rest[0], positional),
            error = null,
            noRc = noRc,
            loginShell = loginShell,
            shellOpts = shellOpts,
            noProfile = noProfile,
            posixMode = posixMode,
            rcFileOverride = rcFileOverride,
        )
    }
}
