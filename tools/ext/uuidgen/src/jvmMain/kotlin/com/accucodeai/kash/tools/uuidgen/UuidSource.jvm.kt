package com.accucodeai.kash.tools.uuidgen

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal actual fun newRandomUuid(): String = UUID.randomUUID().toString()

// --- v1 (time-based) generation ---
//
// RFC 4122 layout, big-endian:
//   time_low(32) | time_mid(16) | time_hi_and_version(16) | clock_seq(16) | node(48)
// where the 100-ns timestamp is counted from 1582-10-15 00:00 UTC (Gregorian
// epoch offset = 122_192_928_000_000_000 100-ns ticks before Unix epoch).
// We synthesize a random multicast node (low bit of MSB = 1, per RFC) and a
// random clock_seq once per process; we monotonically advance the timestamp
// inside the same 100-ns tick to avoid collisions on fast loops.

private val random = SecureRandom()
private val nodeId: Long =
    run {
        val b = ByteArray(6)
        random.nextBytes(b)
        // Set multicast bit so it's clearly not a real MAC.
        b[0] = (b[0].toInt() or 0x01).toByte()
        var n = 0L
        for (x in b) n = (n shl 8) or (x.toLong() and 0xFF)
        n
    }
private val clockSeq: Int = random.nextInt() and 0x3FFF
private val lastTimestamp = AtomicLong(0L)

private const val GREGORIAN_OFFSET_100NS = 122_192_928_000_000_000L

internal actual fun newTimeUuid(): String {
    val now100ns = System.currentTimeMillis() * 10_000L + GREGORIAN_OFFSET_100NS
    val ts =
        lastTimestamp.updateAndGet { prev ->
            if (now100ns > prev) now100ns else prev + 1
        }

    val timeLow = ts and 0xFFFFFFFFL
    val timeMid = (ts shr 32) and 0xFFFFL
    val timeHi = ((ts shr 48) and 0x0FFFL) or 0x1000L // version 1
    val csHiVar = ((clockSeq shr 8) and 0x3F) or 0x80 // variant 10
    val csLo = clockSeq and 0xFF

    return buildString(36) {
        appendHex(timeLow, 8)
        append('-')
        appendHex(timeMid, 4)
        append('-')
        appendHex(timeHi, 4)
        append('-')
        appendHex(csHiVar.toLong(), 2)
        appendHex(csLo.toLong(), 2)
        append('-')
        appendHex(nodeId, 12)
    }
}

private fun StringBuilder.appendHex(
    value: Long,
    width: Int,
) {
    val s = value.toString(16)
    repeat(width - s.length) { append('0') }
    append(s)
}
