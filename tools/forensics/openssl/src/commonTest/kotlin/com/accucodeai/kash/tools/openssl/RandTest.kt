package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RandTest {
    // Raw-byte tests go through -out so we don't have to UTF-8 round-trip
    // binary data through the String-based test harness.

    @Test
    fun rawLength() =
        runTest {
            val fs = InMemoryFs()
            val r = runOpenssl(listOf("rand", "-out", "r", "32"), fs = fs)
            assertEquals(0, r.exit, r.err)
            assertEquals(32, fs.readBytes("/work/r").size)
        }

    @Test
    fun zeroLength() =
        runTest {
            val fs = InMemoryFs()
            val r = runOpenssl(listOf("rand", "-out", "r", "0"), fs = fs)
            assertEquals(0, r.exit, r.err)
            assertEquals(0, fs.readBytes("/work/r").size)
        }

    @Test
    fun hexLength() =
        runTest {
            // hex output is pure ASCII, safe to round-trip through String.
            val r = runOpenssl(listOf("rand", "-hex", "16"))
            assertEquals(0, r.exit)
            // 16 bytes -> 32 hex chars, no newline.
            assertEquals(32, r.out.length)
            for (c in r.out) assertTrue(c in '0'..'9' || c in 'a'..'f', "non-hex char: $c")
        }

    @Test
    fun base64Length() =
        runTest {
            val r = runOpenssl(listOf("rand", "-base64", "48"))
            assertEquals(0, r.exit)
            // 48 raw bytes -> 64 base64 chars, wrapped at 64 cols.
            val stripped = r.out.filter { it != '\n' }
            assertEquals(64, stripped.length)
        }

    @Test
    fun base64SingleLineA() =
        runTest {
            val r = runOpenssl(listOf("rand", "-base64", "-A", "60"))
            assertEquals(0, r.exit)
            assertEquals(1, r.out.count { it == '\n' })
        }

    @Test
    fun hexAndBase64MutuallyExclusive() =
        runTest {
            val r = runOpenssl(listOf("rand", "-hex", "-base64", "8"))
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("mutually exclusive"), r.err)
        }

    @Test
    fun missingLength() =
        runTest {
            val r = runOpenssl(listOf("rand"))
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("missing length"), r.err)
        }

    @Test
    fun invalidLength() =
        runTest {
            val r = runOpenssl(listOf("rand", "abc"))
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("invalid length"), r.err)
        }

    @Test
    fun negativeLength() =
        runTest {
            // -5 looks like an option to the parser, so the unknown-option branch fires.
            val r = runOpenssl(listOf("rand", "-5"))
            assertEquals(1, r.exit)
        }

    @Test
    fun unknownOption() =
        runTest {
            val r = runOpenssl(listOf("rand", "-bogus", "8"))
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("unknown option"), r.err)
        }

    @Test
    fun outFile() =
        runTest {
            val fs = InMemoryFs()
            val r = runOpenssl(listOf("rand", "-out", "rnd.bin", "24"), fs = fs)
            assertEquals(0, r.exit, r.err)
            assertEquals(24, fs.readBytes("/work/rnd.bin").size)
        }

    @Test
    fun twoCallsProduceDifferentBytes() =
        runTest {
            val a = runOpenssl(listOf("rand", "-hex", "32"))
            val b = runOpenssl(listOf("rand", "-hex", "32"))
            assertEquals(0, a.exit)
            assertEquals(0, b.exit)
            assertFalse(a.out == b.out, "two RNG calls should not match")
        }

    @Test
    fun helpFlag() =
        runTest {
            val r = runOpenssl(listOf("rand", "-help"))
            assertEquals(0, r.exit)
            assertTrue(r.out.startsWith("Usage: openssl rand"), r.out)
        }

    @Test
    fun sizeSuffixK() =
        runTest {
            // 1K = 1024 bytes → 2048 hex chars.
            val r = runOpenssl(listOf("rand", "-hex", "1K"))
            assertEquals(0, r.exit)
            assertEquals(2048, r.out.length)
        }

    @Test
    fun sizeSuffixInvalid() =
        runTest {
            val r = runOpenssl(listOf("rand", "1Z"))
            assertEquals(1, r.exit)
        }
}
