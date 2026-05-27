package com.accucodeai.kash.tools.git.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lock down the pkt-line framing against the wire format documented in
 * `Documentation/technical/pack-protocol.txt`. We deliberately use
 * [PktLine.encodeText] to build fixtures rather than hand-rolling hex
 * length prefixes — the lengths are too easy to miscount by eye.
 */
class PktLineTest {
    @Test fun encodesTextWithFourByteHexLengthPrefix() {
        // payload "a\n" (2 bytes) → frame length = 2 + 4 = 6 = 0x0006.
        val frame = PktLine.encodeText("a\n")
        assertEquals("0006a\n", frame.decodeToString())
    }

    @Test fun encodesEmptyPayloadAsLength0004() {
        val frame = PktLine.encode(byteArrayOf())
        assertEquals("0004", frame.decodeToString())
    }

    @Test fun flushIsLiteral0000() {
        assertEquals("0000", PktLine.flush.decodeToString())
    }

    @Test fun readsAndDecodesFrames() {
        val body =
            PktLine.encodeText("# service=git-upload-pack\n") +
                PktLine.flush +
                PktLine.encodeText("1111111111111111111111111111111111111111 refs/heads/main\n") +
                PktLine.encodeText("2222222222222222222222222222222222222222 refs/heads/dev\n") +
                PktLine.flush
        val reader = PktLineReader(body)
        val banner = reader.next()
        assertNotNull(banner)
        assertEquals("# service=git-upload-pack\n", banner.payload!!.decodeToString())
        val flush1 = reader.next()
        assertNotNull(flush1)
        assertTrue(flush1.isFlush)
        val refMain = reader.next()
        assertNotNull(refMain)
        assertTrue(refMain.payload!!.decodeToString().startsWith("1111"))
        val refDev = reader.next()
        assertNotNull(refDev)
        assertTrue(refDev.payload!!.decodeToString().startsWith("2222"))
        val flush2 = reader.next()
        assertNotNull(flush2)
        assertTrue(flush2.isFlush)
        assertNull(reader.next())
    }

    @Test fun parseAdvertisedRefsExtractsRefsAndStripsCapabilities() {
        val sha1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val sha2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        // first ref carries capabilities after a NUL — build the payload
        // by hand to embed the NUL byte.
        val payload1 =
            "$sha1 refs/heads/main".encodeToByteArray() +
                byteArrayOf(0) +
                "multi_ack ofs-delta agent=test\n".encodeToByteArray()
        val frame1 = PktLine.encode(payload1)
        val frame2 = PktLine.encodeText("$sha2 refs/heads/dev\n")
        val body =
            PktLine.encodeText("# service=git-upload-pack\n") +
                PktLine.flush +
                frame1 +
                frame2 +
                PktLine.flush
        val refs = parseAdvertisedRefs(body)
        assertEquals(2, refs.size)
        assertEquals(sha1, refs[0].sha)
        assertEquals("refs/heads/main", refs[0].name)
        assertEquals(sha2, refs[1].sha)
        assertEquals("refs/heads/dev", refs[1].name)
    }

    @Test fun buildUploadPackBodyHasWantsThenFlushThenDone() {
        val wants = listOf("1111111111111111111111111111111111111111", "2222222222222222222222222222222222222222")
        val body = buildUploadPackBody(wants).decodeToString()
        assertTrue(
            body.contains("want 1111111111111111111111111111111111111111 "),
            "first want missing or wrong: <$body>",
        )
        // First want carries the cap list — ofs-delta + side-band-64k +
        // no-progress + agent are always on, filter only when scope.filter
        // is set. Spot-check the always-on ones.
        assertTrue(body.contains("ofs-delta"), "missing ofs-delta cap: <$body>")
        assertTrue(body.contains("side-band-64k"), "missing side-band-64k cap: <$body>")
        assertTrue(body.contains("no-progress"), "missing no-progress cap: <$body>")
        assertTrue(body.contains("agent=kash/0.1"), "missing agent cap: <$body>")
        assertTrue(
            body.contains("want 2222222222222222222222222222222222222222\n"),
            "second want missing or wrong: <$body>",
        )
        assertTrue(body.contains("0000"), "missing flush separator")
        assertTrue(body.endsWith("done\n"), "missing terminating done line")
    }
}
