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
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** MEMFS prefix where kash mounts via the FS plugin. Hardcoded across both sides of the wire. */
private const val KFS_MOUNT: String = "/kash"

/**
 * Outcome of one [PyodideWorkerClient.runInSession] (or [PyodideWorkerClient.execute]).
 *
 * @property exitCode 0 on success, 1 for an uncaught exception, the int from
 *   `sys.exit(N)` when extractable, 124 on timeout.
 * @property resultRepr stringified Python eval result (Pyodide's
 *   `runPythonAsync` return value). For statements this is "None"; for
 *   bare expressions or tuple-yielding scripts (REPL push), it carries
 *   the value. Empty string on error.
 * @property errorMessage uncaught Python error text (Pyodide formats as
 *   `Type: msg`), null on success.
 */
internal data class SessionRunResult(
    val exitCode: Int,
    val resultRepr: String,
    val errorMessage: String?,
)

/**
 * Main-thread side of the Pyodide-in-Worker bridge.
 *
 * One instance per kash session. The Pyodide instance lives inside a
 * dedicated Web Worker (booted lazily on first [execute]); the kash main
 * thread never blocks.
 *
 *  - **Stdin** flows through a SharedArrayBuffer ring so the worker can
 *    satisfy Pyodide's synchronous `setStdin` callback by blocking in
 *    `Atomics.wait`.
 *  - **Filesystem** is a live request-response bridge: a [SabFsServer]
 *    runs on the main thread (which can't `Atomics.wait`) and serves RPC
 *    requests issued by an Emscripten FS plugin loaded into the worker.
 *    The plugin sees Python's open/read/write/stat synchronously and
 *    blocks the worker thread on its own SAB while main does the
 *    suspending kash [FileSystem] work. Replaces the v1 snapshot model.
 *  - **Lifecycle** of stdout/stderr/run-result still goes through
 *    postMessage (worker → main).
 *
 * Per-call sequence:
 *  - First [execute]: boot worker, post init, wait for `ready`.
 *  - Per call: build a fresh [SabFsServer] bound to the call's [FileSystem],
 *    post `fs-init` with the SABs + symlink list, wait for `fs-ready`,
 *    post `chdir`, post `run`, pump stdin, wait for `run-result`.
 *  - On cancellation / timeout: signal EOF + interrupt, terminate the
 *    worker, drop the cached reference. Next execute reboots.
 *  - On [shutdown]: terminate worker. Idempotent.
 */
internal class PyodideWorkerClient(
    private val workerScriptUrl: String = "./pyodide-worker.js",
    private val indexUrl: String = "./pyodide/",
) {
    private var worker: Worker? = null
    private var stdinRing: SabStdin? = null
    private var interruptBuf: SabInterrupt? = null
    private var readyDeferred: CompletableDeferred<Unit>? = null

    /** Per-run state. Set on each [runInSession], cleared on result/cancel. */
    private var runDeferred: CompletableDeferred<SessionRunResult>? = null

    /** Per-session state — set in [beginSession], cleared in [endSession]. */
    private var fsReadyDeferred: CompletableDeferred<Unit>? = null
    private var fsServer: SabFsServer? = null
    private var currentStdout: SuspendSink? = null
    private var currentStderr: SuspendSink? = null
    private var sessionStdin: SuspendSource? = null
    private var sessionActive: Boolean = false

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
     * One-shot: equivalent to [beginSession] → [runInSession] → [endSession].
     * Returns the exit code; SystemExit-style errors are reflected in it.
     */
    suspend fun execute(
        source: String,
        fs: FileSystem,
        cwd: String,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int {
        beginSession(fs, cwd, stdin, stdout, stderr)
        try {
            return runInSession(source).exitCode
        } finally {
            endSession()
        }
    }

    /**
     * Open a Pyodide session: boot the worker, stand up the FS bridge,
     * chdir, and start the stdin pump. The session persists across
     * [runInSession] calls so Python globals + the FS bridge survive
     * — required for the REPL, where each line evaluates on the same
     * `PyodideConsole` and needs filesystem access.
     *
     * Streams are bound for the lifetime of the session; stdin is pumped
     * continuously and each run may consume from it (no `input()` →
     * the SAB just sits unread).
     *
     * Sessions are single-flight. Call [endSession] before re-entering.
     */
    suspend fun beginSession(
        fs: FileSystem,
        cwd: String,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ) {
        check(!sessionActive) { "PyodideWorkerClient: session already active; call endSession() first" }
        ensureBooted()

        currentStdout = stdout
        currentStderr = stderr
        sessionStdin = stdin
        writeChain = null

        // Stand up the FS server BEFORE fs-init so any RPC the worker
        // fires during plugin mount setup is served. Fresh server per
        // session — the fd table is scoped to one Pyodide session, so
        // a REPL session shares fds across pushes but a fresh execute()
        // gets a clean slate.
        val server = SabFsServer(fs, scope)
        fsServer = server
        server.start()

        val fsReady = CompletableDeferred<Unit>()
        fsReadyDeferred = fsReady
        worker!!.postMessage(
            fsInitMsg(
                controlSab = server.controlSab,
                dataSab = server.dataSab,
                dataCapacity = server.dataCapacityBytes,
                mount = KFS_MOUNT,
                symlinks = computeSymlinks(fs, cwd),
            ),
        )
        fsReady.await()

        // Chdir into cwd. With kash mounted at /kash + symlinks redirecting
        // `/tmp`, `/home/user`, etc. to `/kash/...`, an absolute cwd like
        // `/home/user` follows the symlink straight into the live bridge.
        worker!!.postMessage(chdirMsg(Paths.resolve("/", cwd)))

        sessionActive = true
    }

    /**
     * Run [source] inside the open session. Returns the exit code plus the
     * stringified Python result repr — the REPL uses the repr to read
     * `PyodideConsole.push`'s `(syntax_check, exit_code)` tuple.
     *
     * Spawns a stdin pump for the duration of the run only, so the caller
     * (e.g. the REPL prompt loop) can read from [stdin] between runs
     * without contending with the SAB feeder.
     *
     * Throws if called without an open session.
     */
    suspend fun runInSession(source: String): SessionRunResult {
        check(sessionActive) { "PyodideWorkerClient: no active session" }
        writeChain = null
        // Stdin SAB reset MUST happen here on main, BEFORE we launch the pump
        // and BEFORE we post the run message. The worker used to do this
        // inside onRun, but onRun runs on the worker thread and races the
        // main-side pump — when the pump finished first (small stdin, in-
        // memory Buffer source), zeroing head/tail/eof in onRun discarded
        // the entire payload and Python's input() blocked forever waiting
        // for bytes that had already been written and wiped. Doing the
        // reset here on the producer side makes both orderings safe: pump-
        // first writes head=N + eof=1 and Python drains; worker-first sees
        // head=0/tail=0 and blocks in Atomics.wait until the pump notifies.
        stdinRing?.reset()
        val deferred = CompletableDeferred<SessionRunResult>()
        runDeferred = deferred
        val pumpStdinHandle = sessionStdin
        val pumpJob =
            pumpStdinHandle?.let { src ->
                scope.launch { pumpStdin(src, stdinRing!!) }
            }
        stdinPump = pumpJob
        worker!!.postMessage(runMsg(source))
        return try {
            deferred.await()
        } finally {
            // Cancel the pump (NOT signalEof — the SAB stays valid; the
            // worker may consume more bytes on the next runInSession call
            // within the same session).
            pumpJob?.cancel()
            stdinPump = null
        }
    }

    /**
     * Close the open session: stop FS server, clear references.
     *
     * `suspend` because [SabFsServer.stop] awaits in-flight write
     * flushes — by the time this returns, every byte Python wrote
     * (whether explicitly closed or not) is visible to kash's
     * FileSystem. The next shell command can read those files and
     * see the bytes. Skipping this contract was the "Python wrote it,
     * `cat` shows empty" agent bug.
     */
    suspend fun endSession() {
        if (!sessionActive) return
        stdinPump?.cancel()
        stdinPump = null
        // Signal EOF here, on session boundary — any future session opens a
        // fresh ring via reset().
        stdinRing?.signalEof()
        stdinRing?.reset()
        fsServer?.stop()
        fsServer = null
        fsReadyDeferred = null
        currentStdout = null
        currentStderr = null
        sessionStdin = null
        runDeferred = null
        writeChain = null
        sessionActive = false
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
        // shutdown() is non-suspending (called from cancel paths / dtor
        // contexts where suspending isn't viable) and cancels [scope] below.
        // We deliberately do NOT attempt a flush here: a
        // `scope.launch { fsServer.stop() }` would be cancelled by the
        // `scope.cancel()` at the end of this method before it was ever
        // dispatched, making the flush a no-op that only LOOKS like it
        // persists bytes. On a forced shutdown the worker is torn down
        // regardless, so losing the last unflushed bytes is acceptable. The
        // clean exit path goes through endSession(), which suspends until the
        // flush completes.
        fsServer = null
        fsReadyDeferred?.cancel()
        fsReadyDeferred = null
        currentStdout = null
        currentStderr = null
        writeChain = null
        runDeferred?.cancel()
        runDeferred = null
        sessionActive = false
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

            "fs-ready" -> {
                fsReadyDeferred?.complete(Unit)
            }

            "fs-rpc" -> {
                // Worker fired a synchronous FS-plugin call and is now
                // blocked on Atomics.wait. Wake the server; it'll read the
                // request seqno + op off the control SAB and reply.
                fsServer?.notify()
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
                val errorMessage = readMsgErrorMessage(data)
                val resultRepr = readMsgResultRepr(data) ?: ""
                // Surface an uncaught Python exception's traceback. Pyodide
                // raises it to the worker's JS catch rather than echoing it
                // through setStderr, so this `errorMessage` field is the only
                // carrier — drop it and the user gets a bare non-zero exit with
                // no diagnostics. Suppression of clean `sys.exit(N)` lives in
                // PyodideErrorPolicy.
                val errText = PyodideErrorPolicy.stderrTextForError(errorMessage)
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
                    deferred?.complete(SessionRunResult(exitCode, resultRepr, errorMessage))
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
                    // Spurious empty read — give up the dispatcher and let
                    // other coroutines run, then retry. Using `yield()`
                    // (not `delay(1)`) so kotlinx-coroutines-test's virtual
                    // clock doesn't get advanced by 1 ms on every empty
                    // poll — that would fire the engine's outer
                    // `withTimeout(30s)` in microseconds of test time.
                    yield()
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
                        // Ring full — same yield-instead-of-delay reasoning
                        // as above.
                        yield()
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
): JsAny =
    js(
        "({type:'init', indexURL: indexUrl, stdinSab: stdinSab, interruptSab: interruptSab, " +
            "capacity: capacity})",
    )

internal fun runMsg(source: String): JsAny = js("({type:'run', source: source})")

internal fun chdirMsg(path: String): JsAny = js("({type:'chdir', path: path})")

/**
 * Construct the `fs-init` payload: SAB pair for the live FS bridge, mount
 * point inside MEMFS where the kash FS plugin attaches, and the curated
 * symlink list that exposes kash paths at their natural absolute locations.
 */
internal fun fsInitMsg(
    controlSab: JsAny,
    dataSab: JsAny,
    dataCapacity: Int,
    mount: String,
    symlinks: JsAny,
): JsAny =
    js(
        "({type:'fs-init', controlSab: controlSab, dataSab: dataSab, " +
            "dataCapacity: dataCapacity, mount: mount, symlinks: symlinks})",
    )

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

/** Read the optional `resultRepr` field off a `run-result`. */
private fun readMsgResultRepr(data: JsAny): String? {
    if (!jsHasNonNullProp(data, "resultRepr")) return null
    return jsReadStringProp(data, "resultRepr")
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

// ----- Symlink curation -----
//
// The Emscripten FS plugin can only mount kash at ONE point (we chose
// /kash). To make absolute paths like `/tmp/foo.py` resolve into the
// plugin, we install MEMFS-root symlinks: `/tmp -> /kash/tmp`,
// `/home/user -> /kash/home/user`, etc. The curated list below is
// computed per-execute from kash's top-level layout so paths the agent
// actually uses are reachable without snapshotting any content.

/**
 * Pyodide-owned roots we never alias — its stdlib lives here. `tmp` and
 * `home` appear here too but are handled specially below: `/tmp` is aliased
 * anyway (see the note under [computeSymlinks]) and `/home` keeps its dir
 * with only non-`pyodide` subdirs aliased.
 */
private val PYODIDE_RESERVED_TOP: Set<String> =
    setOf("lib", "usr", "proc", "dev", "tmp", "home")

// Note on /tmp: Pyodide's MEMFS does pre-create /tmp. We DO alias it
// regardless — overwriting the empty MEMFS /tmp with a symlink to
// /kash/tmp wins (Emscripten FS lets a symlink shadow an empty dir; we
// `rmdir` it first to be safe on the JS side). For /home we leave the
// dir intact and only alias subdirs that don't collide with
// `/home/pyodide`.

/**
 * Build the symlink list shipped in `fs-init`. Each entry is `{from, to}`:
 * MEMFS will see `from` (an absolute path under MEMFS root) and follow it
 * to `to` (which lives inside the kash FS plugin mount).
 *
 * Strategy:
 *  - Every top-level kash dir except `home` and the Pyodide-reserved set
 *    becomes a symlink at `/<name>`.
 *  - `home/<sub>` becomes `/home/<sub>` for each subdir except `pyodide`.
 *  - The cwd's top-level component is added explicitly if missing, so
 *    `chdir` later finds something.
 */
private suspend fun computeSymlinks(
    fs: FileSystem,
    cwd: String,
): JsAny {
    val pairs = mutableListOf<Pair<String, String>>()

    fun add(
        from: String,
        to: String,
    ) {
        // Always include `/tmp` even if Pyodide pre-created it; the JS side
        // handles the shadow.
        if (pairs.any { it.first == from }) return
        pairs += from to to
    }

    if (fs.exists("/") && fs.isDirectory("/")) {
        for (name in fs.list("/")) {
            if (name in PYODIDE_RESERVED_TOP) continue
            add("/$name", "$KFS_MOUNT/$name")
        }
        // Always alias /tmp specifically (commonly used; Pyodide pre-creates
        // an empty MEMFS /tmp that we shadow).
        if (fs.exists("/tmp") && fs.isDirectory("/tmp")) {
            add("/tmp", "$KFS_MOUNT/tmp")
        }
        // /home subdirs (except pyodide).
        if (fs.exists("/home") && fs.isDirectory("/home")) {
            for (sub in fs.list("/home")) {
                if (sub == "pyodide") continue
                add("/home/$sub", "$KFS_MOUNT/home/$sub")
            }
        }
    }

    // Make sure the cwd is reachable. If cwd is `/etc/foo/bar`, the top
    // component `/etc` needs a symlink — typically already covered by the
    // top-level walk, but cheap to assert.
    val normalized = Paths.resolve("/", cwd)
    if (normalized.length > 1) {
        val top = normalized.indexOf('/', 1).let { if (it < 0) normalized.length else it }
        val topName = normalized.substring(1, top)
        if (topName.isNotEmpty() && topName !in PYODIDE_RESERVED_TOP) {
            add("/$topName", "$KFS_MOUNT/$topName")
        }
    }

    return symlinksToJs(pairs)
}

/** Turn a list of `(from,to)` pairs into a JS array of plain objects. */
private fun symlinksToJs(pairs: List<Pair<String, String>>): JsAny {
    val arr = jsEmptyArray()
    for ((from, to) in pairs) jsArrayPushPair(arr, from, to)
    return arr
}

private fun jsEmptyArray(): JsAny = js("([])")

private fun jsArrayPushPair(
    arr: JsAny,
    from: String,
    to: String,
): Unit = js("{ arr.push({from: from, to: to}); }")
