package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.interpreter.Interpreter
import com.accucodeai.kash.parser.ParseException
import com.accucodeai.kash.parser.Parser
import kotlinx.coroutines.currentCoroutineContext

/**
 * Persistent, non-interactive kash subshell that survives across the whole
 * agent session. One [Interpreter] is constructed on first [execute] call,
 * wraps a forked [com.accucodeai.kash.api.KashProcess] so cwd/env mutations
 * don't leak back to the caller's shell, and is reused for every
 * `shell_exec` tool call.
 *
 * Why persistent: the agent gets enormous value out of state carrying
 * across calls — `cd src && ls`, `export FOO=bar`, `foo() { ... }`,
 * `alias g=git` all behave as the user intuits. A fresh shellRunner per
 * call (POSIX `sh -c` semantics) would wipe that every time.
 *
 * Why non-interactive: no PS1, no job-control prompts, no readline. We
 * always feed an empty stdin and capture stdout/stderr into buffers. The
 * `interactive = false` flag also disables POSIX's "abort on special-
 * builtin failure" interactive override, matching how scripts run.
 *
 * One instance is owned by an [AgentSession] and lives until the session
 * exits. Not thread-safe — the agent loop is single-threaded and tool calls
 * are dispatched sequentially by Koog's iteration loop.
 */
internal class KashAgentShell(
    private val parentCtx: CommandContext,
) {
    private var interp: Interpreter? = null

    /** Result of one tool-call execution. */
    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    )

    /**
     * Parse [command] as a kash script and run it on the persistent
     * interpreter. Returns captured stdout + stderr + exit code. Parse
     * failures surface as `Result("", "<msg>", 2)` so the LLM sees the
     * diagnostic in its `role:tool` content rather than as an exception.
     */
    suspend fun execute(command: String): Result {
        val interp = ensureInterpreter()
        val parsed =
            try {
                Parser(command, interp.aliasResolver).parseScript()
            } catch (e: ParseException) {
                return Result("", "kash: parse error: ${e.message}\n", 2)
            }
        val (stdout, stderr, code) = interp.run(parsed, ByteArray(0), mergeStderr = false)
        return Result(stdout, stderr, code)
    }

    /**
     * Export a shell variable into the persistent subshell so later
     * `shell_exec` calls (and any child process they spawn) see it.
     * Used to bind dropped attachments to `KASH_ATTACHMENT_N` paths.
     *
     * Runs through [execute] (a normal `export` statement) rather than
     * poking the interpreter's var table directly — that table is
     * `internal` to the shell module and, more importantly, mutating it
     * out-of-band would race a tool call mid-flight. Callers must invoke
     * this at a quiescent point (between turns), never concurrently with
     * an in-flight [execute].
     *
     * [value] is expected to be a sanitized FS path (`[A-Za-z0-9._/-]`),
     * so single-quoting is injection-safe; we still reject a stray quote
     * defensively.
     */
    suspend fun exportVar(
        name: String,
        value: String,
    ) {
        if ('\'' in value || '\n' in value) return // never happens for sanitized paths; bail rather than risk injection
        execute("export $name='$value'")
    }

    /** Current working directory of the agent's subshell — for the status line. */
    val cwd: String? get() = interp?.cwd

    private suspend fun ensureInterpreter(): Interpreter {
        interp?.let { return it }
        // Fork the caller's process so env/cwd mutations don't leak back
        // to the host shell. The forked process inherits cwd/env/umask
        // from the caller, and is freshly allocated in the machine's
        // process table.
        val agentProc = parentCtx.process.fork()
        agentProc.commandName = "kash-agent-shell"
        val fresh =
            Interpreter(
                machine = parentCtx.process.machine,
                registry = parentCtx.process.machine.registry,
                process = agentProc,
                parentContext = currentCoroutineContext(),
                interactive = false,
                userDb = parentCtx.userDb,
                sandbox = parentCtx.sandbox,
            )
        interp = fresh
        return fresh
    }
}
