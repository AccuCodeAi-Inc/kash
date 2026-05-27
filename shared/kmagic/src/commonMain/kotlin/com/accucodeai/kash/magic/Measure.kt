package com.accucodeai.kash.magic

/**
 * Best-effort exact length of an embedded item starting at [start] in [blob],
 * for formats whose size is cheaply and unambiguously derivable from structure.
 * Returns null when the length can't be determined without decompression or
 * deep parsing — callers then fall back to carve-to-next-hit.
 *
 * Currently exact for PNG (walk to the IEND chunk) and BMP (header size field).
 * gzip/JPEG/ZIP need stream decoding or multi-record parsing and are deferred.
 */
internal fun measureLength(
    blob: ByteArray,
    start: Int,
    match: MagicMatch,
): Long? =
    when (match.mime) {
        "image/png" -> pngLength(blob, start)
        "image/bmp" -> bmpLength(blob, start)
        else -> null
    }

private fun pngLength(
    blob: ByteArray,
    start: Int,
): Long? {
    // 8-byte signature, then chunks: len(4 BE) + type(4) + data + crc(4).
    var p = start + 8
    while (p + 8 <= blob.size) {
        val len = u32be(blob, p) ?: return null
        val typeStart = p + 4
        if (typeStart + 4 > blob.size) return null
        val type = blob.copyOfRange(typeStart, typeStart + 4).decodeToString()
        val next = p.toLong() + 8L + len + 4L // len + type + data + crc
        if (type == "IEND") {
            return if (next <= blob.size) next - start else null
        }
        if (next > blob.size || next <= p) return null
        p = next.toInt()
    }
    return null
}

private fun bmpLength(
    blob: ByteArray,
    start: Int,
): Long? {
    // BMP file header: "BM", then a 4-byte LE total file size at offset 2.
    if (start + 6 > blob.size) return null
    var v = 0L
    for (k in 0 until 4) v = v or ((blob[start + 2 + k].toLong() and 0xff) shl (8 * k))
    return if (v in 1..(blob.size - start).toLong()) v else null
}

private fun u32be(
    b: ByteArray,
    i: Int,
): Long? {
    if (i + 4 > b.size) return null
    var v = 0L
    for (k in 0 until 4) v = (v shl 8) or (b[i + k].toLong() and 0xff)
    return v
}
