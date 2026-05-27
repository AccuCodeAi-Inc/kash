package com.accucodeai.kash.tools.base64

import com.accucodeai.kash.api.io.SuspendSource

/**
 * Read all bytes from [source] into memory. Base64 is small enough in
 * practice that streaming chunked encoding isn't worth the complexity;
 * read fully then encode/decode.
 */
public expect suspend fun readAllBytes(source: SuspendSource): ByteArray

/** Standard base64 encoding of [bytes] (no line wrapping; no trailing newline). */
public expect fun base64Encode(bytes: ByteArray): String

/**
 * Strict base64 decode. Throws [IllegalArgumentException] on any character
 * outside the standard base64 alphabet (`A-Za-z0-9+/=`).
 */
public expect fun base64DecodeStrict(text: String): ByteArray
