package com.accucodeai.kash.tools.openssl

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end cross-check: encrypt with kash → decrypt with host openssl,
 * and vice versa. Skipped silently when no host openssl binary is found.
 *
 * This catches every byte-level format mismatch (header magic, salt position,
 * KDF parameters, tag placement) in one go — strictly more sensitive than any
 * unit test we could write.
 */
class HostOpensslCrossCheckTest {
    private val hostOpenssl: String? by lazy {
        listOf("/opt/homebrew/bin/openssl", "/usr/local/bin/openssl", "/usr/bin/openssl")
            .firstOrNull { File(it).canExecute() }
    }

    private fun ensureOpenssl(): String? = hostOpenssl

    private fun runProc(
        cmd: List<String>,
        stdin: ByteArray = ByteArray(0),
    ): Triple<Int, ByteArray, ByteArray> {
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        proc.outputStream.use { it.write(stdin) }
        val out = proc.inputStream.readBytes()
        val err = proc.errorStream.readBytes()
        proc.waitFor()
        return Triple(proc.exitValue(), out, err)
    }

    @Test
    fun kashEncryptHostDecrypt_aes256cbc() =
        runTest {
            val bin = ensureOpenssl() ?: return@runTest
            val plaintext = "round-trip via host openssl\n".repeat(5)
            val pw = "swordfish"
            val enc =
                runOpenssl(
                    listOf(
                        "enc",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-iter",
                        "10000",
                        "-md",
                        "sha256",
                        "-a",
                        "-k",
                        pw,
                    ),
                    stdin = plaintext,
                )
            assertEquals(0, enc.exit, enc.err)
            val (code, out, err) =
                runProc(
                    listOf(
                        bin,
                        "enc",
                        "-d",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-iter",
                        "10000",
                        "-md",
                        "sha256",
                        "-a",
                        "-k",
                        pw,
                    ),
                    stdin = enc.out.encodeToByteArray(),
                )
            assertEquals(0, code, "host openssl failed: " + err.decodeToString())
            assertEquals(plaintext, out.decodeToString())
        }

    @Test
    fun hostEncryptKashDecrypt_aes256cbc() =
        runTest {
            val bin = ensureOpenssl() ?: return@runTest
            val plaintext = "host → kash decrypt path\n"
            val pw = "letmein"
            // Use -a (base64) framing for the binary ciphertext round-trip so we
            // don't have to thread raw bytes through the String-based test helper.
            val (code, ct, err) =
                runProc(
                    listOf(
                        bin,
                        "enc",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-iter",
                        "10000",
                        "-md",
                        "sha256",
                        "-a",
                        "-k",
                        pw,
                    ),
                    stdin = plaintext.encodeToByteArray(),
                )
            assertEquals(0, code, "host openssl encrypt failed: " + err.decodeToString())
            val dec =
                runOpenssl(
                    listOf(
                        "enc",
                        "-d",
                        "-aes-256-cbc",
                        "-pbkdf2",
                        "-iter",
                        "10000",
                        "-md",
                        "sha256",
                        "-a",
                        "-k",
                        pw,
                    ),
                    stdin = ct.decodeToString(),
                )
            assertEquals(0, dec.exit, dec.err)
            assertEquals(plaintext, dec.out)
        }

    @Test
    fun kashEncryptHostDecrypt_aes256gcm() =
        runTest {
            val bin = ensureOpenssl() ?: return@runTest
            // Host openssl may or may not support -aes-256-gcm via the `enc` subcommand
            // depending on version (LibreSSL/OpenSSL 1.x lack it; OpenSSL 3.x supports it).
            // Probe first.
            val probe = runProc(listOf(bin, "enc", "-ciphers"))
            val ciphersList = probe.second.decodeToString() + probe.third.decodeToString()
            if (!ciphersList.contains("aes-256-gcm", ignoreCase = true) &&
                !ciphersList.contains("id-aes256-gcm", ignoreCase = true)
            ) {
                return@runTest // host doesn't support GCM via enc; skip
            }

            val plaintext = "gcm round trip\n"
            val pw = "p4ssw0rd"
            val enc =
                runOpenssl(
                    listOf(
                        "enc",
                        "-aes-256-gcm",
                        "-pbkdf2",
                        "-iter",
                        "10000",
                        "-md",
                        "sha256",
                        "-a",
                        "-k",
                        pw,
                    ),
                    stdin = plaintext,
                )
            assertEquals(0, enc.exit, enc.err)
            val (code, out, err) =
                runProc(
                    listOf(
                        bin,
                        "enc",
                        "-d",
                        "-aes-256-gcm",
                        "-pbkdf2",
                        "-iter",
                        "10000",
                        "-md",
                        "sha256",
                        "-a",
                        "-k",
                        pw,
                    ),
                    stdin = enc.out.encodeToByteArray(),
                )
            // If the host openssl errors out on enc/-aes-256-gcm, treat as skip.
            if (code != 0 && err.decodeToString().contains("AEAD ciphers not supported")) return@runTest
            assertEquals(0, code, "host openssl gcm decrypt failed: " + err.decodeToString())
            assertEquals(plaintext, out.decodeToString())
        }
}
