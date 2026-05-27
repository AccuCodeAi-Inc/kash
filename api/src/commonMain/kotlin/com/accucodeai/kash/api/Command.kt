package com.accucodeai.kash.api

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.sandbox.SandboxPolicy
import com.accucodeai.kash.api.user.UserDatabase
import com.accucodeai.kash.fs.FileSystem

/**
 * A command — builtin or user-defined.
 *
 * IO is streaming and **suspend-native**: commands read from a [SuspendSource]
 * and write to two [SuspendSink]s (stdout, stderr) exposed via
 * [CommandContext]. The shapes mirror `kotlinx.io.RawSink`/`RawSource` so a
 * tool body looks the same — only the methods are `suspend`. Pipeline
 * back-pressure parks the *coroutine*, not the dispatcher thread: a saturated
 * channel-backed pipe ([com.accucodeai.kash.api.io.AsyncPipe]) suspends the
 * producer without pinning a worker.
 *
 * Closing the downstream side of a pipe raises
 * [com.accucodeai.kash.api.io.BrokenPipeException] on the next write — that's
 * our SIGPIPE.
 *
 * For UTF-8 string IO use the suspend helpers in
 * [com.accucodeai.kash.api.io.SuspendIo] (`writeUtf8`, `writeLine`,
 * `readUtf8Text`, `readUtf8LineOrNull`).
 */
public interface Command {
    public val name: String

    public suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult
}

public class CommandContext(
    /**
     * The [KashProcess] this tool runs as. POSIX-wise, the "process the
     * kernel handed argv/envp to". Per-process state (env, cwd, umask, fd
     * table, signal mask, credentials) lives here — what used to be
     * separate `fs`/`env`/`cwd`/`umask` slots on this class.
     *
     * Tools read env/cwd/etc via `ctx.process.env`/`.cwd`/`.umask`/`.fs`.
     * INTRINSIC builtins receive the *shell's* process; non-intrinsic
     * tools receive a forked process (mutations don't leak). See
     * [KashMachine.spawn].
     */
    public val process: KashProcess,
    /**
     * If non-null, a tool can use this to parse a string as a kash script
     * and run it in a subshell — env, cwd, and locals mutations DO NOT leak
     * back to the caller (POSIX `$(...)` / `sh -c` semantics). Returns the
     * sub-script's exit status.
     *
     * Tools that need this MUST handle null gracefully (typical convention:
     * write `"<tool>: <feature> requires interpreter context\n"` to
     * [stderr] and return exit 2). Null is the expected value when a tool
     * is unit-tested with a bare [CommandContext] and no interpreter is
     * wired in.
     *
     * Consumers: `sed s///e`, `sh -c`, future `eval`-driven flows.
     */
    public val shellRunner: ShellRunner? = null,
    /**
     * If non-null, a tool can use this to invoke a registered utility by
     * name with caller-provided stdio — mirrors POSIX `execvp` semantics:
     * no script parsing, no inline assignments, intrinsics (`cd`, `set`,
     * etc.) are NOT reachable. Returns 127 for missing name, 126 if the
     * name resolves but isn't callable as a utility, otherwise the
     * utility's exit code.
     *
     * Null-handling convention same as [shellRunner].
     *
     * Consumers: `xargs UTIL`, `find -exec UTIL ;`.
     */
    public val utilityRunner: UtilityRunner? = null,
    /**
     * Session-level interactivity flag. True iff the enclosing
     * [com.accucodeai.kash.Kash.Session] was constructed with
     * `interactive = true`. Coarse — covers things like color autodetection
     * and the "this shell aborts on POSIX-special-builtin failure or
     * doesn't" rule. For per-fd terminal questions like `[ -t N ]` or
     * "should I drop into a REPL", consult [stdinIsTty]/[stdoutIsTty]/
     * [stderrIsTty] instead — those reflect pipe and redirection state too.
     */
    public val isInteractive: Boolean = false,
    /**
     * The session's user database — drives POSIX `id` / `logname`, `~user`
     * tilde expansion, and the default values of `$LOGNAME`/`$USER`/`$HOME`.
     * Defaults to a single-user "user@/home/user" so bare-context tests
     * compile without churn; real sessions get the database injected at
     * [com.accucodeai.kash.Kash.Session] construction.
     */
    public val userDb: UserDatabase = UserDatabase.Default,
    /**
     * Session-level sandbox posture. Tools that have a "trusted" and a
     * "sandboxed" code path (today: `python3-graalpy`) consult this to
     * choose. Most tools ignore it.
     */
    public val sandbox: SandboxPolicy = SandboxPolicy.TRUSTED,
    /**
     * Prefix the shell would use for its own diagnostics from the current
     * statement — typically `"<scriptpath>: line N: "`. BUILTIN-kind tools
     * (those that POSIX classifies as builtins, like `printf`/`echo`/`type`)
     * should prepend this to error messages so a script's diagnostic stream
     * carries the same `<script>: line <N>:` framing as native shell errors.
     *
     * Empty when the tool runs outside an interpreter or when the shell is
     * in interactive mode (where the prefix is suppressed).
     */
    public val shellDiagPrefix: String = "",
    /**
     * State of the `assoc_expand_once` shell option for the enclosing
     * session. False (bash's default) means an associative-array subscript
     * may be expanded more than once — builtins that re-parse a `NAME[sub]`
     * target (`printf -v`) reject a subscript that became syntactically
     * invalid under the second expansion (e.g. an apostrophe key). True
     * means a single expansion, so such targets are accepted. Defaults to
     * false so bare-context tests and non-interpreter callers compile
     * without churn.
     */
    public val assocExpandOnce: Boolean = false,
    /**
     * If non-null, store a value into an array element of a shell variable —
     * `name` is the base variable, `sub` the (already-expanded) subscript
     * text, `value` the string to assign. The interpreter decides indexed vs
     * associative storage exactly as `read NAME[sub]` does. Used by
     * `printf -v NAME[sub]`, which otherwise can only set whole env vars.
     * Null in bare/non-interpreter contexts — callers fall back to a plain
     * `env[name] = value` write. Only wired on in-process builtin dispatch
     * (so the mutation reaches the shell, not a throwaway fork).
     */
    public val setArrayElement: (suspend (name: String, sub: String, value: String) -> Unit)? = null,
    /**
     * Indices into the arg list (as passed to `run`) of arguments that were
     * unquoted array-reference words — a `name`-then-bracketed-subscript
     * shape — in the source (a per-word property computed before expansion).
     * Under `assoc_expand_once`, a builtin re-parsing such a target reads the
     * post-expansion subscript as a single literal key spanning to the
     * closing bracket (so a right-bracket-valued subscript becomes the key);
     * quoted or non-reference args keep the strict "first close-bracket ends
     * the subscript" parse. Empty in bare/non-interpreter contexts.
     */
    public val arrayRefArgs: Set<Int> = emptySet(),
    /**
     * If non-null, reports whether the named shell variable is an associative
     * array. A builtin re-parsing a subscripted target uses this to decide
     * whether a subscript containing a close-bracket character is a literal
     * key (associative) or an invalid identifier (indexed/scalar). Null →
     * treated as false.
     */
    public val isAssocArray: ((name: String) -> Boolean)? = null,
) {
    // --- Forwarded views of the per-process state ---
    //
    // [process] is the source of truth (POSIX-wise: "the kernel handed us
    // argv/envp/fds"). These computed properties give tools the ergonomic
    // `ctx.env` / `ctx.cwd` / `ctx.fs` / `ctx.umask` shorthand without
    // requiring an `import` for an extension. Per-fd tty bits drop the
    // historical 3-boolean triple and consult the OFD instead, which works
    // for any fd ≥ 0 (`[ -t 7 ]` is suddenly answerable).
    //
    // Tools that want explicit semantic clarity can still read
    // `ctx.process.env` — both forms reach the same map.

    public val fs: FileSystem get() = process.machine.fs
    public val env: MutableMap<String, String> get() = process.env
    public val cwd: String get() = process.cwd
    public val umask: Int get() = process.umask
    public val stdinIsTty: Boolean get() = process.isTty(0)
    public val stdoutIsTty: Boolean get() = process.isTty(1)
    public val stderrIsTty: Boolean get() = process.isTty(2)

    /**
     * Live views of the per-process stdio. These read fds 0/1/2 from the
     * process fdTable on every access, so a tool that rewrites
     * `process.fdTable[1]` (e.g. via a redirection) sees the new sink on
     * the next `ctx.stdout` read. The interpreter and [bareCommandContext]
     * install fds 0/1/2 before invoking any [Command.run], so these
     * accessors are never null in practice; the `!!` encodes that invariant.
     */
    public val stdin: SuspendSource get() = process.fdTable[0]!!.ofd.source!!
    public val stdout: SuspendSink get() = process.fdTable[1]!!.ofd.sink!!
    public val stderr: SuspendSink get() = process.fdTable[2]!!.ofd.sink!!
}

/**
 * Everything a [ShellRunner] needs to execute one sub-script. Splitting
 * [scriptName] from [positional] avoids POSIX's `argv[0] = $0` overload
 * inside Kotlin — the translation happens once, at the `sh` builtin
 * boundary. Extend with new defaults when more context needs to flow in,
 * without breaking the [ShellRunner] SAM again.
 *
 * @property script the kash source to parse and execute
 * @property scriptName becomes `$0` inside the sub-script
 * @property positional `$1..$N`
 * @property stdin source the sub-script reads from; null = empty input
 * @property stdout sink that receives the sub-script's stdout
 * @property stderr sink for the sub-script's stderr; null = write to the
 *   caller's stderr (popen-style)
 */
public data class ShellInvocation(
    val script: String,
    val stdout: SuspendSink,
    val scriptName: String = "kash",
    val positional: List<String> = emptyList(),
    val stdin: SuspendSource? = null,
    val stderr: SuspendSink? = null,
    /**
     * True when [script] came from `sh -c '...'`. Diagnostics for such
     * invocations use bash's `$0: -c: line N: ...` prefix rather than the
     * file-script `$scriptName: line N: ...` form.
     */
    val isCLine: Boolean = false,
    /**
     * `sh -o posix` mode — the lexer treats reserved words as never
     * alias-eligible and silently drops reserved-word alias definitions
     * from the parse-time overlay.
     */
    val posixMode: Boolean = false,
)

/**
 * Parse [ShellInvocation.script] as a kash shell script and run it in a
 * subshell. Env, cwd, locals, positional params, and `$0` mutations are
 * isolated from the caller.
 */
public fun interface ShellRunner {
    public suspend fun run(invocation: ShellInvocation): Int
}

/**
 * Invoke the registered utility [name] with [args] (POSIX `execvp`-style).
 * Intrinsics and inline-assignment side effects are NOT performed — a tool
 * calling `cd /tmp` through this does NOT change anyone's cwd.
 *
 * @return 127 if [name] is unknown, 126 if it resolves but is not callable
 *   as a utility, otherwise the utility's exit code.
 */
public fun interface UtilityRunner {
    public suspend fun run(
        name: String,
        args: List<String>,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int
}

public class CommandResult(
    public val exitCode: Int = 0,
    /** Optional new cwd (used by `cd`). */
    public val newCwd: String? = null,
)

public fun defineCommand(
    name: String,
    block: suspend (args: List<String>, ctx: CommandContext) -> CommandResult,
): Command =
    object : Command {
        override val name: String = name

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
        ): CommandResult = block(args, ctx)
    }
