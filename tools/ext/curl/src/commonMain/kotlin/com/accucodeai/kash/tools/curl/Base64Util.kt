package com.accucodeai.kash.tools.curl

/**
 * Tiny base64 encoder used for `Authorization: Basic ...`. Pure-Kotlin,
 * no engine deps — duplicated rather than reach into `:tools:ext:base64`
 * to keep the dependency surface here minimal.
 */
private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun base64Encode(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 3 <= bytes.size) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = bytes[i + 1].toInt() and 0xff
        val b2 = bytes[i + 2].toInt() and 0xff
        sb.append(ALPHABET[b0 shr 2])
        sb.append(ALPHABET[((b0 and 0x3) shl 4) or (b1 shr 4)])
        sb.append(ALPHABET[((b1 and 0xf) shl 2) or (b2 shr 6)])
        sb.append(ALPHABET[b2 and 0x3f])
        i += 3
    }
    val rem = bytes.size - i
    if (rem == 1) {
        val b0 = bytes[i].toInt() and 0xff
        sb.append(ALPHABET[b0 shr 2])
        sb.append(ALPHABET[(b0 and 0x3) shl 4])
        sb.append("==")
    } else if (rem == 2) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = bytes[i + 1].toInt() and 0xff
        sb.append(ALPHABET[b0 shr 2])
        sb.append(ALPHABET[((b0 and 0x3) shl 4) or (b1 shr 4)])
        sb.append(ALPHABET[(b1 and 0xf) shl 2])
        sb.append('=')
    }
    return sb.toString()
}
