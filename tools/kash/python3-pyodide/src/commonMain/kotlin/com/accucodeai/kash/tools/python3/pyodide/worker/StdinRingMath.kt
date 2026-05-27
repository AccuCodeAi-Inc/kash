package com.accucodeai.kash.tools.python3.pyodide.worker

/**
 * Pure cursor math for the SAB-backed stdin ring. Kept in commonMain so JVM
 * tests can exercise wrap-around / EOF / overflow without spinning up a
 * browser worker.
 *
 * The ring is a power-of-two byte buffer. Two cursors live in Int32 slots
 * inside the same SharedArrayBuffer:
 *   - HEAD: producer write cursor (main thread bumps; worker reads via
 *           Atomics.load + Atomics.wait)
 *   - TAIL: consumer read cursor  (worker bumps; main thread reads via
 *           Atomics.load)
 *
 * Both cursors are *unbounded monotone* integers — we only mod by [capacity]
 * when indexing into the byte array. That makes "empty vs full" trivially
 * `head == tail` vs `head - tail == capacity` without losing a slot to
 * disambiguate. Wrap-around: each cursor wraps at [WRAP] which is a multiple
 * of capacity and well below Int32 overflow, so the producer can rebase on
 * wrap.
 *
 * Why not just `Channel<Byte>`? The worker side has to block in a *synchronous*
 * Pyodide `setStdin` callback. Atomics.wait on the SAB head slot is the only
 * primitive that suspends a Worker without an event loop. Channel can't.
 */
public object StdinRingMath {
    /** Producer rebase point. Stays well below Int32.MAX_VALUE. */
    public const val WRAP: Int = 1 shl 30

    /** Slot indices in the Int32 control area. */
    public const val SLOT_HEAD: Int = 0
    public const val SLOT_TAIL: Int = 1
    public const val SLOT_EOF: Int = 2

    /** Number of Int32 control slots (head, tail, eof). */
    public const val CONTROL_SLOTS: Int = 3

    /** Bytes occupied by the control header. */
    public const val CONTROL_BYTES: Int = CONTROL_SLOTS * 4

    /**
     * Number of bytes available for the consumer to read, given the latest
     * snapshot of [head] and [tail]. Always non-negative; equals 0 iff empty.
     */
    public fun available(
        head: Int,
        tail: Int,
    ): Int {
        // Both are monotone modulo WRAP; head is always >= tail in arithmetic
        // distance. Subtract with wrap-aware arithmetic.
        val raw = head - tail
        return if (raw >= 0) raw else raw + WRAP
    }

    /**
     * Free bytes the producer can still write before overrunning the
     * consumer. Reserves one slot? No — we use a separate full/empty
     * distinction via head==tail (empty) vs available==capacity (full), so
     * we can use every byte of [capacity].
     */
    public fun free(
        capacity: Int,
        head: Int,
        tail: Int,
    ): Int = capacity - available(head, tail)

    /**
     * Bump a cursor by [n], wrapping at [WRAP]. The caller is responsible
     * for ensuring [n] in `0..capacity` — overshooting WRAP in one bump is
     * impossible for sane buffer sizes.
     */
    public fun advance(
        cursor: Int,
        n: Int,
    ): Int {
        val next = cursor + n
        return if (next >= WRAP) next - WRAP else next
    }

    /**
     * Index into the byte array for a given cursor, given [capacity]
     * (power-of-two). Returns `cursor mod capacity`.
     */
    public fun byteIndex(
        cursor: Int,
        capacity: Int,
    ): Int = cursor and (capacity - 1)

    /**
     * Plan a contiguous write of up to [requested] bytes starting at the
     * current [head]. Returns a [Region] giving the byte-array offset and
     * the number of bytes that fit without wrapping. The caller writes that
     * many bytes, then either advances head or calls [planWrite] again with
     * the remainder to fill the wrap-around fragment.
     *
     * Returns Region(offset=0, length=0) if there's no free space.
     */
    public fun planWrite(
        capacity: Int,
        head: Int,
        tail: Int,
        requested: Int,
    ): Region {
        val freeBytes = free(capacity, head, tail)
        if (freeBytes == 0 || requested == 0) return Region(0, 0)
        val offset = byteIndex(head, capacity)
        val tillEnd = capacity - offset
        val length = minOf(requested, freeBytes, tillEnd)
        return Region(offset, length)
    }

    /**
     * Same shape as [planWrite] but for the consumer. Returns the next
     * contiguous readable region.
     */
    public fun planRead(
        capacity: Int,
        head: Int,
        tail: Int,
        requested: Int,
    ): Region {
        val avail = available(head, tail)
        if (avail == 0 || requested == 0) return Region(0, 0)
        val offset = byteIndex(tail, capacity)
        val tillEnd = capacity - offset
        val length = minOf(requested, avail, tillEnd)
        return Region(offset, length)
    }

    /**
     * Validate that [capacity] is a positive power of two. Required so
     * `cursor and (capacity - 1)` is equivalent to `cursor mod capacity`.
     */
    public fun requirePowerOfTwo(capacity: Int) {
        require(capacity > 0) { "capacity must be > 0, got $capacity" }
        require(capacity and (capacity - 1) == 0) { "capacity must be a power of two, got $capacity" }
        require(capacity <= WRAP / 2) { "capacity ($capacity) too large for WRAP ($WRAP)" }
    }
}

/** Contiguous byte region: `[offset, offset + length)`. */
public data class Region(
    val offset: Int,
    val length: Int,
)
