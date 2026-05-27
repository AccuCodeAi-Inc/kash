package com.accucodeai.kash.magic

/**
 * A single magic-number signature.
 *
 * @property offset byte offset at which [pattern] must appear.
 * @property pattern expected bytes; an entry of `-1` is a wildcard that
 *   matches any byte (used for length/size fields inside container headers).
 * @property match the result to report when this signature matches and there
 *   is no [refine], or when [refine] is absent.
 * @property refine optional second pass. When present, the signature only
 *   matches if `refine(buffer)` returns non-null, and that value is reported
 *   instead of [match]. Used to extract versions / disambiguate formats that
 *   share a leading magic (e.g. `CA FE BA BE` = Mach-O fat *or* Java class).
 */
internal class Signature(
    val offset: Int,
    val pattern: IntArray,
    val match: MagicMatch,
    val refine: ((ByteArray) -> MagicMatch?)? = null,
) {
    fun matchesAt(buf: ByteArray): Boolean = matchesAtBase(buf, 0)

    /**
     * True if an embedded item of this type starts at [base] in [buf] — i.e.
     * the magic bytes sit at `base + offset`. Used by [KMagic.scan] to find
     * signatures anywhere in a blob, not just at the file start.
     */
    fun matchesAtBase(
        buf: ByteArray,
        base: Int,
    ): Boolean {
        val start = base + offset
        if (start < 0 || buf.size < start + pattern.size) return false
        for (i in pattern.indices) {
            val expected = pattern[i]
            if (expected < 0) continue
            if ((buf[start + i].toInt() and 0xff) != expected) return false
        }
        return true
    }

    /** First concrete (non-wildcard) pattern byte, or -1 if the pattern leads with a wildcard. */
    val leadByte: Int get() = if (pattern.isNotEmpty() && pattern[0] >= 0) pattern[0] else -1
}

/** Parse `"50 4b 03 04"` / `"52 49 46 46 ?? ?? ?? ?? 57 41 56 45"` to an IntArray. */
internal fun bytePattern(hex: String): IntArray {
    val tokens = hex.trim().split(Regex("\\s+"))
    return IntArray(tokens.size) { i ->
        val t = tokens[i]
        if (t == "??" || t == "..") -1 else t.toInt(16)
    }
}
