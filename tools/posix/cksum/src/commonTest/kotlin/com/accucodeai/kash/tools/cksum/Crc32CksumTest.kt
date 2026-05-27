package com.accucodeai.kash.tools.cksum

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reference values for POSIX CRC-32/CKSUM.
 *
 *   $ printf '' | cksum            → 4294967295 0
 *   $ printf 'a' | cksum           → 1220704766 1
 *   $ printf 'abc' | cksum         → 1219131554 3
 *   $ printf '123456789' | cksum   → 930766865 9
 *   $ printf 'a\n' | cksum         → 2768625435 2
 */
class Crc32CksumTest {
    @Test fun empty() {
        val (crc, size) = Crc32Cksum.of(ByteArray(0))
        assertEquals(4294967295L, crc)
        assertEquals(0L, size)
    }

    @Test fun singleA() {
        val (crc, size) = Crc32Cksum.of("a".encodeToByteArray())
        assertEquals(1220704766L, crc)
        assertEquals(1L, size)
    }

    @Test fun abc() {
        val (crc, size) = Crc32Cksum.of("abc".encodeToByteArray())
        assertEquals(1219131554L, crc)
        assertEquals(3L, size)
    }

    @Test fun digits() {
        val (crc, size) = Crc32Cksum.of("123456789".encodeToByteArray())
        assertEquals(930766865L, crc)
        assertEquals(9L, size)
    }

    @Test fun aNewline() {
        val (crc, size) = Crc32Cksum.of("a\n".encodeToByteArray())
        assertEquals(2418082923L, crc)
        assertEquals(2L, size)
    }

    @Test fun incrementalMatchesBulk() {
        val payload = "the quick brown fox jumps over the lazy dog".encodeToByteArray()
        val (bulk, bulkLen) = Crc32Cksum.of(payload)
        val c = Crc32Cksum()
        for (b in payload) c.updateByte(b.toInt() and 0xFF)
        assertEquals(bulk, c.finishUnsigned())
        assertEquals(bulkLen, c.byteCount())
    }

    @Test fun bulkVsIncrementalZeros() {
        val buf = ByteArray(1024)
        val (bulk, _) = Crc32Cksum.of(buf)
        val c = Crc32Cksum()
        for (b in buf) c.updateByte(b.toInt() and 0xFF)
        assertEquals(bulk, c.finishUnsigned())
        assertEquals(1024L, c.byteCount())
    }
}
