package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.ExitStatus
import com.accucodeai.kash.api.FdTableEntry
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.ShellRunner
import com.accucodeai.kash.api.UtilityRunner
import com.accucodeai.kash.api.installFd
import com.accucodeai.kash.api.installStdio
import com.accucodeai.kash.api.io.AsyncPipe
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.api.signal.SigDebug
import com.accucodeai.kash.api.signal.SigExit
import com.accucodeai.kash.api.signal.SigHup
import com.accucodeai.kash.api.signal.SigInt
import com.accucodeai.kash.api.signal.SigQuit
import com.accucodeai.kash.api.signal.SigReturn
import com.accucodeai.kash.api.signal.SigTerm
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.api.util.bufferOf
import com.accucodeai.kash.api.util.emptySource
import com.accucodeai.kash.ast.Connector
import com.accucodeai.kash.ast.FunctionDef
import com.accucodeai.kash.ast.InlineAssignment
import com.accucodeai.kash.ast.RedirOp
import com.accucodeai.kash.ast.RedirTarget
import com.accucodeai.kash.ast.Redirection
import com.accucodeai.kash.ast.Script
import com.accucodeai.kash.ast.SimpleCommand
import com.accucodeai.kash.ast.Statement
import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.jobs.JobControl
import com.accucodeai.kash.parser.ParseException
import com.accucodeai.kash.parser.Parser
import com.accucodeai.kash.snapshot.InterpreterSnapshot
import com.accucodeai.kash.traps.TrapAction
import com.accucodeai.kash.traps.TrapTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.coroutines.CoroutineContext

/**
 * Tree-walking interpreter.
 *
 * Streaming pipeline plumbing: every command receives a [SuspendSource] stdin and
 * writes to two [SuspendSink]s (stdout, stderr). Multi-stage pipelines run each
 * stage in its own coroutine connected by [AsyncPipe]s, so an unbounded
 * producer plus a `head`-style consumer terminates promptly.
 */
public class Interpreter(
    /**
     * The "VM" the interpreter runs on. Sole source of [fs] and the
     * process table. [Kash] / [Kash.Session] share one machine — sessions are sibling processes
     * and run successive interpreters on top of it (sharing process
     * tables, OFD pools, etc.); [forkSubshell] passes the same machine
     * to its fork so children share the kernel with their parent.
     */
    internal val machine: KashMachine,
    internal val registry: CommandRegistry,
    /**
     * Non-interactive shells abort the script when a POSIX special builtin
     * fails ([Special Builtins, bash manual](
     * https://www.gnu.org/software/bash/manual/html_node/Special-Builtins.html)).
     */
    internal val interactive: Boolean = false,
    /**
     * Parent context for the session-level coroutine scope that owns
     * background jobs (`cmd &`). Callers pass `currentCoroutineContext()`
     * from inside a `runTest` block to make backgrounds inherit the test
     * scheduler (virtual time, deterministic cancellation). Production
     * callers default to [Dispatchers.Default]. The Job in [parentContext]
     * becomes the parent of the internal [SupervisorJob], so a cancelled
     * parent (e.g. test scope tear-down) cancels every background.
     */
    parentContext: CoroutineContext = Dispatchers.Default,
    /**
     * Pure code — the session's user database. Backs POSIX `id`/`logname`,
     * `~user` tilde expansion, and the default `$LOGNAME`/`$USER`/`$HOME`
     * values populated by [Kash.Session]. Shared by reference with forked
     * subshells; not serialized in snapshots.
     */
    public val userDb: com.accucodeai.kash.api.user.UserDatabase =
        com.accucodeai.kash.api.user.UserDatabase.Default,
    /**
     * Sandbox posture handed to tools via [com.accucodeai.kash.api.CommandContext].
     * Pure-code, shared by reference with forked subshells; not persisted in
     * snapshots.
     */
    internal val sandbox: com.accucodeai.kash.api.sandbox.SandboxPolicy =
        com.accucodeai.kash.api.sandbox.SandboxPolicy.TRUSTED,
    /**
     * When this interpreter is a forked subshell (via [forkSubshell]) the
     * parent passes its session-level resources here so the fork and parent
     * share the same job table and coroutine scope. Top-level sessions leave
     * this null and create their own.
     */
    internal val sharedSession: SharedSession? = null,
    /**
     * The process this interpreter executes on. The caller (Kash / Kash.Session
     * / [forkSubshell]) is responsible for constructing it — either fresh from
     * the session's initial env/cwd, or via `parent.fork()` for subshells.
     * Per-process state (env, cwd, umask, fd table, pid, signal dispositions)
     * is read straight from this object.
     */
    internal val process: KashProcess,
    /**
     * Backs `$RANDOM`. Defaults to [kotlin.random.Random], producing the
     * usual non-reproducible 0..32767 stream. Embedders that need a fixed
     * seed (deterministic tests, snapshot replay) pass their own generator
     * — e.g. `{ Random(seed).nextInt(0, 32768) }`. Subshells inherit via
     * [forkSubshell].
     */
    internal val randomSource: () -> Int = { kotlin.random.Random.nextInt(0, 32768) },
    /**
     * Time source for the shell. Backs `$EPOCHSECONDS`, `$EPOCHREALTIME`,
     * `date` (wall) AND `$SECONDS`, `times` user/sys (monotonic-since-start).
     * Production binds [SystemShellClock]; conformance tests bind a
     * virtual variant tied to the test scheduler so `sleep` advances
     * `$SECONDS`.
     */
    internal val clock: com.accucodeai.kash.api.clock.ShellClock =
        com.accucodeai.kash.api.clock
            .SystemShellClock(),
) {
    /**
     * Session-level resources that a forked subshell inherits from its
     * parent. Sharing the scope means a parent's `close()` cancels every
     * descendant's background work; sharing [jobControl] means `$!`/`jobs`
     * are unified across `(...)` and `&` — same as bash's "subshells see
     * the parent's job table" behavior (bash's *interactive* mode is
     * stricter; we ignore that nuance).
     */
    public data class SharedSession(
        val scope: CoroutineScope,
        val jobControl: JobControl,
        /**
         * Session-scoped pid → in-shell signal deliverer registry.
         * The root Interpreter registers under `$$` so a synthetic
         * `kill $$` from any fork resolves to the root's
         * [deliverSignal] (whose `foregroundSignals` channel is the
         * one actually being drained by a `run()` loop). Replaces the
         * earlier ad-hoc `rootInterpreter` field — the router scales
         * to additional shell pids without changing this API.
         */
        val signalRouter: com.accucodeai.kash.signals.SignalRouter,
        /**
         * Cooperative coroutine stop-gate — the kash-coroutine
         * analogue of the kernel's `SIGTSTP → process stopped →
         * SIGCONT` cycle. Paused when the foreground statement
         * receives TSTP; every subsequent dispatch (= coroutine
         * resume) is parked on the gate until `fg`/`bg` resumes it.
         * Per-session; backgrounded jobs run outside the gate.
         */
        val stopGate: com.accucodeai.kash.signals.StopGate,
        /**
         * Compiled-glob cache, shared by reference across the whole fork
         * tree. Unlike [hashCache] (copied per fork, since hash entries are
         * `$PATH`-dependent shell state a subshell can diverge on), a
         * [CompiledGlob] is a pure function of its pattern with no shell
         * state, so every `(...)`, `$(...)`, and pipeline stage can safely
         * reuse the parent's warm cache instead of recompiling from cold.
         */
        val globCache: GlobCache,
    )

    /**
     * Unified shell-variable storage — scalars, indexed arrays,
     * associative arrays, attributes, and the function-local scope
     * chain. Sole source of truth; declared early so [env] and the
     * array views can initialize against it without a `by lazy` dance.
     */
    internal val varTable: VarTable = VarTable()

    /**
     * Shell-variable scalar view backed by [varTable]. Reads walk the
     * scope chain (function-locals shadow globals); writes go through
     * the [ProcessEnvAdapter] which also mirrors to [KashProcess.env]
     * so fork-and-exec keeps seeing the same OS env block it always did
     * (subsequently filtered by [pruneToExportedEnv]).
     */
    public val env: MutableMap<String, String> = ProcessEnvAdapter(varTable, process)

    /**
     * True if [name] is a dynamic-special variable (RANDOM, SECONDS,
     * LINENO, BASHPID, …) — its scalar comes from a getter hook and
     * isn't subject to env snapshot/restore. Callers that save and
     * restore env around an exec/cmdsub should filter dynamic vars
     * to avoid round-tripping their values through the setter
     * (which would reseed RANDOM, rebase SECONDS, etc.).
     */
    public fun isDynamicVar(name: String): Boolean = varTable.find(name)?.isDynamic == true

    /**
     * Current working directory — delegates to [process]. POSIX says cwd is
     * per-process state; the shell session's cwd is just its top-level
     * process's cwd.
     */
    public var cwd: String
        get() = process.cwd
        set(value) {
            process.cwd = value
        }
    internal val functions = mutableMapOf<String, FunctionDef>()

    /** Names of functions marked `export -f` — inherited across the
     *  fork-and-exec boundary so `${THIS_SH} -c foo` can find them. */
    internal val exportedFunctions = mutableSetOf<String>()

    /** Names of functions marked `readonly -f` — `unset -f NAME`
     *  rejected, `declare -fr` listing includes them. */
    internal val readonlyFunctions = mutableSetOf<String>()
    internal var positional: List<String> = emptyList()

    /**
     * Runtime alias table — POSIX `alias`/`unalias`. Insertion-ordered so the
     * `alias` listing is deterministic. Expansion is performed by [Lexer]
     * via a character-level input stack (POSIX §2.3.1 / bash `alias-input-stack push`);
     * the interpreter only stores/retrieves.
     */
    internal val aliases: MutableMap<String, String> = linkedMapOf()

    /**
     * Monotonic counter bumped whenever the [aliases] table mutates
     * (write, remove, clear, bulk copy). Sampled by
     * [com.accucodeai.kash.parser.StatementStream] so a cached
     * multi-statement lex is invalidated when alias state changes
     * between yields — POSIX shells re-parse per statement, so
     * `alias switch=case; echo $( switch x in y) bar;; esac )` works
     * even though the cmdsub body's bound finder needs the alias to
     * recognize `switch` as `case`.
     */
    public var aliasVersion: Long = 0L
        internal set

    /**
     * Programmable completion specs registered via the bash `complete`
     * builtin. Keyed by command name; insertion-ordered so `complete` (no
     * args) lists them deterministically. [ShellCompleter] consults this
     * map when completing arguments of a registered command.
     */
    internal val completeSpecs: MutableMap<String, com.accucodeai.kash.completion.CompleteSpec> = linkedMapOf()

    /** `complete -D` default — used when no per-command spec matches. */
    internal var completeDefault: com.accucodeai.kash.completion.CompleteSpec? = null

    /** `complete -E` — applied to completion attempted on an empty command line. */
    internal var completeEmpty: com.accucodeai.kash.completion.CompleteSpec? = null

    /** `complete -I` — applied to the initial (first) word of the command line. */
    internal var completeInitial: com.accucodeai.kash.completion.CompleteSpec? = null

    /**
     * Bash directory stack — backs `dirs`/`pushd`/`popd` and the
     * `${DIRSTACK[@]}` synthetic array. Top of stack is index 0 (matches
     * `dirs` print order and bash convention). The current `cwd` is
     * conceptually always at the top; `pushd DIR` prepends the new dir and
     * `cd`s into it, `popd` drops the top and `cd`s into what was second.
     */
    internal val dirStack: ArrayDeque<String> = ArrayDeque()

    /**
     * Names of intrinsics suppressed by `enable -n NAME`. The resolver
     * (see [resolveCommand]) skips any [IntrinsicCatalog] entry whose name
     * appears here, so the lookup falls through to PATH — matching bash's
     * "use the disk version of `test` instead of the builtin" semantics.
     * `enable NAME` (no `-n`) removes the entry.
     */
    internal val disabledIntrinsics: MutableSet<String> = mutableSetOf()

    /**
     * True iff this shell was invoked as a login shell — `kash -l`, `kash
     * --login`, or argv[0] starting with `-`. Drives the `logout` builtin
     * (which errors with "not login shell" when false) and the
     * `/etc/profile` + `~/.kash_profile|~/.kash_login|~/.profile` +
     * `~/.kash_logout` cascade in
     * [com.accucodeai.kash.tools.kash.StartupFiles].
     */
    internal var isLoginShell: Boolean = false

    /**
     * Outer-script source and invocation flavor — set by the entry points
     * ([runShellScript], [com.accucodeai.kash.tools.kash.KashShellCommand.runScriptText])
     * right before parse, consumed by deferred-cmdsub parse-error emission
     * so the inner error can use the same `emitShellParseError` shape
     * (script name + `-c:` flag + source-line quote) that outer-parse
     * errors use. Pre-Part-B, cmdsub bodies were parsed at outer-parse
     * time so any failure naturally surfaced through that emit path;
     * post-Part-B the parse fires at expansion time and we have to
     * recreate the prefix/source-line ourselves.
     */
    public var currentOuterScript: String = ""
    public var currentOuterIsCLine: Boolean = false

    /**
     * Command history — backs the `history` and `fc` builtins. Newest
     * entry last (so `history[history.size - 1]` is the most recently
     * recorded command). Capacity is informally bounded by
     * `$HISTSIZE`/`$HISTFILESIZE`; the builtin trims on demand.
     *
     * Independent of [com.accucodeai.kash.api.BasicLineEditor]'s internal
     * ring — KashShellCommand bridges interactive entries into this list
     * (TODO: bridge interactive REPL entries here). For non-interactive runs (`bash -c`,
     * script-file mode) the only way to populate this is via
     * `history -s` or `history -r FILE`.
     */
    internal val history: ArrayDeque<String> = ArrayDeque()

    /**
     * True once history has been loaded from `$HISTFILE` (lazily on the
     * first `history`-builtin invocation that doesn't have a `-c`/`-r`
     * directive). Prevents accidental re-load after the user clears or
     * rewrites the in-memory list.
     */
    internal var historyLoaded: Boolean = false

    /**
     * Tracked `shopt` options that the interpreter actually consults at
     * runtime. The map's default values reproduce bash 5.2 defaults. The
     * `shopt` builtin reads / writes this map; the expansion engine,
     * glob engine, and `$BASH_SOURCE` synthesis check named entries to
     * branch behavior. Option names not present here are still accepted
     * silently by `shopt` (for portability shims like `shopt -s extglob`).
     */
    internal val shoptOptions: MutableMap<String, Boolean> =
        com.accucodeai.kash.completion.KNOWN_SHOPT_NAMES
            .associateWithTo(mutableMapOf()) { name ->
                com.accucodeai.kash.completion.DEFAULT_SHOPT_VALUES[name] ?: false
            }

    /**
     * Whether `shopt` option [name] is currently enabled. Read-side
     * accessor that's safe to call from outside the interpreter
     * package — used by [com.accucodeai.kash.parser.StatementStream]
     * to gate lex-time recognition of `?(`, `*(`, etc. on the live
     * `extglob` value.
     */
    public fun isShoptEnabled(name: String): Boolean = shoptOptions[name] == true

    /**
     * Read-only view of [aliases] for the parser. Held as a field so every
     * [com.accucodeai.kash.parser.Parser] construction can pass the same
     * resolver — including the recursive command-substitution parse in
     * [com.accucodeai.kash.parser.chunkToPart].
     */
    public val aliasResolver: com.accucodeai.kash.parser.AliasResolver =
        com.accucodeai.kash.parser
            .AliasResolver { name -> aliases[name] }

    /** `$0`. Top-level sessions see the shell name; sub-scripts override it. */
    internal var dollarZero: String = "kash"

    /** Set `$0` and the diagnostic prefix used in `command not found` etc. */
    public fun setScriptName(name: String) {
        dollarZero = name
    }

    /**
     * Bash-style `<scriptname>: line <N>: ` prefix for shell-emitted
     * diagnostics. Empty when running with the default `$0` (`kash`) so
     * existing unit tests that compare bare `<name>: command not found`
     * output keep working; the conformance runner supplies a real script
     * name via `ExecOptions.scriptName` to enable the prefix.
     *
     * Uses the *absolute* source line ([currentLine]), not the
     * `$LINENO`-relative one. Bash diagnostics always cite the source-file
     * line — e.g. `./attr.tests: line 17: a: readonly variable` even when
     * the violation fires inside a function body, where `$LINENO` would
     * report 1-based within the body.
     */
    internal fun shellDiagPrefix(): String = if (dollarZero == "kash") "" else "$dollarZero: line $currentLine: "

    /**
     * Per-statement I/O state — sinks, their ttyness, the host terminal
     * handle, and the `exec`-persisted-redirections flag. See [ShellIo].
     */
    internal val io: ShellIo = ShellIo()

    internal var outSink: SuspendSink
        get() = io.outSink
        set(v) {
            io.outSink = v
        }
    internal var errSink: SuspendSink
        get() = io.errSink
        set(v) {
            io.errSink = v
        }
    internal var outSinkIsTty: Boolean
        get() = io.outSinkIsTty
        set(v) {
            io.outSinkIsTty = v
        }
    internal var errSinkIsTty: Boolean
        get() = io.errSinkIsTty
        set(v) {
            io.errSinkIsTty = v
        }
    internal var execPersistedRedirections: Boolean
        get() = io.execPersistedRedirections
        set(v) {
            io.execPersistedRedirections = v
        }
    internal var currentStdinIsTty: Boolean
        get() = io.currentStdinIsTty
        set(v) {
            io.currentStdinIsTty = v
        }
    internal var hostTerminal: TerminalControl?
        get() = io.hostTerminal
        set(v) {
            io.hostTerminal = v
        }

    /**
     * Session-level scope for background jobs (`cmd &`). SupervisorJob so a
     * crashed background job neither cancels its siblings nor the foreground.
     *
     * Tied to [parentContext]'s Job via [Job.invokeOnCompletion] rather than
     * `SupervisorJob(parent)`: the latter makes us a child of the parent, so
     * the parent's *normal completion* (e.g. a `runTest` body returning)
     * waits for us — and since `SupervisorJob` never auto-completes, the test
     * harness times out with "active child jobs". The completion-handler
     * approach instead cancels us when the parent finishes, regardless of
     * how it finishes. Explicit [close] still works.
     */
    internal val sessionScope: CoroutineScope =
        sharedSession?.scope
            ?: CoroutineScope(parentContext + SupervisorJob()).also { scope ->
                parentContext[Job]?.invokeOnCompletion { scope.cancel() }
            }

    public var jobControl: JobControl =
        sharedSession?.jobControl ?: JobControl(machine)
        internal set

    /**
     * Set of job ids that exist in the shared [jobControl] but were
     * inherited from the parent at [forkSubshell] time, so this subshell
     * shouldn't *list* them or address them via `%N` / `%+` / `%-` —
     * matches POSIX's "subshells do not inherit the parent's job table"
     * rule. Bare-pid lookups are deliberately NOT filtered through the
     * mask: `kill 1234` from inside `( ... ) &` must still reach a pid
     * the parent backgrounded, which is the behaviour `coproc.tests` line
     * 45 (`{ sleep 1; kill $REFLECT_PID; } &`) depends on.
     *
     * Empty at the top level; populated by [forkSubshell].
     */
    internal var subshellJobMask: Set<Int> = emptySet()

    /** True for forked subshells — used by [close] to no-op rather than tearing down a shared scope. */
    internal val isFork: Boolean = sharedSession != null

    /**
     * Session-scoped pid → in-shell signal deliverer registry.
     * Shared by reference across forks (via [SharedSession]); at the root,
     * constructed fresh and seeded with this interpreter's `shellPid` (i.e.
     * `$$`) in the `init` block below. The `kill` intrinsic consults this
     * before falling through to jobspec/OS lookup — replaces the earlier
     * `rootInterpreter()` walk for the `kill $$` special case.
     */
    internal val signalRouter: com.accucodeai.kash.signals.SignalRouter =
        sharedSession?.signalRouter ?: com.accucodeai.kash.signals
            .SignalRouter()

    /**
     * Cooperative stop-gate for foreground coroutine work. Shared by
     * reference with forks via [SharedSession]; at the root, constructed
     * fresh in init. Pausing this gate parks every coroutine `dispatch`
     * that flows through [stopGateDispatcher] (i.e. the foreground
     * statement and its descendants); backgrounded `&` jobs are launched
     * onto [sessionScope] without the gate and run unfettered.
     */
    public val stopGate: com.accucodeai.kash.signals.StopGate =
        sharedSession?.stopGate ?: com.accucodeai.kash.signals
            .StopGate()

    /**
     * The dispatcher that wraps the parent context's dispatcher (or
     * [Dispatchers.Default] when none is provided) with the [stopGate].
     * Used as the explicit dispatcher for the foreground statement's
     * `async` block; pipeline stages and any other suspending child
     * coroutines inherit it through structured concurrency.
     */
    internal val stopGateDispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy {
        val parentDispatcher =
            parentContext[kotlin.coroutines.ContinuationInterceptor] as? kotlinx.coroutines.CoroutineDispatcher
                ?: kotlinx.coroutines.Dispatchers.Default
        com.accucodeai.kash.signals
            .createStopGateDispatcher(parentDispatcher, stopGate)
    }

    /** Cleared on `$PATH` mutation and on `hash -r`. POSIX [XCU §hash](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_06). */
    internal val hashCache: MutableMap<String, Resolved> = mutableMapOf()

    /**
     * Compiled-glob cache, shared across every [expander] this interpreter
     * builds so repeated string-op patterns (e.g. a basename-strip in a loop
     * body) compile their RE2 forms once. A forked subshell inherits it by
     * reference (via [SharedSession]) — kash forks readily, so a per-fork
     * cache would be cold on every `(...)`/`$(...)`/pipeline stage. The root
     * (no shared session) owns a fresh one; it dies with the session.
     */
    internal val globCache: GlobCache =
        sharedSession?.globCache ?: GlobCache()

    /**
     * Sync the visible `BASH_CMDS` associative array with the current
     * [hashCache] state. Bash exposes its command-hash table as
     * `BASH_CMDS`, so `${BASH_CMDS[name]}` returns the cached path and
     * `${!BASH_CMDS[@]}` lists the cached names. Call after every
     * hashCache write/clear that needs to be observable to scripts.
     */
    internal fun syncBashCmds() {
        val arr = assocArrays["BASH_CMDS"] ?: linkedMapOf<String, String>().also { assocArrays["BASH_CMDS"] = it }
        arr.clear()
        for ((name, r) in hashCache) {
            val path =
                when (r) {
                    is Resolved.Script -> r.path
                    else -> name
                }
            arr[name] = path
        }
    }

    /**
     * Pull `BASH_CMDS[name]=path` writes the user made directly back
     * into [hashCache] before observing the cache. Bash treats the
     * assoc array and the internal hash table as two views over the
     * same storage; we approximate by syncing in both directions on
     * relevant entry points.
     */
    internal fun absorbBashCmds() {
        val arr = assocArrays["BASH_CMDS"] ?: return
        for ((name, path) in arr) {
            // Only absorb names that aren't already hashCache-backed;
            // existing entries (potentially with full Resolved.Builtin
            // / Resolved.Function shape) take precedence.
            if (name !in hashCache) {
                hashCache[name] = Resolved.Script(path)
            }
        }
    }

    /**
     * Sync the visible `BASH_ALIASES` assoc array with the current
     * [aliases] table. Bash exposes the alias table as `BASH_ALIASES`,
     * so `${BASH_ALIASES[name]}` returns the expansion text and
     * `${!BASH_ALIASES[@]}` lists the alias names. Call after every
     * alias write/unalias.
     */
    internal fun syncBashAliases() {
        val arr =
            assocArrays["BASH_ALIASES"]
                ?: linkedMapOf<String, String>().also { assocArrays["BASH_ALIASES"] = it }
        arr.clear()
        for ((name, expansion) in aliases) {
            arr[name] = expansion
        }
    }

    /** Reverse of [syncBashAliases] — pull direct `BASH_ALIASES[k]=v`
     *  writes back into [aliases] before observing the table (e.g. the
     *  `alias -p` listing or the parse-time alias-expansion pass). */
    internal fun absorbBashAliases() {
        val arr = assocArrays["BASH_ALIASES"] ?: return
        var any = false
        for ((name, expansion) in arr) {
            if (name !in aliases || aliases[name] != expansion) {
                aliases[name] = expansion
                any = true
            }
        }
        if (any) aliasVersion++
    }

    /**
     * Per-name lookup count, incremented on every hashCache read. The
     * `hash` listing's leftmost column ("hits") reports this. Zero-
     * initialized when the entry is created; not incremented when
     * `hash -p` or the PATH walk *populates* the entry, only when a
     * subsequent command-resolution reads it.
     */
    internal val hashHits: MutableMap<String, Int> = mutableMapOf()

    /**
     * Per-shell trap table. Forked subshells call [TrapTable.inheritFrom]
     * on a fresh table — POSIX: only ignored signals carry through, handlers reset.
     */
    internal val trapTable: TrapTable = TrapTable()

    /**
     * Effective filesystem — the machine's. Held as a property for the
     * convenience of every call site that still says `fs.exists(...)` /
     * `fs.sink(...)` without going through `machine.fs`.
     */
    internal val fs: FileSystem get() = machine.fs

    init {
        // POSIX getopts requires OPTIND to default to 1 in every new
        // shell. Callers that supplied their own env (e.g. conformance
        // runners that pass `replaceEnv = true`) shouldn't have to
        // remember to seed it.
        if ("OPTIND" !in process.env) process.env["OPTIND"] = "1"
        // Bash conformance fixtures derive their `$0`-prefix from
        // `${BASH_VERSION%%.*}` (e.g. parser.tests's `bashname=bash5`).
        // Seed a 5.x identity so those substitutions resolve.
        if ("BASH_VERSION" !in process.env) process.env["BASH_VERSION"] = "5.3.0"
        // Only the root registers in the [SignalRouter]. Forks
        // intentionally do NOT register their own pids today: a fork's
        // `foregroundSignals` channel has no general drain path, and
        // intercepting `kill <fork-pid>` here would steal the signal
        // from the [JobControl.signal] path that the parent uses to
        // cancel coproc/procsub/pipeline-stage jobs (the
        // `kill $REFLECT_PID` in coproc.tests depends on that route).
        // The full "fork-side trap dispatch" story requires both a
        // drain path AND a way to selectively route fatal-class
        // signals through JobControl while letting USR1/USR2/etc. fire
        // the fork's own trap; that's deferred.
        //
        // `shellPid` is declared later in this file; for the root it
        // equals `process.pid` (the lazy initializer below). Reading
        // `process.pid` directly here sidesteps the declaration-order
        // dance.
        if (!isFork) signalRouter.register(process.pid) { sig -> deliverSignal(sig) }
    }

    /**
     * Filter [child.env] in-place to keep only names marked [VarAttr.Export]
     * in *this* interpreter's [varTable]. POSIX exec semantics: only
     * exported shell variables cross the fresh-process boundary.
     * [KashProcess.fork] (and therefore [KashMachine.spawn] / [forkSubshell])
     * copies env indiscriminately — correct for `(...)` subshells, which
     * inherit shell vars wholesale; wrong for TOOL dispatch and
     * `sh -c`-style sub-scripts. Call this on the child for those paths.
     *
     * Inline env (`FOO=bar cmd`) is added *after* this prune by the
     * dispatcher — POSIX treats inline assignments as implicitly exported
     * for the duration of the command, so they belong in the survivor set.
     */
    internal fun pruneToExportedEnv(child: KashProcess) {
        val exportedNames =
            varTable.visibleNames().filter { varTable.find(it)?.isExported == true }.toSet()
        child.env.keys.retainAll(exportedNames)
    }

    /**
     * Bash's function-export convention: a function `foo` shows up in the
     * environment as `BASH_FUNC_foo%%= { body; }`. On shell startup the
     * receiving bash parses each such entry into a function and removes
     * the env var so it doesn't propagate further. We do the same for
     * fork-and-exec child shells: scan, parse each value as
     * `<name> <body>`, cache the FunctionDef, and drop the env entry.
     *
     * Adversarial values (bash's CVE-2014-* fixtures) parse-fail; we drop
     * them silently. Successful parses give us a cached AST that costs no
     * re-parse on subsequent calls — the whole point of "AST-cached
     * function bodies".
     */
    private fun importBashFuncEnvVars(fork: Interpreter) {
        val keys =
            fork.process.env.keys
                .filter { it.startsWith("BASH_FUNC_") && it.endsWith("%%") }
        for (key in keys) {
            val name = key.removePrefix("BASH_FUNC_").removeSuffix("%%")
            val body = fork.process.env[key] ?: continue
            fork.process.env.remove(key)
            fork.varTable.unset(key)
            // Empty or non-function-shaped body: skip.
            if (!body.trimStart().startsWith("()")) continue
            val source = "$name $body"
            try {
                val ast =
                    com.accucodeai.kash.parser
                        .Parser(source)
                        .parseScript()
                // Strict acceptance: the value must parse to exactly one
                // statement holding exactly one function definition (no
                // trailing commands). Bash's CVE-2014-6271 fix rejects
                // ` { …body…; }; echo INJECTED` for the same reason —
                // otherwise an attacker controlling an env var injects
                // post-definition code.
                if (ast.statements.size != 1) continue
                val onlyPipelines = ast.statements.first().pipelines
                if (onlyPipelines.size != 1) continue
                val onlyCommands = onlyPipelines.first().commands
                if (onlyCommands.size != 1) continue
                val funcDef = onlyCommands.first() as? com.accucodeai.kash.ast.FunctionDef ?: continue
                fork.functions[name] = funcDef
                fork.exportedFunctions += name
            } catch (_: Throwable) {
                // Malformed BASH_FUNC body — drop silently.
            }
        }
    }

    /**
     * POSIX file-creation mask — delegates to [process]. Mutated by the
     * `umask` builtin; threaded into [CommandContext.umask] on every
     * command dispatch and applied to `>FILE` / `>>FILE` redirection sinks.
     * Default `0o022` matches every mainstream login shell.
     */
    internal var umask: Int
        get() = process.umask
        set(value) {
            process.umask = value
        }

    /**
     * Inbound foreground signals. The interpreter drains this between
     * statements to fire trap handlers. INT/TERM additionally cancel
     * [currentForegroundJob] so the running command stops at its next
     * suspension point.
     */
    internal val foregroundSignals: Channel<KashSignal> = Channel(Channel.UNLIMITED)

    /**
     * The currently-executing foreground statement's coroutine [Job]. Set
     * right before each statement launches and cleared right after it
     * completes. [deliverSignal] reads this to decide whether to cancel.
     * Volatile is not available in commonMain (and the JVM signal handler
     * thread *is* the only reader-from-another-thread); the underlying
     * field is a `var` and atomic-write of a reference on the JVM is
     * adequate for the read-then-cancel pattern we use.
     */
    internal var currentForegroundJob: Job? = null

    /**
     * Rolling counter for the loop cooperative-yield checkpoint (see
     * `loopCheckpoint` in InterpreterControlFlow). Shared across nested loops
     * in this interpreter — we only want a periodic breather overall, not
     * per-loop. Per-instance so forked subshells get their own cadence.
     */
    internal var cooperativeTick: Int = 0

    /**
     * The currently-executing foreground statement's [KashJob], when
     * monitor mode (`set -m`) is on and we registered the statement
     * with [jobControl] for stop/cont bookkeeping. Read by
     * [deliverSignal] so TSTP routes through `jobControl.signal`
     * instead of just queueing — that's the path that flips the
     * job's `stopped` flag and marks member processes STOPPED. Null
     * when monitor mode is off (the bash default for non-interactive
     * shells) — TSTP then has no effect beyond what trap handlers
     * configure.
     *
     * No cooperative preemption: an in-process tool that doesn't
     * check this job's stop bit runs to completion regardless. The
     * job is marked stopped briefly and observed by `jobs` IF the
     * tool happened to suspend, otherwise it's invisible. The
     * framework is here for future tool-level integration.
     */
    internal var currentForegroundKashJob: com.accucodeai.kash.jobs.KashJob? = null

    /** True while a foreground statement is mid-flight. Read by [deliverSignal] and by `kash-app`'s REPL signal handler. */
    public var isExecutingForeground: Boolean = false
        private set

    /**
     * Cancel every in-flight background job and tear down [sessionScope].
     * Idempotent. Call once when the owning [com.accucodeai.kash.Kash.Session]
     * is closed. Foreground execution is unaffected (it runs in the caller's
     * scope, not [sessionScope]).
     */
    public fun close() {
        // Forked subshells share their parent's scope/jobControl — tearing
        // them down here would kill the parent too. The top-level
        // Kash.Session.close is the only one that should hit cancelAll/cancel.
        if (isFork) return
        // Release the stop-gate FIRST so any continuation parked on it
        // gets the cancellation cause and unwinds via sessionScope's
        // own cancellation below. Reversing this order would orphan
        // those continuations — they'd sit on a dead gate while the
        // session is being torn down around them.
        stopGate.close()
        signalRouter.unregister(shellPid)
        jobControl.cancelAll()
        sessionScope.cancel()
    }

    /**
     * Deliver [sig] to the foreground shell. Safe to call from any thread —
     * specifically, from a JVM `sun.misc.Signal` handler thread in
     * `kash-app`. INT/TERM also cancel the currently-running statement so a
     * suspending tool (anything that calls `delay` or a suspending read)
     * stops promptly; the trap handler fires from the interpreter loop once
     * the cancellation propagates back.
     *
     * If no foreground command is running, the signal queues up and fires
     * before the next statement.
     */
    public fun deliverSignal(sig: KashSignal) {
        foregroundSignals.trySend(sig)
        // Match KashJob.signal: same four signals cancel the running coroutine.
        if (sig === SigInt || sig === SigTerm || sig === SigHup || sig === SigQuit) {
            currentForegroundJob?.cancel(CancellationException("signal $sig"))
        }
        // TSTP/STOP: pause the stop-gate AND signal the foreground
        // KashJob (which trySends to its stoppedSignal channel, racing
        // the deferred's completion in runStreaming's select). Gate
        // pause means every subsequent coroutine resume parks until a
        // resume — that's the bash-equivalent of the kernel stopping
        // the foreground process group at SIGTSTP.
        //
        // We pause the gate ONLY when there's a foreground KashJob to
        // signal (i.e. monitor mode is on). Without a target, pausing
        // the gate would strand any subsequent dispatch with no
        // resume path — bash's TSTP outside monitor mode is also a
        // no-op (foreground processes can stop via the kernel pgrp,
        // but our in-process model has no analog without monitor).
        if (sig === com.accucodeai.kash.api.signal.SigTstp ||
            sig === com.accucodeai.kash.api.signal.SigStop
        ) {
            val fgJob = currentForegroundKashJob
            if (fgJob != null) {
                jobControl.signal(fgJob, sig)
                stopGate.pause()
            }
        }
        // CONT: open the gate so any parked continuations dispatch.
        // The fg/bg intrinsics ALSO call stopGate.resume explicitly
        // after jobControl.signal(job, SigCont) — calling here too is
        // idempotent (resume on an open gate is a no-op) and covers
        // out-of-band SIGCONT delivery from outside the shell.
        if (sig === com.accucodeai.kash.api.signal.SigCont) {
            stopGate.resume()
        }
    }

    /**
     * Compute the job-id set that a hypothetical fork-of-this-shell
     * should hide from its own `jobs` / `%N` / `%+` / `%-` lookups —
     * the union of (a) what this interpreter already hides and (b)
     * every job currently live in the shared [jobControl] table.
     *
     * Centralized so the two subshell-entry sites — [forkSubshell]
     * (real fork) and [runInInPlaceSubshellScope] (cmdsub fast path)
     * — agree on the rule by construction, instead of hand-copying
     * the formula. Bash semantics: subshells see the parent's job
     * slots occupy ids, but cannot list or address those jobs.
     */
    internal fun nextSubshellJobMask(): Set<Int> = subshellJobMask + jobControl.jobIds()

    /**
     * Reusable outcome holder for [runInInPlaceSubshellScope]. Callers
     * pass one in (typically a per-call instance, kept off the heap-
     * pressure path) and read [innerAborted] / [innerAbortCode] after
     * the scope returns. Pre-cleared on entry to the scope so each
     * use is independent.
     *
     * Why a mutable holder vs. returning a value class: the in-place
     * scope is the hot path for `$()` (ifs-posix.tests runs ~6856
     * iterations × multiple cmdsubs each), and allocating a fresh
     * Pair/data-class per call OOMs the conformance harness. The
     * holder lets the caller stack-allocate-equivalent (one shared
     * instance per cmdsub frame) without paying a `Pair` on every
     * scope entry.
     */
    internal class InPlaceSubshellOutcome {
        var innerAborted: Boolean = false
        var innerAbortCode: Int = 0
    }

    /**
     * Run [block] inside an "in-place subshell" — kash's analogue of
     * zsh's `ESUB_FAKE`. State that POSIX considers boundary state
     * for a subshell (cwd, env, errexit, abort markers, the visible
     * job-mask) is snapshotted on entry and restored on exit on
     * *this* interpreter; no new [Interpreter] is constructed. The
     * companion path is [forkSubshell], which is the heavyweight
     * "real fork" used by `(...)`, pipeline stages, `&`, coproc, and
     * procsub.
     *
     * Why this exists: `ifs-posix.tests` runs 6856 iterations each
     * containing multiple `$(...)` cmdsubs; a real fork per cmdsub
     * exhausts the test harness. The in-place scope catches the
     * common cases (`$(cd /foo)`, `$(export X=Y)`, etc.) without
     * paying for a deep state copy. Functions, aliases, the trap
     * table, varTable scope chain, etc., are deliberately NOT
     * isolated — the cmdsub body sees the parent's full lookup
     * environment, and any mutation to *those* tables leaks back
     * (which differs from a real subshell). Two real-world places
     * exploit this leak: `BASH_REMATCH stays set` reading regex
     * captures inside `$(...)`, and `$(<file)` setting `$REPLY`.
     * Callers that need real isolation must use [forkSubshell].
     *
     * [block]'s return value flows back unchanged. Errors thrown by
     * [block] still get the restore (the save/restore is `try/finally`).
     * If [outcome] is non-null, its fields are written before the
     * restore so the caller can re-arm the parent's abort marker in
     * POSIX-mode propagation paths (default-mode cmdsub absorbs and
     * does not need to pass one).
     */
    internal suspend fun <R> runInInPlaceSubshellScope(
        outcome: InPlaceSubshellOutcome? = null,
        block: suspend () -> R,
    ): R {
        val savedCwd = process.cwd
        // Snapshot env for save/restore — but exclude dynamic specials
        // (RANDOM/SECONDS/LINENO/EPOCHSECONDS/...). Reading them advances
        // the RNG / fires the getter; comparing snapshot-vs-now ALWAYS
        // differs (every getter call returns a fresh value), and the
        // restore loop would write the snapshot value back through the
        // setter — reseeding RANDOM to whatever it happened to produce
        // on snapshot. The dynamic state lives intrinsically on the
        // shell and isn't subject to in-place scope semantics.
        val savedEnv =
            env.keys
                .filter { varTable.find(it)?.isDynamic != true }
                .associateWith { env[it] ?: "" }
        val savedPendingAbort = pendingAbort
        val savedPendingAbortCode = pendingAbortCode
        val savedErrexit = errexit
        val savedSubshellJobMask = subshellJobMask
        // POSIX subshell-environment rule: a command-substitution body
        // must not see the parent's job table. Splice the mask in —
        // `jobs` / `fg %%` / `wait %1` inside the scope resolve against
        // an empty visible-jobs set, exactly as they would in a real
        // forked subshell.
        subshellJobMask = nextSubshellJobMask()
        pendingAbort = false
        pendingAbortCode = 0
        if (outcome != null) {
            outcome.innerAborted = false
            outcome.innerAbortCode = 0
        }
        // Bash quirk: `$(...)` cmdsub does NOT inherit `set -e` from
        // the parent in default (non-POSIX) mode, even though POSIX
        // says it should. Patterns like `$(false; echo ok)` are
        // extremely common; forcing the inner script to abort would
        // surprise users. In POSIX mode (`set -o posix`), bash falls
        // back to the POSIX-mandated behavior: errexit IS inherited,
        // so `$(false; echo ok)` captures nothing and the failure
        // propagates to the outer shell.
        if (!posixModeRuntime) errexit = false
        // cmdsub subshell-environment rule: ERR / DEBUG / RETURN traps
        // are NOT inherited into a $(...) body unless `set -E` / `set -T`
        // is in effect. Push a non-traced frame so [inheritsTrapErr] /
        // [inheritsTrapDebugReturn] short-circuit to false inside the
        // scope. The `errtrace`/`functrace` flags still OR in at fire
        // time so an explicit `set -E` re-enables ERR firing.
        traceFramesActive.addLast(false)
        try {
            val r = block()
            if (outcome != null && pendingAbort) {
                outcome.innerAborted = true
                outcome.innerAbortCode = pendingAbortCode
            }
            return r
        } finally {
            process.cwd = savedCwd
            errexit = savedErrexit
            subshellJobMask = savedSubshellJobMask
            traceFramesActive.removeLast()
            // Restore env: drop keys added by the scope, restore values
            // of pre-existing keys that it mutated.
            val added = env.keys - savedEnv.keys
            for (k in added) env.remove(k)
            for ((k, v) in savedEnv) if (env[k] != v) env[k] = v
            // Default behavior: scope-local abort doesn't propagate.
            // POSIX-mode propagation is the caller's responsibility
            // (it reads the outcome and re-arms the markers).
            pendingAbort = savedPendingAbort
            pendingAbortCode = savedPendingAbortCode
        }
    }

    /**
     * Create a subshell-isolated clone — kash's "real subshell" entry.
     * The fork shares filesystem, registry, coroutine scope, and job
     * table with this interpreter, but deep-copies every piece of
     * mutable shell state (env, cwd, functions, positional params,
     * local scopes, readonly vars, abort flags, lastExit, $0). The
     * fork runs `(...)`-bodies, pipeline stages, background `&`,
     * coproc, and procsub bodies so mutations to those tables in
     * the child don't leak back to the parent — matching POSIX
     * subshell semantics.
     *
     * The fork is single-use: discard after the subshell finishes.
     *
     * **Companion path:** [runInInPlaceSubshellScope] is the kash-
     * specific lightweight subshell (zsh's ESUB_FAKE analogue) used
     * by `$(...)` for perf. They are the two "subshell entry"
     * routes in the codebase; their job-mask rule is shared via
     * [nextSubshellJobMask].
     *
     * **Maintainer note:** every piece of interpreter state that should
     * cross the subshell boundary lives in [copyForkStateInto] — adding a
     * new field means editing that one method, not this one. The
     * constructor wires up the shared resources (machine, registry, session,
     * router, child [KashProcess]); [copyForkStateInto] handles the
     * deep-copies and shallow assignments on top.
     */
    internal fun forkSubshell(): Interpreter {
        // process.fork copies umask/cwd/env (and, in later phases, the
        // rest of the per-process POSIX state).
        val fork =
            Interpreter(
                machine = machine,
                registry = registry,
                interactive = interactive,
                userDb = userDb,
                sandbox = sandbox,
                sharedSession =
                    SharedSession(
                        sessionScope,
                        jobControl,
                        // Share the router by reference — the root is
                        // already registered; fork inherits the same map.
                        signalRouter = signalRouter,
                        // Share the stop-gate by reference too; a fork's
                        // foreground statement under monitor mode would
                        // be pause-gated by the SAME gate the root sees.
                        stopGate = stopGate,
                        // Share the warm compiled-glob cache by reference —
                        // compiled globs are pure, so a subshell reusing the
                        // parent's avoids recompiling from cold on every fork.
                        globCache = globCache,
                    ),
                process = process.fork(),
                randomSource = randomSource,
                clock = clock,
            )
        copyForkStateInto(fork)
        return fork
    }

    /**
     * Deep-copy / inherit every piece of mutable interpreter state that
     * a subshell fork must carry. Called by [forkSubshell] right after
     * the fork is constructed. The split exists so the constructor
     * site stays focused on shared-resource wiring and this method is
     * the single authority on "what state crosses the subshell
     * boundary"; adding new interpreter state requires one new line
     * here and nothing else.
     *
     * The ordering below groups related fields and is meaningful in a
     * few spots: [registerSpecialVariables] must run *after*
     * [varTable.deepCopyInto] so hook-backed specials re-bind against
     * the fork's own copies; [trapTable.inheritFrom] must run last
     * (well, anywhere — but the EXIT-trap reset is conceptually a
     * fork-time boundary so we keep it at the bottom as a marker).
     */
    private fun copyForkStateInto(fork: Interpreter) {
        fork.functions.putAll(functions)
        fork.aliases.putAll(aliases)
        if (aliases.isNotEmpty()) fork.aliasVersion++
        fork.hashCache.putAll(hashCache)
        // Bash `(...)` subshells and pipeline stages see the parent's full
        // shell-variable state — scalars, indexed arrays, associative
        // arrays, attributes, and the function-local scope chain. One call
        // covers all of it; the array views on the fork already read
        // through `fork.varTable`.
        varTable.deepCopyInto(fork.varTable)
        // Hook-backed specials (RANDOM, BASHPID, LINENO, SECONDS, etc.)
        // close over fork-local state — the parent's closures would
        // return the parent's pid/lineno/etc. inside the subshell.
        // [Variable.copy] intentionally drops the hook references; we
        // re-register against the fork here so the subshell sees its
        // own dynamic values.
        fork.registerSpecialVariables()
        fork.positional = positional.toList()
        fork.dollarZero = dollarZero
        fork.lastExit = lastExit
        // Function-local scope chain is carried by `varTable.deepCopyInto`
        // above (it deep-copies both globals and the scope frames). The
        // function-name stack, caller-line stack, $LINENO tracker, etc.
        // ship across via the [callStack] cluster — BASH_LINENO synthesis
        // and FUNCNAME need the parent's frames inside `(...)`.
        fork.callStack.copyFrom(callStack)
        // Directory stack and disabled-intrinsics set inherit into subshells
        // (bash: `pushd` state is visible inside `(...)`; `enable -n test`
        // stays disabled). Login-shell status does NOT inherit — a subshell
        // is never itself a login shell.
        fork.dirStack.addAll(dirStack)
        fork.disabledIntrinsics.addAll(disabledIntrinsics)
        // Snapshot the parent's visible job ids — the fork shares the same
        // [JobControl] instance, but its own jobspec lookups must NOT see
        // these. The fork inherits the parent's existing mask too, so nested
        // subshells still hide ancestors' jobs from each level. Rule is
        // shared with the cmdsub in-place path via [nextSubshellJobMask].
        fork.subshellJobMask = nextSubshellJobMask()
        fork.pendingAbort = pendingAbort
        fork.pendingAbortCode = pendingAbortCode
        // Bash extends POSIX's errexit rules to subshells: a `(...)`
        // launched from an `&&`/`||` non-last leg, an if/while/until
        // condition, etc., inherits the parent's errexit suppression
        // depth — so `set -e` does not abort inside it. The whole
        // option cluster (including errexit + errexitSuppressed) ships
        // across via one call.
        fork.options.copyFrom(options)
        fork.shellDepth = shellDepth
        // POSIX: `$$` is the shell's pid, sticky across subshell forks.
        // Inherit the parent's shellPid so `(echo $$)` matches `echo $$`.
        // `$BASHPID` reads fork.process.pid directly, which IS distinct.
        // `$PPID` is similarly sticky — it tracks the SHELL's parent,
        // not the subshell's parent.
        fork.shellPid = shellPid
        fork.shellPpid = shellPpid
        // BASH_SUBSHELL reports the current subshell depth (0 in the
        // top-level shell, 1 inside `(...)`, 2 inside `( (...) )`, etc.).
        // Used by xtrace to repeat PS4's leading `+` per depth, and by
        // user scripts that condition on `[ $BASH_SUBSHELL -gt 0 ]`.
        fork.bashSubshellDepth = bashSubshellDepth + 1
        // Per POSIX: non-ignored traps reset to default in subshells; ignored
        // signals stay ignored. EXIT is therefore NOT inherited — otherwise
        // `(...)` would fire the parent's EXIT trap on group close. Bash
        // extensions widen the carry: `set -E` (errtrace) also inherits ERR,
        // and `set -T` (functrace) also inherits DEBUG and RETURN. We pass
        // the parent's option flags — they were just copied into `fork.options`
        // above, so reading either source is equivalent.
        fork.trapTable.inheritFrom(trapTable, errtrace = errtrace, functrace = functrace)
        // Subshells inherit the host terminal handle: `(nano …)` and pipeline
        // stages running in subshells still need raw-mode access. Redirection
        // and the per-fd isTty gating in installStdio decide whether the
        // child actually sees it on fd 0/1/2. (The other [io] fields stay at
        // the fork's defaults — sinks come from the dispatch site.)
        fork.hostTerminal = hostTerminal
    }

    internal var lastExit = 0

    /**
     * True while a trap-handler script is executing. Suppresses re-entry of
     * the DEBUG/RETURN/ERR traps from inside the handler itself — bash does
     * the same, otherwise `trap '…' DEBUG` would fire on every command of
     * its own body and loop.
     */
    internal var inTrapHandler: Boolean = false

    /**
     * $? as it was at the moment a trap fired — POSIX interp 1602: a bare
     * `return` inside a trap action exits the action with the exit status
     * of the command immediately *preceding* the trap, not whatever the
     * trap body last ran. Set on trap entry by [runTrapHandler], cleared
     * on exit. Null outside a trap.
     */
    internal var preTrapExit: Int? = null

    /**
     * True only for statements running *directly* in a trap action's script
     * (i.e. the trap-action text as parsed). Pinned to the outer command's
     * line so `$LINENO` in `trap 'echo $LINENO' DEBUG` reports the line of
     * the command being traced. Cleared on function-call entry so that when
     * the trap action calls a function, that function's body sees its own
     * source line in `$LINENO` (per bash 5.x). Distinct from
     * [inTrapHandler], which stays true for the whole call chain to gate
     * trap re-entry.
     */
    internal var lineFrozenForTrap: Boolean = false

    /**
     * Function-call stack and trap-bookkeeping cluster — names + caller
     * lines, `$LINENO` tracker + offset, loop depth, and the per-frame
     * trap-inheritance flags. See [CallStack].
     */
    internal val callStack: CallStack = CallStack()

    internal val tracedFunctions: MutableSet<String> get() = callStack.tracedFunctions
    internal val traceFramesActive: ArrayDeque<Boolean> get() = callStack.traceFramesActive
    internal var currentLine: Int
        get() = callStack.currentLine
        set(v) {
            callStack.currentLine = v
        }
    internal var loopDepth: Int
        get() = callStack.loopDepth
        set(v) {
            callStack.loopDepth = v
        }
    internal var linenoOffset: Int
        get() = callStack.linenoOffset
        set(v) {
            callStack.linenoOffset = v
        }
    internal val functionNameStack: ArrayDeque<String> get() = callStack.functionNameStack
    internal val callerLineStack: ArrayDeque<Int> get() = callStack.callerLineStack

    // Nesting depth of `.`/`source` invocations. `return` is only valid inside
    // a function call or a sourced script body — bash errors otherwise.
    internal var sourcingDepth: Int = 0

    /**
     * Set by [evalArithRaw] when the inner [ArithEval] throws. Callers that
     * care about distinguishing "evaluated to 0" from "failed and we fed
     * back 0 as the conventional default" reset this to false before the
     * call and inspect it after — most notably `for ((init; cond; update))`,
     * which aborts the whole loop on any arith error (otherwise the failing
     * expression would be re-evaluated every iteration until the safety cap).
     */
    internal var arithLastError: Boolean = false

    /**
     * Sticky flag flipped by [evalArithmetic] when `$((expr))` substitution
     * fails. The simple-command driver clears it before expansion, runs
     * argv expansion, and (after expansion completes) skips command
     * execution if it's set — matching bash's "failed arithmetic
     * substitution aborts the simple command" behavior. Without this,
     * `echo $((bogus))` would emit the failure diagnostic AND still run
     * `echo ""` (printing a stray blank line) — the very mismatch
     * `bash-tests-normalize.txt` had to mop up with regexes.
     */
    internal var arithSubstFailedInWordExpansion: Boolean = false

    /**
     * Shell option flags (`set -e` / `set -u` / `set -o ...`) held in a
     * cohesive cluster so [forkSubshell] can copy the full set with one
     * [ShellOptions.copyFrom] call. Each individual option is exposed
     * as a top-level forwarding property below so call sites stay terse.
     */
    internal val options: ShellOptions = ShellOptions()

    internal var errexit: Boolean
        get() = options.errexit
        set(v) {
            options.errexit = v
        }
    internal var errexitSuppressed: Int
        get() = options.errexitSuppressed
        set(v) {
            options.errexitSuppressed = v
        }
    internal var nounset: Boolean
        get() = options.nounset
        set(v) {
            options.nounset = v
        }
    internal var pipefail: Boolean
        get() = options.pipefail
        set(v) {
            options.pipefail = v
        }

    /** `set -m` / `set -o monitor` — gates fg/bg "no job control" diagnostics. */
    internal var monitor: Boolean
        get() = options.monitor
        set(v) {
            options.monitor = v
        }

    /**
     * One-shot override for the next `publishPipeStatus(...)` call.
     * Set by `wait` after reaping a backgrounded pipeline so the post-
     * wait `${PIPESTATUS[@]}` reads the reaped pipeline's per-stage
     * exits — not the trivial 1-element array the `wait` command's own
     * pipeline would otherwise publish. Cleared on consumption.
     */
    internal var pipeStatusOverride: IntArray? = null
    internal var noclobber: Boolean
        get() = options.noclobber
        set(v) {
            options.noclobber = v
        }

    /** Bash's `set -o posix` runtime flag. Public so the per-statement
     *  parse loop in [com.accucodeai.kash.Kash] (different module) can
     *  pass it to [com.accucodeai.kash.parser.StatementStream] as a live
     *  provider. */
    public var posixModeRuntime: Boolean
        get() = options.posixModeRuntime
        set(v) {
            options.posixModeRuntime = v
        }
    internal var restricted: Boolean
        get() = options.restricted
        set(v) {
            options.restricted = v
        }
    internal var extdebugEnabled: Boolean
        get() = options.extdebugEnabled
        set(v) {
            options.extdebugEnabled = v
        }
    internal var xtrace: Boolean
        get() = options.xtrace
        set(v) {
            options.xtrace = v
        }

    /**
     * `set -E` / `set -o errtrace` — when true, the ERR trap is
     * inherited by shell functions, command substitutions, and
     * subshells (the latter via [com.accucodeai.kash.traps.TrapTable.inheritFrom]).
     */
    internal var errtrace: Boolean
        get() = options.errtrace
        set(v) {
            options.errtrace = v
        }

    /**
     * `set -T` / `set -o functrace` — when true, DEBUG and RETURN
     * traps are inherited by shell functions, command substitutions,
     * and subshells.
     */
    internal var functrace: Boolean
        get() = options.functrace
        set(v) {
            options.functrace = v
        }

    /** `set -a` / `set -o allexport` — auto-export new var assignments. POSIX §2.14.1. */
    internal var allexport: Boolean
        get() = options.allexport
        set(v) {
            options.allexport = v
        }

    /** `set -f` / `set -o noglob` — disable pathname expansion. POSIX §2.14.1. */
    internal var noglob: Boolean
        get() = options.noglob
        set(v) {
            options.noglob = v
        }

    /** `set -v` / `set -o verbose` — echo each input line to stderr. POSIX §2.14.1. */
    internal var verbose: Boolean
        get() = options.verbose
        set(v) {
            options.verbose = v
        }

    /** `set -n` / `set -o noexec` — parse but don't execute. POSIX §2.14.1. */
    internal var noexec: Boolean
        get() = options.noexec
        set(v) {
            options.noexec = v
        }

    /**
     * Exit code of the most recently fired DEBUG trap handler. Read by
     * [runSimple] right after `fireDebugTrap()` to decide whether to skip
     * the upcoming command (extdebug + return-2 semantics).
     */
    internal var lastDebugTrapExit: Int = 0

    /**
     * Indexed-array storage — a [MutableMap] view onto [varTable]. Every
     * `indexedArrays[name]` / `indexedArrays.getOrPut(name)` /
     * `indexedArrays.remove(name)` op routes through the unified table; the
     * field itself owns no storage. The "view-MutableMap" shape preserves
     * the legacy call-site syntax so the surrounding code can keep using
     * map idioms without per-callsite rewrites.
     */
    internal val indexedArrays: MutableMap<String, MutableMap<Int, String>> = IndexedArraysView(varTable)

    /** Assoc counterpart of [indexedArrays] — view over [varTable]. */
    internal val assocArrays: MutableMap<String, LinkedHashMap<String, String>> = AssocArraysView(varTable)

    /** [ArithEval.ArrayStore] view over [varTable] — see [InterpreterArithStore]. */
    internal val arithStore: ArithEval.ArrayStore = InterpreterArithStore(this)

    /**
     * fd-number → coproc-array-name owner map. Populated by [runCoproc] when
     * it installs the parent-side pipe fds. Consulted by `applyRedirections`
     * when a `closeAfter` move-fd releases an entry: if the released fd
     * belonged to a coproc, that coproc's `${NAME}[@]` is reset to `-1 -1`,
     * matching bash's `echo ${COPROC[@]}` → `-1 -1` after `exec
     * 4<&${COPROC[0]}-`.
     */
    internal val coprocFdOwner: MutableMap<Int, String> = mutableMapOf()

    /**
     * Parent-side fds backing live `<(...)` / `>(...)` process substitutions.
     *
     * Populated by [evalProcessSubstitution] right after installing the fd
     * into [process.fdTable]. [executeWithStdio] snapshots this set on
     * command entry and closes anything added during the command at exit
     * — so procsubs scope to the enclosing command and don't leak across
     * iterations of a loop.
     *
     * Mechanics: when a consumer like `cat /dev/fd/N` opens the magic
     * symlink, [FdPathResolver.openFromFdTable] dups out a fresh OFD
     * pointing at the same pipe — the consumer holds its own reference,
     * so closing the parent-side entry at command-end doesn't tear the
     * pipe down under it. The OFD's refcount keeps the underlying stream
     * alive until the consumer closes its own fd.
     */
    internal val procsubFds: MutableSet<Int> = mutableSetOf()

    init {
        // POSIX: variables inherited from environ at shell startup are
        // themselves exported in the new shell — that's how `PATH`/`HOME`
        // etc. carry through `sh -c '$(sh -c "echo \$PATH")'` style chains.
        // Seed every name [process.env] arrived with as a Scalar+Export
        // [Variable]. [forkSubshell] overrides this seed by deep-copying
        // the parent's full varTable, so `(...)` subshells inherit the
        // parent's exact export state (not "everything is exported").
        for (name in process.env.keys) {
            varTable.findOrCreate(name).let { v ->
                v.value = VariableValue.Scalar(process.env[name] ?: "")
                v.attrs += VarAttr.Export
            }
        }
        registerSpecialVariables()
        // Bash seeds two empty associative arrays at startup: `BASH_ALIASES`
        // (alias name → expansion) and `BASH_CMDS` (hashed command name →
        // path). They appear in `declare -A` (no args) output even when
        // empty. Seed AFTER the env-import loop so a stray inherited
        // `BASH_ALIASES`/`BASH_CMDS` env var (scalar) gets converted to
        // the assoc-array shape instead of overwriting our seed.
        // Forks inherit via the shared `varTable`; the fork-and-exec path
        // (a fresh shell) re-seeds explicitly after [VarTable.clearShellInternal]
        // — see [seedBashSpecialAssocs].
        if (!isFork) {
            seedBashSpecialAssocs()
        }
    }

    /**
     * Seed the empty `BASH_ALIASES` / `BASH_CMDS` associative arrays bash
     * exposes in every shell. Idempotent via the `isAssoc` guard so it can
     * be called both at construction (top-level shells) and after
     * [VarTable.clearShellInternal] wipes the table at a fork-and-exec
     * boundary (a fresh `sh -c` / `${THIS_SH} script` shell).
     */
    internal fun seedBashSpecialAssocs() {
        if (varTable.find("BASH_ALIASES")?.isAssoc != true) {
            assocArrays["BASH_ALIASES"] = LinkedHashMap()
        }
        if (varTable.find("BASH_CMDS")?.isAssoc != true) {
            assocArrays["BASH_CMDS"] = LinkedHashMap()
        }
    }

    internal class BreakException(
        val count: Int,
    ) : FastControlFlowThrowable()

    internal class ContinueException(
        val count: Int,
    ) : FastControlFlowThrowable()

    internal class ReturnException(
        val code: Int,
    ) : FastControlFlowThrowable()

    internal class ScriptAbortException(
        val code: Int,
    ) : FastControlFlowThrowable()

    internal var pendingAbort: Boolean = false
    internal var pendingAbortCode: Int = 0

    // The shell-option flags (errexit/nounset/pipefail/noclobber/
    // posixModeRuntime/restricted/extdebugEnabled/errexitSuppressed) are
    // declared at the top of the class as forwarding properties over
    // [options: ShellOptions] — see ShellOptions.kt. The cluster lets
    // forkSubshell copy the full option state with one call instead of
    // listing each field by hand.

    /**
     * Flag toggled by [evalCommandSubstitution] to signal that the current
     * word-expansion involved at least one `$(...)`. Bare assignments
     * (`var=$(cmd)`) consult this to decide whether `$?` should reflect
     * the cmdsub's exit (bash extension) or stay zero (POSIX).
     */
    internal var cmdsubExitSeen: Boolean = false

    /**
     * Structured arg-assignments for the currently-executing simple command.
     * Populated by [runSimple] from [SimpleCommand.argAssignments] before
     * dispatch and cleared after. Only the assignment-aware builtins
     * (declare/typeset/local/readonly/export) consult it; ordinary commands
     * never see structured assignments past their command name.
     */
    internal var currentArgAssignments: List<InlineAssignment> = emptyList()

    /**
     * Indices (into the dispatched arg list) of args that were unquoted
     * array-reference words for the currently-executing simple command — a
     * per-word property computed before expansion. Populated by [runSimple]
     * for [ARRAYREF_ARG_BUILTINS] before dispatch, cleared after. read/wait
     * read it directly; printf gets it via `CommandContext`.
     */
    internal var currentArrayRefArgs: Set<Int> = emptySet()

    /**
     * Nesting depth for [ShellRunner] invocations. Bumped in [runShellScript]'s
     * try, decremented in finally. Single-threaded by interpreter invariant,
     * so a plain Int is fine.
     */
    internal var shellDepth: Int = 0
    internal val shellDepthLimit: Int = 100

    /**
     * `$$` — the *shell process's* pid, frozen at startup. Bash semantics:
     * `(echo $$)` inside a subshell prints the PARENT shell's pid, NOT
     * the subshell's — `$$` is sticky across forks. Initialized lazily
     * from [process].pid the first time it's read (the top-level
     * Interpreter is the shell); subshell forks inherit via
     * [forkSubshell] so all descendants see the same value.
     *
     * Subshell-current pid is exposed via `$BASHPID`, which reads
     * [process].pid directly and DOES change in subshells.
     */
    internal var shellPid: Int = process.pid

    /**
     * `$BASH_SUBSHELL` — subshell nesting depth. 0 at top level,
     * incremented on each [forkSubshell] (i.e. for `(...)`, `$(...)`,
     * command substitution, pipeline stages with fork). xtrace uses it
     * to repeat PS4's leading `+` once per level (`++ cmd` inside a
     * single-depth subshell, `+++ cmd` two-deep, etc.).
     */
    internal var bashSubshellDepth: Int = 0

    /**
     * `$PPID` — the shell process's *parent* pid, frozen at startup.
     * Bash semantics: `(echo $PPID)` inside a subshell prints the
     * grandparent's pid (the SHELL's parent), NOT the subshell's
     * parent. Sticky across forks just like [shellPid].
     *
     * 0 when the shell was launched without a recorded parent (init).
     */
    internal var shellPpid: Int = process.ppid ?: 0

    /**
     * Capture the current shell state into a slot. Only safe to call between
     * top-level [run] invocations — i.e. when no pipelines are in flight and
     * the transient [outSink]/[errSink] buffers belong to the caller, not to
     * us.
     *
     * The filesystem is **not** captured here — it's owned by the machine and
     * snapshotted once at that level (see
     * [com.accucodeai.kash.snapshot.MachineSnapshot]). A slot is pure shell
     * state.
     */
    public fun snapshot(): InterpreterSnapshot =
        InterpreterSnapshot(
            env = env.toMap(),
            cwd = cwd,
            functions = functions.toMap(),
            positional = positional.toList(),
            dollarZero = dollarZero,
            lastExit = lastExit,
            // Function-local scopes are part of the unified [varTable] now.
            // The snapshot field stays for binary compatibility but is
            // always empty — a full varTable serialization is a follow-up.
            localScopes = emptyList(),
            readonlyVars = varTable.visibleNames().filter { varTable.find(it)?.isReadonly == true }.toSet(),
            pendingAbort = pendingAbort,
            pendingAbortCode = pendingAbortCode,
            aliases = aliases.toMap(),
        )

    /**
     * Restore previously captured shell state into this interpreter. The
     * filesystem is NOT touched — a slot carries no FS; the machine restores
     * the FS once at its own level before this runs (see
     * [com.accucodeai.kash.snapshot.restoreFsAndSlots]).
     */
    public fun restore(s: InterpreterSnapshot) {
        env.clear()
        env.putAll(s.env)
        cwd = s.cwd
        functions.clear()
        functions.putAll(s.functions)
        positional = s.positional.toList()
        dollarZero = s.dollarZero
        lastExit = s.lastExit
        // Legacy snapshots may have populated `localScopes` — drop them.
        // Once a varTable-aware snapshot lands, restore will rebuild the
        // scope chain from there.
        for (n in s.readonlyVars) {
            varTable.findOrCreate(n).attrs += VarAttr.Readonly
        }
        pendingAbort = s.pendingAbort
        pendingAbortCode = s.pendingAbortCode
        aliases.clear()
        aliases.putAll(s.aliases)
        aliasVersion++
    }

    public suspend fun run(
        script: Script,
        initialStdin: ByteArray,
        mergeStderr: Boolean = false,
    ): Triple<String, String, Int> {
        val out = Buffer()
        val err = if (mergeStderr) out else Buffer()
        // Preserve the historical semantics: bash treats the prompt's stdin
        // as tty-shaped on the first statement of an interactive session iff
        // no script-level stdin was supplied. Non-interactive sessions never
        // have a tty.
        val stdinIsTty = interactive && initialStdin.isEmpty()
        val code =
            runStreaming(
                source = ScriptStatementSource(script.statements),
                initialStdin = bufferOf(initialStdin),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
                stdinIsTty = stdinIsTty,
                terminalControl = null,
            )
        return Triple(out.readString(), err.readString(), code)
    }

    /**
     * Lexer-collected parse-time diagnostics that should be emitted to
     * stderr interleaved with execution. The interpreter flushes warnings
     * whose `line` falls in `[statement.line, nextStatement.line)` *before*
     * running each statement so they appear in roughly the same position
     * bash emits them — bash interleaves parse and execute, so a warning
     * captured at parse time for statement K shows up just before K runs.
     */
    public var pendingLexWarnings: MutableList<com.accucodeai.kash.parser.ParseWarning> = mutableListOf()

    private suspend fun emitLexWarning(w: com.accucodeai.kash.parser.ParseWarning) {
        if (w.suppressLinePrefix) {
            errSink.writeUtf8("$dollarZero: ${w.message}\n")
        } else {
            errSink.writeUtf8("$dollarZero: line ${w.line}: ${w.message}\n")
        }
    }

    private suspend fun flushLexWarningsBefore(line: Int) {
        if (pendingLexWarnings.isEmpty()) return
        val it = pendingLexWarnings.iterator()
        while (it.hasNext()) {
            val w = it.next()
            if (w.line < line) {
                emitLexWarning(w)
                it.remove()
            }
        }
    }

    private suspend fun flushAllLexWarnings() {
        for (w in pendingLexWarnings) emitLexWarning(w)
        pendingLexWarnings.clear()
    }

    /** Stream-driven entry point: each [StatementSource.next] is invoked just
     *  before the corresponding statement executes, so for a
     *  [StreamStatementSource] the re-parse picks up the live
     *  [posixModeRuntime] / alias state — the bash read-parse-execute
     *  loop equivalent. */
    public suspend fun runStreaming(
        source: StatementSource,
        initialStdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
        stdinIsTty: Boolean,
        terminalControl: TerminalControl? = null,
    ): Int {
        outSink = stdout
        errSink = stderr
        hostTerminal = terminalControl
        // Script-level out/err are the host terminal if the session is
        // interactive. Subshells re-enter run through forkSubshell, but
        // that path reconfigures these fields appropriately (forks bind to
        // capture buffers — never tty).
        outSinkIsTty = interactive
        errSinkIsTty = interactive
        // Stamp fd 0/1/2 into the shell process's table so script-level
        // `exec N>&2` / `exec 0<&5` can dup against them — without this,
        // the script's first reference to fd 2 (e.g. `exec 5>&2` for the
        // jobs.tests save-and-silence dance) hits an empty fdTable slot
        // and emits "Bad file descriptor". Skip when the caller already
        // populated fd 0/1/2 (DevNullTest's `s.process.installStdio(...)`
        // before `execStreaming`) so we don't replace the caller's OFD
        // with a different one and break the "fd preserved across script
        // execution" invariant that test pins.
        if (process.fdTable[1] == null && process.fdTable[2] == null) {
            installStdioFds(
                process,
                initialStdin,
                stdout,
                stderr,
                stdinIsTty = stdinIsTty,
                stdoutIsTty = interactive,
                stderrIsTty = interactive,
                terminalControl = terminalControl,
            )
        }
        var stdin: SuspendSource = initialStdin
        // Caller tells us whether the supplied stdin is the host terminal —
        // drives `[ -t 0 ]` and the python3/nano REPL gating. Only ever true
        // for the very first statement; subsequent statements see emptySource.
        currentStdinIsTty = stdinIsTty
        // Seed lex warnings from the source's initial scan. The for-loop
        // below calls `flushLexWarningsBefore(nextLine)` to interleave them
        // with each statement, matching bash's "warning followed by output."
        // Streaming sources (StatementStream-backed) capture warnings on
        // their first fetch; eager sources (ScriptStatementSource) return
        // empty here — callers that want warnings on the eager path push
        // them into [pendingLexWarnings] directly.
        pendingLexWarnings.addAll(source.initialWarnings())
        try {
            // Drain any signals that arrived between scripts (rare — usually
            // means a Ctrl-C landed before the first statement runs).
            drainForegroundSignals()
            // supervisorScope: cancelling one statement's child Deferred
            // does NOT fail the scope, so the for-loop can keep iterating
            // through the script after an INT-cancelled statement (matching
            // bash: INT cancels the current foreground command, runs the
            // trap, and proceeds to the next line).
            //
            // We use `async`/`await` rather than `launch`/`join` because
            // `await()` re-throws the deferred's exception in this
            // coroutine — so the existing outer `catch` arms for
            // BreakException / ContinueException / ReturnException /
            // ScriptAbortException keep working. Only CancellationException
            // (from `deliverSignal(SigInt/SigTerm)`) is caught here, since
            // that is the case where the for-loop must continue rather
            // than abort.
            supervisorScope {
                while (true) {
                    val stmt = source.next() ?: break
                    // Bash recovery: when an earlier statement hit a syntax
                    // error, the lex-stream skipped past it and kept going.
                    // Emit the diagnostic now, just before the next
                    // statement's output, so the recovered statements still
                    // interleave with their parse errors in source order.
                    for (e in source.drainRecoveredErrors()) {
                        emitShellParseError(
                            errSink,
                            currentOuterScript,
                            dollarZero,
                            currentOuterIsCLine,
                            e,
                        )
                        lastExit = 1
                    }
                    // Tick the top-level statement counter — but only
                    // when *this* interpreter owns its `jobControl`
                    // (top-level shell, or `runShellScript`-style exec
                    // fork that replaced the inherited jobControl).
                    // In-place `(...)` / `$(...)` subshells share the
                    // parent's jobControl and must NOT bump, or the
                    // parent's "is this job from the current statement?"
                    // accounting drifts every time a substitution runs.
                    if (!isFork || jobControl !== sharedSession?.jobControl) {
                        jobControl.topLevelStatementCounter++
                    }
                    // Bash interleaves parse-time warnings with execution.
                    // Flush warnings whose line is at or within this
                    // statement's source span (start line to last
                    // consumed line — for a heredoc that's the closing
                    // delimiter line, EOF or otherwise). Warnings on
                    // later lines stay pending until a future statement
                    // reaches them.
                    val consumedToLine = source.lastConsumedLine().coerceAtLeast(stmt.line)
                    flushLexWarningsBefore(consumedToLine + 1)
                    // POSIX §2.14.1 `set -v` (verbose): echo each input
                    // line to stderr immediately before lex/exec. We use
                    // the pretty-printed AST as a kash-side approximation
                    // of bash's "raw shell input line" — semantically
                    // equivalent shell, even if formatting differs.
                    if (verbose) {
                        errSink.writeUtf8(prettyPrintStatement(stmt))
                        errSink.writeUtf8("\n")
                    }
                    // POSIX §2.14.1 `set -n` (noexec): parse but don't
                    // execute. Skip the dispatch entirely; preserve $?
                    // (lastExit) at its prior value.
                    if (noexec) continue
                    val capturedStdin = stdin
                    isExecutingForeground = true
                    // Synthetic tty-pgrp ownership: a foreground statement
                    // running directly on the shell's own process owns
                    // the foreground slot for its lifetime, expressed as
                    // the shell's own pid (the foreground "pgrp leader"
                    // is the shell itself when no pipeline-fork happened
                    // yet). Backgrounded pipelines never touch this;
                    // they get their own pgid in `dispatchBackground`
                    // and are not the foreground.
                    val savedForegroundPgrp = machine.foregroundPgrp
                    machine.foregroundPgrp = process.pid
                    // Monitor mode: build a foreground KashJob WITHOUT
                    // inserting it into the job table. While running it
                    // has no `%N` slot (bash semantics — `false &; fg %1`
                    // resolves %1 to the backgrounded false, not the
                    // in-flight foreground statement). On TSTP we promote
                    // it via `JobControl.promoteStoppedForegroundJob`,
                    // which allocates a real id, inserts into byId, and
                    // bumps %+/%- so `fg %N` / `fg` (default %+) find
                    // it. On normal completion it never enters byId.
                    val fgKashJob =
                        if (monitor && !stmt.background) {
                            // `&`-backgrounded statements run-and-detach: the
                            // shell never blocks on them, so there's nothing
                            // for Ctrl-Z to suspend. Skip the fgKashJob path
                            // — the stopGateDispatcher + select dance adds
                            // dispatch hops that, under the test scheduler,
                            // can let virtual time leak forward past
                            // pending background `delay()`s and corrupt
                            // `wait -n` snapshot ordering.
                            jobControl.makeForegroundJob(
                                pretty = prettyPrintStatement(stmt),
                                memberPid = process.pid,
                                startedUnderMonitor = true,
                            )
                        } else {
                            null
                        }
                    currentForegroundKashJob = fgKashJob
                    // Monitor mode → wrap the foreground async with the
                    // stop-gate dispatcher so every suspension parks on
                    // a paused gate. Non-monitor → plain async on the
                    // inherited context (no extra dispatch hop, no
                    // change in scheduler quantum ordering vs pre-gate
                    // behavior). The async body writes job.done on
                    // completion so a Ctrl-Z + fg cycle works under
                    // monitor mode.
                    val deferred =
                        if (fgKashJob != null) {
                            // Launch on [sessionScope] (NOT the enclosing
                            // supervisorScope) so a Ctrl-Z'd statement can
                            // park indefinitely on the stop-gate without
                            // pinning runStreaming. Without this, the
                            // supervisorScope refuses to complete until
                            // every child finishes — but a stopped
                            // deferred is only resumed by a future
                            // fg/bg/CONT, so the REPL never gets to draw
                            // the next prompt (dead-prompt bug after ^Z).
                            // The deferred's invariant — completing
                            // `fgKashJob.done` in the body's exit path —
                            // still holds; we just decouple its lifecycle
                            // from this statement's loop iteration.
                            sessionScope.async(stopGateDispatcher) {
                                val exit = runStatement(stmt, capturedStdin)
                                if (!fgKashJob.done.isCompleted) {
                                    fgKashJob.done.complete(exit)
                                }
                                exit
                            }
                        } else {
                            async {
                                runStatement(stmt, capturedStdin)
                            }
                        }
                    currentForegroundJob = deferred
                    fgKashJob?.driverJob = deferred
                    // Race the deferred's normal completion against
                    // the foreground KashJob's stop signal. If we have
                    // no KashJob (non-monitor mode), there's nothing
                    // to race; just await.
                    var cancelled = false
                    var stoppedMid = false
                    if (fgKashJob == null) {
                        try {
                            lastExit = deferred.await()
                        } catch (_: CancellationException) {
                            cancelled = true
                        } catch (e: com.accucodeai.kash.parser.ParseException) {
                            errSink.writeUtf8(
                                "${shellDiagPrefix()}${e.message ?: "bad substitution"}\n",
                            )
                            lastExit = 1
                        }
                    } else {
                        try {
                            kotlinx.coroutines.selects.select<Unit> {
                                deferred.onAwait { exit ->
                                    lastExit = exit
                                }
                                fgKashJob.stoppedSignal.onReceive {
                                    stoppedMid = true
                                }
                            }
                        } catch (_: CancellationException) {
                            cancelled = true
                        } catch (e: com.accucodeai.kash.parser.ParseException) {
                            errSink.writeUtf8(
                                "${shellDiagPrefix()}${e.message ?: "bad substitution"}\n",
                            )
                            lastExit = 1
                        }
                    }
                    currentForegroundJob = null
                    if (fgKashJob != null) {
                        if (stoppedMid) {
                            // Promote into byId — allocate a real id,
                            // insert, bump %+/%-. Now `fg %N` and
                            // `fg`/`bg` (default %+) resolve to this
                            // job. Bash sync-point notification on
                            // stderr uses the post-promotion id.
                            val promotedId = jobControl.promoteStoppedForegroundJob(fgKashJob)
                            errSink.writeUtf8(
                                "[$promotedId]+  Stopped                    ${fgKashJob.command}\n",
                            )
                            // $? after a Ctrl-Z is 128 + SIGTSTP (= 148).
                            // Bash sets it the same. The user can read
                            // `$?` between Ctrl-Z and the next command.
                            lastExit = 148
                            // Reopen the shell-wide stop-gate so the NEXT
                            // statement's coroutine body can dispatch. The
                            // gate is per-shell, not per-statement: leaving
                            // it paused stranded every subsequent
                            // `async(stopGateDispatcher) { … }` on
                            // `gate.invokeOnCompletion`, so the REPL would
                            // redraw the prompt but new commands wouldn't
                            // actually run (live-shell symptom: `^Z; ^Z;
                            // agent` → `agent` does nothing). Trade-off:
                            // the stopped job's parked-on-the-gate
                            // continuations also resume — for a CPU/IO-
                            // less body like `sleep N` this means the job
                            // logically completes in the background
                            // shortly after `^Z` rather than parking until
                            // `fg`. Acceptable until a per-job stop-gate
                            // replaces this shell-wide one; the
                            // alternative (frozen prompt) is strictly
                            // worse user experience.
                            stopGate.resume()
                        } else {
                            // Normal completion: the deferred's async
                            // body already called `done.complete(exit)`.
                            // The job was never in byId so nothing to
                            // reap; it goes out of scope when we clear
                            // `currentForegroundKashJob` below. Just
                            // make sure `done` is completed for the
                            // cancelled-path safety net.
                            if (!fgKashJob.done.isCompleted) {
                                fgKashJob.done.complete(if (cancelled) 130 else lastExit)
                            }
                        }
                    }
                    currentForegroundKashJob = null
                    isExecutingForeground = false
                    machine.foregroundPgrp = savedForegroundPgrp
                    if (cancelled) {
                        // POSIX: INT-cancelled command yields $? = 130
                        // (128 + SIGINT). The for-loop continues unless the
                        // trap handler chooses to abort.
                        lastExit = 130
                    }
                    drainForegroundSignals()
                    stdin = emptySource()
                    // Stdin ttyness only applies to the first statement —
                    // after the script-level stdin is consumed it becomes
                    // an empty buffer for subsequent statements.
                    currentStdinIsTty = false
                    // errexit / special-builtin abort: same break protocol
                    // as runStatements. Without this, `set -e; false; echo x`
                    // would still run `echo x` because runStreaming kept
                    // iterating past the failure.
                    if (pendingAbort) {
                        lastExit = pendingAbortCode
                        pendingAbort = false
                        pendingAbortCode = 0
                        break
                    }
                }
                // Any warnings that didn't match a statement's [line, next.line)
                // range (e.g. warnings on lines after the last statement)
                // still need to surface.
                flushAllLexWarnings()
                // Drain any recovered mid-stream errors that fired after the
                // last yielded statement (e.g. an unterminated trailing
                // statement after the recovered ones).
                for (e in source.drainRecoveredErrors()) {
                    emitShellParseError(
                        errSink,
                        currentOuterScript,
                        dollarZero,
                        currentOuterIsCLine,
                        e,
                    )
                    lastExit = 1
                }
                // POSIX shell semantics: any syntax error in source
                // that followed the last cleanly-parsed statement runs
                // the earlier statements first, then surfaces the
                // diagnostic and exits non-zero. The [StatementSource]
                // holds the deferred error if one occurred;
                // [ScriptStatementSource] always returns null.
                source.pendingError()?.let { e ->
                    // Match [emitShellParseError]'s diagnostic shape so the
                    // `-c:` prefix and second-line source echo land for
                    // file/cline scripts whose execution drives this loop.
                    emitShellParseError(
                        errSink,
                        currentOuterScript,
                        dollarZero,
                        currentOuterIsCLine,
                        e,
                    )
                    lastExit = 2
                }
            }
        } catch (
            _: BreakException,
        ) {
        } catch (
            _: ContinueException,
        ) {
        } catch (r: ReturnException) {
            lastExit = r.code
        } catch (e: ScriptAbortException) {
            lastExit = e.code
        } catch (e: Expander.ParameterError) {
            // POSIX §2.6.2: `${param?word}` with `param` unset/null must
            // write a diagnostic to stderr; a non-interactive shell then
            // terminates. Bash format: `<script>: line <N>: <name>: <msg>`.
            errSink.writeUtf8(
                "${shellDiagPrefix()}${e.name}: ${e.msg.ifEmpty { "parameter null or not set" }}\n",
            )
            lastExit = 1
        } finally {
            // POSIX EXIT trap: fires when the shell terminates, regardless
            // of how it got here. `trap '…' EXIT` is the cleanup-script
            // pattern every shell-savvy admin reaches for. The handler
            // executes with the failing-script's exit status as `$?` and
            // its own exit status is discarded — bash semantics.
            runExitTrap()
        }
        return lastExit
    }

    /**
     * Drain any signals delivered via [deliverSignal] since the last poll,
     * and fire matching trap handlers. Called between statements so a
     * handler's commands execute as if they were a new top-level statement
     * — they cannot be cancelled by the same signal that triggered them
     * (otherwise `trap 'echo got; exit' INT` couldn't even print).
     */
    internal suspend fun drainForegroundSignals() {
        while (true) {
            val result = foregroundSignals.tryReceive()
            val sig = result.getOrNull() ?: break
            if (sig === SigExit) continue // EXIT only fires from run()'s outermost finally
            val action = trapTable.get(sig) as? TrapAction.Handler ?: continue
            runTrapHandler(sig, action)
        }
    }

    // -------- Tool-facing hooks (ShellRunner / UtilityRunner) --------

    /**
     * Build a [ShellRunner] that parses [script] as kash and runs it in a
     * subshell — env/cwd/locals mutations do NOT leak back. [callerStderr] is
     * the stderr to use when the runner is invoked with `stderr = null`
     * (popen-style default — sub-script stderr passes through to the caller).
     */
    internal fun makeShellRunner(callerStderr: SuspendSink): ShellRunner =
        ShellRunner { inv ->
            runShellScript(
                script = inv.script,
                stdin = inv.stdin ?: emptySource(),
                stdout = inv.stdout,
                stderr = inv.stderr ?: callerStderr,
                scriptName = inv.scriptName,
                positional = inv.positional,
                isCLine = inv.isCLine,
                posixMode = inv.posixMode,
            )
        }

    /**
     * Build a [UtilityRunner] whose invocations are parented to [parent].
     *
     * Caller pattern:
     *   - Builtins / intrinsics handed the shell's process: pass nothing
     *     (default = [process]).
     *   - Inside a forked TOOL: pass the tool's child process so nested
     *     `xargs cat`-style invocations become grandchildren of the
     *     shell, inheriting the tool's env/cwd mutations as POSIX
     *     specifies. Without this, every utility call would fork off the
     *     shell directly and lose the parent tool's effective state.
     */
    internal fun makeUtilityRunner(parent: KashProcess = process): UtilityRunner =
        UtilityRunner { name, args, stdin, stdout, stderr ->
            runUtilityDirect(name, args, parent, stdin, stdout, stderr)
        }

    /**
     * POSIX subshell semantics for a tool-supplied script string. Runs the
     * sub-script on a [forkSubshell] — env/cwd/locals/functions/aliases/
     * traps/fdTable redirections etc. mutate the fork's state and are
     * dropped when the fork is discarded; the caller's interpreter is
     * untouched. Parse failures are reported to [stderr] as a non-zero
     * exit, not thrown.
     *
     * Per bash semantics, the sh runner models a fresh `${THIS_SH} ./script`
     * invocation: parent-shell variable attributes (readonly, integer, case)
     * do not cross the fresh-process boundary, so they're cleared on the
     * fork after [forkSubshell]'s default inheritance.
     */
    internal suspend fun runShellScript(
        script: String,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
        scriptName: String = "kash",
        positional: List<String> = emptyList(),
        isCLine: Boolean = false,
        posixMode: Boolean = false,
    ): Int {
        if (shellDepth >= shellDepthLimit) {
            stderr.writeUtf8("kash: shell recursion limit exceeded\n")
            return 2
        }
        // Snapshot outer-script context for deferred-cmdsub parse-error
        // emission (see InterpreterExpand.emitCmdSubParseError). Restored
        // on exit so a nested shell-runner doesn't permanently rebind
        // the parent's view.
        val savedOuterScript = currentOuterScript
        val savedOuterIsCLine = currentOuterIsCLine
        currentOuterScript = script
        currentOuterIsCLine = isCLine
        try {
            // POSIX shell semantics: re-parse each statement against
            // the LIVE shell state. We can't pre-parse — mid-script
            // `set -o posix` / `shopt -s extglob` changes lex rules for
            // subsequent statements, and a syntax error past the
            // toggle-point must be evaluated under the toggled rules,
            // not the entry-time rules.
            val fork =
                try {
                    forkSubshell()
                } catch (e: com.accucodeai.kash.api.ForkException) {
                    stderr.writeUtf8("${shellDiagPrefix()}fork: ${e.message}\n")
                    return 1
                }
            // [forkSubshell] is calibrated for `(...)`-style subshells, which
            // bash inherits broadly. `sh -c` / `sed s///e` are *exec*, not fork —
            // a fresh shell process — so we drop everything POSIX says doesn't
            // survive an exec:
            //   - Variable attributes (readonly/integer/case) — shell-internal,
            //     don't cross the exec boundary.
            //   - Functions, aliases, PATH hash cache — defined in the parent's
            //     shell-private memory; a fresh /bin/sh starts empty.
            //   - Function-local scope stack — there's no enclosing function in
            //     a fresh process.
            //   - `$?` — POSIX undefined at shell entry; bash resets to 0.
            // Non-exported env vars also don't survive exec, but kash today
            // doesn't track exported-ness in [process.env] (it's a flat map),
            // so they leak the same way they leak into every external command —
            // a broader kash limitation, not specific to this path.
            // Strip non-exported parent vars from the fork's env *before*
            // clearing varAttrs (the prune reads parent's varAttrs to decide
            // which names are exported).
            //
            // Setup-and-run block: every statement from here through
            // [fork.runStreaming] is wrapped in one try so the finally below
            // always tears down the registered KashProcess (and, once added,
            // the signalRouter handler). A throw in `importBashFuncEnvVars`
            // or `fork.varTable` mutations would otherwise leak the entire
            // fork interpreter via [machine]'s process map.
            try {
                pruneToExportedEnv(fork.process)
                // Shell-internal variable state (scalars, indexed/assoc arrays,
                // attrs, local scopes) doesn't survive exec — bash starts a fresh
                // shell-variable table from `extern char **environ`. One call covers
                // it; the array views read through `fork.varTable`.
                fork.varTable.clearShellInternal()
                // Fork-and-exec semantics: a fresh shell process owns its own
                // job table, not the parent's. `(...)`-subshells still share
                // [jobControl] via [SharedSession] (and the visibility mask
                // governs jobspec scope), but an exec'd shell starts empty.
                // Without this, `${THIS_SH} -c 'jobs'` would list every
                // background job the launching shell owns, and parent-side
                // notifications (`[1]+ Done sleep …`) would emit at the
                // child's synchronization points using the child's stderr.
                fork.jobControl =
                    com.accucodeai.kash.jobs
                        .JobControl(machine)
                fork.subshellJobMask = emptySet()
                // Same boundary for $$/PPID: a fresh shell process has its own
                // shellPid (matches its [KashProcess.pid]) so `kill -USR1 $$`
                // from inside the child reaches the child, not the launching
                // shell. The matching [signalRouter.register] happens inside
                // the try below so a throw between here and runStreaming can't
                // leak the closure (it captures [fork] — the entire interp).
                fork.shellPid = fork.process.pid
                fork.shellPpid = process.pid
                // Carry `export -f`-marked functions across the fork-and-exec
                // boundary. Bash serializes via BASH_FUNC_<name>%%=<body> env
                // vars; we take both paths so this fork sees parent-exported
                // functions even when the caller is a foreign program (bash
                // itself, or a script run via posix-exec) that uses the env-var
                // convention.
                val exportedFns = exportedFunctions.mapNotNull { n -> functions[n]?.let { n to it } }
                fork.functions.clear()
                for ((n, fn) in exportedFns) {
                    fork.functions[n] = fn
                    fork.exportedFunctions += n
                }
                // BASH_FUNC_<name>%%=<body> path: parse each entry's value as a
                // function definition once, cache the FunctionDef, and strip the
                // env var (matching bash, which doesn't propagate these to the
                // sub-process's environ). Parse failures are silently dropped —
                // bash's CVE-2014-* hardening also rejects malformed entries,
                // and our parser will throw on the adversarial inputs in the
                // exportfunc.tests CVE fixtures by design.
                importBashFuncEnvVars(fork)
                fork.aliases.clear()
                fork.aliasVersion++
                fork.hashCache.clear()
                // varTable.clearShellInternal above already dropped the scope
                // chain. Reset the call-stack cluster so the new shell starts at
                // top-level with no inherited FUNCNAME/BASH_LINENO frames.
                fork.callStack.copyFrom(CallStack())
                fork.lastExit = 0
                // Per POSIX, vars inherited via environ at shell startup are
                // exported by default in the new shell. After the prune above,
                // fork.process.env contains exactly the parent's exports — seed
                // each one as Scalar+Export in the fork's varTable so reads via
                // `$NAME` see the inherited value (env reads go through
                // [ProcessEnvAdapter]/varTable, not [process.env] directly).
                for ((name, value) in fork.process.env) {
                    val v = fork.varTable.findOrCreate(name)
                    v.value = VariableValue.Scalar(value)
                    v.attrs += VarAttr.Export
                }
                // clearShellInternal also dropped the dynamic-specials registry;
                // re-register so RANDOM/BASHPID/LINENO/etc. resolve in the new shell.
                fork.registerSpecialVariables()
                // ...and dropped the seeded BASH_ALIASES/BASH_CMDS — a fresh
                // exec'd shell still starts with both as empty assoc arrays
                // (visible in `declare -A`), so re-seed them here.
                fork.seedBashSpecialAssocs()
                fork.positional = positional
                fork.dollarZero = scriptName
                fork.posixModeRuntime = posixMode
                fork.shellDepth = shellDepth + 1
                // Propagate outer-script context so deferred cmdsub parse errors
                // inside this fork (Part B) emit with the right prefix / source
                // quote. The fork's parse-error path uses these fields.
                fork.currentOuterScript = script
                fork.currentOuterIsCLine = isCLine
                // Register the fork's signal deliverer LAST, immediately
                // before runStreaming. The closure captures [fork]; the
                // outer finally below releases it so a runStreaming throw
                // or a setup-statement throw both unwind cleanly.
                signalRouter.register(fork.process.pid) { sig -> fork.deliverSignal(sig) }
                // Route through runStreaming (not runStatements) so the
                // per-statement lex-warning interleave fires for sub-shell
                // scripts too — bash emits e.g. comsub-eof heredoc warnings
                // just before the heredoc's command runs, not at the
                // shell's startup.
                // POSIX: signals ignored at shell startup cannot be
                // re-trapped. Snapshot the current IGN set so the child's
                // `trap '…' SIG` for a startup-ignored SIG silently does
                // nothing — matching bash's behavior for `trap '' USR2;
                // ${THIS_SH} ./script.sub` (child can't observe USR2).
                fork.trapTable.sealStartupIgnored()
                val stream =
                    com.accucodeai.kash.parser.StatementStream(
                        source = script,
                        aliasResolver = fork.aliasResolver,
                        posixModeProvider = { fork.posixModeRuntime },
                        extglobProvider = { fork.isShoptEnabled("extglob") },
                        aliasVersionProvider = { fork.aliasVersion },
                        // Sub-shell `bash -c '...'` aborts on first top-level
                        // syntax error (status 2). The in-command recovery
                        // path (e.g. `a=(x & y); echo $?`) still runs for
                        // file-script invocations below.
                        abortOnSyntaxError = isCLine,
                    )
                val source =
                    com.accucodeai.kash.interpreter
                        .StreamStatementSource(stream)
                val execExit =
                    fork.runStreaming(
                        source = source,
                        initialStdin = stdin,
                        stdout = stdout,
                        stderr = stderr,
                        stdinIsTty = currentStdinIsTty,
                        terminalControl = hostTerminal,
                    )
                return execExit
            } finally {
                // Symmetric teardown: unregister the signal handler (drops
                // the closure → fork is GC-eligible) and unregister the
                // KashProcess from the machine's pid map. Both must run
                // even if any statement in the setup block above threw.
                signalRouter.unregister(fork.process.pid)
                machine.unregisterProcess(fork.process.pid)
            }
        } finally {
            currentOuterScript = savedOuterScript
            currentOuterIsCLine = savedOuterIsCLine
        }
    }

    /**
     * Format a parse failure in bash's diagnostic style. For `sh -c '...'`
     * bash prefixes with `<$0>: -c: line N: ...`; for file scripts it uses
     * `<scriptName>: line N: ...`. POSIX §2.8.1 mandates a diagnostic and
     * non-zero exit but leaves the format unspecified — we match bash so
     * conformance fixtures match.
     *
     * When the parser supplied an offending token, the message already has
     * the bash-style `syntax error near unexpected token \`<tok>'` shape.
     * For sh -c we also emit the second line `: \`<source line N>'` quoting
     * the offending source line, as bash does.
     */
    internal suspend fun emitShellParseError(
        stderr: SuspendSink,
        script: String,
        scriptName: String,
        isCLine: Boolean,
        e: ParseException,
    ) {
        val msg = e.message ?: "parse error"
        val ln = e.line
        val prefix = if (isCLine) "$scriptName: -c: line $ln" else "$scriptName: line $ln"
        if (ln > 0) {
            stderr.writeUtf8("$prefix: $msg\n")
            // Bash emits the second-line source echo for parse errors with
            // a concrete offending token, but suppresses it for
            // unexpected-EOF style errors (no token to point at). Both `-c
            // script` and `./file.sub` invocations get this echo.
            if (e.offendingToken != null) {
                val srcLine = script.lineSequence().elementAtOrNull(ln - 1).orEmpty()
                stderr.writeUtf8("$prefix: `$srcLine'\n")
            }
        } else {
            // Pre-token failure (e.g. lexer threw before any line info). Fall
            // back to the legacy `sh: <msg>` shape.
            stderr.writeUtf8("sh: $msg\n")
        }
    }

    /**
     * POSIX `execvp`-style utility invocation parented to [parent]: look
     * up [name] in the registry, dispatch `spec.command.run(args, ctx)`
     * with caller-supplied stdio, return the exit code. No parsing, no
     * inline assignments, no intrinsic dispatch. `newCwd` is discarded
     * (a `cd inside xargs` must not leak), and `pendingAbort` is untouched.
     *
     * Dispatch mirrors [runResolvedSpec]:
     *   - `TOOL` → `machine.spawn(parent)`, child runs the command, reap.
     *     For a `xargs cat` call where xargs itself is already a forked
     *     tool, [parent] is xargs's child process and cat becomes a real
     *     grandchild — mutations stay scoped to cat's lifetime.
     *   - `BUILTIN` / `SPECIAL_BUILTIN` → in-process on [parent]. cd
     *     would mutate xargs's child (which dies), so the mutation
     *     evaporates — POSIX-correct isolation without special-casing.
     *   - intrinsic name → 126 (intrinsics aren't utilities, POSIX rule).
     */
    internal suspend fun runUtilityDirect(
        name: String,
        args: List<String>,
        parent: KashProcess,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int {
        if (com.accucodeai.kash.intrinsics.IntrinsicCatalog.byName
                .containsKey(name)
        ) {
            stderr.writeUtf8("$name: not executable as a utility\n")
            return 126
        }
        val spec = registry[name]
        if (spec == null) {
            stderr.writeUtf8("${shellDiagPrefix()}$name: command not found\n")
            return 127
        }
        val command =
            spec.command ?: run {
                stderr.writeUtf8("$name: not executable as a utility\n")
                return 126
            }
        return if (spec.kind == CommandKind.TOOL) {
            val sr =
                machine.spawn(parent) { child ->
                    // POSIX exec: only exports cross the boundary.
                    pruneToExportedEnv(child)
                    child.commandName = name
                    child.argv = listOf(name) + args
                    installStdioFds(child, stdin, stdout, stderr)
                    val ctx =
                        CommandContext(
                            process = child,
                            shellRunner = makeShellRunner(callerStderr = stderr),
                            utilityRunner = makeUtilityRunner(child),
                            isInteractive = interactive,
                            userDb = userDb,
                            sandbox = sandbox,
                            assocExpandOnce = shoptOptions["assoc_expand_once"] == true,
                        )
                    command.run(args, ctx).exitCode
                }
            if (sr.pid < 0) {
                stderr.writeUtf8("${shellDiagPrefix()}fork: retry: Resource temporarily unavailable\n")
                1
            } else {
                (machine.wait(sr.pid) as? ExitStatus.Exited)?.code ?: 0
            }
        } else {
            // BUILTIN / SPECIAL_BUILTIN — run in-process on [parent]. POSIX
            // says the utility's stdio is its own; the parent's fdTable[0..2]
            // must survive the call so a caller tool whose CommandContext is
            // built on [parent] (and whose `ctx.stdin/stdout/stderr` are live
            // views of fdTable[0..2]) sees its stdio intact after we return.
            val saved0 = parent.fdTable[0]
            val saved1 = parent.fdTable[1]
            val saved2 = parent.fdTable[2]
            installStdioFds(parent, stdin, stdout, stderr)
            val ctx =
                CommandContext(
                    process = parent,
                    shellRunner = makeShellRunner(callerStderr = stderr),
                    utilityRunner = makeUtilityRunner(parent),
                    isInteractive = interactive,
                    userDb = userDb,
                    sandbox = sandbox,
                    assocExpandOnce = shoptOptions["assoc_expand_once"] == true,
                    setArrayElement = { name, sub, value -> setBuiltinArrayElementTarget(name, sub, value) },
                    arrayRefArgs = currentArrayRefArgs,
                    isAssocArray = { n -> varTable.find(n)?.isAssoc == true },
                )
            try {
                command.run(args, ctx).exitCode
            } finally {
                if (saved0 != null) parent.fdTable[0] = saved0 else parent.fdTable.remove(0)
                if (saved1 != null) parent.fdTable[1] = saved1 else parent.fdTable.remove(1)
                if (saved2 != null) parent.fdTable[2] = saved2 else parent.fdTable.remove(2)
            }
        }
    }

    // -------- Functions --------

    // -------- Helpers --------

    internal suspend fun runStatements(
        statements: List<Statement>,
        stdin: SuspendSource,
        stdio: Stdio,
    ): Int {
        val savedOut = outSink
        val savedErr = errSink
        val savedOutTty = outSinkIsTty
        val savedErrTty = errSinkIsTty
        val savedStdinTty = currentStdinIsTty
        val savedHostTerminal = hostTerminal
        outSink = stdio.stdout
        errSink = stdio.stderr
        outSinkIsTty = stdio.stdoutIsTty
        errSinkIsTty = stdio.stderrIsTty
        currentStdinIsTty = stdio.stdinIsTty
        hostTerminal = stdio.terminalControl
        try {
            var exit = 0
            for (s in statements) {
                exit = runStatement(s, stdin)
                // Drain pending foreground signals between statements
                // (inside function bodies, brace groups, etc.) so a
                // trap fires close to its triggering point — bash's
                // "check for pending signals at safe points" rule.
                // Without this, `kill -USR1 $$` inside a function body
                // wouldn't fire the trap until after the function
                // returned, and a `return N` in the trap would error
                // because the function frame is already gone.
                drainForegroundSignals()
                // Errexit/special-builtin abort raised the flag — bail out
                // of this statement list so the parent catch (script-top
                // or runStatementsInFork) can unwind cleanly.
                if (pendingAbort) break
            }
            return exit
        } finally {
            outSink = savedOut
            errSink = savedErr
            outSinkIsTty = savedOutTty
            errSinkIsTty = savedErrTty
            currentStdinIsTty = savedStdinTty
            hostTerminal = savedHostTerminal
        }
    }

    // -------- Inner types --------

    internal class Stdio(
        val stdin: SuspendSource,
        val stdout: SuspendSink,
        val stderr: SuspendSink,
        /**
         * Per-fd terminal-ness. Threaded through pipelines and redirections
         * so a downstream tool sees an honest answer to "am I reading from
         * the user's tty?" instead of relying on the coarse session bit.
         * Defaults to false — only the top-level [Stdio] built in [run]
         * gets these set true, and only when the session is interactive.
         */
        val stdinIsTty: Boolean = false,
        val stdoutIsTty: Boolean = false,
        val stderrIsTty: Boolean = false,
        /**
         * Handle to the host terminal device for tools that flip raw mode
         * (kash's own REPL, nano, reset). Non-null only when the
         * corresponding fd is a tty AND the host wired one through. Cleared
         * on redirection/pipeline/command-substitution boundaries the same
         * way [stdinIsTty] is.
         */
        val terminalControl: TerminalControl? = null,
        /**
         * Per-fd backing path for `/proc/self/fd/N` reporting. Set when
         * [applyRedirections] opened a file for fd 0/1/2 (`cmd > file`,
         * `cmd < file`, `cmd 2> file`); null when fd N inherits the parent's
         * raw stream (pipe stage, the host stdio, command substitution
         * capture buffer). `installStdio` stamps these onto the rebuilt OFDs
         * so a tool reading `readlink /proc/self/fd/1` sees the file path.
         */
        val stdinPath: String? = null,
        val stdoutPath: String? = null,
        val stderrPath: String? = null,
    )

    internal companion object {
        /**
         * Special POSIX builtins whose non-zero exit reflects *child* logic
         * rather than a fault in the builtin itself, and therefore should
         * NOT trigger POSIX §2.8.1 script-abort. `eval` is the canonical
         * case: a parse error in `eval`'s argument is a script-level error
         * surfaced as exit 2, but bash continues past it.
         */
        val NON_ABORTING_SPECIAL_BUILTINS =
            setOf(
                // POSIX §2.8.1: special builtins' "failures during
                // preparation" (redirection, expansion, ill-formed args)
                // exit the script; their *plain* non-zero exits do NOT.
                // We don't yet distinguish preparation failures from
                // normal failures, so we whitelist the cases where bash's
                // observable behavior is "keep running" — `.` failing to
                // find a sourced file, `eval` returning the inner
                // script's exit, etc.
                "eval",
                ".",
                "source",
                "set",
                // break/continue are POSIX §2.14 special builtins, but `break`
                // at top-level or `continue` past the loop depth are
                // observably non-fatal in bash. Excluded from abort here so
                // those edge cases don't terminate scripts.
                "break",
                "continue",
                // `export` / `readonly` name-validation failures (rejecting
                // `=` or `/` in -f names, or `=` in -v names) are plain
                // non-zero exits in bash, not preparation failures — the
                // script continues. Tested by bash/exportfunc3.sub which
                // expects `echo status: $?` to print "status: 1" right
                // after a rejected `export -f 'foo=bar'`.
                "export",
                "readonly",
                // `trap` option errors (`-p -P` conflict, `-P` without
                // signals, `-x` invalid option) are non-zero exits with a
                // usage message — bash's observable behavior is "print
                // the diagnostic and continue", not script-abort. See
                // bash/trap.tests lines 118–121.
                "trap",
                // `shift N` with N > $# (or N < 0) is a plain non-zero exit
                // in bash — "shift count out of range", script continues.
                // Not a preparation failure, so it must not abort. (getopts6
                // relies on `shift $((OPTIND-1))` after the args are gone.)
                "shift",
            )

        val RESERVED_KEYWORDS =
            setOf(
                "if",
                "then",
                "elif",
                "else",
                "fi",
                "for",
                "in",
                "do",
                "done",
                "while",
                "until",
                "case",
                "esac",
                "function",
                "{",
                "}",
                "!",
            )
    }
}
