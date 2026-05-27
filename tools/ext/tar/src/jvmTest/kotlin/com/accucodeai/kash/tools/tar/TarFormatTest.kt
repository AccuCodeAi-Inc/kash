package com.accucodeai.kash.tools.tar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TarFormatTest {
    @Test
    fun encodeOctalPaddingAndTerminator() {
        val out = TarFormat.encodeOctal(0b111_101_101.toLong(), 8)
        // 7 chars + NUL
        assertEquals("0000755", out.copyOfRange(0, 7).toString(Charsets.US_ASCII))
        assertEquals(0.toByte(), out[7])
    }

    @Test
    fun encodeOctalZero() {
        val out = TarFormat.encodeOctal(0L, 12)
        assertEquals("00000000000", out.copyOfRange(0, 11).toString(Charsets.US_ASCII))
        assertEquals(0.toByte(), out[11])
    }

    @Test
    fun decodeOctalRoundTrips() {
        val enc = TarFormat.encodeOctal(123456L, 12)
        val block = ByteArray(512)
        enc.copyInto(block, 124, 0, 12)
        assertEquals(123456L, TarFormat.decodeOctal(block, 124, 12))
    }

    @Test
    fun decodeOctalAcceptsTrailingSpace() {
        val block = ByteArray(512)
        val raw = "012345  "
        for (i in raw.indices) block[148 + i] = raw[i].code.toByte()
        // 012345 octal = 0o12345
        assertEquals(0b001_010_011_100_101.toLong(), TarFormat.decodeOctal(block, 148, 8))
    }

    @Test
    fun checksumOfZeroHeaderIs256SpacesPlusTypeflagZero() {
        val block = ByteArray(512)
        assertEquals(8 * 0x20, TarFormat.checksum(block))
    }

    @Test
    fun isZeroBlockDetectsAllZero() {
        assertTrue(TarFormat.isZeroBlock(ByteArray(512)))
    }

    @Test
    fun isZeroBlockRejectsAnyNonZero() {
        val b = ByteArray(512)
        b[100] = 1
        assertTrue(!TarFormat.isZeroBlock(b))
    }

    @Test
    fun buildUstarHeaderHasMagicAndChecksum() {
        val h =
            buildUstarHeader(
                name = "hello.txt",
                mode = 0b110_100_100,
                uid = 0,
                gid = 0,
                size = 5L,
                mtime = 0L,
                typeflag = TarFormat.TF_REGULAR,
            )
        assertEquals(512, h.size)
        // magic at 257..261 = "ustar", 262 = NUL, 263..264 = "00"
        val magic = h.copyOfRange(257, 262).toString(Charsets.US_ASCII)
        assertEquals("ustar", magic)
        assertEquals(0.toByte(), h[262])
        assertEquals('0'.code.toByte(), h[263])
        assertEquals('0'.code.toByte(), h[264])
        // typeflag at 156 = '0'
        assertEquals('0'.code.toByte(), h[156])
        // chksum self-consistent
        val recorded = TarFormat.decodeOctal(h, 148, 8)
        val computed = TarFormat.checksum(h).toLong()
        assertEquals(computed, recorded)
        // name
        val name = TarFormat.decodeString(h, 0, 100)
        assertEquals("hello.txt", name)
    }

    @Test
    fun gnuBase256BinarySizeDecodes() {
        val block = ByteArray(512)
        block[124] = 0x80.toByte()
        // remaining 11 bytes: 0,0,0,0,0,0,0,0,0x12,0x34,0x56
        block[133] = 0x12
        block[134] = 0x34
        block[135] = 0x56
        val want = (0x12L shl 16) or (0x34L shl 8) or 0x56L
        assertEquals(want, TarFormat.decodeOctal(block, 124, 12))
    }
}
