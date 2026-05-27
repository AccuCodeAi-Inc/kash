package com.accucodeai.kash.tools.tar

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

public actual object Bzip2Filter {
    public actual fun decompress(source: SuspendSource): SuspendSource? =
        // decompressConcatenated=true matches `bunzip2`'s multi-stream behavior
        DecodingSource(source) { BZip2CompressorInputStream(it, true) }

    public actual fun compress(sink: SuspendSink): SuspendSink? = EncodingSink(sink) { BZip2CompressorOutputStream(it) }
}

public actual object XzFilter {
    public actual fun decompress(source: SuspendSource): SuspendSource? = DecodingSource(source) { XZInputStream(it) }

    public actual fun compress(sink: SuspendSink): SuspendSink? =
        EncodingSink(sink) { XZOutputStream(it, LZMA2Options()) }
}

/**
 * Shared decode-side helper for codecs whose JVM API is `InputStream`-only.
 * Reads the entire compressed upstream into memory, then streams decoded
 * bytes out chunk-by-chunk to the consumer — matches GzipFilter's approach
 * (true per-chunk streaming would need a worker thread with PipedStream).
 */
internal class DecodingSource(
    private val upstream: SuspendSource,
    private val wrap: (InputStream) -> InputStream,
) : SuspendSource {
    private var stream: InputStream? = null
    private var eof = false

    private suspend fun ensureStream(): InputStream? {
        if (stream != null) return stream
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
        stream = wrap(ByteArrayInputStream(bytes))
        return stream
    }

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (eof) return -1L
        val s = ensureStream() ?: return -1L
        val want = minOf(byteCount, 8 * 1024L).toInt().coerceAtLeast(1)
        val chunk = ByteArray(want)
        val n = s.read(chunk)
        if (n == -1) {
            eof = true
            return -1L
        }
        sink.write(chunk, 0, n)
        return n.toLong()
    }

    override fun close() {
        try {
            stream?.close()
        } catch (_: Throwable) {
        }
        upstream.close()
    }
}

/**
 * Shared encode-side helper. Buffers all encoded bytes until
 * [FinishableSink.finishCompression] is called, then drains in one shot —
 * matches GzipFilter (the codec must commit its trailer at close time).
 */
internal class EncodingSink(
    private val downstream: SuspendSink,
    wrap: (OutputStream) -> OutputStream,
) : SuspendSink,
    FinishableSink {
    private val baos = ByteArrayOutputStream()
    private val enc = wrap(baos)
    private var finished = false

    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        val chunk = ByteArray(byteCount.toInt())
        var off = 0
        while (off < chunk.size) {
            val n = source.readAtMostTo(chunk, off, chunk.size)
            if (n <= 0) break
            off += n
        }
        enc.write(chunk, 0, off)
    }

    override suspend fun flush() {
        // No-op: trailers commit at finishCompression().
    }

    override suspend fun finishCompression() {
        if (finished) return
        finished = true
        enc.close()
        val bytes = baos.toByteArray()
        baos.reset()
        if (bytes.isNotEmpty()) {
            val buf = Buffer()
            buf.write(bytes)
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
