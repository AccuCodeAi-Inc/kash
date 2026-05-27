package com.accucodeai.kash.tools.git.http

import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.framedObject
import com.accucodeai.kash.tools.git.plumbing.objectSha
import com.accucodeai.kash.tools.git.plumbing.zlibInflateChunk

/**
 * Smart-HTTP packfile reader.
 *
 * A packfile is `"PACK"` + 4-byte BE version (2 or 3) + 4-byte BE
 * object count, followed by N entries, followed by a trailing 20-byte
 * SHA-1 of the pack content (which we skip — we trust the transport).
 *
 * Each entry's header is git's variable-length encoding:
 *   first byte: bit 7 = "more bytes follow", bits 6-4 = type, bits 3-0
 *               = the low 4 bits of the inflated size.
 *   each subsequent byte: bit 7 = continuation, bits 6-0 = next 7 bits
 *               of the size (little-endian by byte order).
 *
 * Types 1..4 are full objects (`COMMIT`, `TREE`, `BLOB`, `TAG`); type 6
 * is `OFS_DELTA` (base referenced by negative byte offset within the
 * pack) and type 7 is `REF_DELTA` (base referenced by 20-byte SHA).
 *
 * After the header bytes (and the delta's base reference for delta
 * types) comes a zlib-compressed payload. We use [zlibInflateChunk] to
 * pull exactly that stream, which reports how many input bytes the
 * inflater consumed — we advance our cursor by that many.
 *
 * Delta entries are resolved against their already-decoded base. We
 * keep a per-pack `offset → (type, inflated bytes)` map for OFS_DELTA
 * resolution; REF_DELTA falls back to the caller-supplied [haveBase]
 * lookup (returning the framed object payload, the same shape the
 * `GitObjectResolver` produces).
 *
 * Return value: a list of `(sha, framedBytes)` pairs in pack order,
 * ready to be handed to the kash `ObjectStore` (which zlib-deflates
 * them on the way down to loose objects).
 */
public class PackfileReader(
    private val data: ByteArray,
    private val haveBase: (sha: String) -> ByteArray? = { null },
) {
    private var offset: Int = 0

    /** Maps pack-offset → (objectType, inflated payload) for delta base resolution. */
    private val resolved: MutableMap<Int, Pair<ObjectType, ByteArray>> = mutableMapOf()

    /**
     * Parse the packfile and return one `(sha, framedBytes)` pair per
     * entry, in pack order. Tools typically index by sha; we keep the
     * list shape so callers can also order-replay.
     */
    public fun readAll(): List<Pair<String, ByteArray>> {
        require(data.size >= 12) { "packfile: shorter than header ($data.size bytes)" }
        require(
            data[0] == 'P'.code.toByte() &&
                data[1] == 'A'.code.toByte() &&
                data[2] == 'C'.code.toByte() &&
                data[3] == 'K'.code.toByte(),
        ) { "packfile: missing PACK magic" }
        val version = readUInt32BE(4)
        require(version == 2 || version == 3) { "packfile: unsupported version $version" }
        val count = readUInt32BE(8)
        offset = 12
        val out = ArrayList<Pair<String, ByteArray>>(count)
        repeat(count) {
            out += readEntry()
        }
        return out
    }

    private fun readEntry(): Pair<String, ByteArray> {
        val entryStart = offset
        val (type, _size) = readEntryHeader()
        @Suppress("UNUSED_VARIABLE")
        val expectedSize = _size
        val (objType, payload) =
            when (type) {
                1, 2, 3, 4 -> {
                    val inflated = inflateNext()
                    val ot = baseType(type)
                    resolved[entryStart] = ot to inflated
                    ot to inflated
                }

                6 -> {
                    // OFS_DELTA: variable-length base-offset (relative
                    // back to a prior entry's start), then deltified zlib.
                    val baseOffset = entryStart - readOfsDelta()
                    val base =
                        resolved[baseOffset]
                            ?: error("packfile: OFS_DELTA base at offset=$baseOffset not yet decoded")
                    val deltified = inflateNext()
                    val target = applyDelta(base.second, deltified)
                    resolved[entryStart] = base.first to target
                    base.first to target
                }

                7 -> {
                    // REF_DELTA: 20-byte base sha, then deltified zlib.
                    require(offset + 20 <= data.size) { "packfile: truncated REF_DELTA base sha" }
                    val baseSha = bytesToHex20(data, offset)
                    offset += 20
                    val baseFramed =
                        haveBase(baseSha)
                            ?: error("packfile: REF_DELTA base $baseSha not available locally")
                    val baseParsed = parseFramedBase(baseFramed)
                    val deltified = inflateNext()
                    val target = applyDelta(baseParsed.second, deltified)
                    resolved[entryStart] = baseParsed.first to target
                    baseParsed.first to target
                }

                else -> {
                    error("packfile: unknown entry type $type")
                }
            }
        val sha = objectSha(objType, payload)
        val framed = framedObject(objType, payload)
        return sha to framed
    }

    private fun readEntryHeader(): Pair<Int, Long> {
        val first = data[offset].toInt() and 0xff
        offset++
        val type = (first ushr 4) and 0x7
        var size = (first and 0xf).toLong()
        var shift = 4
        if ((first and 0x80) != 0) {
            while (true) {
                require(offset < data.size) { "packfile: truncated entry header" }
                val b = data[offset].toInt() and 0xff
                offset++
                size = size or ((b and 0x7f).toLong() shl shift)
                shift += 7
                if ((b and 0x80) == 0) break
            }
        }
        return type to size
    }

    /**
     * The OFS_DELTA offset encoding: a sequence of bytes with bit 7 = "more
     * bytes follow", bits 6-0 contributing high-to-low; each non-first
     * group also implicitly adds `1` to the accumulated value (see git's
     * `Documentation/technical/pack-format.txt`).
     */
    private fun readOfsDelta(): Int {
        require(offset < data.size) { "packfile: truncated OFS_DELTA offset" }
        var b = data[offset].toInt() and 0xff
        offset++
        var value = (b and 0x7f).toLong()
        while ((b and 0x80) != 0) {
            value++
            require(offset < data.size) { "packfile: truncated OFS_DELTA offset" }
            b = data[offset].toInt() and 0xff
            offset++
            value = (value shl 7) or (b and 0x7f).toLong()
        }
        require(value <= Int.MAX_VALUE) { "packfile: OFS_DELTA offset too large for Int ($value)" }
        return value.toInt()
    }

    private fun inflateNext(): ByteArray {
        val chunk = zlibInflateChunk(data, offset)
        offset += chunk.consumed
        return chunk.output
    }

    private fun baseType(packType: Int): ObjectType =
        when (packType) {
            1 -> ObjectType.COMMIT
            2 -> ObjectType.TREE
            3 -> ObjectType.BLOB
            4 -> ObjectType.TAG
            else -> error("packfile: not a base object type: $packType")
        }

    private fun parseFramedBase(framed: ByteArray): Pair<ObjectType, ByteArray> {
        val parsed =
            com.accucodeai.kash.tools.git.plumbing
                .parseFramedObject(framed)
        return parsed.type to parsed.payload
    }

    private fun readUInt32BE(at: Int): Int {
        val b0 = data[at].toInt() and 0xff
        val b1 = data[at + 1].toInt() and 0xff
        val b2 = data[at + 2].toInt() and 0xff
        val b3 = data[at + 3].toInt() and 0xff
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }
}

/**
 * Apply a git "delta" (the format produced by `pack-objects` for
 * deltified entries). The header is two variable-length-encoded sizes
 * (source size, target size); after that, each op byte either:
 *  - copies a span from [base] (bit 7 set; bits 6-4 select which of
 *    the next three bytes encode the offset, bits 3-0 size — missing
 *    "size" implicitly 0x10000), or
 *  - inserts the next N bytes literally (bit 7 clear; bits 6-0 = N).
 *
 * Public so callers can synthesize deltas in tests; in production
 * [PackfileReader] is the only caller.
 */
public fun applyDelta(
    base: ByteArray,
    delta: ByteArray,
): ByteArray {
    var i = 0
    val srcSize = readVarintSize(delta, i).also { i = it.second }.first
    require(srcSize == base.size.toLong()) {
        "delta: source-size mismatch (header=$srcSize base=${base.size})"
    }
    val tgtSize = readVarintSize(delta, i).also { i = it.second }.first
    val out = ByteArray(tgtSize.toInt())
    var written = 0
    while (i < delta.size) {
        val op = delta[i].toInt() and 0xff
        i++
        if ((op and 0x80) != 0) {
            // copy from base
            var srcOff = 0
            var len = 0
            if ((op and 0x01) != 0) {
                srcOff = srcOff or (delta[i++].toInt() and 0xff)
            }
            if ((op and 0x02) != 0) {
                srcOff = srcOff or ((delta[i++].toInt() and 0xff) shl 8)
            }
            if ((op and 0x04) != 0) {
                srcOff = srcOff or ((delta[i++].toInt() and 0xff) shl 16)
            }
            if ((op and 0x08) != 0) {
                srcOff = srcOff or ((delta[i++].toInt() and 0xff) shl 24)
            }
            if ((op and 0x10) != 0) {
                len = len or (delta[i++].toInt() and 0xff)
            }
            if ((op and 0x20) != 0) {
                len = len or ((delta[i++].toInt() and 0xff) shl 8)
            }
            if ((op and 0x40) != 0) {
                len = len or ((delta[i++].toInt() and 0xff) shl 16)
            }
            if (len == 0) len = 0x10000
            base.copyInto(out, written, srcOff, srcOff + len)
            written += len
        } else if (op != 0) {
            // insert literal
            delta.copyInto(out, written, i, i + op)
            written += op
            i += op
        } else {
            error("delta: reserved opcode 0x00")
        }
    }
    require(written.toLong() == tgtSize) { "delta: produced $written bytes, expected $tgtSize" }
    return out
}

private fun readVarintSize(
    data: ByteArray,
    offset: Int,
): Pair<Long, Int> {
    var i = offset
    var value = 0L
    var shift = 0
    while (true) {
        require(i < data.size) { "varint: truncated" }
        val b = data[i].toInt() and 0xff
        i++
        value = value or ((b and 0x7f).toLong() shl shift)
        shift += 7
        if ((b and 0x80) == 0) break
    }
    return value to i
}

private val HEX = "0123456789abcdef".toCharArray()

private fun bytesToHex20(
    src: ByteArray,
    off: Int,
): String {
    val sb = StringBuilder(40)
    for (k in 0 until 20) {
        val v = src[off + k].toInt() and 0xff
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0f])
    }
    return sb.toString()
}
