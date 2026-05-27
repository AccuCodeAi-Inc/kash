package com.accucodeai.kash.tools.python3.pyodide.worker

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StdinRingMathTest {
    @Test fun availableIsZeroWhenEmpty() {
        assertEquals(0, StdinRingMath.available(head = 0, tail = 0))
        assertEquals(0, StdinRingMath.available(head = 12345, tail = 12345))
    }

    @Test fun availableTracksDistanceWithoutWrap() {
        assertEquals(7, StdinRingMath.available(head = 17, tail = 10))
    }

    @Test fun availableHandlesWrap() {
        // head wrapped past WRAP, tail did not — distance is small positive.
        val head = 5
        val tail = StdinRingMath.WRAP - 3
        assertEquals(8, StdinRingMath.available(head = head, tail = tail))
    }

    @Test fun freeAccountsForOccupiedBytes() {
        val cap = 64
        assertEquals(cap, StdinRingMath.free(cap, head = 0, tail = 0))
        assertEquals(cap - 5, StdinRingMath.free(cap, head = 5, tail = 0))
    }

    @Test fun advanceWrapsAtWrapConstant() {
        val nearWrap = StdinRingMath.WRAP - 3
        assertEquals(2, StdinRingMath.advance(nearWrap, 5))
    }

    @Test fun byteIndexIsModulo() {
        val cap = 16
        assertEquals(0, StdinRingMath.byteIndex(0, cap))
        assertEquals(7, StdinRingMath.byteIndex(7, cap))
        assertEquals(0, StdinRingMath.byteIndex(16, cap))
        assertEquals(3, StdinRingMath.byteIndex(19, cap))
    }

    @Test fun planWriteFromEmptyFillsToCapacityOrRequest() {
        val cap = 16
        // empty ring, ask for 8 — get 8 from offset 0
        val r = StdinRingMath.planWrite(cap, head = 0, tail = 0, requested = 8)
        assertEquals(Region(0, 8), r)
    }

    @Test fun planWriteSplitsAtWrap() {
        val cap = 16
        // head at 14, tail at 6 — 2 bytes till end of array, asking for 5
        val r = StdinRingMath.planWrite(cap, head = 14, tail = 6, requested = 5)
        assertEquals(Region(14, 2), r)
        // Caller advances head by 2; ring is now [tail=6 .. head=16), 10 bytes
        // occupied, 6 free. Next write fragment lands at offset 0 (wrap).
        val r2 = StdinRingMath.planWrite(cap, head = 16, tail = 6, requested = 3)
        assertEquals(Region(0, 3), r2)
    }

    @Test fun planWriteCapsAtFreeBytes() {
        val cap = 16
        // 12 bytes occupied, only 4 free
        val r = StdinRingMath.planWrite(cap, head = 12, tail = 0, requested = 100)
        assertEquals(Region(12, 4), r)
    }

    @Test fun planWriteReturnsEmptyWhenFull() {
        val cap = 16
        val r = StdinRingMath.planWrite(cap, head = 16, tail = 0, requested = 5)
        assertEquals(Region(0, 0), r)
    }

    @Test fun planReadMirrorsPlanWrite() {
        val cap = 16
        // 10 bytes available, ask for 4 — get 4 from offset of tail
        val r = StdinRingMath.planRead(cap, head = 10, tail = 0, requested = 4)
        assertEquals(Region(0, 4), r)
        // tail near end, must split
        val r2 = StdinRingMath.planRead(cap, head = 18, tail = 14, requested = 10)
        assertEquals(Region(14, 2), r2)
    }

    @Test fun planReadReturnsEmptyWhenEmpty() {
        val r = StdinRingMath.planRead(16, head = 7, tail = 7, requested = 5)
        assertEquals(Region(0, 0), r)
    }

    @Test fun requirePowerOfTwoRejectsBadSizes() {
        assertFailsWith<IllegalArgumentException> { StdinRingMath.requirePowerOfTwo(0) }
        assertFailsWith<IllegalArgumentException> { StdinRingMath.requirePowerOfTwo(3) }
        assertFailsWith<IllegalArgumentException> { StdinRingMath.requirePowerOfTwo(StdinRingMath.WRAP) }
        StdinRingMath.requirePowerOfTwo(1)
        StdinRingMath.requirePowerOfTwo(64)
        StdinRingMath.requirePowerOfTwo(65536)
    }

    /**
     * End-to-end round trip on the pure math: write 1000 bytes through a
     * 64-byte ring in small chunks, read them back, assert FIFO order. The
     * production code is structurally identical, just with Atomics.store /
     * Atomics.load wrapping each cursor mutation.
     */
    @Test fun roundTripThroughSmallRingPreservesOrder() {
        val cap = 64
        val ring = ByteArray(cap)
        var head = 0
        var tail = 0

        val source = ByteArray(1000) { (it and 0xFF).toByte() }
        val sink = ByteArray(1000)
        var written = 0
        var read = 0

        while (read < source.size) {
            // Producer writes as much as possible.
            while (written < source.size) {
                val r = StdinRingMath.planWrite(cap, head, tail, source.size - written)
                if (r.length == 0) break
                source.copyInto(ring, r.offset, written, written + r.length)
                head = StdinRingMath.advance(head, r.length)
                written += r.length
            }
            // Consumer drains a small chunk (≤7 bytes) so we exercise wrap.
            val r = StdinRingMath.planRead(cap, head, tail, requested = 7)
            if (r.length == 0) continue
            ring.copyInto(sink, read, r.offset, r.offset + r.length)
            tail = StdinRingMath.advance(tail, r.length)
            read += r.length
        }

        assertEquals(source.toList(), sink.toList())
    }
}
