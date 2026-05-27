package com.accucodeai.kash.tools.cksum

/**
 * POSIX `cksum` CRC-32 — the CRC-32/CKSUM variant.
 *
 * - Polynomial: 0x04C11DB7 (no reflection of input or output).
 * - After consuming all data bytes, the file length is fed in as a
 *   little-endian-by-byte stream (emit `len & 0xFF`, then `len >> 8`,
 *   etc., stopping once the residual is zero — empty file feeds nothing).
 * - Final XOR (complement) the accumulator with 0xFFFFFFFF.
 *
 * Reference values:
 *   empty       → 4294967295 (0xFFFFFFFF), size 0
 *   "a"         → 1220704766, size 1
 *   "abc"       → 1219131554, size 3
 *   "123456789" → 930766865, size 9
 */
public class Crc32Cksum {
    private var crc: Int = 0
    private var length: Long = 0L

    public fun update(
        bytes: ByteArray,
        offset: Int = 0,
        count: Int = bytes.size - offset,
    ) {
        var c = crc
        var i = offset
        val end = offset + count
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            val idx = ((c ushr 24) xor b) and 0xFF
            c = (c shl 8) xor TABLE[idx]
            i++
        }
        crc = c
        length += count.toLong()
    }

    public fun updateByte(b: Int) {
        val idx = ((crc ushr 24) xor (b and 0xFF)) and 0xFF
        crc = (crc shl 8) xor TABLE[idx]
        length++
    }

    /** Returns the 32-bit POSIX cksum value (unsigned, range 0..0xFFFFFFFF). */
    public fun finishUnsigned(): Long {
        // Feed length bytes (LE, stripping trailing zeros — empty input feeds nothing).
        var c = crc
        var n = length
        while (n != 0L) {
            val b = (n and 0xFF).toInt()
            val idx = ((c ushr 24) xor b) and 0xFF
            c = (c shl 8) xor TABLE[idx]
            n = n ushr 8
        }
        return (c.inv().toLong() and 0xFFFFFFFFL)
    }

    public fun byteCount(): Long = length

    public companion object {
        public val TABLE: IntArray =
            IntArray(256).also { t ->
                for (i in 0 until 256) {
                    var c = i shl 24
                    repeat(8) {
                        c = if ((c and 0x80000000.toInt()) != 0) (c shl 1) xor POLY else c shl 1
                    }
                    t[i] = c
                }
            }

        private const val POLY: Int = 0x04C11DB7

        /** Compute the cksum CRC + byte count over the given byte array. */
        public fun of(bytes: ByteArray): Pair<Long, Long> {
            val c = Crc32Cksum()
            c.update(bytes)
            return c.finishUnsigned() to c.byteCount()
        }
    }
}
