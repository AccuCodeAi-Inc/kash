package com.accucodeai.kash.tools.git.plumbing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compressed bytes can drift between zlib implementations even at the
 * same level, so the contract we test is the uncompressed roundtrip plus
 * the zlib framing (RFC 1950 header in the first two bytes).
 */
class ZlibTest {
    @Test fun roundTripEmpty() {
        val deflated = zlibDeflate(ByteArray(0))
        assertTrue(deflated.size >= 2)
        assertEquals(0, zlibInflate(deflated).size)
    }

    @Test fun roundTripSmall() {
        val data = "the quick brown fox jumps over the lazy dog".encodeToByteArray()
        val out = zlibInflate(zlibDeflate(data))
        assertEquals(data.toList(), out.toList())
    }

    @Test fun roundTripBinary() {
        val data = ByteArray(4096) { (it xor (it ushr 3)).toByte() }
        val out = zlibInflate(zlibDeflate(data))
        assertEquals(data.toList(), out.toList())
    }

    @Test fun frameLooksLikeZlib() {
        // RFC 1950: first byte is the CMF (compression method+flags).
        // Low nibble is the compression method, which must be 8 (deflate).
        val out = zlibDeflate("x".encodeToByteArray())
        assertEquals(8, out[0].toInt() and 0x0f)
    }
}
