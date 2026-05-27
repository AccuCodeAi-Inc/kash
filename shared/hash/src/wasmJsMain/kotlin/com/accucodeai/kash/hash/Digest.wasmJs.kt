package com.accucodeai.kash.hash

import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.kotlincrypto.core.digest.Digest
import org.kotlincrypto.hash.md.MD5
import org.kotlincrypto.hash.sha1.SHA1
import org.kotlincrypto.hash.sha2.SHA224
import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.hash.sha2.SHA384
import org.kotlincrypto.hash.sha2.SHA512

private fun newDigest(alg: HashAlg): Digest =
    when (alg) {
        HashAlg.MD5 -> MD5()
        HashAlg.SHA1 -> SHA1()
        HashAlg.SHA224 -> SHA224()
        HashAlg.SHA256 -> SHA256()
        HashAlg.SHA384 -> SHA384()
        HashAlg.SHA512 -> SHA512()
    }

public actual fun hashBytes(
    alg: HashAlg,
    data: ByteArray,
): ByteArray {
    val md = newDigest(alg)
    md.update(data)
    return md.digest()
}

public actual suspend fun hashStream(
    alg: HashAlg,
    source: SuspendSource,
): String {
    val md = newDigest(alg)
    val buf = Buffer()
    while (true) {
        val n = source.readAtMostTo(buf, 8 * 1024L)
        if (n == -1L) break
        md.update(buf.readByteArray())
    }
    return toHex(md.digest())
}
