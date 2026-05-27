package com.accucodeai.kash.tools.openssl

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswdTest {
    // Vectors generated against reference crypt implementations.
    // md5-crypt: '$1$saltsalt$qjXMvbEw8oaL.CzflDtaK/' for password "password".
    @Test
    fun md5CryptKnownVector() =
        runTest {
            val r = runOpenssl(listOf("passwd", "-1", "-salt", "saltsalt", "password"))
            assertEquals(0, r.exit)
            assertEquals("\$1\$saltsalt\$qjXMvbEw8oaL.CzflDtaK/\n", r.out)
        }

    @Test
    fun apr1KnownVector() =
        runTest {
            // From the Apache docs (htpasswd reference). password="myPassword", salt="rqXexS6Z"
            val r = runOpenssl(listOf("passwd", "-apr1", "-salt", "rqXexS6Z", "myPassword"))
            assertEquals(0, r.exit)
            assertEquals("\$apr1\$rqXexS6Z\$QK/GOWpcYWrvXocW5.iZu1\n", r.out)
        }

    @Test
    fun sha256CryptVector() =
        runTest {
            // Drepper's test #1: pw="Hello world!", salt="saltstring"
            val r = runOpenssl(listOf("passwd", "-5", "-salt", "saltstring", "Hello world!"))
            assertEquals(0, r.exit)
            assertEquals(
                "\$5\$saltstring\$5B8vYYiY.CVt1RlTTf8KbXBH3hsxY/GNooZaBBGWEc5\n",
                r.out,
            )
        }

    @Test
    fun sha512CryptVector() =
        runTest {
            // Drepper's test #1 for sha512.
            val r = runOpenssl(listOf("passwd", "-6", "-salt", "saltstring", "Hello world!"))
            assertEquals(0, r.exit)
            assertEquals(
                "\$6\$saltstring\$svn8UoSVapNtMuq1ukKS4tPQd8iKwSMHWjl/O817" +
                    "G3uBnIFNjnQJuesI68u4OTLiBFdcbYEdFCoEOfaS35inz1\n",
                r.out,
            )
        }

    @Test
    fun stdinReadsPassword() =
        runTest {
            val r = runOpenssl(listOf("passwd", "-1", "-salt", "saltsalt", "-stdin"), stdin = "password\n")
            assertEquals(0, r.exit)
            assertEquals("\$1\$saltsalt\$qjXMvbEw8oaL.CzflDtaK/\n", r.out)
        }

    @Test
    fun requiresPassword() =
        runTest {
            val r = runOpenssl(listOf("passwd", "-1", "-salt", "abc"))
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("no password"), r.err)
        }

    @Test
    fun requiresSalt() =
        runTest {
            val r = runOpenssl(listOf("passwd", "-1", "hello"))
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("salt"), r.err)
        }

    @Test
    fun unknownOptionRejected() =
        runTest {
            val r = runOpenssl(listOf("passwd", "-bogus"))
            assertEquals(1, r.exit)
        }

    @Test
    fun helpFlag() =
        runTest {
            val r = runOpenssl(listOf("passwd", "-help"))
            assertEquals(0, r.exit)
            assertTrue(r.out.startsWith("Usage: openssl passwd"), r.out)
        }

    @Test
    fun tableFormat() =
        runTest {
            val r =
                runOpenssl(
                    listOf("passwd", "-1", "-salt", "xyz", "-table", "secret"),
                )
            assertEquals(0, r.exit)
            val parts = r.out.trim().split('\t')
            assertEquals(2, parts.size)
            assertEquals("secret", parts[0])
            assertTrue(parts[1].startsWith("\$1\$xyz\$"), parts[1])
        }

    @Test
    fun tableReverseFormat() =
        runTest {
            val r =
                runOpenssl(
                    listOf("passwd", "-1", "-salt", "xyz", "-table", "-reverse", "secret"),
                )
            assertEquals(0, r.exit)
            val parts = r.out.trim().split('\t')
            assertEquals(2, parts.size)
            assertTrue(parts[0].startsWith("\$1\$xyz\$"), parts[0])
            assertEquals("secret", parts[1])
        }

    @Test
    fun stdinMultiplePasswords() =
        runTest {
            val r =
                runOpenssl(
                    listOf("passwd", "-1", "-salt", "xyz", "-stdin"),
                    stdin = "alpha\nbeta\ngamma\n",
                )
            assertEquals(0, r.exit)
            val lines = r.out.trim().lines()
            assertEquals(3, lines.size)
            for (line in lines) assertTrue(line.startsWith("\$1\$xyz\$"), line)
            // Three distinct passwords should give three distinct hashes.
            assertEquals(3, lines.toSet().size)
        }

    @Test
    fun inFileReadsPasswords() =
        runTest {
            val fs =
                com.accucodeai.kash.fs
                    .InMemoryFs()
            fs.writeBytes("/work/pwd.txt", "alpha\nbeta\n".encodeToByteArray())
            val r =
                runOpenssl(
                    listOf("passwd", "-1", "-salt", "xyz", "-in", "pwd.txt"),
                    fs = fs,
                )
            assertEquals(0, r.exit)
            val lines = r.out.trim().lines()
            assertEquals(2, lines.size)
        }

    @Test
    fun aixmd5NotSupported() =
        runTest {
            val r = runOpenssl(listOf("passwd", "-aixmd5"))
            assertEquals(1, r.exit)
            assertTrue("not supported" in r.err, r.err)
        }
}
