package com.accucodeai.kash.tools.git.plumbing

/**
 * zlib block deflate/inflate. Git uses zlib (RFC 1950, with the 2-byte
 * header and Adler-32 trailer — NOT raw deflate) for loose-object storage
 * and packfile deltified content.
 *
 * Compressed bytes can differ between zlib versions even at the same
 * compression level — but the *uncompressed* bytes are what gives the
 * object its SHA, so a host extracting our `.git/` and re-running real git
 * tooling against it will succeed. Tests assert uncompressed-roundtrip,
 * not compressed-byte-match.
 */
public expect fun zlibDeflate(
    data: ByteArray,
    level: Int = 1,
): ByteArray

public expect fun zlibInflate(data: ByteArray): ByteArray

/**
 * Inflated output paired with the number of *input* bytes consumed.
 * Used by packfile parsers, where each entry's zlib stream is followed
 * directly by the next entry's variable-length header and we don't know
 * the deflate end-position until the inflater reports BFINAL=1.
 */
public data class InflatedChunk(
    val output: ByteArray,
    val consumed: Int,
)

/**
 * Inflate the zlib stream starting at [offset] in [data]. Returns the
 * decompressed bytes plus how many bytes of [data] (from [offset]) were
 * consumed. Throws if the stream is truncated or malformed.
 */
public expect fun zlibInflateChunk(
    data: ByteArray,
    offset: Int = 0,
): InflatedChunk
