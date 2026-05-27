@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.python3.pyodide.worker

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Main-thread side of the Pyodide-in-Worker bridge.
 *
 * One instance per kash session. The Pyodide instance lives inside a
 * dedicated Web Worker (booted lazily on first [execute]); the kash main
 * thread never blocks. Stdin flows through a SharedArrayBuffer ring so the
 * worker can satisfy Pyodide's synchronous `setStdin` callback by blocking
 * in `Atomics.wait`. Everything else (filesystem snapshot, stdout chunks,
 * lifecycle) goes through postMessage.
 *
 * Lifecycle:
 *  - First [execute]: boot worker, post init, wait for `ready`.
 *  - Per-call: snapshot kash FS into the worker's MEMFS via fs-mkdir /
 *    fs-file messages; send a `run` message; pump caller's [SuspendSource]
 *    into the SAB until the worker posts `run-result`; signal EOF; return.
 *  - On cancellation / timeout: signal EOF + interrupt, terminate the
 *    worker, drop the cached reference. Next execute reboots.
 *  - On [shutdown]: terminate worker. Idempotent.
 */
internal class PyodideWorkerClient(
    private val workerScriptUrl: String = "./pyodide-worker.js",
    private val indexUrl: String = "./pyodide/",
    private val mountPoint: String = "/kash",
) {
    private var worker: Worker? = null
    private var stdinRing: SabStdin? = null
    private var interruptBuf: SabInterrupt? = null
    private var readyDeferred: CompletableDeferred<Unit>? = null

    /** Per-call state. Set in [execute], cleared on result/cancel. */
    private var runDeferred: CompletableDeferred<Int>? = null
    private var currentStdout: SuspendSink? = null
    private var currentStderr: SuspendSink? = null

    /** Pump-job that copies kash stdin into the SAB ring. */
    private var stdinPump: Job? = null

    /**
     * Tail of the serialized output-write chain for the in-flight run. Each
     * `out`/`err` chunk (and the final captured `errorMessage`) appends a
     * launch that first joins its predecessor, so writes land on the sinks in
     * worker-emit order despite running as separate coroutines. `run-result`
     * joins this tail before completing the run, giving us a drain barrier so
     * no output is lost or reordered relative to the returned exit code.
     */
    private var writeChain: Job? = null

    /** Scope for our internal pumps; cancelled at [shutdown]. */
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True if the page can use SAB (COOP/COEP set + browser supports it). */
    val isSupported: Boolean
        get() = hasSharedArrayBufferConstructor() && isCrossOriginIsolated()

    /**
     * Run [source] in Python. Caller supplies the streams; we own the
     * worker. Returns the exit code. Throws on infrastructure failures
     * (worker boot, postMessage errors) — those should be caught by the
     * engine wrapper and surfaced as a stderr line + non-zero exit.
     */
    suspend fun execute(
        source: String,
        fs: FileSystem,
        cwd: String,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int {
        ensureBooted()

        currentStdout = stdout
        currentStderr = stderr
        writeChain = null
        val deferred = CompletableDeferred<Int>()
        runDeferred = deferred

        try {
            // 1. Snapshot kash FS into the worker's MEMFS.
            snapshotFsTo(worker!!, fs, mountPoint)
            worker!!.postMessage(chdirMsg(mountPoint + Paths.resolve("/", cwd)))

            // 2. Start the stdin pump. Lives until run-result or cancel.
            stdinPump =
                scope.launch {
                    pumpStdin(stdin, stdinRing!!)
                }

            // 3. Kick off the run.
            worker!!.postMessage(runMsg(source))

            // 4. Suspend until the worker posts {type:'run-result'}.
            val exitCode = deferred.await()
            return exitCode
        } finally {
            stdinPump?.cancel()
            stdinPump = null
            stdinRing?.signalEof()
            currentStdout = null
            currentStderr = null
            runDeferred = null
            writeChain = null
        }
    }

    /**
     * Raise SIGINT inside the worker's Python interpreter. Pyodide raises
     * KeyboardInterrupt at the next bytecode boundary.
     */
    fun interrupt() {
        interruptBuf?.interrupt()
    }

    /**
     * Force-stop the worker and drop the cached references. Next execute
     * reboots from scratch. Idempotent.
     */
    fun shutdown() {
        try {
            worker?.terminate()
        } catch (_: Throwable) {
        }
        worker = null
        stdinRing = null
        interruptBuf = null
        readyDeferred = null
        stdinPump?.cancel()
        stdinPump = null
        currentStdout = null
        currentStderr = null
        writeChain = null
        runDeferred?.cancel()
        runDeferred = null
        scope.cancel()
    }

    // ----- private -----

    private suspend fun ensureBooted() {
        if (worker != null && readyDeferred?.isCompleted == true) return
        if (worker == null) {
            val w = Worker(workerScriptUrl.toJsString())
            val ring = SabStdin()
            val interrupt = SabInterrupt()
            val ready = CompletableDeferred<Unit>()

            w.onmessage = { ev ->
                val data = ev.data
                if (data != null) handleMessage(data)
            }
            w.onerror = { err ->
                // Worker-level errors (script load failures, etc.) are fatal.
                // Fail the in-flight run + the ready handshake; the engine
                // will catch and surface to the user.
                val msg = "pyodide worker error: $err"
                readyDeferred?.completeExceptionally(RuntimeException(msg))
                runDeferred?.completeExceptionally(RuntimeException(msg))
            }

            worker = w
            stdinRing = ring
            interruptBuf = interrupt
            readyDeferred = ready

            w.postMessage(
                initMsg(
                    indexUrl = indexUrl,
                    stdinSab = ring.sab,
                    interruptSab = interrupt.sab,
                    capacity = ring.capacityBytes,
                    mountPoint = mountPoint,
                ),
            )
        }
        readyDeferred!!.await()
    }

    private fun handleMessage(data: JsAny) {
        // Unknown message types fall through silently — forwards-compat with
        // worker versions that grow new event types.
        when (readMsgType(data)) {
            "ready" -> {
                readyDeferred?.complete(Unit)
            }

            "out" -> {
                val bytes = readMsgBytes(data) ?: return
                val sink = currentStdout ?: return
                enqueueWrite(sink, bytes)
            }

            "err" -> {
                val bytes = readMsgBytes(data) ?: return
                val sink = currentStderr ?: return
                enqueueWrite(sink, bytes)
            }

            "run-result" -> {
                val exitCode = readMsgExitCode(data)
                // Surface an uncaught Python exception's traceback. Pyodide
                // raises it to the worker's JS catch rather than echoing it
                // through setStderr, so this `errorMessage` field is the only
                // carrier — drop it and the user gets a bare non-zero exit with
                // no diagnostics. Suppression of clean `sys.exit(N)` lives in
                // PyodideErrorPolicy.
                val errText = PyodideErrorPolicy.stderrTextForError(readMsgErrorMessage(data))
                val stderrSink = currentStderr
                if (errText != null && stderrSink != null) {
                    enqueueWrite(stderrSink, errText.encodeToByteArray())
                }
                // Drain barrier: only complete the run once every queued write
                // (including the error text above) has landed on its sink, so
                // the exit code never races ahead of the output.
                val tail = writeChain
                val deferred = runDeferred
                scope.launch {
                    tail?.join()
                    deferred?.complete(exitCode)
                }
            }
        }
    }

    /**
     * Append [bytes] to the serialized [writeChain] so output lands on [sink]
     * in worker-emit order. Each link joins its predecessor before writing;
     * `run-result` joins the tail as a drain barrier. Single-threaded wasmJs
     * means no lock is needed to mutate [writeChain] here.
     */
    private fun enqueueWrite(
        sink: SuspendSink,
        bytes: ByteArray,
    ) {
        val prev = writeChain
        writeChain =
            scope.launch {
                prev?.join()
                sink.writeBytes(bytes)
            }
    }

    private suspend fun pumpStdin(
        source: SuspendSource,
        ring: SabStdin,
    ) {
        val buf = Buffer()
        try {
            while (true) {
                val n = source.readAtMostTo(buf, 4096L)
                if (n < 0L) break
                if (n == 0L) {
                    // Spurious empty read — yield briefly so we don't spin.
                    delay(1)
                    continue
                }
                val bytes = buf.readByteArray()
                var written = 0
                while (written < bytes.size) {
                    val w =
                        if (written == 0) {
                            ring.write(bytes)
                        } else {
                            ring.write(bytes.copyOfRange(written, bytes.size))
                        }
                    if (w == 0) {
                        // Ring full — yield and retry.
                        delay(1)
                    } else {
                        written += w
                    }
                }
            }
        } finally {
            ring.signalEof()
        }
    }
}

// ----- JS message builders / readers -----
//
// We construct outgoing messages as plain JS object literals (untyped
// JsAny). Each builder is a one-line `js("…")` expression so the wasm-side
// import closure stays trivial.

private fun initMsg(
    indexUrl: String,
    stdinSab: JsAny,
    interruptSab: JsAny,
    capacity: Int,
    mountPoint: String,
): JsAny =
    js(
        "({type:'init', indexURL: indexUrl, stdinSab: stdinSab, interruptSab: interruptSab, " +
            "capacity: capacity, mountPoint: mountPoint})",
    )

internal fun runMsg(source: String): JsAny = js("({type:'run', source: source})")

internal fun chdirMsg(path: String): JsAny = js("({type:'chdir', path: path})")

internal fun fsMkdirMsg(path: String): JsAny = js("({type:'fs-mkdir', path: path})")

internal fun fsFileMsg(
    path: String,
    bytes: JsAny,
): JsAny = js("({type:'fs-file', path: path, bytes: bytes})")

private fun readMsgType(data: JsAny): String = jsReadStringProp(data, "type")

private fun readMsgExitCode(data: JsAny): Int = jsReadIntProp(data, "exitCode")

/**
 * Read the optional `errorMessage` field off a `run-result`. Returns null when
 * the field is absent/null/undefined (the success case) so we don't coerce it
 * to the literal string "null" via `String(...)`.
 */
private fun readMsgErrorMessage(data: JsAny): String? {
    if (!jsHasNonNullProp(data, "errorMessage")) return null
    return jsReadStringProp(data, "errorMessage")
}

private fun jsHasNonNullProp(
    obj: JsAny,
    name: String,
): Boolean = js("(obj[name] !== null && obj[name] !== undefined)")

private fun readMsgBytes(data: JsAny): ByteArray? {
    val len = jsReadByteArrayLen(data, "bytes")
    if (len < 0) return null
    val out = ByteArray(len)
    for (i in 0 until len) out[i] = jsReadByteAt(data, "bytes", i).toByte()
    return out
}

private fun jsReadStringProp(
    obj: JsAny,
    name: String,
): String = js("String(obj[name])")

private fun jsReadIntProp(
    obj: JsAny,
    name: String,
): Int = js("(obj[name] | 0)")

private fun jsReadByteArrayLen(
    obj: JsAny,
    name: String,
): Int = js("(obj[name] && obj[name].length !== undefined) ? obj[name].length : -1")

private fun jsReadByteAt(
    obj: JsAny,
    name: String,
    i: Int,
): Int = js("(obj[name][i] & 0xFF)")

// ----- FS snapshot helpers -----

/**
 * Walk [fs] and post fs-mkdir / fs-file messages to the worker so the
 * worker's MEMFS mirrors the kash tree under [mountPoint]. Matches the
 * semantics of the in-process `KashEmscriptenFs.populateRecursive` so we
 * inherit its skip-list (/dev, /proc) and survive-broken-files behavior.
 *
 * No flush-back yet — Python-side writes don't round-trip to kash. The
 * v1 KDoc on KashEmscriptenFs documents this same limitation.
 */
private suspend fun snapshotFsTo(
    worker: Worker,
    fs: FileSystem,
    mountPoint: String,
) {
    snapshotRecursive(worker, fs, "/", mountPoint)
}

private suspend fun snapshotRecursive(
    worker: Worker,
    fs: FileSystem,
    path: String,
    mountPoint: String,
) {
    if (path == "/dev" || path == "/proc") return
    if (!fs.exists(path)) return
    if (fs.isDirectory(path)) {
        worker.postMessage(fsMkdirMsg(mountPoint + Paths.normalize(path)))
        for (name in fs.list(path)) {
            val child = if (path == "/") "/$name" else "$path/$name"
            snapshotRecursive(worker, fs, child, mountPoint)
        }
    } else {
        val bytes =
            try {
                fs.readBytes(path)
            } catch (_: Throwable) {
                return
            }
        worker.postMessage(fsFileMsg(mountPoint + Paths.normalize(path), bytes.toJsUint8ArrayForWorker()))
    }
}

/**
 * Convert a Kotlin [ByteArray] to a JS `Uint8Array`. Same shape as the
 * helper in KashEmscriptenFs.kt but exposed locally so the worker module
 * doesn't pull in the in-process FS bridge.
 */
private fun ByteArray.toJsUint8ArrayForWorker(): JsAny {
    val len = this.size
    val arr = jsArrayOfSizeWorker(len)
    for (i in 0 until len) jsArraySetWorker(arr, i, this[i].toInt() and 0xff)
    return uint8ArrayFromArrayWorker(arr)
}

private fun jsArrayOfSizeWorker(n: Int): JsAny = js("(new Array(n))")

private fun jsArraySetWorker(
    arr: JsAny,
    i: Int,
    v: Int,
): Unit = js("{ arr[i] = v; }")

private fun uint8ArrayFromArrayWorker(arr: JsAny): JsAny = js("(new Uint8Array(arr))")
