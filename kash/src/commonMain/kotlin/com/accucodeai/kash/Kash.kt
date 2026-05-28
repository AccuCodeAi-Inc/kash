package com.accucodeai.kash

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.ExecResult
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.asSpec
import com.accucodeai.kash.api.installStdio
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.api.sandbox.SandboxPolicy
import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.api.terminal.ControllingTty
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.api.user.UserDatabase
import com.accucodeai.kash.api.util.bufferOf
import com.accucodeai.kash.fs.FileAccess
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.fs.installSystemBin
import com.accucodeai.kash.interpreter.Interpreter
import com.accucodeai.kash.snapshot.InterpreterSnapshot
import com.accucodeai.kash.tools.ext.extCommands
import com.accucodeai.kash.tools.forensics.forensicsCommands
import com.accucodeai.kash.tools.kash.all.kashCommands
import com.accucodeai.kash.tools.posix.posixCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * Public embedding entry point — the VM kash sessions run on.
 *
 * One [Kash] per app: owns the [machine], filesystem, command registry, and
 * the init process. Multiple [Session]s coexist on a single [Kash] — they
 * share the FS/registry/process table but each has its own pid, env, cwd,
 * shell variables, function table, and aliases. Modeled on Lua's
 * `lua_newstate` / `lua_newthread` split and Jupyter's kernel + session
 * shape.
 *
 * Two usage patterns:
 *  - **One-shot**: `kash.exec(script, options)` — fresh session per call,
 *    no state retained. Closes its session immediately.
 *  - **Stateful**: `val s = kash.newSession(); s.exec(...)` — long-lived
 *    session that retains env/vars/cwd/aliases across `exec`s. Close with
 *    `s.close()` (or `s.use { ... }`).
 *
 * The filesystem is shared across all sessions: files written by one are
 * visible to others. Each session is its own POSIX session leader
 * (setsid-style: `sid = pgid = pid`) so `kill -<pgid>`, signal routing via
 * [machine.sessionSignalReceivers], and `/dev/tty` resolution work
 * independently per session.
 */
public class Kash(
    fs: FileSystem = InMemoryFs(clock = { Clock.System.now().epochSeconds }),
    registry: CommandRegistry? = null,
    customCommands: List<Command> = emptyList(),
    /**
     * The session's user database — drives POSIX `id` / `logname`, `~user`
     * tilde expansion, and the default values for `$LOGNAME`/`$USER`/`$HOME`
     * in [initialEnv]. Pure code — must be re-supplied at restore time.
     */
    public val userDatabase: UserDatabase = UserDatabase.Default,
    /**
     * Default sandbox posture for sessions created on this VM. Each
     * [newSession] call can override.
     */
    public val sandbox: SandboxPolicy = SandboxPolicy.TRUSTED,
    /**
     * Initial env handed to each new session. Tests / embedders pass an
     * explicit map; defaults to [defaultEnvFor]`(userDatabase)`.
     */
    public val initialEnv: Map<String, String> = defaultEnvFor(userDatabase),
    /**
     * Initial cwd handed to each new session. Defaults to the user's
     * home directory.
     */
    public val initialCwd: String = userDatabase.current().home,
    /**
     * Whether sessions default to interactive. Each [newSession] call
     * can override.
     */
    public val isInteractive: Boolean = false,
    /**
     * Parent context for each session's background-job scope. Pass
     * `currentCoroutineContext()` from inside a `runTest` block so
     * background jobs (`cmd &`) inherit the test scheduler.
     */
    public val parentContext: CoroutineContext = Dispatchers.Default,
    /**
     * Backs `$RANDOM`. Default returns a fresh 0..32767 from the platform
     * RNG. Tests and replay harnesses inject a seeded source for
     * deterministic output.
     */
    public val randomSource: () -> Int = { kotlin.random.Random.nextInt(0, 32768) },
    /**
     * Time source backing `$EPOCHSECONDS`, `$EPOCHREALTIME`, `$SECONDS`,
     * `date`, and `times`. Defaults to wall+monotonic from the host;
     * conformance tests inject a virtual clock tied to the test scheduler.
     */
    public val clock: com.accucodeai.kash.api.clock.ShellClock =
        com.accucodeai.kash.api.clock
            .SystemShellClock(),
    /**
     * Machine-wide outbound-network capability. Defaults to allow-all.
     */
    public val networkPolicy: NetworkPolicy = NetworkPolicy.None,
    /**
     * Wall-clock epoch at which this VM "booted." Stamp at construction
     * (e.g. `Clock.System.now().epochSeconds`). Default 0 means "boot time
     * unknown"; consumers treat 0 as "decline to compute."
     */
    public val bootEpochSeconds: Long = 0L,
) : AutoCloseable {
    /**
     * The effective command catalog. If [registry] was supplied, [customCommands]
     * are appended to it; otherwise the standard catalog ([defaultCommandSpecs])
     * is used as the base.
     */
    public val registry: CommandRegistry =
        run {
            val extras = customCommands.map { cmd -> if (cmd is CommandSpec) cmd else cmd.asSpec() }
            if (registry != null) {
                if (extras.isEmpty()) registry else CommandRegistry(registry.specs + extras)
            } else {
                CommandRegistry(defaultCommandSpecs() + extras)
            }
        }

    // ProcFs (mounted by installSystemBin) reads from a process-table
    // supplier. The machine — which owns the table — doesn't exist yet at
    // FS-construction time, so route through `machineRef` (populated below).
    private var machineRef: KashMachine? = null

    /**
     * Effective filesystem — the caller's FS wrapped with the synthetic
     * `/usr/bin` mount (POSIX [XCU §2.9.1.1.e](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_09_01_01))
     * via [installSystemBin].
     */
    public val fs: FileSystem =
        installSystemBin(
            fs,
            registry = this::registry,
            processes = { machineRef?.processTable ?: emptyMap() },
        )

    /**
     * The kernel-equivalent VM that every session on this [Kash] shares.
     * Multiple [Session]s coexist on this one machine — they see one process
     * table, one filesystem, one `/proc`.
     */
    public val machine: KashMachine =
        KashMachine(
            fs = this.fs,
            registry = this.registry,
            bootEpochSeconds = bootEpochSeconds,
            clock = clock,
            networkPolicy = networkPolicy,
        ).also { machineRef = it }

    init {
        // Pre-seed init so the first session's fork() lands the shell at
        // a stable, predictable pid (typically 2).
        machine.ensureInit(cwd = initialCwd, env = initialEnv.toMutableMap())
    }

    /**
     * Create a new stateful session on this VM. Multiple sessions can
     * coexist — they share the FS/registry/process table but get their own
     * pid, env, cwd, shell vars, functions, aliases.
     *
     * Each session is its own POSIX session leader (`sid = pgid = pid`) so
     * `kill -<pgid>`, [machine.sessionSignalReceivers] routing, and per-tab
     * `/dev/tty` resolution work independently.
     *
     * @param env initial env for the session. Defaults to this VM's [initialEnv].
     * @param cwd initial cwd. Defaults to this VM's [initialCwd].
     * @param interactive whether the session is interactive (REPL semantics —
     *   a failed POSIX special builtin doesn't terminate the session).
     * @param sandbox sandbox posture override; defaults to the VM's [sandbox].
     * @param customCommands extra commands appended to the registry FOR THIS SESSION ONLY.
     * @param controllingTty optional terminal bundle for this session — set
     *   when the session leader is bound to a real (or virtual) tty so
     *   `open("/dev/tty")` resolves and signals route correctly.
     */
    public fun newSession(
        env: Map<String, String>? = null,
        cwd: String? = null,
        interactive: Boolean = this.isInteractive,
        sandbox: SandboxPolicy = this.sandbox,
        customCommands: List<Command> = emptyList(),
        controllingTty: ControllingTty? = null,
    ): Session {
        val init = machine.ensureInit()
        val process =
            init.fork().apply {
                // Become a POSIX session leader. linux: setsid(2). Each
                // session gets its own controlling tty, so /dev/tty and
                // signal targeting route to the right session.
                this.sid = this.pid
                this.pgid = this.pid
                this.cwd = cwd ?: this@Kash.initialCwd
                this.env.clear()
                this.env.putAll(env ?: this@Kash.initialEnv)
                this.commandName = "kash"
                this.argv = listOf("kash")
            }
        machine.sessions[process.pid] =
            com.accucodeai.kash.api.Session(
                sid = process.pid,
                leaderPid = process.pid,
                controllingTty = controllingTty,
            )
        // When the caller supplies a controlling tty, install fd 0/1/2
        // OFD entries against it on the session leader. That makes
        // `open("/dev/tty")` and "redirection hides the tty" semantics
        // work without the caller doing the boilerplate themselves —
        // matches the JVM REPL bootstrap and the browser tab spawn.
        if (controllingTty != null) {
            process.installStdio(tty = controllingTty)
        }
        val effectiveRegistry =
            if (customCommands.isEmpty()) {
                registry
            } else {
                CommandRegistry(
                    registry.specs +
                        customCommands.map { cmd -> if (cmd is CommandSpec) cmd else cmd.asSpec() },
                )
            }
        return Session(process, effectiveRegistry, interactive, sandbox)
    }

    /**
     * One-shot exec — runs [script] on a freshly forked session and discards
     * the session afterwards. Equivalent to
     * `newSession(...).use { it.exec(script, options) }` but inlined to keep
     * the historical one-shot signature.
     *
     * Each [exec] gets isolated shell state (env, vars, locals); the
     * filesystem persists across calls because it lives on the VM.
     */
    public suspend fun exec(
        script: String,
        options: ExecOptions = ExecOptions(),
    ): ExecResult {
        val env =
            if (options.replaceEnv) {
                options.env.toMap()
            } else {
                initialEnv + options.env
            }
        val cwd = options.cwd ?: initialCwd
        val session =
            newSession(
                env = env,
                cwd = cwd,
                interactive = false,
            )
        return try {
            session.execWithOptions(script, options)
        } finally {
            session.close()
        }
    }

    /**
     * One-shot exec with file-access tracing toggled inline — the terse
     * "yolo" form: `kash.exec("python3 build.py", traceAccess = true)`, then
     * read [ExecResult.touched] (or its `reads()` / `writes()` helpers).
     * Sugar for `exec(script, ExecOptions(traceAccess = traceAccess))`.
     */
    public suspend fun exec(
        script: String,
        traceAccess: Boolean,
    ): ExecResult = exec(script, ExecOptions(traceAccess = traceAccess))

    /**
     * Cancel every session currently registered on this VM and tear down
     * the machine. Idempotent. After [close], [newSession] / [exec] must
     * not be called.
     */
    override fun close() {
        // Drop session entries we own. Per-session close is the caller's
        // responsibility for explicit sessions; we no-op here for any that
        // are still live because their Interpreter scope drives the
        // background-job teardown.
        machine.sessions.clear()
    }

    /**
     * Stateful execution handle. Created by [newSession]; retains shell
     * state (env, cwd, function definitions, locals, aliases, shopt) across
     * [exec] calls. Closing the session reaps its process from the machine's
     * process table; the VM continues to live and may have other sessions.
     */
    public inner class Session internal constructor(
        public val process: KashProcess,
        registry: CommandRegistry,
        public val isInteractive: Boolean,
        sandbox: SandboxPolicy,
    ) : AutoCloseable {
        /** The VM this session runs on. Same instance as the enclosing [Kash]. */
        public val machine: KashMachine get() = this@Kash.machine

        /** The VM's filesystem. Shared across every session on this [Kash]. */
        public val fs: FileSystem get() = this@Kash.fs

        internal val interpreter: Interpreter =
            Interpreter(
                machine = machine,
                registry = registry,
                process = process,
                interactive = isInteractive,
                parentContext = this@Kash.parentContext,
                userDb = this@Kash.userDatabase,
                sandbox = sandbox,
                randomSource = this@Kash.randomSource,
                clock = this@Kash.clock,
            )

        public val cwd: String get() = interpreter.cwd
        public val env: Map<String, String> get() = interpreter.env

        /**
         * Mutate a single env var from outside the interpreter. Not synchronized
         * — call from the controlling thread between [exec] invocations.
         */
        public fun setEnv(
            name: String,
            value: String,
        ) {
            interpreter.env[name] = value
        }

        /**
         * Run [script] on this session — env, cwd, vars, functions persist
         * across calls.
         */
        public suspend fun exec(script: String): ExecResult {
            // Per-statement read-parse-execute (see [execWithOptions] for
            // the rationale). Bypass [Interpreter.run] to avoid building
            // a Script up front — the stream re-parses per statement
            // against live `posixModeRuntime` / aliases.
            val stream =
                com.accucodeai.kash.parser.StatementStream(
                    source = script,
                    aliasResolver = interpreter.aliasResolver,
                    posixModeProvider = { interpreter.posixModeRuntime },
                    extglobProvider = { interpreter.isShoptEnabled("extglob") },
                    aliasVersionProvider = { interpreter.aliasVersion },
                )
            val source =
                com.accucodeai.kash.interpreter
                    .StreamStatementSource(stream)
            val out = Buffer()
            val err = Buffer()
            val stdinIsTty = isInteractive
            val code =
                interpreter.runStreaming(
                    source = source,
                    initialStdin = bufferOf(ByteArray(0)),
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                    stdinIsTty = stdinIsTty,
                )
            return ExecResult(out.readString(), err.readString(), code)
        }

        /**
         * Run [script] with full [ExecOptions] semantics: per-call env / cwd
         * override (transient — restored after), stdin, mergeStderr, scriptName.
         *
         * Per-call env/cwd overrides reset to the session's persistent values
         * after the call. Mutations made by the script (`export X=1`, `cd /tmp`)
         * persist as usual.
         */
        public suspend fun execWithOptions(
            script: String,
            options: ExecOptions,
        ): ExecResult {
            // Per-statement read-parse-execute loop: each statement is
            // parsed against the live shell state immediately before it
            // executes, so mid-script `set -o posix` / `shopt -s extglob`
            // / alias mutations affect subsequent statements' lexing —
            // the POSIX-shell reader-loop shape.
            //
            // The StatementStream re-reads `interpreter.posixModeRuntime`
            // (and the live aliasResolver, which is already a closure over
            // the interpreter's alias table) before each parse pass.
            val statementStream =
                com.accucodeai.kash.parser.StatementStream(
                    source = script,
                    aliasResolver = interpreter.aliasResolver,
                    posixModeProvider = { interpreter.posixModeRuntime },
                    extglobProvider = { interpreter.isShoptEnabled("extglob") },
                    aliasVersionProvider = { interpreter.aliasVersion },
                )
            val statementSource =
                com.accucodeai.kash.interpreter
                    .StreamStatementSource(statementStream)
            // Per-call env/cwd: take a snapshot so we can restore.
            // Skip dynamic specials (RANDOM/SECONDS/LINENO/...) — reading
            // them advances the underlying state, and restoring via
            // `env[name] = saved` would route through their setter and
            // reseed/rebase. Their state lives on the interpreter itself.
            val savedEnv =
                interpreter.env.keys
                    .filter { !interpreter.isDynamicVar(it) }
                    .associateWith { interpreter.env[it] ?: "" }
            val savedCwd = interpreter.cwd
            val effectiveEnv =
                if (options.replaceEnv) {
                    options.env.toMap()
                } else {
                    // Skip dynamic vars (RANDOM, SECONDS, LINENO, …) when
                    // snapshotting. Reading them advances state (RANDOM)
                    // or rebases internal counters (SECONDS), and putting
                    // the snapshot back through `putAll` would route the
                    // value through each var's setter — RANDOM=N reseeds,
                    // SECONDS=N rebases. The round-trip is invisible by
                    // construction in bash; we mirror that by excluding
                    // them from the snapshot.
                    interpreter.env.keys
                        .asSequence()
                        .filter { !interpreter.isDynamicVar(it) }
                        .associateWith { interpreter.env[it] ?: "" } + options.env
                }
            interpreter.env.clear()
            interpreter.env.putAll(effectiveEnv)
            // Mirror Interpreter's init-block guarantees: POSIX getopts
            // requires OPTIND=1 in every shell, and bash-conformance fixtures
            // rely on a BASH_VERSION. Re-seed when the caller's env (often
            // `replaceEnv = true` from conformance runners) wiped them.
            if ("OPTIND" !in interpreter.env) interpreter.env["OPTIND"] = "1"
            if ("BASH_VERSION" !in interpreter.env) interpreter.env["BASH_VERSION"] = "5.3.0"
            // Bash guarantees IFS is set at shell start to its POSIX default
            // (space/tab/newline). Tests like `${IFS+x}` rely on `set` being
            // true; without this re-seed, `replaceEnv=true` callers (the
            // conformance harness) see IFS unset and `${IFS+x}` collapses
            // to empty.
            if ("IFS" !in interpreter.env) interpreter.env["IFS"] = " \t\n"
            options.cwd?.let { interpreter.cwd = it }
            interpreter.setScriptName(options.scriptName)
            // Outer-script context for parse-error / deferred-cmdsub
            // diagnostics. The `-c` shape is identified by the default
            // script name — matches [KashShellCommand]'s heuristic.
            val savedOuterScript = interpreter.currentOuterScript
            val savedOuterIsCLine = interpreter.currentOuterIsCLine
            interpreter.currentOuterScript = script
            interpreter.currentOuterIsCLine =
                options.scriptName == "kash" || options.scriptName == "bash"
            val out = Buffer()
            val err = if (options.mergeStderr) out else Buffer()
            // File-access capture (opt-in). A synchronous observer on the
            // bus funnels every recorded touch into an unbounded channel —
            // emit() calls it inline, so by the time runStreaming returns
            // the channel holds the complete set (no async drain). When off,
            // nothing is registered and the recording layer allocates nothing.
            val traceChannel: Channel<FileAccess>? =
                if (options.traceAccess) Channel(Channel.UNLIMITED) else null
            val traceObserver: ((FileAccess) -> Unit)? =
                traceChannel?.let { ch -> { ch.trySend(it) } }
            traceObserver?.let { machine.fileAccess.addObserver(it) }
            // Parse warnings are no longer collected up-front — each
            // StatementStream.next() returns its own warnings on first
            // yield, and the source-driven runStreaming loop attributes
            // them to the upcoming statement via flushLexWarningsBefore.
            val code =
                try {
                    interpreter.runStreaming(
                        source = statementSource,
                        initialStdin = bufferOf(options.stdin.encodeToByteArray()),
                        stdout = out.asSuspendSink(),
                        stderr = err.asSuspendSink(),
                        stdinIsTty = false,
                    )
                } finally {
                    // Reset transient per-call overrides. Note: script
                    // mutations (`export X=1`, `cd /tmp`) are LOST here.
                    // That matches the one-shot semantics; for sticky state
                    // use [exec] without options.
                    interpreter.env.clear()
                    interpreter.env.putAll(savedEnv)
                    interpreter.cwd = savedCwd
                    interpreter.currentOuterScript = savedOuterScript
                    interpreter.currentOuterIsCLine = savedOuterIsCLine
                    traceObserver?.let { machine.fileAccess.removeObserver(it) }
                    traceChannel?.close()
                }
            val touched = traceChannel?.let { drainTouched(it) } ?: emptyList()
            return ExecResult(out.readString(), err.readString(), code, touched)
        }

        /**
         * Streaming exec: stdout/stderr go straight to the caller's sinks
         * instead of being buffered. Used when running raw-mode tools (`nano`)
         * that need to paint the terminal mid-execution.
         */
        public suspend fun execStreaming(
            script: String,
            stdin: SuspendSource,
            stdout: SuspendSink,
            stderr: SuspendSink,
            stdinIsTty: Boolean = false,
            terminalControl: TerminalControl? = null,
        ): Int {
            // Per-statement parse via StatementStream. See [execWithOptions].
            val stream =
                com.accucodeai.kash.parser.StatementStream(
                    source = script,
                    aliasResolver = interpreter.aliasResolver,
                    posixModeProvider = { interpreter.posixModeRuntime },
                    extglobProvider = { interpreter.isShoptEnabled("extglob") },
                    aliasVersionProvider = { interpreter.aliasVersion },
                )
            val source =
                com.accucodeai.kash.interpreter
                    .StreamStatementSource(stream)
            return interpreter.runStreaming(source, stdin, stdout, stderr, stdinIsTty, terminalControl)
        }

        /**
         * Deliver a kash signal to the foreground shell. Thread-safe.
         * INT/TERM cancel the currently-executing statement; the matching trap
         * handler fires from the interpreter's between-statements drain.
         */
        public fun deliverSignal(signal: KashSignal) {
            interpreter.deliverSignal(signal)
        }

        /**
         * Run the registry's `kash` command on this session's process — i.e.
         * hand control to the shell REPL. The kash command spins up its own
         * interpreter inside [process] (using the fd table the caller wired
         * up via [controllingTty] at [newSession] time), runs until exit,
         * and returns the exit code.
         *
         * This is the host-driven entry point that backs the JVM REPL and
         * each browser tab: the embedder wants the shell to "take over" the
         * process and read user input, not embed it as a buffered library
         * call. Caller is responsible for [close]ing the session afterwards.
         */
        public suspend fun runShellCommand(args: List<String> = emptyList()): Int {
            val cmd =
                machine.registry["kash"]?.command
                    ?: error("'kash' command missing from registry")
            val ctx =
                com.accucodeai.kash.api.CommandContext(
                    process = process,
                    isInteractive = isInteractive,
                    userDb = this@Kash.userDatabase,
                )
            return cmd.run(args, ctx).exitCode
        }

        /** True while a foreground statement is mid-flight. */
        public val isExecutingForeground: Boolean
            get() = interpreter.isExecutingForeground

        /**
         * Capture a quiescent snapshot of this session's state. Safe to call
         * between [exec] invocations. The VM's [fs] must be an [InMemoryFs].
         */
        public fun snapshot(): InterpreterSnapshot = interpreter.snapshot()

        /**
         * Cancel every in-flight background job, tear down the session's
         * coroutine scope, and unregister this session's process from the
         * VM's process table. Idempotent.
         */
        override fun close() {
            interpreter.close()
            machine.sessions.remove(process.pid)
            machine.unregisterProcess(process.pid)
        }
    }

    public companion object {
        /**
         * Default contents of `$HOME/.kashrc` — written into the session's
         * VFS the first time an interactive session starts with no existing
         * rc file. Aliases mirror what `/etc/bashrc` ships on most Linux
         * distros: `ls`/`grep` colorize for tty stdout, scripts stay
         * byte-identical.
         *
         * Users edit this file the normal way (`nano ~/.kashrc`) and changes
         * apply to the next session.
         */
        public const val DEFAULT_KASHRC: String =
            $$"""# kashrc — interactive non-login shell startup file.
#
# Sourced once per interactive non-login session from the kash VFS.
# NOT read by login shells (those source ~/.kash_profile instead),
# `kash -c '…'`, `kash script.sh`, or piped/non-tty invocations.
#
# Override with `kash --rcfile FILE` / `--init-file FILE`, or skip rc
# sourcing entirely with `--norc`.

# GNU coreutils convention: color when stdout is a tty.
alias ls='ls --color=auto'
alias grep='grep --color=auto'
alias egrep='egrep --color=auto'
alias fgrep='fgrep --color=auto'
"""

        /**
         * Default contents of `$HOME/.kash_profile` — written into the
         * session's VFS the first time an interactive session starts
         * with no existing profile. Sourced by **login** shells only
         * (`kash -l`, `kash --login`), after `/etc/profile`.
         *
         * Mirrors what `/etc/skel/.bash_profile` ships on most Linux
         * distros: a stub that also sources `~/.kashrc` so one rc edit
         * covers both login and non-login interactive shells.
         */
        public const val DEFAULT_KASH_PROFILE: String =
            $$"""# kash_profile — login shell startup file.
#
# Sourced once per interactive login session (`kash -l`, `kash --login`),
# after /etc/profile. NOT read by non-login interactive shells (those
# source ~/.kashrc directly) or by `kash -c '…'` (those honor $KASH_ENV
# / $BASH_ENV instead).
#
# Conventionally, login profiles re-source the interactive rc so a
# single edit to ~/.kashrc covers both login and non-login terminals.

if [ -r "$HOME/.kashrc" ]; then
    . "$HOME/.kashrc"
fi
"""

        /**
         * Rehydrate a session snapshot onto an existing [Kash]. Creates a
         * fresh session, then restores its interpreter state (env, cwd,
         * functions, locals, aliases, etc.) from [snapshot].
         *
         * The VM's filesystem is NOT replaced — caller is responsible for
         * restoring the FS independently via [com.accucodeai.kash.fs.InMemoryFs]
         * construction with the snapshot's `fs` field.
         */
        public fun attachSession(
            snapshot: InterpreterSnapshot,
            attachTo: Kash,
            interactive: Boolean = false,
            customCommands: List<Command> = emptyList(),
            controllingTty: ControllingTty? = null,
        ): Session {
            val session =
                attachTo.newSession(
                    env = snapshot.env,
                    cwd = snapshot.cwd,
                    interactive = interactive,
                    customCommands = customCommands,
                    controllingTty = controllingTty,
                )
            session.interpreter.restore(snapshot)
            return session
        }

        /**
         * Rehydrate a session into a freshly-built VM. Convenience over
         * [attachSession] for the common single-session use case — builds a
         * [Kash] with the supplied pure-code config (registry, fs, userDb,
         * sandbox, parentContext, clock), then attaches the snapshot to a
         * new session on it. The snapshot's `fs` field seeds the in-memory
         * filesystem.
         *
         * Sized to match the old `KashSession.restore(...)` API so callers
         * with a one-session-per-VM model migrate without restructuring.
         */
        public fun restoreSession(
            snapshot: InterpreterSnapshot,
            registry: CommandRegistry,
            customCommands: List<Command> = emptyList(),
            interactive: Boolean = false,
            userDatabase: UserDatabase = UserDatabase.Default,
            sandbox: SandboxPolicy = SandboxPolicy.TRUSTED,
            parentContext: CoroutineContext = Dispatchers.Default,
            clock: () -> Long = { Clock.System.now().epochSeconds },
        ): Session {
            val fs = InMemoryFs(snapshot.fs, clock)
            val kash =
                Kash(
                    fs = fs,
                    registry = registry,
                    userDatabase = userDatabase,
                    sandbox = sandbox,
                    parentContext = parentContext,
                )
            return attachSession(
                snapshot = snapshot,
                attachTo = kash,
                interactive = interactive,
                customCommands = customCommands,
            )
        }
    }
}

/**
 * Standard kash command catalog: the shell itself (`kash`) plus every
 * POSIX, ext, and kash-only tool. Heavy opt-in tools that need extra
 * runtime state (`python3` needs a [com.accucodeai.kash.tools.python3
 * .PythonEngine]) are NOT here — `kash-app` and `kash-app-web` append
 * those themselves with the appropriate per-target engine.
 */
public fun defaultCommandSpecs(): List<CommandSpec> = posixCommands + extCommands + forensicsCommands + kashCommands

/** Build a fresh standard [CommandRegistry] for tests and other embeddings. */
public fun standardRegistry(): CommandRegistry = CommandRegistry(defaultCommandSpecs())

/**
 * Bootstrap env for a fresh [Kash] session. Pulls `$HOME`, `$LOGNAME`,
 * `$USER` from [userDb]; pins `$PATH` to `/usr/bin:/bin` (the ToolsFs
 * mount points). Public so embedders can extend the map without
 * re-deriving the user fields.
 */
public fun defaultEnvFor(userDb: UserDatabase): Map<String, String> {
    val u = userDb.current()
    return mapOf(
        "HOME" to u.home,
        "PATH" to "/usr/bin:/bin",
        "PWD" to u.home,
        "LOGNAME" to u.name,
        "USER" to u.name,
        // POSIX getopts: OPTIND starts at 1 in every new shell.
        "OPTIND" to "1",
    )
}

/**
 * Drain a closed, buffered [Channel] of [FileAccess] into a list. The
 * observer fed it synchronously during the exec, so after close every
 * event is already buffered — `tryReceive` empties it without suspending.
 */
private fun drainTouched(channel: Channel<FileAccess>): List<FileAccess> {
    val touched = ArrayList<FileAccess>()
    while (true) {
        val r = channel.tryReceive()
        if (r.isSuccess) touched.add(r.getOrThrow()) else break
    }
    return touched
}
