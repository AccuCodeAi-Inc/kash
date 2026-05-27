package com.accucodeai.kash.tools.gzip

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeBytes
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream

private const val GZIP_MAGIC_1: Byte = 0x1f.toByte()
private const val GZIP_MAGIC_2: Byte = 0x8b.toByte()
private const val CHUNK = 8 * 1024

/** Drain entire source into a ByteArray. */
private suspend fun readAllBytes(source: SuspendSource): ByteArray {
    val buf = Buffer()
    while (true) {
        val n = source.readAtMostTo(buf, CHUNK.toLong())
        if (n == -1L) break
        yield()
    }
    return buf.readByteArray()
}

public actual suspend fun gzipCompress(
    source: SuspendSource,
    sink: SuspendSink,
    level: Int,
    storeName: String?,
    storeMtime: Long,
) {
    // Custom header (so we can control FNAME/MTIME), raw Deflater body
    // (nowrap=true), then CRC32 + ISIZE trailer.
    sink.writeBytes(buildGzipHeader(storeName, storeMtime))

    val deflater = Deflater(level.coerceIn(Deflater.BEST_SPEED, Deflater.BEST_COMPRESSION), true)
    val crc = CRC32()
    var isize = 0L
    val readBuf = Buffer()
    val outChunk = ByteArray(CHUNK)
    try {
        while (true) {
            val n = source.readAtMostTo(readBuf, CHUNK.toLong())
            if (n == -1L) break
            val data = readBuf.readByteArray()
            if (data.isNotEmpty()) {
                crc.update(data, 0, data.size)
                isize += data.size
                deflater.setInput(data)
                while (!deflater.needsInput()) {
                    val produced = deflater.deflate(outChunk, 0, outChunk.size, Deflater.NO_FLUSH)
                    if (produced > 0) sink.writeBytes(outChunk.copyOf(produced))
                }
            }
            yield()
        }
        deflater.finish()
        while (!deflater.finished()) {
            val produced = deflater.deflate(outChunk, 0, outChunk.size, Deflater.NO_FLUSH)
            if (produced > 0) sink.writeBytes(outChunk.copyOf(produced))
        }
    } finally {
        deflater.end()
    }
    // Trailer: CRC32 (LE) + ISIZE mod 2^32 (LE).
    val trailer = ByteArray(8)
    val c = crc.value.toInt()
    trailer[0] = (c and 0xff).toByte()
    trailer[1] = ((c ushr 8) and 0xff).toByte()
    trailer[2] = ((c ushr 16) and 0xff).toByte()
    trailer[3] = ((c ushr 24) and 0xff).toByte()
    val s = (isize and 0xffffffffL).toInt()
    trailer[4] = (s and 0xff).toByte()
    trailer[5] = ((s ushr 8) and 0xff).toByte()
    trailer[6] = ((s ushr 16) and 0xff).toByte()
    trailer[7] = ((s ushr 24) and 0xff).toByte()
    sink.writeBytes(trailer)
}

private fun buildGzipHeader(
    name: String?,
    mtime: Long,
): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0x1f)
    out.write(0x8b)
    out.write(8) // CM=deflate
    var flg = 0
    if (name != null) flg = flg or 0x08 // FNAME
    out.write(flg)
    val m = (mtime and 0xffffffffL).toInt()
    out.write(m and 0xff)
    out.write((m ushr 8) and 0xff)
    out.write((m ushr 16) and 0xff)
    out.write((m ushr 24) and 0xff)
    out.write(0) // XFL
    out.write(255) // OS = unknown
    if (name != null) {
        for (ch in name) {
            val c = ch.code
            if (c in 1..0xff) out.write(c)
        }
        out.write(0)
    }
    return out.toByteArray()
}

public actual suspend fun gzipDecompress(
    source: SuspendSource,
    sink: SuspendSink,
) {
    val all = readAllBytes(source)
    if (all.size < 2 || all[0] != GZIP_MAGIC_1 || all[1] != GZIP_MAGIC_2) {
        throw IllegalArgumentException("not in gzip format")
    }
    val gis = GZIPInputStream(all.inputStream())
    val buf = ByteArray(CHUNK)
    try {
        while (true) {
            val n = gis.read(buf)
            if (n <= 0) break
            sink.writeBytes(buf.copyOf(n))
            yield()
        }
    } finally {
        gis.close()
    }
}

public actual suspend fun gzipTest(source: SuspendSource) {
    val all = readAllBytes(source)
    if (all.size < 2 || all[0] != GZIP_MAGIC_1 || all[1] != GZIP_MAGIC_2) {
        throw IllegalArgumentException("not in gzip format")
    }
    val gis = GZIPInputStream(all.inputStream())
    val sink = ByteArray(CHUNK)
    try {
        while (true) {
            val n = gis.read(sink)
            if (n <= 0) break
        }
    } finally {
        gis.close()
    }
}
