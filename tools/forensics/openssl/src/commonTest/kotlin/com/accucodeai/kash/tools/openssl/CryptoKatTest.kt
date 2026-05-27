package com.accucodeai.kash.tools.openssl

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA1
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Known-Answer Tests proving the underlying cryptography-kotlin primitives
 * agree with published vectors. These guard the EncSubcommand glue against
 * silent regressions of the library or its provider.
 */
@OptIn(dev.whyoleg.cryptography.DelicateCryptographyApi::class)
class CryptoKatTest {
    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    private fun toHex(b: ByteArray): String =
        buildString(b.size * 2) {
            val a = "0123456789abcdef"
            for (x in b) {
                val v = x.toInt() and 0xff
                append(a[v ushr 4])
                append(a[v and 0xf])
            }
        }

    // NIST SP 800-38A F.2.1 — AES-128-CBC, single block.
    @Test
    fun aes128CbcNistF21() =
        runTest {
            val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
            val iv = hex("000102030405060708090a0b0c0d0e0f")
            val pt = hex("6bc1bee22e409f96e93d7e117393172a")
            val expected = "7649abac8119b246cee98e9b12e9197d"

            val k =
                cryptographyProvider()
                    .get(AES.CBC)
                    .keyDecoder()
                    .decodeFromByteArray(AES.Key.Format.RAW, key)
            val ct = k.cipher(padding = false).encryptWithIv(iv, pt)
            assertEquals(expected, toHex(ct))
        }

    // NIST SP 800-38D — AES-256-GCM test case 13 (PT=empty, AAD=empty, no AAD support here).
    // Use a simpler vector from a NIST CAVP-style test:
    //  Key = 00..00 (32 bytes)
    //  IV  = 00..00 (12 bytes)
    //  PT  = empty
    //  Expected tag: 530f8afbc74536b9a963b4f1c4cb738b  (NIST GCM-AES-256, test 1)
    @Test
    fun aes256GcmEmptyKat() =
        runTest {
            val key = ByteArray(32)
            val iv = ByteArray(12)
            val k =
                cryptographyProvider()
                    .get(AES.GCM)
                    .keyDecoder()
                    .decodeFromByteArray(AES.Key.Format.RAW, key)
            val ct = k.cipher(tagSize = 128.bits).encryptWithIv(iv, ByteArray(0))
            assertEquals("530f8afbc74536b9a963b4f1c4cb738b", toHex(ct))
        }

    // PBKDF2 HMAC-SHA1 from RFC 6070 Test #2:
    //   P = "password", S = "salt", c = 2, dkLen = 20
    //   DK = ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957
    @Test
    fun pbkdf2HmacSha1Rfc6070Test2() =
        runTest {
            val pbk = cryptographyProvider().get(PBKDF2)
            val derivation = pbk.secretDerivation(SHA1, 2, 20.bytes, "salt".encodeToByteArray())
            val dk = derivation.deriveSecretToByteArray("password".encodeToByteArray())
            assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", toHex(dk))
        }

    // PBKDF2 HMAC-SHA256 from RFC 7914 §11 (also widely-cited vector).
    //   P = "passwd", S = "salt", c = 1, dkLen = 64
    //   First 32 bytes:
    //   55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc
    @Test
    fun pbkdf2HmacSha256Vector() =
        runTest {
            val pbk = cryptographyProvider().get(PBKDF2)
            val derivation = pbk.secretDerivation(SHA256, 1, 64.bytes, "salt".encodeToByteArray())
            val dk = derivation.deriveSecretToByteArray("passwd".encodeToByteArray())
            assertEquals(
                "55ac046e56e3089fec1691c22544b605" +
                    "f94185216dde0465e68b9d57c20dacbc",
                toHex(dk.copyOfRange(0, 32)),
            )
        }
}
