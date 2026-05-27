package com.accucodeai.kash.api.io

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers [BufferedSuspendSource] and its batched [readUtf8LineOrNull] /
 * [readUtf8DelimitedOrNull] overloads. The point of this wrapper is to
 * avoid one upstream call per byte for line readers — see
 * `BufferedSuspendSourceTest.requestsBatchUpstreamReads` for the
 * invariant that proves the batching actually happens.
 */
class BufferedSuspendSourceTest {
    /**
     * Counts every [readAtMostTo] call against the upstream so the test
     * can assert "exactly one upstream read for a 1 KB line read
     * line-by-line, not 1024".
     */
    private class CountingSource(
        private val backing: Buffer,
    ) : SuspendSource {
        var readCalls: Int = 0
            private set

        override suspend fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            readCalls++
            return backing.readAtMostTo(sink, byteCount)
        }

        override fun close() {}
    }

    private fun bufferOf(vararg bytes: Int): Buffer {
        val b = Buffer()
        b.write(bytes.map { it.toByte() }.toByteArray())
        return b
    }

    @Test fun asciiLineRoundTripsThroughBuffer() =
        runTest {
            val src = bufferOf(0x68, 0x69, 0x0A).asSuspendSource().buffered()
            assertEquals("hi", src.readUtf8LineOrNull())
            assertNull(src.readUtf8LineOrNull())
        }

    @Test fun multibyteUtf8RoundTripsThroughBuffer() =
        runTest {
            // "café\n😀\n"
            val src =
                bufferOf(
                    0x63,
                    0x61,
                    0x66,
                    0xC3,
                    0xA9,
                    0x0A, // café\n
                    0xF0,
                    0x9F,
                    0x98,
                    0x80,
                    0x0A, // 😀\n
                ).asSuspendSource().buffered()
            assertEquals("café", src.readUtf8LineOrNull())
            assertEquals("😀", src.readUtf8LineOrNull())
            assertNull(src.readUtf8LineOrNull())
        }

    @Test fun trailingLineWithoutNewline() =
        runTest {
            val src = bufferOf(0x68, 0xC3, 0xA9, 0x6C, 0x6C, 0x6F).asSuspendSource().buffered()
            assertEquals("héllo", src.readUtf8LineOrNull())
            assertNull(src.readUtf8LineOrNull())
        }

    @Test fun emptyUpstreamReturnsNull() =
        runTest {
            val src = Buffer().asSuspendSource().buffered()
            assertNull(src.readUtf8LineOrNull())
        }

    @Test fun bufferedIsIdempotent() =
        runTest {
            // Wrapping a BufferedSuspendSource again returns the same instance —
            // critical so callers don't accidentally fork the lookahead.
            val once = Buffer().asSuspendSource().buffered()
            val twice = once.buffered()
            assertSame(once, twice)
        }

    @Test fun readsBatchedAcrossManyLines() =
        runTest {
            // 1 KB of ASCII broken into 1-char lines: 512 newlines, 512 chars.
            // Without buffering, a per-byte readUtf8LineOrNull would issue
            // ~1024 upstream reads. With BufferedSuspendSource (chunkSize 8 KiB)
            // we expect exactly 1 upstream read for the whole stream.
            val bytes = ByteArray(1024)
            for (i in 0 until 512) {
                bytes[i * 2] = ('a' + (i % 26)).code.toByte()
                bytes[i * 2 + 1] = '\n'.code.toByte()
            }
            val backing = Buffer().also { it.write(bytes) }
            val counter = CountingSource(backing)
            val src = counter.buffered()
            var lines = 0
            while (src.readUtf8LineOrNull() != null) lines++
            assertEquals(512, lines)
            // 1 read to fill the buffer + 1 read that returns EOF (-1).
            // Anything in (0, 4] is acceptable — the point is "not 1024".
            assertTrue(counter.readCalls <= 4, "expected ≤4 upstream reads, got ${counter.readCalls}")
        }

    @Test fun readAtMostToForwardsAfterBufferDrained() =
        runTest {
            // Buffered wrapper must behave as a regular SuspendSource for
            // callers that mix line reads with raw byte reads. After
            // reading one line, raw readAtMostTo should pick up the rest.
            val src = bufferOf(0x61, 0x0A, 0x62, 0x63).asSuspendSource().buffered() // "a\nbc"
            assertEquals("a", src.readUtf8LineOrNull())
            val sink = Buffer()
            val n = src.readAtMostTo(sink, 16L)
            assertEquals(2L, n)
            assertEquals("bc", sink.readString())
        }

    @Test fun chunkBoundaryAcrossLineDelimiter() =
        runTest {
            // Construct a source whose chunkSize forces refills mid-line.
            // 4-byte chunks: each refill returns 4 bytes; the line
            // "hello\n" spans two refills.
            val backing = Buffer().also { it.write("hello\nworld\n".encodeToByteArray()) }
            val src = backing.asSuspendSource().buffered(chunkSize = 4)
            assertEquals("hello", src.readUtf8LineOrNull())
            assertEquals("world", src.readUtf8LineOrNull())
            assertNull(src.readUtf8LineOrNull())
        }
}
