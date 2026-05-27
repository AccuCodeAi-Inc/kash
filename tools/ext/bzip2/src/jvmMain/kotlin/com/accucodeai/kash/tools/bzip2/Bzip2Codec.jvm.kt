package com.accucodeai.kash.tools.bzip2

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Pattern: drain the suspend source into a `ByteArray`, run the synchronous
 * codec on `Dispatchers.IO`, write the resulting `ByteArray` to the suspend
 * sink. Buffers compressed payloads in memory — matches `GzipFilter` and the
 * tar `Bzip2XzFilter`. A true incremental bridge needs a worker thread with
 * `PipedInputStream`; deferred until a use case demands it.
 */

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

public actual suspend fun bzip2Compress(
    src: SuspendSource,
    dst: SuspendSink,
    blockSize100k: Int,
) {
    require(blockSize100k in 1..9) { "blockSize100k must be 1..9, got $blockSize100k" }
    val input = drainToBytes(src)
    val output =
        withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            BZip2CompressorOutputStream(out, blockSize100k).use { it.write(input) }
            out.toByteArray()
        }
    emitBytes(dst, output)
}

public actual suspend fun bzip2Decompress(
    src: SuspendSource,
    dst: SuspendSink,
) {
    val input = drainToBytes(src)
    val output =
        withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            try {
                // decompressConcatenated=true matches `bunzip2`'s multi-stream behavior.
                BZip2CompressorInputStream(ByteArrayInputStream(input), true).use { bzIn ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = bzIn.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                    }
                }
            } catch (e: IOException) {
                throw Bzip2FormatException(e.message ?: "invalid bzip2 input", e)
            } catch (e: IllegalArgumentException) {
                throw Bzip2FormatException(e.message ?: "invalid bzip2 input", e)
            }
            out.toByteArray()
        }
    emitBytes(dst, output)
}

public actual suspend fun bzip2Test(src: SuspendSource): Long {
    val input = drainToBytes(src)
    return withContext(Dispatchers.IO) {
        var total = 0L
        try {
            BZip2CompressorInputStream(ByteArrayInputStream(input), true).use { bzIn ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = bzIn.read(buf)
                    if (n == -1) break
                    total += n
                }
            }
        } catch (e: IOException) {
            throw Bzip2FormatException(e.message ?: "invalid bzip2 input", e)
        } catch (e: IllegalArgumentException) {
            throw Bzip2FormatException(e.message ?: "invalid bzip2 input", e)
        }
        total
    }
}
