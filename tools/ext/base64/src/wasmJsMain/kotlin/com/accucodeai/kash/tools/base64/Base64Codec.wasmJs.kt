package com.accucodeai.kash.tools.base64

import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

public actual suspend fun readAllBytes(source: SuspendSource): ByteArray {
    val buf = Buffer()
    while (true) {
        val n = source.readAtMostTo(buf, 8 * 1024L)
        if (n == -1L) break
    }
    return buf.readByteArray()
}

@OptIn(ExperimentalEncodingApi::class)
public actual fun base64Encode(bytes: ByteArray): String = Base64.Default.encode(bytes)

@OptIn(ExperimentalEncodingApi::class)
public actual fun base64DecodeStrict(text: String): ByteArray = Base64.Default.decode(text)
