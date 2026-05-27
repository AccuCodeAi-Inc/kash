package com.accucodeai.kash.tools.base64

import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.util.Base64

public actual suspend fun readAllBytes(source: SuspendSource): ByteArray {
    val buf = Buffer()
    while (true) {
        val n = source.readAtMostTo(buf, 8 * 1024L)
        if (n == -1L) break
    }
    return buf.readByteArray()
}

public actual fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

public actual fun base64DecodeStrict(text: String): ByteArray {
    // java.util.Base64.getDecoder() rejects characters outside the standard
    // alphabet (including embedded whitespace) — it throws
    // IllegalArgumentException for any non-alphabet char.
    return Base64.getDecoder().decode(text)
}
