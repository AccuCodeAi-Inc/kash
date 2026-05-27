package com.accucodeai.kash.tools.openssl

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenSslDispatcherTest {
    @Test
    fun noArgsErrors() =
        runTest {
            val r = runOpenssl(emptyList())
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("missing subcommand"), r.err)
        }

    @Test
    fun unknownSubcommand() =
        runTest {
            val r = runOpenssl(listOf("bogus"))
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("unknown command"), r.err)
        }

    @Test
    fun version() =
        runTest {
            val r = runOpenssl(listOf("version"))
            assertEquals(0, r.exit)
            assertTrue(r.out.startsWith("kash openssl"), r.out)
        }

    @Test
    fun helpListsSupported() =
        runTest {
            val r = runOpenssl(listOf("help"))
            assertEquals(0, r.exit)
            assertTrue(r.out.contains("dgst"), r.out)
            assertTrue(r.out.contains("base64"), r.out)
            assertTrue(r.out.contains("passwd"), r.out)
            assertTrue(r.out.contains("Not yet supported"), r.out)
        }

    @Test
    fun deferredSubcommandErrorsCleanly() =
        runTest {
            // genrsa remains in the v2 deferred set.
            val r = runOpenssl(listOf("genrsa"))
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("not yet supported"), r.err)
        }

    @Test
    fun encRequiresPbkdf2() =
        runTest {
            // enc is implemented now; -pbkdf2 is required to opt out of legacy KDF.
            val r = runOpenssl(listOf("enc", "-aes-256-cbc", "-k", "x"), stdin = "hi")
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("legacy MD5-KDF"), r.err)
        }

    @Test
    fun randEmitsBytes() =
        runTest {
            val r = runOpenssl(listOf("rand", "-hex", "16"))
            assertEquals(0, r.exit, r.err)
            assertEquals(32, r.out.length) // 16 bytes -> 32 hex chars
        }
}
