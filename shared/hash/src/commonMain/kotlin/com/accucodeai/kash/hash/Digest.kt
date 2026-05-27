package com.accucodeai.kash.hash

import com.accucodeai.kash.api.io.SuspendSource

/**
 * Hash algorithms supported across kash. The wire identifier here is the
 * shasum-style bit length so callers from `shasum` can pass `1`, `256`,
 * etc. without translating. `0` is the legacy MD5 hook `shasum` exposes.
 */
public enum class HashAlg(
    public val bits: Int,
) {
    MD5(0),
    SHA1(1),
    SHA224(224),
    SHA256(256),
    SHA384(384),
    SHA512(512),
    ;

    public companion object {
        public fun ofBits(bits: Int): HashAlg =
            entries.firstOrNull { it.bits == bits }
                ?: error("unsupported hash algorithm: $bits")
    }
}

/**
 * Hash a complete byte array — the synchronous, block-oriented entry point
 * used by formats that frame their own input (git objects, pack index,
 * etc.). Returns the raw digest bytes (20 for SHA-1, 32 for SHA-256, …).
 */
public expect fun hashBytes(
    alg: HashAlg,
    data: ByteArray,
): ByteArray

/**
 * Stream [source] through the digest in chunks — the right shape for
 * arbitrarily large user input (`shasum file.iso`). Returns lowercase hex.
 */
public expect suspend fun hashStream(
    alg: HashAlg,
    source: SuspendSource,
): String

/** Lowercase hex encoding. */
public fun toHex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        sb.append(digits[v ushr 4])
        sb.append(digits[v and 0x0f])
    }
    return sb.toString()
}

/** Convenience: block-hash + hex-encode in one call. */
public fun hashHex(
    alg: HashAlg,
    data: ByteArray,
): String = toHex(hashBytes(alg, data))

/** Parse 2N-char hex back to raw bytes. */
public fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex length must be even" }
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = ((nibble(hex[i * 2]) shl 4) or nibble(hex[i * 2 + 1])).toByte()
    }
    return out
}

private fun nibble(c: Char): Int =
    when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> throw IllegalArgumentException("bad hex char: $c")
    }
