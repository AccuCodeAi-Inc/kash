package com.accucodeai.kash.hash

import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.security.MessageDigest

private fun jdkName(alg: HashAlg): String =
    when (alg) {
        HashAlg.MD5 -> "MD5"
        HashAlg.SHA1 -> "SHA-1"
        HashAlg.SHA224 -> "SHA-224"
        HashAlg.SHA256 -> "SHA-256"
        HashAlg.SHA384 -> "SHA-384"
        HashAlg.SHA512 -> "SHA-512"
    }

public actual fun hashBytes(
    alg: HashAlg,
    data: ByteArray,
): ByteArray = MessageDigest.getInstance(jdkName(alg)).digest(data)

public actual suspend fun hashStream(
    alg: HashAlg,
    source: SuspendSource,
): String {
    val md = MessageDigest.getInstance(jdkName(alg))
    val buf = Buffer()
    while (true) {
        val n = source.readAtMostTo(buf, 8 * 1024L)
        if (n == -1L) break
        md.update(buf.readByteArray())
    }
    return toHex(md.digest())
}
