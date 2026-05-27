package com.accucodeai.kash.hash

/**
 * Block SHA-1 convenience. Git is SHA-1-keyed throughout (object names,
 * pack-index entries, ref values), so the git tool reaches for this on
 * every object write — keeping a sharp entry point avoids `HashAlg.SHA1`
 * appearing in every line of the plumbing layer.
 */
public fun sha1(data: ByteArray): ByteArray = hashBytes(HashAlg.SHA1, data)

/** Lowercase 40-char hex of [sha1]. */
public fun sha1Hex(data: ByteArray): String = toHex(sha1(data))
