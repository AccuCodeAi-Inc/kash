package com.accucodeai.kash.tools.lz4

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

// Buffer-then-process — matches Bzip2Codec / GzipFilter / Bzip2XzFilter.
// commons-compress's framed-LZ4 streams are pure InputStream/OutputStream
// and the codec is small, so peak memory = one archive's worth.

private suspend fun drainToBytes(src: SuspendSource): ByteArray {
    val all = Buffer()
    while (true) {
        val n = src.readAtMostTo(all, 64 * 1024L)
        if (n == -1L) break
    }
    return all.readByteArray()
}

private suspend fun emitBytes(
    dst: SuspendSink,
    bytes: ByteArray,
) {
    if (bytes.isEmpty()) {
        dst.flush()
        return
    }
    val buf = Buffer()
    buf.write(bytes)
    dst.write(buf, buf.size)
    dst.flush()
}

public actual suspend fun lz4Compress(
    src: SuspendSource,
    dst: SuspendSink,
) {
    val input = drainToBytes(src)
    val output =
        withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            FramedLZ4CompressorOutputStream(out).use { it.write(input) }
            out.toByteArray()
        }
    emitBytes(dst, output)
}

public actual suspend fun lz4Decompress(
    src: SuspendSource,
    dst: SuspendSink,
) {
    val input = drainToBytes(src)
    val output =
        withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            try {
                FramedLZ4CompressorInputStream(ByteArrayInputStream(input), true).use { lz ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = lz.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                    }
                }
            } catch (e: IOException) {
                throw Lz4FormatException(e.message ?: "invalid lz4 input", e)
            } catch (e: IllegalArgumentException) {
                throw Lz4FormatException(e.message ?: "invalid lz4 input", e)
            }
            out.toByteArray()
        }
    emitBytes(dst, output)
}

public actual suspend fun lz4Test(src: SuspendSource): Long {
    val input = drainToBytes(src)
    return withContext(Dispatchers.IO) {
        var total = 0L
        try {
            FramedLZ4CompressorInputStream(ByteArrayInputStream(input), true).use { lz ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = lz.read(buf)
                    if (n == -1) break
                    total += n
                }
            }
        } catch (e: IOException) {
            throw Lz4FormatException(e.message ?: "invalid lz4 input", e)
        } catch (e: IllegalArgumentException) {
            throw Lz4FormatException(e.message ?: "invalid lz4 input", e)
        }
        total
    }
}
