@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.tar

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.shared.fflate.bytesToJsString
import com.accucodeai.kash.shared.fflate.gunzipSync
import com.accucodeai.kash.shared.fflate.gzipSync
import com.accucodeai.kash.shared.fflate.jsStringToBytes
import com.accucodeai.kash.shared.fflate.toU8
import com.accucodeai.kash.shared.fflate.u8ToJsString
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

// Browser-side tar -z via `fflate` (MIT). Same buffer-then-process pattern
// as the JVM side: drain the source, run fflate once, emit. fflate + byte
// marshaling come from :shared:fflate (ES-module imports, NOT `require`).
// bzip2/xz remain unsupported on wasmJs — those libs aren't shipped as a
// single npm dep.

private fun emptyOpts(): JsAny = js("({})")

private fun fflateGzip(src: JsString): JsString = u8ToJsString(gzipSync(toU8(src), emptyOpts()))

private fun fflateGunzip(src: JsString): JsString = u8ToJsString(gunzipSync(toU8(src)))

public actual object GzipFilter {
    public actual fun decompress(source: SuspendSource): SuspendSource? = WasmGunzipSource(source)

    public actual fun compress(sink: SuspendSink): SuspendSink? = WasmGzipSink(sink)
}

public actual object Bzip2Filter {
    public actual fun decompress(source: SuspendSource): SuspendSource? = null

    public actual fun compress(sink: SuspendSink): SuspendSink? = null
}

public actual object XzFilter {
    public actual fun decompress(source: SuspendSource): SuspendSource? = null

    public actual fun compress(sink: SuspendSink): SuspendSink? = null
}

private class WasmGunzipSource(
    private val upstream: SuspendSource,
) : SuspendSource {
    private var decoded: ByteArray? = null
    private var offset: Int = 0
    private var eof: Boolean = false

    private suspend fun ensure(): ByteArray? {
        decoded?.let { return it }
        val all = Buffer()
        while (true) {
            val n = upstream.readAtMostTo(all, 64 * 1024L)
            if (n == -1L) break
        }
        val bytes = all.readByteArray()
        if (bytes.isEmpty()) {
            eof = true
            return null
        }
        val out = jsStringToBytes(fflateGunzip(bytesToJsString(bytes)).toString())
        decoded = out
        return out
    }

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (eof) return -1L
        val src = ensure() ?: return -1L
        if (offset >= src.size) {
            eof = true
            return -1L
        }
        val want = minOf(byteCount, (src.size - offset).toLong()).toInt()
        sink.write(src, offset, offset + want)
        offset += want
        return want.toLong()
    }

    override fun close() {
        upstream.close()
    }
}

private class WasmGzipSink(
    private val downstream: SuspendSink,
) : SuspendSink,
    FinishableSink {
    private val staging = Buffer()
    private var finished = false

    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        // Accumulate raw bytes; gzip's trailer is committed in finishCompression().
        var remaining = byteCount
        while (remaining > 0) {
            val n = source.readAtMostTo(staging, remaining)
            if (n == -1L) break
            remaining -= n
        }
    }

    override suspend fun flush() {
        // No-op until finishCompression — partial gzip frames are invalid.
    }

    override suspend fun finishCompression() {
        if (finished) return
        finished = true
        val raw = staging.readByteArray()
        val encoded = jsStringToBytes(fflateGzip(bytesToJsString(raw)).toString())
        if (encoded.isNotEmpty()) {
            val buf = Buffer()
            buf.write(encoded)
            downstream.write(buf, buf.size)
            downstream.flush()
        }
    }

    override fun close() {
        try {
            downstream.close()
        } catch (_: Throwable) {
        }
    }
}
