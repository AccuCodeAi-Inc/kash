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
import com.accucodeai.kash.tools.python3.pyodide.worker.PyodideWorkerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pyodide-backed [PythonEngine] for the wasmJs target.
 *
 * Architectural mirror of `GraalPyEngine` from `:tools:kash:python3-graalpy`:
 *  - argv parsing lives in `Python3Command` and is reused unchanged
 *  - stdin / stdout / stderr are kash's `SuspendSource` / `SuspendSink`
 *  - filesystem access is routed through a [KashEmscriptenFs] mount so
 *    `open()` inside Python lands in kash's [FileSystem]
 *  - timeout enforced via coroutine `withTimeout`; on expiry we drop the
 *    cached runtime so the next invocation starts fresh (Pyodide has no
 *    hard interrupt that races synchronous Python without setInterruptBuffer
 *    polling — left as a future improvement)
 *
 * Differences from GraalPy:
 *  - The Pyodide runtime is shared across invocations (loading it costs
 *    ~10 MB / a few seconds; we won't pay that per `python3` call). User
 *    code still runs in a fresh `globals()` per invocation because
 *    `runPythonAsync` evaluates each program in its own scope when the
 *    script body doesn't bind module-level names back to `pyodide.globals`.
 *  - There is no sandbox knob — the wasm + browser tab is the sandbox.
 *    `SandboxPolicy.SAFE` short-circuits the same way (return 126).
 */
public class PyodideEngine : PythonEngine {
    override val name: String = "Pyodide"

    /**
     * Cached Pyodide handle, populated on first `execute` and reused.
     */
    private var cached: PyodideAPI? = null

    /**
     * Tracks the currently-mounted KashEmscriptenFs handle (if any). Pyodide's
     * FS only supports one mount per path, so we mount kash at [MOUNT_POINT]
     * lazily on first use and re-bind the underlying [FileSystem] per
     * invocation by mutating the handle's `fs` property — cheaper than
     * unmount + remount.
     */
    private var fsBridge: KashEmscriptenFs? = null

    /**
     * Worker-backed backend, lazily created when the hosting page is
     * cross-origin-isolated (COOP/COEP set). When non-null, [execute] routes
     * through this so the worker's `Atomics.wait`-based stdin can serve
     * `input()`. When null, falls back to the in-process path which has the
     * known always-EOF stdin limitation documented at [bindStreams].
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

        // Worker path: real stdin via SAB+Atomics. Required for any script
        // that calls input() / reads sys.stdin. Selected when the hosting
        // page is cross-origin-isolated.
        if (workerSupported) {
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
                val msg = e.message
                if (!msg.isNullOrBlank()) stderr.writeBytes((msg + "\n").encodeToByteArray())
                extractSystemExitCode(msg) ?: 1
            }
        }

        // In-process fallback: page lacks COOP/COEP or browser doesn't
        // support SharedArrayBuffer. Stdin is always-EOF — interactive
        // scripts will hit EOFError. Functional for non-interactive use.
        val pyodide = ensureLoaded()
        bindFileSystem(pyodide, fs, cwd)
        bindStreams(pyodide, stdin, stdout, stderr)

        return try {
            withTimeout(timeoutMillis.milliseconds) {
                pyodide.runPythonAsync(full.toJsString()).await<JsAny?>()
            }
            0
        } catch (_: TimeoutCancellationException) {
            cached = null
            fsBridge = null
            stderr.writeBytes("python3: execution timed out after ${timeoutMillis}ms\n".encodeToByteArray())
            124
        } catch (e: Throwable) {
            val msg = e.message
            if (!msg.isNullOrBlank()) {
                stderr.writeBytes((msg + "\n").encodeToByteArray())
            }
            extractSystemExitCode(msg) ?: 1
        }
    }

    /**
     * Release any held resources. The worker (if any) is terminated; the
     * in-process Pyodide instance is GC'd with the engine. Idempotent.
     */
    public fun shutdown() {
        workerClient?.shutdown()
        workerClient = null
        cached = null
        fsBridge = null
    }

    private suspend fun ensureLoaded(): PyodideAPI {
        cached?.let { return it }
        // Use the relative `./pyodide/` path the gradle bundle task populates.
        // The path is served by the same web server as our js bundle so it
        // works offline and avoids CDN cross-origin trickery.
        val api = loadPyodideWithIndexUrl("./pyodide/").await<PyodideAPI>()
        cached = api
        return api
    }

    private suspend fun bindFileSystem(
        api: PyodideAPI,
        fs: FileSystem,
        cwd: String,
    ) {
        val bridge = fsBridge ?: KashEmscriptenFs.mount(api, fs, cwd).also { fsBridge = it }
        bridge.rebind(fs, cwd)
        // chdir into the kash mount so relative paths in Python land where
        // the shell expects.
        api.FS.chdir((MOUNT_POINT + Paths.resolve("/", cwd)).toJsString())
    }

    private fun bindStreams(
        api: PyodideAPI,
        @Suppress("UNUSED_PARAMETER") stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ) {
        // V1 stdin: always EOF. Pyodide's stdin callback is synchronous and
        // can't suspend on a kash SuspendSource. Phase 3 will add an event-
        // driven feed via SharedArrayBuffer for the interactive REPL.
        api.setStdin(makeEofStdinOptions())

        val stdoutHandler: (JsString) -> Unit = { line ->
            launchAndForget { stdout.writeBytes((line.toString() + "\n").encodeToByteArray()) }
        }
        val stderrHandler: (JsString) -> Unit = { line ->
            launchAndForget { stderr.writeBytes((line.toString() + "\n").encodeToByteArray()) }
        }
        api.setStdout(makeBatchedSinkOptions(stdoutHandler))
        api.setStderr(makeBatchedSinkOptions(stderrHandler))
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
     * We can't reuse CPython's `code.interact()` directly (that's a
     * blocking loop calling `sys.stdin.readline()`, and our stdin is a
     * suspending kash channel). Instead we run the readline loop in
     * Kotlin: write the prompt, read bytes from [stdin] until `\n`,
     * push the line to a `PyodideConsole` instance, inspect the
     * returned status (`complete` / `incomplete` / `syntax-error`) to
     * choose the next prompt. PyodideConsole takes care of:
     *   - statement-boundary detection (so multi-line `def`/`class`/
     *     `if` blocks work)
     *   - printing `repr(value)` for bare expressions
     *   - routing print/exception output through the stdout/stderr
     *     handlers we wired in [bindStreams]
     */
    override suspend fun runInteractiveRepl(
        fs: FileSystem,
        cwd: String,
        @Suppress("UNUSED_PARAMETER") env: Map<String, String>,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int {
        val pyodide =
            try {
                ensureLoaded()
            } catch (e: Throwable) {
                stderr.writeBytes(("python3: failed to load Pyodide: ${e.message}\n").encodeToByteArray())
                return 1
            }
        bindFileSystem(pyodide, fs, cwd)
        bindStreams(pyodide, stdin, stdout, stderr)

        // Fresh console per REPL session. Using runPythonAsync (not
        // runPython) wraps the import + construction in Pyodide's
        // top-level-await trampoline, which surfaces JS-side throws as
        // Kotlin/Wasm Throwables we can catch normally.
        try {
            pyodide
                .runPythonAsync(
                    "from pyodide.console import PyodideConsole".toJsString(),
                ).await<JsAny?>()
            pyodide
                .runPythonAsync(
                    "_kash_console = PyodideConsole()".toJsString(),
                ).await<JsAny?>()
            // PyodideConsole.runcode catches SystemExit internally and
            // dumps the traceback to stderr instead of letting it
            // bubble up — so `exit()` at the prompt prints a noisy
            // asyncio traceback and the REPL keeps running. Monkey-
            // patch sys.exit / builtins.exit / builtins.quit to record
            // the requested code in a module-level slot we can poll
            // after each push, so we exit cleanly with the right code.
            pyodide
                .runPythonAsync(EXIT_PATCH.toJsString())
                .await<JsAny?>()
        } catch (e: Throwable) {
            stderr.writeBytes(("python3: REPL init failed: ${e.message}\n").encodeToByteArray())
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

            // Fast-path bare `exit` / `quit` / `exit()` / `quit()`.
            // CPython's `exit` is a sitebuiltins.Quitter that calls
            // sys.exit() with no useful repr if you forget the `()`.
            // We match that UX: any of these forms exits cleanly with
            // code 0, no traceback.
            val trimmed = line.trim()
            if (trimmed == "exit" || trimmed == "quit" ||
                trimmed == "exit()" || trimmed == "quit()"
            ) {
                return 0
            }

            // PyodideConsole.push returns a `ConsoleFuture` (extends
            // asyncio.Future). The `syntax_check` attribute on the
            // future itself tells us whether the source was complete /
            // incomplete / a syntax error — the *awaited result* is
            // whatever the executed code produced (usually None for a
            // statement like `print(...)`), which is not what we want.
            // We await the future to let `runcode` finish (and to drive
            // top-level `await` inside user code), then read
            // `.syntax_check` off the future itself.
            //
            // Final expression reads the exit-slot we installed in
            // EXIT_PATCH; if non-None, the line called sys.exit(N).
            // We return that as a tuple along with syntax_check.
            val pushCode = (
                "_kash_repl_fut = _kash_console.push(" + quote(line) + ")\n" +
                    "await _kash_repl_fut\n" +
                    "(_kash_repl_fut.syntax_check, _kash_repl_exit_code)"
            )

            val rawText =
                try {
                    val raw = pyodide.runPythonAsync(pushCode.toJsString()).await<JsAny?>()
                    raw?.toString() ?: ""
                } catch (e: Throwable) {
                    // Belt and suspenders: in case Pyodide ever does
                    // bubble SystemExit (older versions did), still
                    // catch and parse it.
                    val msg = e.message ?: ""
                    if (msg.contains("SystemExit")) {
                        val code = extractSystemExitCode(msg) ?: 0
                        return code
                    }
                    stderr.writeBytes(("python3: REPL error: $msg\n").encodeToByteArray())
                    ""
                }

            // Parse the tuple Python produced. Two cases:
            //  - "('complete', None)" / "('incomplete', None)" — keep
            //    looping with the right prompt
            //  - "('complete', 0)" — sys.exit(N) was called; exit kash
            //    cleanly with that code
            val (status, exitCode) = parseStatusTuple(rawText)
            if (exitCode != null) {
                // Reset the slot in case the REPL is re-entered later
                // in the same Pyodide instance (we don't today, but
                // hygiene).
                try {
                    pyodide.runPythonAsync("_kash_repl_exit_code = None".toJsString()).await<JsAny?>()
                } catch (_: Throwable) {
                }
                return exitCode
            }

            prompt = if (status == "incomplete") "... " else ">>> "
        }
    }

    /**
     * Read one line from [stdin] (bytes up to `\n` exclusive). Returns
     * null on EOF before any bytes arrive. The cooked-mode line
     * discipline in [com.accucodeai.kash.ui.ComposeTerminal] guarantees
     * bytes arrive in line-sized chunks already, so this is mostly a
     * pass-through.
     */
    private suspend fun readOneLine(stdin: SuspendSource): String? {
        val sb = StringBuilder()
        val tmp = kotlinx.io.Buffer()
        while (true) {
            val n = stdin.readAtMostTo(tmp, 1)
            if (n < 0L) return if (sb.isEmpty()) null else sb.toString()
            if (n == 0L) continue
            val b = tmp.readByte().toInt() and 0xFF
            if (b == 0x0A) return sb.toString() // LF
            sb.append(b.toChar())
        }
    }

    public companion object {
        /** Virtual mount point under which kash's [FileSystem] appears inside Pyodide. */
        public const val MOUNT_POINT: String = "/kash"

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
         * exit `1`.
         */
        internal fun extractSystemExitCode(msg: String?): Int? {
            if (msg == null) return null
            val marker = "SystemExit: "
            val i = msg.lastIndexOf(marker)
            if (i < 0) return null
            val rest = msg.substring(i + marker.length).trimEnd()
            return rest.toIntOrNull()
        }

        /**
         * Python source executed once at REPL boot. Overrides
         * `sys.exit`, `builtins.exit`, and `builtins.quit` so that
         * calling any of them sets a module-level `_kash_repl_exit_code`
         * to the requested exit code, and still raises `SystemExit`
         * (matching CPython behavior). PyodideConsole.runcode catches
         * the SystemExit and prints a traceback — but our post-push
         * polling reads `_kash_repl_exit_code` to detect it and shut
         * the REPL down cleanly instead.
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
         * Cheap regex-free split — Python prints tuples deterministically
         * as `('complete', None)` etc. Returns (statusStr, exitCodeOrNull).
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

/**
 * Construct `{ batched: handler, isatty: false }` from JS, capturing
 * [handler] by closure. Defined out-of-line because Kotlin/Wasm's `js("...")`
 * captures locals by name and we want a single named lambda parameter.
 */
private fun makeBatchedSinkOptions(handler: (JsString) -> Unit): JsAny =
    js("({ batched: function (s) { handler(s); }, isatty: false })")

/** Build `{ stdin: () => null, isatty: false }` — always-EOF stdin handler. */
private fun makeEofStdinOptions(): JsAny = js("({ stdin: function () { return null; }, isatty: false })")

/**
 * Fire-and-forget helper for Pyodide's *synchronous* JS callbacks. The
 * callback can't suspend on a kash SuspendSink, so we launch the write on a
 * global scope. Order is preserved per-sink because each call appends to the
 * same underlying Buffer sequentially via the same dispatcher.
 *
 * GlobalScope is acceptable here: the work is bounded (one write per
 * callback), has no parent to cancel, and the wasmJs runtime is
 * single-threaded so we can't introduce real concurrency.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun launchAndForget(block: suspend () -> Unit) {
    @Suppress("OPT_IN_USAGE")
    GlobalScope.launch(Dispatchers.Default) { block() }
}

@Suppress("UNUSED")
private fun CoroutineScope.unused() {} // satisfy unused-import lint if any
