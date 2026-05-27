package com.accucodeai.kash.test

import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.io.Buffer

/**
 * An infinite [SuspendSource] that throws after it has been asked to
 * deliver more than [safetyLimit] bytes. Lets tests assert that a tool
 * stops reading early on a `head -c N`-style request without risking
 * an actual OOM when the tool has a bug and tries to drain the source.
 *
 * Each call to [readAtMostTo] returns up to [chunkSize] bytes (default
 * 4 KiB, matching real `/dev/urandom`'s `getrandom(2)` short-read
 * threshold). Bytes are pseudo-random but **deterministic for a given
 * [seed]** so tests can assert exact output.
 *
 * Typical use:
 * ```
 *   val src = BoundedSuspendSource()
 *   val rc = MyToolCommand().run(listOf("-c", "16"), ctx)
 *   // If MyToolCommand drains the source, the test fails with
 *   // AssertionError before allocating gigabytes of memory.
 * ```
 */
public class BoundedSuspendSource(
    private val safetyLimit: Long = 1L * 1024 * 1024, // 1 MiB
    private val chunkSize: Int = 4 * 1024,
    private val seed: Int = 0x4B415348, // "KASH"
) : SuspendSource {
    public var bytesServed: Long = 0L
        private set

    private var rng: Int = seed

    public fun nextByte(): Byte {
        // Tiny LCG — predictable, no allocation, deterministic across platforms.
        rng = rng * 1103515245 + 12345
        return (rng ushr 16).toByte()
    }

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (byteCount <= 0L) return 0L
        val n = byteCount.coerceAtMost(chunkSize.toLong()).toInt()
        val bytes = ByteArray(n) { nextByte() }
        sink.write(bytes)
        bytesServed += n
        check(bytesServed <= safetyLimit) {
            "BoundedSuspendSource exceeded safety limit of $safetyLimit bytes " +
                "(consumer pulled $bytesServed) — looks like the consumer is " +
                "trying to drain an infinite source"
        }
        return n.toLong()
    }

    override fun close() {}
}
