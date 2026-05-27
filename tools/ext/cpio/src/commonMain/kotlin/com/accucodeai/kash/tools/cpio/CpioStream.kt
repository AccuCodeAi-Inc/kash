package com.accucodeai.kash.tools.cpio

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write

/**
 * A counting [SuspendSource] wrapper so the reader can compute the padding
 * needed to land on a 4-byte boundary. cpio's newc format pads name and
 * payload to the next multiple of 4 *counting from the start of the archive*.
 */
internal class CountingSource(
    private val upstream: SuspendSource,
) : SuspendSource {
    var bytesRead: Long = 0L
        private set

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val n = upstream.readAtMostTo(sink, byteCount)
        if (n > 0) bytesRead += n
        return n
    }

    override fun close() {
        upstream.close()
    }

    /** Read exactly [n] bytes or throw on EOF. */
    suspend fun readExact(n: Int): ByteArray {
        val buf = Buffer()
        var remaining = n.toLong()
        while (remaining > 0) {
            val got = readAtMostTo(buf, remaining)
            if (got == -1L) throw CpioParseException("unexpected EOF (wanted $n bytes)")
            remaining -= got
        }
        return buf.readByteArray(n)
    }

    /** Skip [n] bytes from the upstream, counting them. */
    suspend fun skipExact(n: Int) {
        if (n <= 0) return
        val buf = Buffer()
        var remaining = n.toLong()
        while (remaining > 0) {
            val got = readAtMostTo(buf, remaining)
            if (got == -1L) throw CpioParseException("unexpected EOF (wanted to skip $n)")
            remaining -= got
            buf.clear()
        }
    }
}

/**
 * A counting [SuspendSink] so the writer can emit the right amount of
 * padding bytes. Mirrors [CountingSource].
 */
internal class CountingSink(
    private val upstream: SuspendSink,
) : SuspendSink {
    var bytesWritten: Long = 0L
        private set

    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        upstream.write(source, byteCount)
        bytesWritten += byteCount
    }

    override suspend fun flush() {
        upstream.flush()
    }

    override fun close() {
        upstream.close()
    }

    suspend fun writeRaw(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val buf = Buffer()
        buf.write(bytes)
        write(buf, bytes.size.toLong())
    }

    suspend fun padTo(alignment: Int) {
        val rem = (bytesWritten % alignment).toInt()
        if (rem != 0) {
            writeRaw(ByteArray(alignment - rem))
        }
    }
}

public class CpioParseException(
    message: String,
) : RuntimeException(message)

private fun parseHex(
    bytes: ByteArray,
    off: Int,
    len: Int,
): Long {
    var v = 0L
    for (i in 0 until len) {
        val c = bytes[off + i].toInt() and 0xff
        val digit =
            when {
                c in '0'.code..'9'.code -> c - '0'.code
                c in 'a'.code..'f'.code -> 10 + (c - 'a'.code)
                c in 'A'.code..'F'.code -> 10 + (c - 'A'.code)
                else -> throw CpioParseException("invalid hex digit at offset $off+$i: ${c.toChar()}")
            }
        v = (v shl 4) or digit.toLong()
    }
    return v
}

private fun parseOct(
    bytes: ByteArray,
    off: Int,
    len: Int,
): Long {
    var v = 0L
    for (i in 0 until len) {
        val c = bytes[off + i].toInt() and 0xff
        if (c < '0'.code || c > '7'.code) {
            throw CpioParseException("invalid octal digit at offset $off+$i: ${c.toChar()}")
        }
        v = (v shl 3) or (c - '0'.code).toLong()
    }
    return v
}

private fun formatHex(
    v: Long,
    len: Int,
): ByteArray {
    val out = ByteArray(len)
    var rem = v
    for (i in len - 1 downTo 0) {
        val d = (rem and 0xfL).toInt()
        out[i] = (if (d < 10) '0'.code + d else 'a'.code + (d - 10)).toByte()
        rem = rem ushr 4
    }
    return out
}

private fun formatOct(
    v: Long,
    len: Int,
): ByteArray {
    val out = ByteArray(len)
    var rem = v
    for (i in len - 1 downTo 0) {
        val d = (rem and 0x7L).toInt()
        out[i] = ('0'.code + d).toByte()
        rem = rem ushr 3
    }
    return out
}

// ---------------------------------------------------------------------------
// Newc reader/writer
// ---------------------------------------------------------------------------

/**
 * Read one newc entry header + name. Caller then either consumes the
 * payload bytes (size + padding) or skips them.
 *
 * Returns null at clean EOF *before* the first byte of a header (i.e. archive
 * input ended without us reading any header — typically already consumed
 * the trailer).
 */
internal suspend fun readNewcHeader(src: CountingSource): CpioHeader? {
    // Try a 1-byte probe to detect clean EOF; then read the rest of the magic.
    val probe = Buffer()
    val got = src.readAtMostTo(probe, 1L)
    if (got == -1L) return null
    val first = probe.readByte()
    val tail = src.readExact(5)
    val magic = byteArrayOf(first) + tail
    val ms = magic.decodeToString()
    if (ms != "070701") throw CpioParseException("bad newc magic: '$ms'")

    // Remaining header = 110 - 6 = 104 bytes
    val rest = src.readExact(104)
    // newc fields after magic (offsets relative to `rest`):
    // 0:ino 8:mode 16:uid 24:gid 32:nlink 40:mtime 48:filesize
    // 56:devmajor 64:devminor 72:rdevmajor 80:rdevminor 88:namesize 96:check
    val ino = parseHex(rest, 0, 8)
    val mode = parseHex(rest, 8, 8).toInt()
    val uid = parseHex(rest, 16, 8).toInt()
    val gid = parseHex(rest, 24, 8).toInt()
    val nlink = parseHex(rest, 32, 8).toInt()
    val mtime = parseHex(rest, 40, 8)
    val fsize = parseHex(rest, 48, 8)
    val devMaj = parseHex(rest, 56, 8).toInt()
    val devMin = parseHex(rest, 64, 8).toInt()
    val rDevMaj = parseHex(rest, 72, 8).toInt()
    val rDevMin = parseHex(rest, 80, 8).toInt()
    val namesize = parseHex(rest, 88, 8).toInt()
    // 96..104 = check field, ignored.

    if (namesize <= 0) throw CpioParseException("invalid namesize $namesize")
    val nameBytes = src.readExact(namesize)
    // Drop trailing NUL
    val nameStr =
        run {
            var end = nameBytes.size
            if (end > 0 && nameBytes[end - 1] == 0.toByte()) end--
            nameBytes.decodeToString(0, end)
        }
    // Pad name to 4-byte boundary counted from archive start.
    val afterName = src.bytesRead
    val pad = ((4 - (afterName % 4)) % 4).toInt()
    if (pad > 0) src.skipExact(pad)

    return CpioHeader(
        name = nameStr,
        mode = mode,
        uid = uid,
        gid = gid,
        nlink = nlink,
        mtime = mtime,
        size = fsize,
        ino = ino,
        devMajor = devMaj,
        devMinor = devMin,
        rDevMajor = rDevMaj,
        rDevMinor = rDevMin,
    )
}

/** After reading the header, skip the payload (with its trailing padding). */
internal suspend fun skipNewcPayload(
    src: CountingSource,
    h: CpioHeader,
) {
    if (h.size > 0) src.skipExact(h.size.toInt())
    val pad = ((4 - (src.bytesRead % 4)) % 4).toInt()
    if (pad > 0) src.skipExact(pad)
}

/** Read header payload into memory (caller decides what to do with it). */
internal suspend fun readNewcPayload(
    src: CountingSource,
    h: CpioHeader,
): ByteArray {
    val data = if (h.size > 0) src.readExact(h.size.toInt()) else ByteArray(0)
    val pad = ((4 - (src.bytesRead % 4)) % 4).toInt()
    if (pad > 0) src.skipExact(pad)
    return data
}

internal suspend fun writeNewcEntry(
    sink: CountingSink,
    h: CpioHeader,
    payload: ByteArray,
) {
    val nameBytes = h.name.encodeToByteArray()
    val nameWithNul = nameBytes + 0.toByte()
    val namesize = nameWithNul.size

    val header = Buffer()
    header.write("070701".encodeToByteArray())
    header.write(formatHex(h.ino, 8))
    header.write(formatHex(h.mode.toLong() and 0xFFFFFFFFL, 8))
    header.write(formatHex(h.uid.toLong() and 0xFFFFFFFFL, 8))
    header.write(formatHex(h.gid.toLong() and 0xFFFFFFFFL, 8))
    header.write(formatHex(h.nlink.toLong() and 0xFFFFFFFFL, 8))
    header.write(formatHex(h.mtime, 8))
    header.write(formatHex(payload.size.toLong(), 8))
    header.write(formatHex(h.devMajor.toLong() and 0xFFFFFFFFL, 8))
    header.write(formatHex(h.devMinor.toLong() and 0xFFFFFFFFL, 8))
    header.write(formatHex(h.rDevMajor.toLong() and 0xFFFFFFFFL, 8))
    header.write(formatHex(h.rDevMinor.toLong() and 0xFFFFFFFFL, 8))
    header.write(formatHex(namesize.toLong(), 8))
    header.write(formatHex(0L, 8)) // check
    val headerBytes = header.readByteArray(110)
    sink.writeRaw(headerBytes)

    sink.writeRaw(nameWithNul)
    sink.padTo(4)

    if (payload.isNotEmpty()) sink.writeRaw(payload)
    sink.padTo(4)
}

// ---------------------------------------------------------------------------
// Odc reader/writer
// ---------------------------------------------------------------------------
//
// odc header (76 bytes):
//   0: magic     6 octal
//   6: dev       6
//  12: ino       6
//  18: mode      6
//  24: uid       6
//  30: gid       6
//  36: nlink     6
//  42: rdev      6
//  48: mtime    11
//  59: namesize  6
//  65: filesize 11

internal suspend fun readOdcHeader(src: CountingSource): CpioHeader? {
    val probe = Buffer()
    val got = src.readAtMostTo(probe, 1L)
    if (got == -1L) return null
    val first = probe.readByte()
    val tail = src.readExact(5)
    val magic = byteArrayOf(first) + tail
    val ms = magic.decodeToString()
    if (ms != "070707") throw CpioParseException("bad odc magic: '$ms'")

    val rest = src.readExact(70)
    val dev = parseOct(rest, 0, 6).toInt()
    val ino = parseOct(rest, 6, 6)
    val mode = parseOct(rest, 12, 6).toInt()
    val uid = parseOct(rest, 18, 6).toInt()
    val gid = parseOct(rest, 24, 6).toInt()
    val nlink = parseOct(rest, 30, 6).toInt()
    val rdev = parseOct(rest, 36, 6).toInt()
    val mtime = parseOct(rest, 42, 11)
    val namesize = parseOct(rest, 53, 6).toInt()
    val fsize = parseOct(rest, 59, 11)

    if (namesize <= 0) throw CpioParseException("invalid namesize $namesize")
    val nameBytes = src.readExact(namesize)
    var end = nameBytes.size
    if (end > 0 && nameBytes[end - 1] == 0.toByte()) end--
    val nameStr = nameBytes.decodeToString(0, end)
    // No alignment padding in odc.

    return CpioHeader(
        name = nameStr,
        mode = mode,
        uid = uid,
        gid = gid,
        nlink = nlink,
        mtime = mtime,
        size = fsize,
        ino = ino,
        devMajor = dev,
        devMinor = 0,
        rDevMajor = rdev,
        rDevMinor = 0,
    )
}

internal suspend fun readOdcPayload(
    src: CountingSource,
    h: CpioHeader,
): ByteArray = if (h.size > 0) src.readExact(h.size.toInt()) else ByteArray(0)

internal suspend fun skipOdcPayload(
    src: CountingSource,
    h: CpioHeader,
) {
    if (h.size > 0) src.skipExact(h.size.toInt())
}

internal suspend fun writeOdcEntry(
    sink: CountingSink,
    h: CpioHeader,
    payload: ByteArray,
) {
    val nameBytes = h.name.encodeToByteArray()
    val nameWithNul = nameBytes + 0.toByte()
    val namesize = nameWithNul.size

    val header = Buffer()
    header.write("070707".encodeToByteArray())
    header.write(formatOct(h.devMajor.toLong() and 0x3FFFFL, 6))
    header.write(formatOct(h.ino and 0x3FFFFL, 6))
    header.write(formatOct(h.mode.toLong() and 0x3FFFFL, 6))
    header.write(formatOct(h.uid.toLong() and 0x3FFFFL, 6))
    header.write(formatOct(h.gid.toLong() and 0x3FFFFL, 6))
    header.write(formatOct(h.nlink.toLong() and 0x3FFFFL, 6))
    header.write(formatOct(h.rDevMajor.toLong() and 0x3FFFFL, 6))
    header.write(formatOct(h.mtime and 0x7FFFFFFFFFFL, 11))
    header.write(formatOct(namesize.toLong(), 6))
    header.write(formatOct(payload.size.toLong(), 11))
    val headerBytes = header.readByteArray(76)
    sink.writeRaw(headerBytes)

    sink.writeRaw(nameWithNul)
    if (payload.isNotEmpty()) sink.writeRaw(payload)
}

// ---------------------------------------------------------------------------
// Trailer helpers
// ---------------------------------------------------------------------------

internal fun trailerHeader(): CpioHeader =
    CpioHeader(
        name = CPIO_TRAILER,
        mode = 0,
        uid = 0,
        gid = 0,
        nlink = 1,
        mtime = 0L,
        size = 0L,
    )

internal suspend fun writeTrailer(
    sink: CountingSink,
    fmt: CpioFormat,
) {
    val h = trailerHeader()
    when (fmt) {
        CpioFormat.NEWC -> writeNewcEntry(sink, h, ByteArray(0))
        CpioFormat.ODC -> writeOdcEntry(sink, h, ByteArray(0))
    }
    // Pad the whole archive out to a 512-byte block in newc (GNU cpio
    // does this too). Keep simple — only pad to 4 in newc; 512-block
    // padding is optional and many readers don't require it.
    if (fmt == CpioFormat.NEWC) sink.padTo(4)
}
