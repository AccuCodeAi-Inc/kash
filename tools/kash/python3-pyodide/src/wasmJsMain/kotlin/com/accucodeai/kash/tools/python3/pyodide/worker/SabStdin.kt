@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.python3.pyodide.worker

/**
 * Main-thread side of the SAB-backed stdin pipe. Allocates the
 * SharedArrayBuffer (control header + ring data), exposes `write` / `eof`
 * helpers that the kash main thread calls as it pumps the user's
 * [com.accucodeai.kash.api.io.SuspendSource]. The matching consumer lives
 * inside `pyodide-worker.js` and uses `Atomics.wait` to block.
 *
 * The buffer is shared by reference with the worker — we postMessage the
 * [sab] over to the worker once at boot. Both sides hold typed-array views
 * over the same memory.
 *
 * **Important — capacity vs flow control.** This ring is small (64 KB
 * default). If the user types faster than Python reads (effectively never
 * happens at human speed), [write] will partial-write and the producer pump
 * loop just retries on the next tick. We deliberately do NOT block the
 * producer with `Atomics.wait` — that would risk reentrancy if the producer
 * happens to be the worker too. The main thread never blocks; only the
 * Pyodide worker does.
 */
internal class SabStdin(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        StdinRingMath.requirePowerOfTwo(capacity)
    }

    /** Total SAB size: control header + ring bytes. */
    private val totalBytes: Int = StdinRingMath.CONTROL_BYTES + capacity

    /**
     * The SharedArrayBuffer transferred to the worker on init. Held as a
     * `JsAny` because we don't have a Kotlin/Wasm external for the SAB
     * constructor result.
     */
    val sab: JsAny = newSharedArrayBuffer(totalBytes)

    /** Int32 view onto the control header for atomic ops on head/tail/eof. */
    private val ctrl: JsAny = newInt32View(sab, 0, StdinRingMath.CONTROL_SLOTS)

    /** Uint8 view onto the ring data region for plain byte stores. */
    private val data: JsAny = newUint8View(sab, StdinRingMath.CONTROL_BYTES, capacity)

    /** Capacity in bytes — useful for tests and worker handshake. */
    val capacityBytes: Int get() = capacity

    /**
     * Write up to [bytes].size bytes. Returns the number actually written
     * (could be less if the ring is nearly full). After every successful
     * write we [Atomics.notify] the head slot so a worker blocked in
     * `Atomics.wait` wakes immediately.
     *
     * Returns 0 only if the ring is genuinely full. Caller's pump loop
     * should yield and retry rather than spinning.
     */
    fun write(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val head = atomicLoad(ctrl, StdinRingMath.SLOT_HEAD)
        val tail = atomicLoad(ctrl, StdinRingMath.SLOT_TAIL)
        var off = 0
        var newHead = head
        while (off < bytes.size) {
            val region = StdinRingMath.planWrite(capacity, newHead, tail, bytes.size - off)
            if (region.length == 0) break
            // Copy bytes one at a time. wasmJs has no bulk-copy primitive
            // to a Uint8Array view, but the inner loop is short (≤ region
            // length) and bytes flow in human-typed chunks anyway.
            for (i in 0 until region.length) {
                uint8Set(data, region.offset + i, bytes[off + i].toInt() and 0xFF)
            }
            newHead = StdinRingMath.advance(newHead, region.length)
            off += region.length
        }
        if (newHead != head) {
            // Publish the new head atomically AFTER the byte stores are visible
            // (SAB stores are sequentially consistent in JS, so the atomic store
            // serves as the release fence).
            atomicStore(ctrl, StdinRingMath.SLOT_HEAD, newHead)
            atomicNotify(ctrl, StdinRingMath.SLOT_HEAD, count = 1)
        }
        return off
    }

    /**
     * Signal end-of-input. The worker's Pyodide stdin callback observes
     * this on its next read attempt and returns 0 (EOF) to Python.
     * Idempotent.
     */
    fun signalEof() {
        atomicStore(ctrl, StdinRingMath.SLOT_EOF, 1)
        atomicNotify(ctrl, StdinRingMath.SLOT_HEAD, count = 1)
    }

    /** Reset cursors + EOF flag between consecutive `run` invocations. */
    fun reset() {
        atomicStore(ctrl, StdinRingMath.SLOT_HEAD, 0)
        atomicStore(ctrl, StdinRingMath.SLOT_TAIL, 0)
        atomicStore(ctrl, StdinRingMath.SLOT_EOF, 0)
    }

    companion object {
        /** 64 KB ring — far larger than any reasonable typed input burst. */
        const val DEFAULT_CAPACITY: Int = 64 * 1024
    }
}

/**
 * Pyodide's `setInterruptBuffer` expects an `Int32Array(1)` over a
 * SharedArrayBuffer. Storing the signal number (2 for SIGINT) makes Python
 * raise `KeyboardInterrupt` at the next bytecode boundary.
 */
internal class SabInterrupt {
    val sab: JsAny = newSharedArrayBuffer(4)
    private val view: JsAny = newInt32View(sab, 0, 1)

    /** Raise SIGINT (2) inside the worker's Python interpreter. */
    fun interrupt() {
        atomicStore(view, 0, SIGINT)
    }

    /** Clear any pending interrupt. Called on each new run boundary. */
    fun clear() {
        atomicStore(view, 0, 0)
    }

    companion object {
        const val SIGINT: Int = 2
    }
}

// ----- JS interop primitives -----
//
// Kotlin/Wasm doesn't ship typed externals for SharedArrayBuffer/Atomics, so
// we wrap each call in a tiny `js(...)` literal. Each function is a single
// expression so the wasm-side imports closure stays minimal.

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

private fun uint8Set(
    view: JsAny,
    index: Int,
    value: Int,
): Unit = js("{ view[index] = value; }")
