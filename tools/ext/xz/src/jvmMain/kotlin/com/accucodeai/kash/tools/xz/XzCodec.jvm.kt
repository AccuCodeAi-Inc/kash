package com.accucodeai.kash.tools.xz

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private suspend fun readAllBytes(source: SuspendSource): ByteArray {
    val buf = Buffer()
    while (true) {
        val n = source.readAtMostTo(buf, 64 * 1024L)
        if (n == -1L) break
    }
    return buf.readByteArray()
}

private fun clampPreset(preset: Int): Int = preset.coerceIn(0, 9)

public actual suspend fun xzCompress(
    src: SuspendSource,
    dst: SuspendSink,
    format: XzFormat,
    preset: Int,
) {
    val input = readAllBytes(src)
    val output =
        withContext(Dispatchers.IO) {
            val sink = ByteArrayOutputStream()
            val p = clampPreset(preset)
            when (format) {
                XzFormat.XZ -> {
                    XZOutputStream(sink, LZMA2Options(p)).use { it.write(input) }
                }

                XzFormat.LZMA -> {
                    // Legacy .lzma "alone" format. -1 = unknown uncompressed size.
                    LZMAOutputStream(sink, LZMA2Options(p), -1L).use { it.write(input) }
                }
            }
            sink.toByteArray()
        }
    dst.writeBytes(output)
}

public actual suspend fun xzDecompress(
    src: SuspendSource,
    dst: SuspendSink,
    format: XzFormat,
) {
    val input = readAllBytes(src)
    val output =
        withContext(Dispatchers.IO) {
            val sink = ByteArrayOutputStream(input.size)
            try {
                when (format) {
                    XzFormat.XZ -> {
                        XZInputStream(ByteArrayInputStream(input)).use { it.copyTo(sink) }
                    }

                    XzFormat.LZMA -> {
                        LZMAInputStream(ByteArrayInputStream(input)).use { it.copyTo(sink) }
                    }
                }
            } catch (e: org.tukaani.xz.XZFormatException) {
                throw IllegalArgumentException("File format not recognized")
            } catch (e: org.tukaani.xz.CorruptedInputException) {
                throw IllegalArgumentException(e.message ?: "Compressed data is corrupt")
            } catch (e: org.tukaani.xz.UnsupportedOptionsException) {
                throw IllegalArgumentException(e.message ?: "Unsupported options")
            } catch (e: java.io.EOFException) {
                throw IllegalArgumentException("Unexpected end of input")
            }
            sink.toByteArray()
        }
    dst.writeBytes(output)
}
