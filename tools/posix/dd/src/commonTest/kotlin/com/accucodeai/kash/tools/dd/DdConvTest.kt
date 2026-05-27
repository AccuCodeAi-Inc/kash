package com.accucodeai.kash.tools.dd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DdConvTest {
    @Test fun `lcase converts ASCII letters only`() {
        val r = DdConv.lcase("ABCxyz!@".encodeToByteArray())
        assertEquals("abcxyz!@", r.decodeToString())
    }

    @Test fun `ucase converts ASCII letters only`() {
        val r = DdConv.ucase("abcXYZ!@".encodeToByteArray())
        assertEquals("ABCXYZ!@", r.decodeToString())
    }

    @Test fun `lcase leaves high bytes alone`() {
        val r = DdConv.lcase(byteArrayOf(0xC3.toByte(), 0x9E.toByte()))
        assertContentEquals(byteArrayOf(0xC3.toByte(), 0x9E.toByte()), r)
    }

    @Test fun `swab swaps byte pairs`() {
        val r = DdConv.swab(byteArrayOf(1, 2, 3, 4))
        assertContentEquals(byteArrayOf(2, 1, 4, 3), r)
    }

    @Test fun `swab on odd length leaves trailing byte`() {
        val r = DdConv.swab(byteArrayOf(1, 2, 3, 4, 5))
        assertContentEquals(byteArrayOf(2, 1, 4, 3, 5), r)
    }

    @Test fun `swab single byte unchanged`() {
        val r = DdConv.swab(byteArrayOf(7))
        assertContentEquals(byteArrayOf(7), r)
    }

    @Test fun `sync pads with NUL up to size`() {
        val r = DdConv.sync(byteArrayOf(1, 2, 3), 8)
        assertContentEquals(byteArrayOf(1, 2, 3, 0, 0, 0, 0, 0), r)
    }

    @Test fun `sync already-full unchanged`() {
        val r = DdConv.sync(byteArrayOf(1, 2, 3, 4), 4)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), r)
    }

    @Test fun `sync over-full unchanged`() {
        val r = DdConv.sync(byteArrayOf(1, 2, 3, 4, 5), 4)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), r)
    }

    @Test fun `block pads records to cbs with spaces`() {
        val tc = IntArray(1)
        val r = DdConv.block("hi\nworld\n".encodeToByteArray(), cbs = 5, truncCounter = tc)
        // "hi   " + "world"
        assertEquals("hi   world", r.decodeToString())
        assertEquals(0, tc[0])
    }

    @Test fun `block truncates oversized records and counts`() {
        val tc = IntArray(1)
        val r = DdConv.block("toolong\nok\n".encodeToByteArray(), cbs = 4, truncCounter = tc)
        // "tool" + "ok  "
        assertEquals("toolok  ", r.decodeToString())
        assertEquals(1, tc[0])
    }

    @Test fun `unblock strips trailing spaces and adds newline`() {
        val r = DdConv.unblock("hi   world".encodeToByteArray(), cbs = 5)
        assertEquals("hi\nworld\n", r.decodeToString())
    }

    @Test fun `unblock handles full record without trailing space`() {
        val r = DdConv.unblock("abcde".encodeToByteArray(), cbs = 5)
        assertEquals("abcde\n", r.decodeToString())
    }

    @Test fun `unblock partial trailing record`() {
        val r = DdConv.unblock("abcdefg ".encodeToByteArray(), cbs = 5)
        // first record "abcde" -> "abcde\n"; second "fg  " (3 bytes here, but we have "fg ")
        // record is "fg " stripped to "fg" + "\n"
        assertEquals("abcde\nfg\n", r.decodeToString())
    }
}
