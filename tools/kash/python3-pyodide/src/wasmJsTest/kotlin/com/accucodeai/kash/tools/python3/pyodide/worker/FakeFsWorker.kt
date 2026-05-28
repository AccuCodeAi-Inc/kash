@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.python3.pyodide.worker

import kotlinx.coroutines.yield

/**
 * Drives [SabFsServer]'s SAB protocol from the test side — stands in for the
 * Pyodide worker which would normally do all this from inside
 * `pyodide-worker.js`. Tests construct one of these, hand it the SABs
 * exposed by [SabFsServer], and call [request] to fire a synchronous
 * op + read back the response.
 *
 * **Why this works on the main thread:** we never call `Atomics.wait` here
 * (browsers forbid it on main). Instead, after notifying the server, we
 * yield to the coroutine dispatcher in a poll loop until the response
 * seqno catches up. The server's dispatcher runs on the same single-
 * threaded wasmJs event loop and serves the request between yields.
 */
internal class FakeFsWorker(
    private val controlSab: JsAny,
    private val dataSab: JsAny,
    private val dataCapacity: Int,
    private val notify: () -> Unit,
) {
    private val control: JsAny = newInt32View(controlSab, 0, SabFsProtocol.CONTROL_SLOTS)
    private val data: JsAny = newUint8View(dataSab, 0, dataCapacity)

    private var reqSeq: Int = 0

    /**
     * Response descriptor — what [request] returns. `payload` is the
     * data-SAB slice the server wrote, copied out so subsequent calls
     * can't mutate it under the test's feet.
     */
    data class Resp(
        val status: Int,
        val payloadLen: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )

    /**
     * Fire one RPC. Encodes [args] into ARG0..ARG3 + [payload] into the
     * data region, bumps REQ_SEQ, notifies the server, then yields until
     * RESP_SEQ catches up. Returns the response slots + a copy of the
     * data region (only the first [Resp.payloadLen] bytes are meaningful).
     */
    suspend fun request(
        op: Int,
        arg0: Int = 0,
        arg1: Int = 0,
        arg2: Int = 0,
        arg3: Int = 0,
        payload: ByteArray = ByteArray(0),
    ): Resp {
        require(payload.size <= dataCapacity) { "payload too large: ${payload.size} > $dataCapacity" }
        for (i in payload.indices) uint8Set(data, i, payload[i].toInt() and 0xFF)
        storeAtomic(control, SabFsProtocol.SLOT_OP, op)
        storeAtomic(control, SabFsProtocol.SLOT_ARG0, arg0)
        storeAtomic(control, SabFsProtocol.SLOT_ARG1, arg1)
        storeAtomic(control, SabFsProtocol.SLOT_ARG2, arg2)
        storeAtomic(control, SabFsProtocol.SLOT_ARG3, arg3)
        storeAtomic(control, SabFsProtocol.SLOT_PAYLOAD_LEN, payload.size)

        reqSeq = (reqSeq + 1) or 0
        storeAtomic(control, SabFsProtocol.SLOT_REQ_SEQ, reqSeq)
        notify()

        // Poll for the response on the same thread (no Atomics.wait on
        // main). `yield()` (not `delay(1)`) gives the server's dispatcher
        // coroutine a turn without advancing virtual time — important
        // because consumers running under kotlinx-coroutines-test's
        // `runTest` would otherwise see their `withTimeout` window
        // consumed by the poll loop.
        val deadline = currentMillis() + 5_000.0
        while (loadAtomic(control, SabFsProtocol.SLOT_RESP_SEQ) != reqSeq) {
            yield()
            if (currentMillis() > deadline) {
                error("FakeFsWorker.request: timed out waiting for op=$op seq=$reqSeq")
            }
        }

        val status = loadAtomic(control, SabFsProtocol.SLOT_STATUS)
        val payloadLen = loadAtomic(control, SabFsProtocol.SLOT_PAYLOAD_LEN)
        val outArg0 = loadAtomic(control, SabFsProtocol.SLOT_ARG0)
        val outArg1 = loadAtomic(control, SabFsProtocol.SLOT_ARG1)
        val out = ByteArray(payloadLen)
        for (i in 0 until payloadLen) out[i] = uint8Get(data, i).toByte()
        return Resp(status, payloadLen, outArg0, outArg1, out)
    }
}

// ---- JS interop primitives (duplicated from SabFsServer's private
// helpers — tests live in a separate file so the production helpers stay
// `private`). Tiny and stable enough to copy.

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

private fun loadAtomic(
    view: JsAny,
    index: Int,
): Int = js("Atomics.load(view, index)")

private fun storeAtomic(
    view: JsAny,
    index: Int,
    value: Int,
): Unit = js("{ Atomics.store(view, index, value); }")

private fun uint8Get(
    view: JsAny,
    index: Int,
): Int = js("(view[index] | 0)")

private fun uint8Set(
    view: JsAny,
    index: Int,
    value: Int,
): Unit = js("{ view[index] = value; }")

private fun currentMillis(): Double = js("performance.now()")
