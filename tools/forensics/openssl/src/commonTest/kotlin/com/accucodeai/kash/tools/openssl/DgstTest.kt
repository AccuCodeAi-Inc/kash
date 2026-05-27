package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DgstTest {
    // Standard KATs.
    private val emptyMd5 = "d41d8cd98f00b204e9800998ecf8427e"
    private val abcMd5 = "900150983cd24fb0d6963f7d28e17f72"
    private val emptySha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709"
    private val abcSha1 = "a9993e364706816aba3e25717850c26c9cd0d89d"
    private val emptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val abcSha224 = "23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7"
    private val abcSha384 =
        "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed" +
            "8086072ba1e7cc2358baeca134c825a7"
    private val abcSha512 =
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
            "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"

    // RFC 4231 test case 1: key = 20 bytes of 0x0b, data = "Hi There"
    //   HMAC-SHA256 = b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7
    @Test
    fun hmacSha256Rfc4231Case1() =
        runTest {
            val r =
                runOpenssl(
                    listOf(
                        "dgst",
                        "-sha256",
                        "-hmac",
                        "" +
                            "",
                    ),
                    stdin = "Hi There",
                )
            assertEquals(0, r.exit, r.err)
            assertTrue(
                r.out.trim().endsWith("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
                r.out,
            )
        }

    @Test
    fun hmacWithFileFormatsLabel() =
        runTest {
            val fs =
                com.accucodeai.kash.fs
                    .InMemoryFs()
            fs.writeBytes("/work/data.txt", "Hi There".encodeToByteArray())
            val r =
                runOpenssl(
                    listOf("dgst", "-sha256", "-hmac", "key", "data.txt"),
                    fs = fs,
                )
            assertEquals(0, r.exit, r.err)
            assertTrue(r.out.startsWith("HMAC-SHA256(data.txt)= "), r.out)
        }

    @Test
    fun dgstSha256DefaultStdin() =
        runTest {
            val r = runOpenssl(listOf("dgst", "-sha256"), stdin = "abc")
            assertEquals(0, r.exit)
            assertEquals("$abcSha256\n", r.out)
        }

    @Test
    fun dgstSha256EmptyStdin() =
        runTest {
            val r = runOpenssl(listOf("dgst", "-sha256"), stdin = "")
            assertEquals(0, r.exit)
            assertEquals("$emptySha256\n", r.out)
        }

    @Test
    fun dgstAllAlgsAbc() =
        runTest {
            assertEquals("$abcMd5\n", runOpenssl(listOf("dgst", "-md5"), "abc").out)
            assertEquals("$abcSha1\n", runOpenssl(listOf("dgst", "-sha1"), "abc").out)
            assertEquals("$abcSha224\n", runOpenssl(listOf("dgst", "-sha224"), "abc").out)
            assertEquals("$abcSha256\n", runOpenssl(listOf("dgst", "-sha256"), "abc").out)
            assertEquals("$abcSha384\n", runOpenssl(listOf("dgst", "-sha384"), "abc").out)
            assertEquals("$abcSha512\n", runOpenssl(listOf("dgst", "-sha512"), "abc").out)
        }

    @Test
    fun dgstEmptyAllAlgs() =
        runTest {
            assertEquals("$emptyMd5\n", runOpenssl(listOf("dgst", "-md5"), "").out)
            assertEquals("$emptySha1\n", runOpenssl(listOf("dgst", "-sha1"), "").out)
            assertEquals("$emptySha256\n", runOpenssl(listOf("dgst", "-sha256"), "").out)
        }

    @Test
    fun dgstFileFormatsWithLabel() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/x.txt", "abc".encodeToByteArray())
            val r = runOpenssl(listOf("dgst", "-sha256", "x.txt"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("SHA256(x.txt)= $abcSha256\n", r.out)
        }

    @Test
    fun dgstMultipleFiles() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a", "abc".encodeToByteArray())
            fs.writeBytes("/work/b", ByteArray(0))
            val r = runOpenssl(listOf("dgst", "-md5", "a", "b"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("MD5(a)= $abcMd5\nMD5(b)= $emptyMd5\n", r.out)
        }

    @Test
    fun dgstMissingFileNonZero() =
        runTest {
            // Use InMemoryFs (NullFs returns empty source instead of throwing).
            val r = runOpenssl(listOf("dgst", "-sha1", "nope"), fs = InMemoryFs())
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("No such file"), r.err)
        }

    @Test
    fun dgstBinaryOutputsRawBytes() =
        runTest {
            // Write raw bytes to an -out file so the buffer doesn't mangle non-UTF-8.
            val fs = InMemoryFs()
            val r = runOpenssl(listOf("dgst", "-md5", "-binary", "-out", "bin"), stdin = "abc", fs = fs)
            assertEquals(0, r.exit)
            val bytes = fs.readBytes("/work/bin")
            assertEquals(16, bytes.size)
            assertEquals(0x90.toByte(), bytes[0])
            assertEquals(0x72.toByte(), bytes[15])
        }

    @Test
    fun shortcutMd5() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/x", "abc".encodeToByteArray())
            val r = runOpenssl(listOf("md5", "x"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("MD5(x)= $abcMd5\n", r.out)
        }

    @Test
    fun shortcutSha256Stdin() =
        runTest {
            val r = runOpenssl(listOf("sha256"), stdin = "abc")
            assertEquals(0, r.exit)
            assertEquals("$abcSha256\n", r.out)
        }

    @Test
    fun dashFileMeansStdin() =
        runTest {
            val r = runOpenssl(listOf("dgst", "-sha1", "-"), stdin = "abc")
            assertEquals(0, r.exit)
            assertEquals("SHA1(stdin)= $abcSha1\n", r.out)
        }

    @Test
    fun macRejected() =
        runTest {
            // -mac/-macopt remain unsupported even though -hmac is implemented.
            val r = runOpenssl(listOf("dgst", "-sha256", "-mac", "HMAC"), stdin = "abc")
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("not yet supported"), r.err)
        }

    @Test
    fun unknownAlgRejected() =
        runTest {
            val r = runOpenssl(listOf("dgst", "-blake42"), stdin = "abc")
            assertEquals(1, r.exit)
        }

    @Test
    fun dgstOutFile() =
        runTest {
            val fs = InMemoryFs()
            val r = runOpenssl(listOf("dgst", "-sha256", "-out", "sum"), stdin = "abc", fs = fs)
            assertEquals(0, r.exit)
            // Output went to file, not stdout.
            assertEquals("", r.out)
            val content = fs.readBytes("/work/sum").decodeToString()
            assertEquals("$abcSha256\n", content)
        }

    @Test
    fun dgstHelp() =
        runTest {
            val r = runOpenssl(listOf("dgst", "-help"))
            assertEquals(0, r.exit)
            assertTrue(r.out.startsWith("Usage: openssl dgst"))
            assertTrue("-hmac" in r.out)
        }

    @Test
    fun dgstList() =
        runTest {
            val r = runOpenssl(listOf("dgst", "-list"))
            assertEquals(0, r.exit)
            val algs = r.out.trim().lines()
            assertEquals(listOf("md5", "sha1", "sha224", "sha256", "sha384", "sha512"), algs)
        }

    @Test
    fun dgstColonHex() =
        runTest {
            val r = runOpenssl(listOf("dgst", "-md5", "-c"), stdin = "abc")
            assertEquals(0, r.exit)
            // 900150983cd24fb0d6963f7d28e17f72 → 90:01:50:...
            assertEquals("90:01:50:98:3c:d2:4f:b0:d6:96:3f:7d:28:e1:7f:72\n", r.out)
        }

    @Test
    fun dgstCoreutilsFormatStdin() =
        runTest {
            val r = runOpenssl(listOf("dgst", "-sha256", "-r"), stdin = "abc")
            assertEquals(0, r.exit)
            assertEquals("$abcSha256  -\n", r.out)
        }

    @Test
    fun dgstCoreutilsFormatFile() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/x", "abc".encodeToByteArray())
            val r = runOpenssl(listOf("dgst", "-sha256", "-r", "x"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("$abcSha256  x\n", r.out)
        }

    @Test
    fun dgstHmacEnv() =
        runTest {
            val r =
                runOpenssl(
                    listOf("dgst", "-sha256", "-hmac-env", "MYKEY"),
                    stdin = "Hi There",
                    env = mutableMapOf("MYKEY" to "".repeat(20)),
                )
            assertEquals(0, r.exit)
            // RFC 4231 case 1: stdin produces bare hex (label only for named files).
            assertEquals(
                "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7\n",
                r.out,
            )
        }

    @Test
    fun dgstHmacEnvMissingVar() =
        runTest {
            val r = runOpenssl(listOf("dgst", "-sha256", "-hmac-env", "NOPE"))
            assertEquals(1, r.exit)
            assertTrue("variable not set" in r.err)
        }
}
