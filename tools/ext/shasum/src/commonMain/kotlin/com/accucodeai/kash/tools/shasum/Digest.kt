package com.accucodeai.kash.tools.shasum

import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.hash.HashAlg
import com.accucodeai.kash.hash.hashStream

/**
 * Thin shim over [hashStream] — keeps the shasum commands using the
 * bit-length identifier they already parse from `-a`, while the actual
 * hash machinery lives in `:shared:hash`. `0` denotes MD5 (the legacy
 * `shasum --md5` hook).
 */
internal suspend fun shaDigest(
    algBits: Int,
    source: SuspendSource,
): String = hashStream(HashAlg.ofBits(algBits), source)
