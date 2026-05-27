package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Base64Test {
    @Test
    fun encodeEmpty() =
        runTest {
            val r = runOpenssl(listOf("base64"), stdin = "")
            assertEquals(0, r.exit)
            assertEquals("\n", r.out)
        }

    @Test
    fun encodeRfc4648Vectors() =
        runTest {
            // RFC 4648 §10.
            assertEquals("Zg==\n", runOpenssl(listOf("base64"), "f").out)
            assertEquals("Zm8=\n", runOpenssl(listOf("base64"), "fo").out)
            assertEquals("Zm9v\n", runOpenssl(listOf("base64"), "foo").out)
            assertEquals("Zm9vYg==\n", runOpenssl(listOf("base64"), "foob").out)
            assertEquals("Zm9vYmE=\n", runOpenssl(listOf("base64"), "fooba").out)
            assertEquals("Zm9vYmFy\n", runOpenssl(listOf("base64"), "foobar").out)
        }

    @Test
    fun decodeRfc4648Vectors() =
        runTest {
            assertEquals("foobar", runOpenssl(listOf("base64", "-d"), "Zm9vYmFy\n").out)
            assertEquals("foo", runOpenssl(listOf("base64", "-d"), "Zm9v").out)
            assertEquals("fo", runOpenssl(listOf("base64", "-d"), "Zm8=").out)
        }

    @Test
    fun wrapAt64Cols() =
        runTest {
            // 65 bytes of 'a' → 88 base64 chars; should wrap at 64.
            val data = "a".repeat(65)
            val r = runOpenssl(listOf("base64"), stdin = data)
            assertEquals(0, r.exit)
            val lines = r.out.trimEnd('\n').split('\n')
            assertEquals(2, lines.size, "expected 2 lines, got: ${r.out}")
            assertEquals(64, lines[0].length)
        }

    @Test
    fun aFlagSingleLine() =
        runTest {
            val data = "a".repeat(65)
            val r = runOpenssl(listOf("base64", "-A"), stdin = data)
            assertEquals(0, r.exit)
            val lines = r.out.trimEnd('\n').split('\n')
            assertEquals(1, lines.size, "expected single line, got: ${r.out}")
        }

    @Test
    fun decodeTolerantOfWhitespace() =
        runTest {
            val input = "Zm9v\nYmFy\n"
            val r = runOpenssl(listOf("base64", "-d"), stdin = input)
            assertEquals(0, r.exit)
            assertEquals("foobar", r.out)
        }

    @Test
    fun decodeRejectsGarbage() =
        runTest {
            val r = runOpenssl(listOf("base64", "-d"), stdin = "!!!@@")
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("invalid"), r.err)
        }

    @Test
    fun inOutFile() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/raw", "hello".encodeToByteArray())
            val r = runOpenssl(listOf("base64", "-in", "raw", "-out", "enc"), fs = fs)
            assertEquals(0, r.exit)
            val encoded = fs.readBytes("/work/enc").decodeToString()
            assertEquals("aGVsbG8=\n", encoded)
        }

    @Test
    fun roundTrip() =
        runTest {
            val payload = "The quick brown fox jumps over the lazy dog\n"
            val enc = runOpenssl(listOf("base64"), stdin = payload).out
            val dec = runOpenssl(listOf("base64", "-d"), stdin = enc).out
            assertEquals(payload, dec)
        }

    @Test
    fun helpFlag() =
        runTest {
            val r = runOpenssl(listOf("base64", "-help"))
            assertEquals(0, r.exit)
            assertTrue(r.out.startsWith("Usage: openssl base64"), r.out)
        }
}
