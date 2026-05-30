@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.python3.pyodide.worker

import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.NotADirectory
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.tools.python3.pyodide.worker.SabFsProtocol.Op
import com.accucodeai.kash.tools.python3.pyodide.worker.SabFsProtocol.Open.O_APPEND
import com.accucodeai.kash.tools.python3.pyodide.worker.SabFsProtocol.Open.O_CREAT
import com.accucodeai.kash.tools.python3.pyodide.worker.SabFsProtocol.Open.O_RDWR
import com.accucodeai.kash.tools.python3.pyodide.worker.SabFsProtocol.Open.O_TRUNC
import com.accucodeai.kash.tools.python3.pyodide.worker.SabFsProtocol.Open.O_WRONLY
import com.accucodeai.kash.tools.python3.pyodide.worker.SabFsProtocol.Stat
import com.accucodeai.kash.tools.python3.pyodide.worker.SabFsProtocol.Status
import com.accucodeai.kash.tools.python3.pyodide.worker.SabFsProtocol.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Main-thread side of the FS-RPC bridge.
 *
 * Owns the two SharedArrayBuffers ([controlSab] / [dataSab]) and a fd-table.
 * The Pyodide worker, holding the same SAB references, blocks in
 * `Atomics.wait` inside its synchronous Emscripten FS-plugin callbacks; this
 * server reads each request off a [Channel] (fed by the worker's `fs-rpc`
 * port message), dispatches to the kash [FileSystem], encodes the response
 * into the SABs, and `Atomics.notify`s the worker to wake.
 *
 * **Why the main thread can't `Atomics.wait`:** browsers throw
 * `TypeError: Atomics.wait cannot be called on the main thread` to keep the
 * UI responsive. So the server runs as a normal coroutine on the main JS
 * thread (driven by Dispatchers.Default / Main), reads its wake-ups from a
 * Kotlin Channel populated by an `onmessage` callback, and performs all
 * actual filesystem work via the suspending [FileSystem] surface. Nothing
 * blocks here; only the worker blocks.
 *
 * **Fd table:** per-fd state lives in [openFiles]. v1 buffers the full file
 * contents on OPEN — a `ReadBuffer` for read-mode fds, a growable
 * `WriteBuffer` for write-mode fds, flushed back to `fs.writeBytes` on
 * CLOSE. This handles the agent's typical workloads (Python sources, JSON,
 * CSV) without streaming machinery.
 *
 * **We deliberately don't stream, and probably never will.** A streaming
 * implementation would only pay off for GB-scale files — but those aren't a
 * realistic browser workload in the first place: the whole runtime (Pyodide
 * heap + this fd buffer + the page) lives in a single tab's address space,
 * so reading a file that large OOMs the tab no matter how we shuttle the
 * bytes. The in-memory buffer isn't the bottleneck there; the platform is.
 * So whole-file buffering is treated as the permanent design, not a v1
 * placeholder. If a hard cap is ever wanted, guard [opOpen]'s `fs.readBytes`
 * and fail with EIO rather than letting a giant read wedge the tab.
 *
 * **Lifecycle:** [start] launches the dispatcher coroutine in [scope]; the
 * same scope is cancelled on [stop]. The worker reboot path (see
 * [PyodideWorkerClient.shutdown]) drops the server too — the SABs and fd
 * table go with it.
 */
internal class SabFsServer(
    private val fs: FileSystem,
    /** Coroutine scope owned by the worker client; we don't make our own. */
    private val scope: CoroutineScope,
    /** Data SAB capacity, in bytes — matches what the worker maps. */
    private val dataCapacity: Int = SabFsProtocol.DEFAULT_DATA_BYTES,
) {
    /** Control SAB shared with the worker. Allocated here, passed via init. */
    val controlSab: JsAny = newSharedArrayBuffer(SabFsProtocol.CONTROL_BYTES)
    private val controlView: JsAny = newInt32View(controlSab, 0, SabFsProtocol.CONTROL_SLOTS)

    /** Data SAB shared with the worker. */
    val dataSab: JsAny = newSharedArrayBuffer(dataCapacity)
    private val dataView: JsAny = newUint8View(dataSab, 0, dataCapacity)

    /** Capacity of the data SAB the worker should map. */
    val dataCapacityBytes: Int get() = dataCapacity

    /** Pending RPC kicks from the worker. Each value is the request seqno. */
    private val pending: Channel<Int> = Channel(capacity = Channel.UNLIMITED)

    private var dispatcher: Job? = null

    /** Last-seen request seqno; bumped per loop iteration so we drop stale port pings. */
    private var lastReqSeq: Int = 0

    /** Next fd to vend. Starts at 100 to avoid colliding with Emscripten's own fds. */
    private var nextFd: Int = 100
    private val openFiles: HashMap<Int, FdEntry> = HashMap()

    /** Tell the server a `fs-rpc` notify arrived. Cheap — just enqueues the seqno. */
    fun notify() {
        // The actual seqno lives in the control SAB; we drop the slot value
        // into the channel as a wake hint. Loop reads the real seqno itself.
        pending.trySend(0)
    }

    /** Launch the dispatcher loop. Idempotent. */
    fun start() {
        if (dispatcher != null) return
        dispatcher =
            scope.launch {
                for (ignored in pending) {
                    // Drain every pending request — worker may have notified
                    // us multiple times; each `Atomics.load` gives us the live
                    // sequence number, and we serve every new seqno we see.
                    while (true) {
                        val seq = atomicLoad(controlView, SabFsProtocol.SLOT_REQ_SEQ)
                        if (seq == lastReqSeq) break
                        lastReqSeq = seq
                        serveOne()
                        // After serving, publish RESP_SEQ = seq and wake the worker.
                        atomicStore(controlView, SabFsProtocol.SLOT_RESP_SEQ, seq)
                        atomicNotify(controlView, SabFsProtocol.SLOT_RESP_SEQ, count = 1)
                    }
                }
            }
    }

    /**
     * Stop the dispatcher and flush any unclosed write-mode fds back to
     * kash's FileSystem.
     *
     * Suspending because the flush MUST complete before we return —
     * otherwise the next kash shell command can read the file before
     * the bytes land. (Reported symptom: `python3 -c "open(p,'w').write('x')"`
     * leaves an empty file because Python's GC doesn't always close the
     * fd inside the `runPythonAsync` window, so the session-end flush
     * is the only thing that materializes the bytes. The previous
     * `scope.launch { flushWrite(it) }` fire-and-forget returned
     * before the launched coroutine ran — kash printed the next prompt
     * and `cat` read the still-empty file.)
     *
     * Idempotent.
     */
    suspend fun stop() {
        dispatcher?.cancel()
        dispatcher = null
        pending.close()
        // Snapshot + clear first so any concurrent op can't see the
        // entries we're about to flush.
        val toFlush =
            buildList<FdEntry.Write> {
                for ((_, entry) in openFiles) {
                    if (entry is FdEntry.Write) add(entry)
                }
            }
        openFiles.clear()
        for (entry in toFlush) flushWrite(entry)
    }

    // ---- request dispatch ---------------------------------------------------

    private suspend fun serveOne() {
        val op = atomicLoad(controlView, SabFsProtocol.SLOT_OP)
        try {
            when (op) {
                Op.STAT -> opStat()
                Op.LIST -> opList()
                Op.OPEN -> opOpen()
                Op.READ -> opRead()
                Op.WRITE -> opWrite()
                Op.CLOSE -> opClose()
                Op.MKDIR -> opMkdir()
                Op.RMDIR -> opRmdir()
                Op.UNLINK -> opUnlink()
                Op.RENAME -> opRename()
                else -> replyStatus(Status.ENOSYS)
            }
        } catch (e: FileNotFound) {
            replyStatus(Status.ENOENT)
        } catch (e: NotADirectory) {
            replyStatus(Status.ENOTDIR)
        } catch (e: Throwable) {
            // Unknown failure — log to console and surface as EIO so Python
            // sees a real error rather than hanging.
            consoleError("kash-fs-server: op $op threw: ${e.message}")
            replyStatus(Status.EIO)
        }
    }

    private fun readPayloadBytes(): ByteArray {
        val n = atomicLoad(controlView, SabFsProtocol.SLOT_PAYLOAD_LEN)
        if (n <= 0) return ByteArray(0)
        if (n > dataCapacity) {
            // Worker wouldn't have sent more than we mapped, but guard anyway.
            return ByteArray(0)
        }
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = uint8Get(dataView, i).toByte()
        return out
    }

    private fun readPayloadString(): String = readPayloadBytes().decodeToString()

    /** Write a status-only response. */
    private fun replyStatus(status: Int) {
        atomicStore(controlView, SabFsProtocol.SLOT_STATUS, status)
        atomicStore(controlView, SabFsProtocol.SLOT_PAYLOAD_LEN, 0)
    }

    /** Write a status + outbound payload bytes (max [dataCapacity]). */
    private fun replyBytes(
        status: Int,
        bytes: ByteArray,
    ) {
        val n = bytes.size.coerceAtMost(dataCapacity)
        for (i in 0 until n) uint8Set(dataView, i, bytes[i].toInt() and 0xFF)
        atomicStore(controlView, SabFsProtocol.SLOT_PAYLOAD_LEN, n)
        atomicStore(controlView, SabFsProtocol.SLOT_STATUS, status)
    }

    // ---- per-op handlers ----------------------------------------------------

    private fun opStat() {
        val path = readPayloadString()

        // Check the fd table first — a file opened for write but not yet
        // closed exists "in flight" (Python's BufferedWriter holds the
        // path open, but our flush-on-close design hasn't materialized it
        // in kash FS yet). Without this, a Python script doing
        // `f = open('/x','w'); f.write('hi'); os.stat('/x')` gets a
        // spurious ENOENT — every other Python implementation, including
        // CPython, reports the file as existing (with the buffered size)
        // once `open()` returns. Synthesize stat from the GrowableBuf so
        // that contract holds.
        val openWrite =
            openFiles.values.firstOrNull { it is FdEntry.Write && it.path == path } as FdEntry.Write?
        if (openWrite != null && !fs.exists(path)) {
            writeI64Le(
                dataView,
                Stat.OFF_SIZE,
                openWrite.buf
                    .toByteArray()
                    .size
                    .toLong(),
            )
            writeI64Le(dataView, Stat.OFF_MTIME, 0L)
            writeI32Le(dataView, Stat.OFF_MODE, 0b110_100_100) // 0o644
            writeI32Le(dataView, Stat.OFF_TYPE, Type.REGULAR)
            for (i in 24 until 32) uint8Set(dataView, i, 0)
            atomicStore(controlView, SabFsProtocol.SLOT_PAYLOAD_LEN, Stat.SIZE)
            atomicStore(controlView, SabFsProtocol.SLOT_STATUS, Status.OK)
            return
        }

        if (!fs.exists(path)) {
            replyStatus(Status.ENOENT)
            return
        }
        val s = fs.stat(path)
        // Pack 32-byte stat blob, little-endian.
        writeI64Le(dataView, Stat.OFF_SIZE, s.size)
        writeI64Le(dataView, Stat.OFF_MTIME, s.mtimeEpochSeconds)
        writeI32Le(dataView, Stat.OFF_MODE, s.mode)
        val type =
            when {
                fs.isDirectory(path) -> Type.DIRECTORY
                else -> Type.REGULAR
            }
        writeI32Le(dataView, Stat.OFF_TYPE, type)
        for (i in 24 until 32) uint8Set(dataView, i, 0)
        atomicStore(controlView, SabFsProtocol.SLOT_PAYLOAD_LEN, Stat.SIZE)
        atomicStore(controlView, SabFsProtocol.SLOT_STATUS, Status.OK)
    }

    private fun opList() {
        val path = readPayloadString()
        if (!fs.exists(path)) {
            replyStatus(Status.ENOENT)
            return
        }
        if (!fs.isDirectory(path)) {
            replyStatus(Status.ENOTDIR)
            return
        }
        val names = fs.list(path)
        // NUL-separated UTF-8 (the only safe POSIX-filename delimiter).
        val sb = StringBuilder()
        var first = true
        for (n in names) {
            if (!first) sb.append(Ansi.NUL)
            sb.append(n)
            first = false
        }
        replyBytes(Status.OK, sb.toString().encodeToByteArray())
    }

    private suspend fun opOpen() {
        val flags = atomicLoad(controlView, SabFsProtocol.SLOT_ARG0)
        // mode (creation perms) in ARG1 — applied implicitly by [flushWrite]
        // via the default `writeBytes` mode. Honored only on first
        // materialization; existing files keep their mode.
        val path = readPayloadString()
        val wantWrite = (flags and (O_WRONLY or O_RDWR)) != 0
        val wantTrunc = (flags and O_TRUNC) != 0
        val wantAppend = (flags and O_APPEND) != 0
        val wantCreate = (flags and O_CREAT) != 0

        val exists = fs.exists(path)
        if (!exists && !wantCreate) {
            replyStatus(Status.ENOENT)
            return
        }
        if (exists && fs.isDirectory(path)) {
            // Linux semantics: open(dir, O_RDONLY) succeeds — the fd can
            // be stat'd / fstat'd but reads return EISDIR. Only WRITE
            // opens of a directory return EISDIR at open time.
            //
            // This matters because Python's import machinery (zipimporter
            // + FileFinder) tries to open every `sys.path` entry as a
            // file to probe for zip archives. If we EISDIR on the open,
            // some Pyodide-side handlers don't catch it and the REPL
            // bombs out trying to `import rlcompleter` from the cwd.
            // Returning a zero-byte read-only fd matches what CPython on
            // Linux does and unblocks the import probe.
            if (wantWrite) {
                replyStatus(Status.EISDIR)
                return
            }
            val fd = nextFd++
            openFiles[fd] = FdEntry.Read(path, ByteArray(0))
            atomicStore(controlView, SabFsProtocol.SLOT_ARG0, fd)
            replyStatus(Status.OK)
            return
        }

        // No touch-create here — the real write happens at flush-on-close in
        // [flushWrite], where any kash-side failure (missing parent dir,
        // denied write, read-only mount) surfaces with the actual exception
        // text instead of being flattened to EACCES at open time. For
        // O_CREAT|O_WRONLY of a missing file the entry starts dirty so an
        // empty close() still materializes the file.
        val fd = nextFd++
        val entry: FdEntry =
            if (wantWrite) {
                val initial =
                    when {
                        wantTrunc || !exists -> ByteArray(0)
                        else -> runCatching { fs.readBytes(path) }.getOrElse { ByteArray(0) }
                    }
                FdEntry.Write(
                    path = path,
                    buf = growable(initial),
                    append = wantAppend,
                    dirty = true,
                )
            } else {
                FdEntry.Read(path, fs.readBytes(path))
            }
        openFiles[fd] = entry
        atomicStore(controlView, SabFsProtocol.SLOT_ARG0, fd)
        replyStatus(Status.OK)
    }

    private fun opRead() {
        val fd = atomicLoad(controlView, SabFsProtocol.SLOT_ARG0)
        val pos = readI64Le(SabFsProtocol.SLOT_ARG1)
        val len = atomicLoad(controlView, SabFsProtocol.SLOT_ARG3)
        val entry =
            openFiles[fd] ?: run {
                replyStatus(Status.EBADF)
                return
            }
        val bytes: ByteArray =
            when (entry) {
                is FdEntry.Read -> entry.bytes
                is FdEntry.Write -> entry.buf.toByteArray()
            }
        if (pos >= bytes.size) {
            replyBytes(Status.OK, ByteArray(0))
            return
        }
        val end = (pos + len).toInt().coerceAtMost(bytes.size)
        val out = bytes.copyOfRange(pos.toInt(), end)
        replyBytes(Status.OK, out)
    }

    private fun opWrite() {
        val fd = atomicLoad(controlView, SabFsProtocol.SLOT_ARG0)
        val pos = readI64Le(SabFsProtocol.SLOT_ARG1)
        val bytes = readPayloadBytes()
        val entry =
            openFiles[fd] as? FdEntry.Write ?: run {
                replyStatus(Status.EBADF)
                return
            }
        // O_APPEND is server-authoritative: POSIX requires every write on an
        // append fd to land at the current end of file regardless of the
        // offset, and we don't want to depend on Emscripten reliably seeking
        // a *custom* FS plugin to EOF before each write. For non-append fds
        // honor the caller's position (random-access writes, seek+write).
        val writePos = if (entry.append) entry.buf.length else pos.toInt()
        entry.buf.writeAt(writePos, bytes)
        entry.dirty = true
        atomicStore(controlView, SabFsProtocol.SLOT_ARG0, bytes.size)
        replyStatus(Status.OK)
    }

    private suspend fun opClose() {
        val fd = atomicLoad(controlView, SabFsProtocol.SLOT_ARG0)
        val entry =
            openFiles.remove(fd) ?: run {
                replyStatus(Status.EBADF)
                return
            }
        val ok = if (entry is FdEntry.Write) flushWrite(entry) else true
        replyStatus(if (ok) Status.OK else Status.EIO)
    }

    /**
     * Flush the buffered contents of a write-mode fd to kash's [FileSystem].
     * Returns `true` on success, `false` on failure (which [opClose] turns
     * into EIO so Python sees the error from `close()`). The exception text
     * is logged to the browser console for diagnosis since stderr/stdout
     * routing is already torn down by the time GC-triggered close runs.
     */
    private suspend fun flushWrite(entry: FdEntry.Write): Boolean {
        if (!entry.dirty) return true
        return try {
            fs.writeBytes(entry.path, entry.buf.toByteArray())
            true
        } catch (e: Throwable) {
            consoleError("kash-fs-server: flush failed for ${entry.path}: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    private fun opMkdir() {
        // mode in ARG0; we ignore for now since FileSystem.mkdirs takes its own.
        val path = readPayloadString()
        if (fs.exists(path)) {
            replyStatus(Status.EEXIST)
            return
        }
        fs.mkdirs(path)
        replyStatus(Status.OK)
    }

    private fun opRmdir() {
        val path = readPayloadString()
        if (!fs.exists(path)) {
            replyStatus(Status.ENOENT)
            return
        }
        if (!fs.isDirectory(path)) {
            replyStatus(Status.ENOTDIR)
            return
        }
        fs.remove(path)
        replyStatus(Status.OK)
    }

    private fun opUnlink() {
        val path = readPayloadString()
        if (!fs.exists(path)) {
            replyStatus(Status.ENOENT)
            return
        }
        if (fs.isDirectory(path)) {
            replyStatus(Status.EISDIR)
            return
        }
        fs.remove(path)
        replyStatus(Status.OK)
    }

    private suspend fun opRename() {
        // Wire format: ARG0 = from-byte-length, payload = from\0to (NUL separator)
        val fromLen = atomicLoad(controlView, SabFsProtocol.SLOT_ARG0)
        val full = readPayloadBytes()
        if (fromLen <= 0 || fromLen >= full.size) {
            replyStatus(Status.EINVAL)
            return
        }
        val from = full.copyOfRange(0, fromLen).decodeToString()
        // skip the NUL separator at full[fromLen]
        val to = full.copyOfRange(fromLen + 1, full.size).decodeToString()
        fs.rename(from, to)
        replyStatus(Status.OK)
    }

    // ---- helpers -----------------------------------------------------------

    private fun readI64Le(slotLo: Int): Long {
        // We only allocate 1 slot for position; clamp to Int range. Files
        // beyond 2 GiB aren't a realistic agent-workflow concern, and the
        // data-SAB cap is already 1 MiB. If we need 64-bit later, claim
        // a second slot for the high half.
        return atomicLoad(controlView, slotLo).toLong() and 0xFFFFFFFFL
    }

    private fun writeI64Le(
        view: JsAny,
        off: Int,
        v: Long,
    ) {
        for (i in 0 until 8) uint8Set(view, off + i, ((v ushr (i * 8)).toInt()) and 0xFF)
    }

    private fun writeI32Le(
        view: JsAny,
        off: Int,
        v: Int,
    ) {
        for (i in 0 until 4) uint8Set(view, off + i, (v ushr (i * 8)) and 0xFF)
    }

    // ---- fd-table types ----------------------------------------------------

    private sealed class FdEntry {
        class Read(
            val path: String,
            val bytes: ByteArray,
        ) : FdEntry()

        class Write(
            val path: String,
            val buf: GrowableBuf,
            /** O_APPEND fd — opWrite ignores the caller offset and writes at EOF. */
            val append: Boolean,
            var dirty: Boolean = true,
        ) : FdEntry()
    }

    /** Tiny growable byte buffer that supports random-access writes. */
    private class GrowableBuf(
        initial: ByteArray = ByteArray(0),
    ) {
        private var data: ByteArray = initial
        private var size: Int = initial.size

        /** Current logical length (highest written offset), in bytes. */
        val length: Int get() = size

        fun writeAt(
            pos: Int,
            bytes: ByteArray,
        ) {
            val need = pos + bytes.size
            if (need > data.size) {
                var cap = if (data.size == 0) 64 else data.size
                while (cap < need) cap *= 2
                data = data.copyOf(cap)
            }
            for (i in bytes.indices) data[pos + i] = bytes[i]
            if (need > size) size = need
        }

        fun toByteArray(): ByteArray = data.copyOf(size)
    }

    private fun growable(initial: ByteArray): GrowableBuf = GrowableBuf(initial)
}

// ---- JS interop primitives -------------------------------------------------
//
// These mirror the helpers in SabStdin.kt. We don't share them because
// SabStdin's module is private; duplicating is cheaper than adding a public
// surface for two-line functions.

private fun newSharedArrayBuffer(byteLength: Int): JsAny = js("new SharedArrayBuffer(byteLength)")

private fun newInt32View(
    sab: JsAny,
    byteOffset: Int,
    length: Int,
): JsAny = js("new Int32Array(sab, byteOffset, length)")

private fun newUint8View(
    sab: JsAny,
    byteOffset: Int,
    length: Int,
): JsAny = js("new Uint8Array(sab, byteOffset, length)")

private fun atomicLoad(
    view: JsAny,
    index: Int,
): Int = js("Atomics.load(view, index)")

private fun atomicStore(
    view: JsAny,
    index: Int,
    value: Int,
): Unit = js("{ Atomics.store(view, index, value); }")

private fun atomicNotify(
    view: JsAny,
    index: Int,
    count: Int,
): Unit = js("{ Atomics.notify(view, index, count); }")

private fun uint8Get(
    view: JsAny,
    index: Int,
): Int = js("(view[index] | 0)")

private fun uint8Set(
    view: JsAny,
    index: Int,
    value: Int,
): Unit = js("{ view[index] = value; }")

private fun consoleError(msg: String): Unit = js("{ console.error(msg); }")
