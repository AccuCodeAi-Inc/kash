package com.accucodeai.kash.tools.python3

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.api.sandbox.SandboxPolicy
import com.accucodeai.kash.fs.FileSystem

/**
 * Pluggable Python execution backend.
 *
 * `:tools:python3` defines the shell-side command (argv parsing, exit-code
 * mapping) and leaves the actual interpreter selection to a separately
 * registered [PythonEngine]. The JVM-only `:tools:python3-graalpy` module
 * supplies a GraalPy-backed implementation; future modules can add Pyodide
 * (wasm targets), a host-CPython subprocess engine, or any other Python
 * runtime without touching the command itself.
 *
 * Including `Python3Module` without also including an engine module will
 * fail at Koin startup with an unresolved-dependency error — that's the
 * intended explicit failure mode: pick an engine.
 */
public interface PythonEngine {
    /**
     * Run a Python program. Implementations should:
     * - decode [stdin] / write [stdout] / [stderr] as UTF-8 byte streams,
     * - route Python `open()` / `os.listdir()` / etc. through [fs] (no
     *   direct host filesystem access),
     * - apply [timeoutMillis] as a wall-clock budget and return exit `124`
     *   on timeout (matches GNU `timeout(1)`),
     * - return exit `1` on Python exceptions that escape the program, with
     *   the traceback already written to [stderr].
     */
    public suspend fun execute(
        source: PythonSource,
        scriptArgs: List<String>,
        fs: FileSystem,
        env: Map<String, String>,
        cwd: String,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
        timeoutMillis: Long,
        /**
         * Session sandbox posture. Engines that can run with stricter isolation
         * (e.g. GraalPy's `Context.Builder.sandbox(...)`) should honor this.
         * Engines that don't model the distinction may ignore it. Engines
         * that *can't* honor [SandboxPolicy.UNTRUSTED] on the current runtime
         * (e.g. GraalPy on community GraalVM) MUST refuse to start with a
         * clear stderr diagnostic rather than silently downgrading.
         */
        sandbox: SandboxPolicy = SandboxPolicy.TRUSTED,
        /**
         * Session network posture. Engines that can gate outbound network
         * access at the interpreter level should honor [NetworkPolicy.DenyAll]
         * (no Python stdlib `socket`/`urllib`/`requests` calls succeed).
         * [NetworkPolicy.Allowlist] is currently best-effort or no-op
         * inside an interpreter — see docs/SECURITY.md for the per-engine
         * story.
         */
        networkPolicy: NetworkPolicy = NetworkPolicy.None,
    ): Int

    /** Engine identifier, surfaced by `python3 --version`. */
    public val name: String

    /**
     * Drop into an interactive Python REPL using the caller's [stdin] /
     * [stdout] / [stderr]. Used by `python3` when invoked bare (no `-c` /
     * `-m` / file / `-`) from an interactive kash session. Default impl
     * returns [REPL_NOT_SUPPORTED]; engines that can actually drive a REPL
     * (e.g. GraalPy on JVM) override.
     *
     * The engine receives the per-process OFD-backed streams, NOT raw host
     * `System.in/out/err`. That's how kash routes the embedded REPL's I/O
     * through the same fd-0 byte pump that owns the host stdin (avoiding
     * the race with the line editor) and how shell redirections like
     * `python3 > out.txt` reach the engine without it knowing.
     */
    public suspend fun runInteractiveRepl(
        fs: FileSystem,
        cwd: String,
        env: Map<String, String>,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int = REPL_NOT_SUPPORTED

    public companion object {
        public const val REPL_NOT_SUPPORTED: Int = -1
    }
}

/** What to execute: a literal string, a stdlib module, a script file, or stdin. */
public sealed interface PythonSource {
    /** `-c CODE` — execute CODE as a Python program. */
    public data class Code(
        val code: String,
    ) : PythonSource

    /** `-m MODULE` — run the named module as a script. */
    public data class Module(
        val module: String,
    ) : PythonSource

    /** `python3 path/to/script.py` — execute the file. */
    public data class File(
        val path: String,
    ) : PythonSource

    /** `python3` with no operand, or `python3 -` — read the program from stdin. */
    public data object Stdin : PythonSource
}
