@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.python3.pyodide

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.sandbox.SandboxPolicy
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.tools.python3.PythonEngine
import com.accucodeai.kash.tools.python3.PythonSource
import com.accucodeai.kash.tools.python3.pyodide.worker.PyodideErrorPolicy
import com.accucodeai.kash.tools.python3.pyodide.worker.PyodideWorkerClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pyodide-backed [PythonEngine] for the wasmJs target.
 *
 * **Worker-only.** Pyodide runs in a dedicated Web Worker via
 * [PyodideWorkerClient]; the kash main thread never blocks. Filesystem
 * access is a live request-response bridge (see [worker.SabFsServer] +
 * the Emscripten FS plugin in `pyodide-worker.js`) backed by
 * SharedArrayBuffer + `Atomics.wait`, which requires cross-origin
 * isolation (COOP `same-origin` + COEP `require-corp`). Pages without
 * isolation can't run kash's Python at all — we surface a clear error
 * rather than silently degrading.
 *
 * The previous "in-process Pyodide on the main thread with a snapshot FS"
 * fallback was deleted: it couldn't serve `input()` (no async stdin) and
 * its snapshot FS prevented round-trip writes between Python and the kash
 * shell. The worker path is now the only path.
 *
 * Architectural mirror of `GraalPyEngine` from `:tools:kash:python3-graalpy`:
 *  - argv parsing lives in `Python3Command` and is reused unchanged
 *  - stdin / stdout / stderr are kash's `SuspendSource` / `SuspendSink`
 *  - filesystem access lands in kash's [FileSystem] via the live bridge
 *  - timeout enforced via coroutine `withTimeout`; on expiry the worker
 *    is terminated so the next invocation starts fresh
 *
 * Both [execute] and [runInteractiveRepl] go through the same worker. The
 * REPL uses [PyodideWorkerClient]'s session API ([PyodideWorkerClient.beginSession]
 * / [PyodideWorkerClient.runInSession]): one Pyodide instance, one FS
 * bridge, many push calls. Python globals persist across pushes because
 * the worker's `runPythonAsync` evaluates each push in the same Pyodide
 * scope.
 */
public class PyodideEngine : PythonEngine {
    override val name: String = "Pyodide"

    /**
     * Worker-backed backend, created lazily on first [execute].
     */
    private var workerClient: PyodideWorkerClient? = null

    /** Cached "is worker viable" check — done once per engine instance. */
    private val workerSupported: Boolean by lazy {
        val probe = PyodideWorkerClient()
        val ok = probe.isSupported
        if (!ok) probe.shutdown()
        ok
    }

    override suspend fun execute(
        source: PythonSource,
        scriptArgs: List<String>,
        fs: FileSystem,
        env: Map<String, String>,
        cwd: String,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
        timeoutMillis: Long,
        sandbox: SandboxPolicy,
        networkPolicy: com.accucodeai.kash.api.sandbox.NetworkPolicy,
    ): Int {
        // NetworkPolicy inside Python: enforced only at SandboxPolicy.UNTRUSTED.
        // Pyodide doesn't honor the SandboxPolicy tiers — the wasm + browser
        // tab IS the sandbox — but the kash-level contract still applies:
        // anything other than [NetworkPolicy.None] requires UNTRUSTED. We
        // can't enforce that policy from inside Pyodide (the browser fetch
        // API is shared with the host page, and a soft monkey-patch of
        // pyodide.http.pyfetch is bypassable from native modules), so we
        // refuse to start rather than ship a misleading partial gate.
        if (networkPolicy !== com.accucodeai.kash.api.sandbox.NetworkPolicy.None &&
            sandbox != SandboxPolicy.UNTRUSTED
        ) {
            stderr.writeBytes(
                (
                    "python3: NetworkPolicy other than None requires sandbox=UNTRUSTED; " +
                        "got sandbox=$sandbox. See docs/SECURITY.md.\n"
                ).encodeToByteArray(),
            )
            return 126
        }
        if (sandbox == SandboxPolicy.SAFE) {
            stderr.writeBytes(
                "python3: disabled under sandbox=SAFE (no host-FS access permitted)\n".encodeToByteArray(),
            )
            return 126
        }

        if (!workerSupported) {
            stderr.writeBytes(
                (
                    "python3: requires a cross-origin-isolated page " +
                        "(COOP `same-origin` + COEP `require-corp`). Serve the app with " +
                        "those headers so SharedArrayBuffer is available — Pyodide's stdin " +
                        "and the kash filesystem bridge both need it.\n"
                ).encodeToByteArray(),
            )
            return 126
        }

        val program: String =
            when (source) {
                is PythonSource.Code -> {
                    source.code
                }

                is PythonSource.Module -> {
                    "import runpy; runpy.run_module(${quote(source.module)}, run_name='__main__')"
                }

                is PythonSource.File -> {
                    val abs = Paths.resolve(cwd, source.path)
                    if (!fs.exists(abs)) {
                        val msg = "python3: can't open file '${source.path}': No such file or directory\n"
                        stderr.writeBytes(msg.encodeToByteArray())
                        return 2
                    }
                    fs.source(abs).readUtf8Text()
                }

                PythonSource.Stdin -> {
                    stdin.readUtf8Text()
                }
            }

        val sysArgvScript: String =
            when (source) {
                is PythonSource.Code -> "-c"
                is PythonSource.Module -> "-m"
                is PythonSource.File -> source.path
                PythonSource.Stdin -> ""
            }

        val prelude = buildSysArgvPrelude(sysArgvScript, scriptArgs)
        val full = prelude + "\n" + program

        val client = workerClient ?: PyodideWorkerClient().also { workerClient = it }
        return try {
            withTimeout(timeoutMillis.milliseconds) {
                client.execute(full, fs, cwd, stdin, stdout, stderr)
            }
        } catch (_: TimeoutCancellationException) {
            // Worker may be wedged in a CPU loop — terminate so the
            // next invocation gets a clean instance. Pyodide load is
            // ~3s but correctness > latency on the rare timeout path.
            client.shutdown()
            workerClient = null
            stderr.writeBytes("python3: execution timed out after ${timeoutMillis}ms\n".encodeToByteArray())
            124
        } catch (e: Throwable) {
            PyodideErrorPolicy.stderrTextForError(e.message)?.let {
                stderr.writeBytes(it.encodeToByteArray())
            }
            PyodideErrorPolicy.extractSystemExitCode(e.message) ?: 1
        }
    }

    /**
     * Release any held resources. The worker (if any) is terminated; the
     * in-process REPL Pyodide instance is GC'd with the engine. Idempotent.
     */
    public fun shutdown() {
        workerClient?.shutdown()
        workerClient = null
    }

    private fun buildSysArgvPrelude(
        scriptName: String,
        args: List<String>,
    ): String {
        val parts = mutableListOf<String>()
        parts += quote(scriptName)
        args.forEach { parts += quote(it) }
        return "import sys\nsys.argv = [${parts.joinToString(", ")}]"
    }

    /**
     * Drive an interactive Python REPL backed by `pyodide.console.PyodideConsole`.
     *
     * Runs on the worker via [PyodideWorkerClient]'s session API — same
     * Pyodide instance, same live kash FS bridge, same `input()` capability
     * as `python3 -c …`. Each REPL line becomes one [PyodideWorkerClient.runInSession]
     * call; Python globals (including `_kash_console`) persist across
     * pushes because the Pyodide runtime in the worker stays alive for the
     * session.
     *
     * We can't reuse CPython's `code.interact()` directly (that's a
     * blocking loop calling `sys.stdin.readline()`, and our stdin is a
     * suspending kash channel). Instead we run the readline loop in
     * Kotlin: write the prompt, read bytes from [stdin] until `\n`, push
     * the line to `_kash_console`, inspect the returned
     * `(syntax_check, exit_code)` tuple to choose the next prompt or
     * exit. Stdin is fed to the SAB ring only during each run, so the
     * Kotlin-side prompt loop and Python's `input()` don't fight over it.
     */
    override suspend fun runInteractiveRepl(
        fs: FileSystem,
        cwd: String,
        @Suppress("UNUSED_PARAMETER") env: Map<String, String>,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int {
        if (!workerSupported) {
            stderr.writeBytes(
                (
                    "python3: REPL requires a cross-origin-isolated page " +
                        "(COOP `same-origin` + COEP `require-corp`).\n"
                ).encodeToByteArray(),
            )
            return 126
        }
        val client = workerClient ?: PyodideWorkerClient().also { workerClient = it }

        client.beginSession(fs, cwd, stdin, stdout, stderr)
        try {
            // PyodideConsole.runcode catches SystemExit internally and dumps
            // the traceback to stderr instead of letting it bubble up — so
            // `exit()` at the prompt would otherwise print a noisy asyncio
            // traceback and the REPL would keep running. Monkey-patch
            // sys.exit / builtins.exit / builtins.quit to record the
            // requested code in a module-level slot we poll after each push.
            val initSource =
                "from pyodide.console import PyodideConsole\n" +
                    "_kash_console = PyodideConsole()\n" +
                    EXIT_PATCH
            val initResult =
                try {
                    client.runInSession(initSource)
                } catch (e: Throwable) {
                    stderr.writeBytes(("python3: REPL init failed: ${e.message}\n").encodeToByteArray())
                    return 1
                }
            if (initResult.errorMessage != null) {
                stderr.writeBytes(
                    ("python3: REPL init failed: ${initResult.errorMessage}\n").encodeToByteArray(),
                )
                return 1
            }

            val banner = "Python on Pyodide — type exit() or Ctrl-D to quit\n"
            stdout.writeBytes(banner.encodeToByteArray())
            stdout.flush()

            var prompt = ">>> "
            while (true) {
                stdout.writeBytes(prompt.encodeToByteArray())
                stdout.flush()

                val line =
                    readOneLine(stdin) ?: run {
                        stdout.writeBytes("\n".encodeToByteArray())
                        return 0
                    }

                val trimmed = line.trim()
                if (trimmed == "exit" || trimmed == "quit" ||
                    trimmed == "exit()" || trimmed == "quit()"
                ) {
                    return 0
                }

                // Per push: feed line to PyodideConsole, await its
                // ConsoleFuture, return (syntax_check, _kash_repl_exit_code)
                // as the script's final expression so we read the tuple off
                // [SessionRunResult.resultRepr].
                val pushCode = (
                    "_kash_repl_fut = _kash_console.push(" + quote(line) + ")\n" +
                        "await _kash_repl_fut\n" +
                        "(_kash_repl_fut.syntax_check, _kash_repl_exit_code)"
                )

                val result =
                    try {
                        client.runInSession(pushCode)
                    } catch (e: Throwable) {
                        stderr.writeBytes(("python3: REPL error: ${e.message}\n").encodeToByteArray())
                        continue
                    }

                // SystemExit caught at the worker (not via the slot path).
                if (result.errorMessage != null) {
                    val sysExit = extractSystemExitCode(result.errorMessage)
                    if (sysExit != null) return sysExit
                    // Real error already piped to stderr by the worker's
                    // stderrTextForError path; just keep looping.
                }

                val (status, exitCode) = parseStatusTuple(result.resultRepr)
                if (exitCode != null) {
                    // Reset the slot so a future session can re-enter cleanly.
                    try {
                        client.runInSession("_kash_repl_exit_code = None")
                    } catch (_: Throwable) {
                    }
                    return exitCode
                }

                prompt = if (status == "incomplete") "... " else ">>> "
            }
        } finally {
            client.endSession()
        }
    }

    /**
     * Read one line from [stdin] (bytes up to `\n` exclusive). Returns
     * null on EOF before any bytes arrive.
     *
     * Bytes are accumulated raw and decoded as UTF-8 once, at the line
     * boundary — NOT per byte. Decoding each byte as a char (`b.toChar()`)
     * would Latin-1-mangle any multibyte input: `é` (0xC3 0xA9) becomes two
     * chars, every CJK char becomes three, and the REPL pushes mojibake into
     * Python. kash is UTF-8 internally and CJK REPL input is a supported case.
     */
    private suspend fun readOneLine(stdin: SuspendSource): String? {
        val line = kotlinx.io.Buffer()
        val tmp = kotlinx.io.Buffer()
        var sawAny = false
        while (true) {
            val n = stdin.readAtMostTo(tmp, 1)
            if (n < 0L) return if (!sawAny) null else line.readByteArray().decodeToString()
            if (n == 0L) continue
            sawAny = true
            val b = tmp.readByte()
            if (b.toInt() == 0x0A) return line.readByteArray().decodeToString() // LF
            line.writeByte(b)
        }
    }

    public companion object {
        /** Quote a string for inclusion in a Python source literal. */
        internal fun quote(s: String): String =
            "\"" +
                s
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"") +
                "\""

        /**
         * Pyodide formats `SystemExit` traceback messages as `SystemExit: N`.
         * Parse the integer so an explicit `sys.exit(N)` is reflected in the
         * kash exit code instead of being lumped into the generic-uncaught
         * exit `1`. Delegates to [PyodideErrorPolicy] so the worker path and
         * the REPL path agree on the rule.
         */
        internal fun extractSystemExitCode(msg: String?): Int? = PyodideErrorPolicy.extractSystemExitCode(msg)

        /**
         * Python source executed once at REPL boot. Overrides
         * `sys.exit`, `builtins.exit`, and `builtins.quit` so calling
         * any of them sets a module-level `_kash_repl_exit_code` to the
         * requested exit code, and still raises `SystemExit` (matching
         * CPython behavior). PyodideConsole.runcode catches the
         * SystemExit and prints a traceback; our post-push polling
         * reads `_kash_repl_exit_code` to detect it and shut down
         * cleanly instead.
         */
        internal val EXIT_PATCH: String =
            """
            import sys as _kash_sys, builtins as _kash_builtins
            _kash_repl_exit_code = None
            def _kash_repl_record_exit(code=0):
                global _kash_repl_exit_code
                _kash_repl_exit_code = code if isinstance(code, int) else 0
                raise SystemExit(_kash_repl_exit_code)
            _kash_builtins.exit = _kash_repl_record_exit
            _kash_builtins.quit = _kash_repl_record_exit
            _kash_sys.exit = _kash_repl_record_exit
            """.trimIndent()

        /**
         * Parse the tuple repr Python writes for `(syntax_check, exit_code)`.
         * Returns (statusStr, exitCodeOrNull).
         */
        internal fun parseStatusTuple(raw: String): Pair<String, Int?> {
            val s = raw.trim().removePrefix("(").removeSuffix(")")
            val parts = s.split(",").map { it.trim() }
            val status =
                parts.getOrNull(0)?.removePrefix("'")?.removeSuffix("'")
                    ?: "complete"
            val codeStr = parts.getOrNull(1) ?: "None"
            val exitCode = if (codeStr == "None") null else codeStr.toIntOrNull()
            return status to exitCode
        }
    }
}
