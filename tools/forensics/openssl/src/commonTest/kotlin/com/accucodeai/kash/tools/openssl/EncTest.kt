package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncTest {
    // ----- argument parsing / preconditions ---------------------------------

    @Test
    fun pbkdf2Required() =
        runTest {
            val r = runOpenssl(listOf("enc", "-aes-256-cbc", "-k", "secret"), stdin = "hi")
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("legacy MD5-KDF"), r.err)
        }

    @Test
    fun unknownCipherRejected() =
        runTest {
            val r =
                runOpenssl(
                    listOf("enc", "-aes-1024-quark", "-pbkdf2", "-k", "x"),
                    stdin = "",
                )
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("unknown option or cipher"), r.err)
        }

    @Test
    fun noCipherRejected() =
        runTest {
            val r = runOpenssl(listOf("enc", "-pbkdf2", "-k", "x"), stdin = "")
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("no cipher"), r.err)
        }

    @Test
    fun missingPasswordRejected() =
        runTest {
            val r = runOpenssl(listOf("enc", "-aes-256-cbc", "-pbkdf2"), stdin = "x")
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("no password"), r.err)
        }

    @Test
    fun passPassPrefix() =
        runTest {
            val r =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-pbkdf2", "-pass", "pass:swordfish", "-S", "0001020304050607"),
                    stdin = "hello",
                )
            assertEquals(0, r.exit)
            assertTrue(r.out.isNotEmpty())
        }

    @Test
    fun nosaltRequiresExplicitSalt() =
        runTest {
            val r =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-pbkdf2", "-k", "p", "-nosalt"),
                    stdin = "hi",
                )
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("-nosalt requires -S"), r.err)
        }

    // ----- round trip per cipher (in-memory) -------------------------------

    private suspend fun roundTrip(cipher: String) {
        // Encryption output is binary; route through -a (base64) so the
        // String-based test harness's UTF-8 round-trip is lossless.
        val plaintext = "the quick brown fox jumps over the lazy dog\n".repeat(3)
        val enc =
            runOpenssl(
                listOf(
                    "enc",
                    "-$cipher",
                    "-pbkdf2",
                    "-iter",
                    "10000",
                    "-k",
                    "hunter2",
                    "-S",
                    "1122334455667788",
                    "-a",
                ),
                stdin = plaintext,
            )
        assertEquals(0, enc.exit, "encrypt($cipher) stderr=${enc.err}")
        assertTrue(enc.out.isNotEmpty())

        val dec =
            runOpenssl(
                listOf("enc", "-d", "-$cipher", "-pbkdf2", "-iter", "10000", "-k", "hunter2", "-a"),
                stdin = enc.out,
            )
        assertEquals(0, dec.exit, "decrypt($cipher) stderr=${dec.err}")
        assertEquals(plaintext, dec.out)
    }

    @Test fun roundTripAes128Cbc() = runTest { roundTrip("aes-128-cbc") }

    @Test fun roundTripAes192Cbc() = runTest { roundTrip("aes-192-cbc") }

    @Test fun roundTripAes256Cbc() = runTest { roundTrip("aes-256-cbc") }

    @Test fun roundTripAes128Ctr() = runTest { roundTrip("aes-128-ctr") }

    @Test fun roundTripAes256Ctr() = runTest { roundTrip("aes-256-ctr") }

    @Test fun roundTripAes128Gcm() = runTest { roundTrip("aes-128-gcm") }

    @Test fun roundTripAes256Gcm() = runTest { roundTrip("aes-256-gcm") }

    // ----- envelope correctness --------------------------------------------

    @Test
    fun encryptOutputHasSaltedHeader() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/in", "hello".encodeToByteArray())
            val r =
                runOpenssl(
                    listOf(
                        "enc",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-k",
                        "p",
                        "-S",
                        "0011223344556677",
                        "-in",
                        "in",
                        "-out",
                        "out",
                    ),
                    fs = fs,
                )
            assertEquals(0, r.exit, r.err)
            val out = fs.readBytes("/work/out")
            // "Salted__" magic
            assertEquals("Salted__", out.copyOfRange(0, 8).decodeToString())
            // explicit salt bytes
            assertEquals(
                0x00.toByte(),
                out[8],
            )
            assertEquals(0x77.toByte(), out[15])
        }

    @Test
    fun decryptWithWrongKeyFailsGcm() =
        runTest {
            val enc =
                runOpenssl(
                    listOf("enc", "-aes-256-gcm", "-pbkdf2", "-a", "-k", "right", "-S", "00112233aabbccdd"),
                    stdin = "secret-payload",
                )
            assertEquals(0, enc.exit, enc.err)
            val dec =
                runOpenssl(
                    listOf("enc", "-d", "-aes-256-gcm", "-pbkdf2", "-a", "-k", "wrong"),
                    stdin = enc.out,
                )
            assertEquals(1, dec.exit)
            assertTrue(dec.err.contains("bad decrypt"), dec.err)
        }

    @Test
    fun decryptWithWrongKeyFailsCbc() =
        runTest {
            // CBC will usually fail because PKCS#7 padding doesn't validate.
            val enc =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-pbkdf2", "-a", "-k", "right", "-S", "0001020304050607"),
                    stdin = "hello hello hello hello hello\n",
                )
            assertEquals(0, enc.exit)
            var failures = 0
            for (badPw in listOf("wrong1", "wrong2", "wrong3", "wrong4", "wrong5", "wrong6", "wrong7", "wrong8")) {
                val dec =
                    runOpenssl(
                        listOf("enc", "-d", "-aes-256-cbc", "-pbkdf2", "-a", "-k", badPw),
                        stdin = enc.out,
                    )
                if (dec.exit != 0 || dec.out != "hello hello hello hello hello\n") failures++
            }
            // At least most of these must fail; the PKCS#7 padding check rejects them.
            assertTrue(failures >= 7, "expected most wrong keys to fail, got $failures/8 failures")
        }

    // ----- explicit salt envelope round-trip --------------------------------

    @Test
    fun explicitSaltEnvelope() =
        runTest {
            // Use base64 mode end-to-end to keep stdin/stdout printable.
            val enc =
                runOpenssl(
                    listOf(
                        "enc",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-iter",
                        "1000",
                        "-md",
                        "sha256",
                        "-a",
                        "-k",
                        "swordfish",
                        "-S",
                        "0011223344556677",
                    ),
                    stdin = "envelope test\n",
                )
            assertEquals(0, enc.exit, enc.err)
            // Header sanity check: decode base64 and confirm "Salted__" magic at the head.
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            val raw =
                kotlin.io.encoding.Base64.decode(
                    enc.out.filter { it != '\n' && it != '\r' && it != ' ' && it != '\t' },
                )
            assertEquals("Salted__", raw.copyOfRange(0, 8).decodeToString())
            val dec =
                runOpenssl(
                    listOf(
                        "enc",
                        "-d",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-iter",
                        "1000",
                        "-md",
                        "sha256",
                        "-a",
                        "-k",
                        "swordfish",
                    ),
                    stdin = enc.out,
                )
            assertEquals(0, dec.exit, dec.err)
            assertEquals("envelope test\n", dec.out)
        }

    // ----- base64 mode ------------------------------------------------------

    @Test
    fun base64EncryptDecryptRoundTrip() =
        runTest {
            val enc =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-pbkdf2", "-a", "-k", "pw", "-S", "0011223344556677"),
                    stdin = "base64 envelope\n",
                )
            assertEquals(0, enc.exit, enc.err)
            // base64 output should consist of alphabet + newlines, no raw bytes
            for (b in enc.out.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                assertTrue(c == '\n'.code || c in 0x20..0x7e, "non-printable byte $c in base64 output")
            }
            val dec =
                runOpenssl(
                    listOf("enc", "-d", "-aes-256-cbc", "-pbkdf2", "-a", "-k", "pw"),
                    stdin = enc.out,
                )
            assertEquals(0, dec.exit, dec.err)
            assertEquals("base64 envelope\n", dec.out)
        }

    @Test
    fun base64SingleLineWithA() =
        runTest {
            val r =
                runOpenssl(
                    listOf(
                        "enc",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-a",
                        "-A",
                        "-k",
                        "x",
                        "-S",
                        "0011223344556677",
                    ),
                    stdin = "x".repeat(200),
                )
            assertEquals(0, r.exit, r.err)
            // -A means exactly one newline at the end
            assertEquals(1, r.out.count { it == '\n' })
        }

    // ----- iteration / md sensitivity --------------------------------------

    @Test
    fun differentIterCountsProduceDifferentCiphertext() =
        runTest {
            val a =
                runOpenssl(
                    listOf(
                        "enc",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-iter",
                        "1000",
                        "-a",
                        "-k",
                        "p",
                        "-S",
                        "0011223344556677",
                    ),
                    stdin = "hello",
                )
            val b =
                runOpenssl(
                    listOf(
                        "enc",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-iter",
                        "2000",
                        "-a",
                        "-k",
                        "p",
                        "-S",
                        "0011223344556677",
                    ),
                    stdin = "hello",
                )
            assertEquals(0, a.exit)
            assertEquals(0, b.exit)
            assertFalse(a.out == b.out, "different -iter should yield different ciphertext")
        }

    @Test
    fun sha512MdRoundTrip() =
        runTest {
            val enc =
                runOpenssl(
                    listOf(
                        "enc",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-md",
                        "sha512",
                        "-a",
                        "-k",
                        "p",
                        "-S",
                        "0011223344556677",
                    ),
                    stdin = "sha512 derived\n",
                )
            assertEquals(0, enc.exit, enc.err)
            val dec =
                runOpenssl(
                    listOf("enc", "-d", "-aes-256-cbc", "-pbkdf2", "-md", "sha512", "-a", "-k", "p"),
                    stdin = enc.out,
                )
            assertEquals(0, dec.exit, dec.err)
            assertEquals("sha512 derived\n", dec.out)
        }

    @Test
    fun unsupportedMdRejected() =
        runTest {
            val r =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-pbkdf2", "-md", "md5", "-k", "x"),
                    stdin = "x",
                )
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("unsupported digest"), r.err)
        }

    // ----- file I/O end-to-end ---------------------------------------------

    @Test
    fun fileInOutRoundTrip() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/plain.txt", "file payload\n".encodeToByteArray())
            val enc =
                runOpenssl(
                    listOf(
                        "enc",
                        "-aes-256-gcm",
                        "-pbkdf2",
                        "-k",
                        "p",
                        "-S",
                        "0011223344556677",
                        "-in",
                        "plain.txt",
                        "-out",
                        "cipher.bin",
                    ),
                    fs = fs,
                )
            assertEquals(0, enc.exit, enc.err)
            val dec =
                runOpenssl(
                    listOf(
                        "enc",
                        "-d",
                        "-aes-256-gcm",
                        "-pbkdf2",
                        "-k",
                        "p",
                        "-in",
                        "cipher.bin",
                        "-out",
                        "plain.out",
                    ),
                    fs = fs,
                )
            assertEquals(0, dec.exit, dec.err)
            assertEquals("file payload\n", fs.readBytes("/work/plain.out").decodeToString())
        }

    @Test
    fun passEnvSource() =
        runTest {
            val r =
                runOpenssl(
                    listOf(
                        "enc",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-pass",
                        "env:MYPW",
                        "-a",
                        "-S",
                        "0011223344556677",
                    ),
                    stdin = "hello from env\n",
                    env = mutableMapOf("MYPW" to "from-env-secret"),
                )
            assertEquals(0, r.exit, r.err)
            // Round-trip with the same env-derived password.
            val dec =
                runOpenssl(
                    listOf("enc", "-d", "-aes-256-cbc", "-pbkdf2", "-pass", "env:MYPW", "-a"),
                    stdin = r.out,
                    env = mutableMapOf("MYPW" to "from-env-secret"),
                )
            assertEquals(0, dec.exit, dec.err)
            assertEquals("hello from env\n", dec.out)
        }

    @Test
    fun passEnvMissingVarErrors() =
        runTest {
            val r =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-pbkdf2", "-pass", "env:NO_SUCH_VAR"),
                    stdin = "x",
                )
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("NO_SUCH_VAR"), r.err)
        }

    @Test
    fun helpFlag() =
        runTest {
            val r = runOpenssl(listOf("enc", "-help"))
            assertEquals(0, r.exit)
            assertTrue(r.out.startsWith("Usage: openssl enc"), r.out)
        }

    @Test
    fun listCiphers() =
        runTest {
            val r = runOpenssl(listOf("enc", "-list"))
            assertEquals(0, r.exit)
            val lines = r.out.trim().lines()
            assertTrue("aes-256-cbc" in lines, r.out)
            assertTrue("aes-256-gcm" in lines, r.out)
        }

    @Test
    fun rawKeyIvRoundTripCbc() =
        runTest {
            val key = "0".repeat(64) // 32 bytes
            val iv = "0".repeat(32) // 16 bytes
            val plaintext = "secret message"
            val encR =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-K", key, "-iv", iv, "-a"),
                    stdin = plaintext,
                )
            assertEquals(0, encR.exit, encR.err)
            val ciphertext = encR.out
            val decR =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-d", "-K", key, "-iv", iv, "-a"),
                    stdin = ciphertext,
                )
            assertEquals(0, decR.exit, decR.err)
            assertEquals(plaintext, decR.out)
        }

    @Test
    fun rawKeyIvRoundTripCtr() =
        runTest {
            val key = "11".repeat(16) // 16 bytes
            val iv = "22".repeat(16)
            val plaintext = "another message"
            val encR =
                runOpenssl(
                    listOf("enc", "-aes-128-ctr", "-K", key, "-iv", iv, "-a"),
                    stdin = plaintext,
                )
            assertEquals(0, encR.exit, encR.err)
            val decR =
                runOpenssl(
                    listOf("enc", "-aes-128-ctr", "-d", "-K", key, "-iv", iv, "-a"),
                    stdin = encR.out,
                )
            assertEquals(0, decR.exit, decR.err)
            assertEquals(plaintext, decR.out)
        }

    @Test
    fun rawKeyRejectsPasswordOpts() =
        runTest {
            val r =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-K", "00".repeat(32), "-iv", "00".repeat(16), "-k", "pw"),
                    stdin = "x",
                )
            assertEquals(1, r.exit)
            assertTrue("password" in r.err, r.err)
        }

    @Test
    fun kfileReadsPassword() =
        runTest {
            val fs =
                com.accucodeai.kash.fs
                    .InMemoryFs()
            fs.writeBytes("/work/pwd", "topsecret\n".encodeToByteArray())
            // Round-trip using -kfile for both encrypt and decrypt.
            val encR =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-pbkdf2", "-kfile", "pwd", "-a"),
                    stdin = "hello",
                    fs = fs,
                )
            assertEquals(0, encR.exit, encR.err)
            val decR =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-d", "-pbkdf2", "-kfile", "pwd", "-a"),
                    stdin = encR.out,
                    fs = fs,
                )
            assertEquals(0, decR.exit, decR.err)
            assertEquals("hello", decR.out)
        }

    @Test
    fun nopadRequiresBlockAlignedPlaintext() =
        runTest {
            val key = "00".repeat(32)
            val iv = "00".repeat(16)
            // 15 bytes — not a multiple of 16.
            val r =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-K", key, "-iv", iv, "-nopad"),
                    stdin = "123456789012345",
                )
            assertEquals(1, r.exit)
            assertTrue("nopad" in r.err, r.err)
        }

    @Test
    fun nopadRoundTrip() =
        runTest {
            val key = "00".repeat(32)
            val iv = "00".repeat(16)
            // Exactly 16 bytes.
            val plaintext = "0123456789ABCDEF"
            val encR =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-K", key, "-iv", iv, "-nopad", "-a"),
                    stdin = plaintext,
                )
            assertEquals(0, encR.exit, encR.err)
            val decR =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-d", "-K", key, "-iv", iv, "-nopad", "-a"),
                    stdin = encR.out,
                )
            assertEquals(0, decR.exit, decR.err)
            assertEquals(plaintext, decR.out)
        }

    @Test
    fun nopadRejectedOnCtr() =
        runTest {
            val r =
                runOpenssl(
                    listOf("enc", "-aes-256-ctr", "-K", "00".repeat(32), "-iv", "00".repeat(16), "-nopad"),
                    stdin = "x",
                )
            assertEquals(1, r.exit)
            assertTrue("CBC" in r.err, r.err)
        }

    @Test
    fun printKeyIvExits() =
        runTest {
            val r =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-pbkdf2", "-k", "pw", "-S", "0011223344556677", "-P"),
                )
            assertEquals(0, r.exit)
            // -P writes to stderr (matching openssl) and exits without reading stdin.
            assertTrue("salt=" in r.err, r.err)
            assertTrue("key=" in r.err, r.err)
            assertTrue("iv =" in r.err, r.err)
        }

    @Test
    fun printKeyIvContinues() =
        runTest {
            val r =
                runOpenssl(
                    listOf("enc", "-aes-256-cbc", "-pbkdf2", "-k", "pw", "-S", "0011223344556677", "-p", "-a"),
                    stdin = "x",
                )
            assertEquals(0, r.exit)
            assertTrue("key=" in r.err, r.err)
            // Still produced ciphertext.
            assertTrue(r.out.isNotEmpty(), "expected ciphertext output")
        }
}
