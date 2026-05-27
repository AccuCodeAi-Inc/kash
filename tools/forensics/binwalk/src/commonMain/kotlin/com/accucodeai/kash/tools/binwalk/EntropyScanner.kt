package com.accucodeai.kash.tools.binwalk

import kotlin.math.ln

/** Normalized Shannon entropy (0.0–1.0) of [block] — bits/byte divided by 8. */
internal fun shannonEntropy(
    block: ByteArray,
    length: Int = block.size,
): Double {
    if (length == 0) return 0.0
    val counts = IntArray(256)
    for (i in 0 until length) counts[block[i].toInt() and 0xff]++
    var h = 0.0
    val n = length.toDouble()
    for (c in counts) {
        if (c == 0) continue
        val p = c / n
        h -= p * (ln(p) / LN2)
    }
    return h / 8.0
}

private const val LN2 = 0.6931471805599453

/** A computed entropy reading for one block. */
internal data class EntropyBlock(
    val offset: Long,
    val entropy: Double,
)

/** Entropy at or above this (normalized) is flagged as likely compressed/encrypted. */
internal const val HIGH_ENTROPY: Double = 0.95
