package com.accucodeai.kash.tools.zstd

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import io.airlift.compress.zstd.ZstdInputStream
import io.airlift.compress.zstd.ZstdOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.io.InputStream
import java.io.OutputStream

public actual val zstdSupported: Boolean = true

private const val CHUNK = 8 * 1024

public actual suspend fun zstdCompress(
    src: SuspendSource,
    sink: SuspendSink,
    level: Int,
) {
    // aircompressor's ZstdOutputStream has no level knob on the streaming
    // API — it always uses the library's default. We accept and ignore
    // `level` to honor `-1..-19` flags from the CLI.
    val bytesIn = readAllToBytes(src)
    val out = ByteArrayOutputStreamSuspend()
    withContext(Dispatchers.IO) {
        val zout = ZstdOutputStream(out)
        zout.write(bytesIn)
        zout.close()
    }
    writeAllFromBytes(sink, out.bytes())
}

public actual suspend fun zstdDecompress(
    src: SuspendSource,
    sink: SuspendSink,
) {
    val bytesIn = readAllToBytes(src)
    val outBuf = ByteArrayOutputStreamSuspend()
    withContext(Dispatchers.IO) {
        val zin: InputStream = ZstdInputStream(bytesIn.inputStream())
        val buf = ByteArray(CHUNK)
        while (true) {
            val n = zin.read(buf)
            if (n < 0) break
            outBuf.write(buf, 0, n)
        }
        zin.close()
    }
    writeAllFromBytes(sink, outBuf.bytes())
}

// --- helpers ---

private suspend fun readAllToBytes(src: SuspendSource): ByteArray {
    val acc = Buffer()
    while (true) {
        val n = src.readAtMostTo(acc, CHUNK.toLong())
        if (n == -1L) break
    }
    return acc.readByteArray()
}

private suspend fun writeAllFromBytes(
    sink: SuspendSink,
    bytes: ByteArray,
) {
    if (bytes.isEmpty()) {
        sink.flush()
        return
    }
    val buf = Buffer()
    buf.write(bytes)
    sink.write(buf, buf.size)
    sink.flush()
}

/** Mutable byte sink — simpler than java.io.ByteArrayOutputStream for our use. */
private class ByteArrayOutputStreamSuspend : OutputStream() {
    private val baos = java.io.ByteArrayOutputStream(16 * 1024)

    override fun write(b: Int) {
        baos.write(b)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        baos.write(b, off, len)
    }

    fun bytes(): ByteArray = baos.toByteArray()
}
