@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.python3.pyodide

import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.Paths
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Bridges kash's [FileSystem] into Pyodide's Emscripten FS.
 *
 * **Architectural mirror of**
 * `tools/kash/python3-graalpy/src/jvmMain/kotlin/.../KashPolyglotFileSystem.kt`,
 * but built for the asymmetric reality of Emscripten FS plugins:
 *  - Emscripten FS plugin hooks are **synchronous** (called from C-level
 *    Python `open()`/`read()`/`write()`).
 *  - kash's `FileSystem` is **suspending** for the read/write surface
 *    (`source`/`sink` return `SuspendSource`/`SuspendSink`).
 *  - wasmJs has no thread to block on — `runBlocking` does not exist.
 *
 * V1 resolution: **snapshot-on-mount, async-flush-on-close**.
 *
 *  1. At `mount()` / `rebind()` we walk kash's FS and pre-populate Pyodide's
 *     default `MEMFS` at [PyodideEngine.MOUNT_POINT] with the current
 *     contents. Reads from Python see the snapshot.
 *  2. Writes from Python go to MEMFS in-process. On script completion we
 *     could (in a future revision) walk MEMFS and write changes back to
 *     kash's FS via `launchAndForget`.
 *
 * Known V1 limitations:
 *  - Python writes do **not** round-trip back to kash's FS yet. A future
 *    revision will add a flush-back path on engine teardown.
 *  - Concurrent kash-side modification during a Python invocation is not
 *    observed by the running Python.
 *  - Large files materialize fully in the wasm heap.
 *
 * These tradeoffs are deliberate for the "Phase 2: get Python on wasm
 * working" milestone — Phase 3 (Compose multiplexer) will drive the harder
 * bidirectional-sync requirements.
 */
internal class KashEmscriptenFs private constructor(
    private val api: PyodideAPI,
) {
    private var fs: FileSystem? = null
    private var cwd: String = "/"

    /**
     * Re-bind to a new [FileSystem] view (and [cwd]). Re-populates the
     * MEMFS mount under [PyodideEngine.MOUNT_POINT] from the new fs.
     */
    suspend fun rebind(
        fs: FileSystem,
        cwd: String,
    ) {
        this.fs = fs
        this.cwd = cwd
        populateFromKash(fs)
    }

    /**
     * Walk kash's FS and write each regular file into Pyodide's MEMFS at
     * `${MOUNT_POINT}/<path>`. Done synchronously from a coroutine context
     * — every kash FS read goes through `launchAndForget`, completing
     * before the actual Python eval begins because we kick this off from a
     * `suspend` caller (`PyodideEngine.bindFileSystem` is called inside
     * `execute`).
     *
     * Suspension semantics: writes are fire-and-forget by design. The
     * populate runs in coroutine order on the single wasmJs thread, so by
     * the time `runPythonAsync` is awaited, all writes have been queued
     * and the Emscripten FS reflects them.
     */
    private suspend fun populateFromKash(fs: FileSystem) {
        // Synchronous so any failures surface to the caller. The previous
        // version kicked this off in GlobalScope.launch, which returned
        // immediately and let `chdir` race ahead of the population —
        // when the walk later threw (e.g. on /dev/tty), chdir landed on
        // a half-empty MEMFS and threw JsException.
        populateRecursive(fs, "/")
    }

    private suspend fun populateRecursive(
        fs: FileSystem,
        path: String,
    ) {
        // Skip unreadable virtual mounts wholesale. /dev hosts device files
        // (/dev/tty, /dev/null) that legitimately throw on read; /proc is
        // per-process and shouldn't be snapshotted into Python's view.
        // Skipping these keeps the walk resilient without per-file
        // try/catch noise.
        if (path == "/dev" || path == "/proc") return
        if (!fs.exists(path)) return
        if (fs.isDirectory(path)) {
            ensureMemfsDir(MOUNT_POINT_INTERNAL + Paths.normalize(path))
            for (name in fs.list(path)) {
                val child = if (path == "/") "/$name" else "$path/$name"
                populateRecursive(fs, child)
            }
        } else {
            // Even outside /dev a file might be unreadable (broken
            // symlink, special node) — survive that without aborting the
            // whole snapshot.
            val bytes =
                try {
                    fs.readBytes(path)
                } catch (_: Throwable) {
                    return
                }
            writeMemfsFile(MOUNT_POINT_INTERNAL + Paths.normalize(path), bytes)
        }
    }

    private fun ensureMemfsDir(absInsidePyodide: String) {
        // `FS.mkdirTree` is a no-op if the directory already exists.
        api.FS.mkdirTree(absInsidePyodide.toJsString())
    }

    private fun writeMemfsFile(
        absInsidePyodide: String,
        bytes: ByteArray,
    ) {
        // Ensure parent dir exists. Splitting on `/` is fine — POSIX paths.
        val slash = absInsidePyodide.lastIndexOf('/')
        if (slash > 0) {
            ensureMemfsDir(absInsidePyodide.substring(0, slash))
        }
        api.FS.writeFile(absInsidePyodide.toJsString(), bytes.toJsUint8Array())
    }

    companion object {
        /**
         * Internal mirror of [PyodideEngine.MOUNT_POINT] — kept local so the
         * mount-point constant lives next to its sole consumer.
         */
        private const val MOUNT_POINT_INTERNAL: String = "/kash"

        /**
         * Mount kash's filesystem at [PyodideEngine.MOUNT_POINT] and return a
         * handle that can be `rebind()`'d on subsequent invocations.
         *
         * The actual Emscripten mount is MEMFS — we don't (yet) implement a
         * custom FS plugin. See the class KDoc for the rationale and v1
         * limitations.
         */
        suspend fun mount(
            api: PyodideAPI,
            fs: FileSystem,
            cwd: String,
        ): KashEmscriptenFs {
            // MEMFS is the default Pyodide FS; the directory just needs to
            // exist. We don't call `FS.mount(...)` because that would shadow
            // the default and we'd lose Pyodide's stdlib mount at `/`.
            api.FS.mkdirTree(MOUNT_POINT_INTERNAL.toJsString())
            val bridge = KashEmscriptenFs(api)
            bridge.rebind(fs, cwd)
            return bridge
        }
    }
}

/**
 * Run a suspend block on the global scope so it executes on the same wasmJs
 * dispatcher as the caller. Used by [KashEmscriptenFs] to perform kash FS
 * reads from a non-suspend code path.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun flushScope(block: suspend () -> Unit) {
    @Suppress("OPT_IN_USAGE")
    GlobalScope.launch(Dispatchers.Default) { block() }
}

/**
 * Convert a Kotlin [ByteArray] to a JS `Uint8Array`. Pyodide's
 * `FS.writeFile` accepts strings or typed arrays; we pass the typed array
 * to avoid UTF-8 round-trips for binary content.
 */
internal fun ByteArray.toJsUint8Array(): JsAny {
    // Build a JS array literal of integers, then construct a Uint8Array
    // from it. Less efficient than direct memory copy but works without
    // additional externals.
    val len = this.size
    val arr = jsArrayOfSize(len)
    for (i in 0 until len) jsArraySet(arr, i, this[i].toInt() and 0xff)
    return uint8ArrayFromArray(arr)
}

private fun jsArrayOfSize(n: Int): JsAny = js("(new Array(n))")

private fun jsArraySet(
    arr: JsAny,
    i: Int,
    v: Int,
): Unit = js("{ arr[i] = v; }")

private fun uint8ArrayFromArray(arr: JsAny): JsAny = js("(new Uint8Array(arr))")
