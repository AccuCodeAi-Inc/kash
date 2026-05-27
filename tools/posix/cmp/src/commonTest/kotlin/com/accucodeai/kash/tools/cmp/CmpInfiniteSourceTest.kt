package com.accucodeai.kash.tools.cmp

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.BoundedSuspendSource
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for: `cmp file - < /dev/urandom` used to read both
 * operands fully into memory via `readAllBytes()` before comparing,
 * which OOMed against unbounded sources. cmp now streams in chunks
 * and short-circuits on the first mismatch.
 */
class CmpInfiniteSourceTest {
    private suspend fun writeFile(
        fs: InMemoryFs,
        path: String,
        bytes: ByteArray,
    ) {
        fs.sink(path).also { sink ->
            sink.writeBytes(bytes)
            sink.close()
        }
    }

    @Test fun cmpStopsAtFirstMismatchEvenIfStdinIsInfinite() =
        runTest {
            val fs = InMemoryFs()
            // /a holds a single 0x00 byte. BoundedSuspendSource (seed
            // "KASH") yields a non-zero byte first, so cmp must bail
            // either on the first mismatch or with "EOF on /a" after
            // one byte — either way, before draining the infinite side.
            writeFile(fs, "/a", byteArrayOf(0x00))

            val out = Buffer()
            val err = Buffer()
            val infinite = BoundedSuspendSource()
            val ctx =
                bareCommandContext(
                    fs = fs,
                    env = mutableMapOf(),
                    cwd = "/",
                    stdin = infinite,
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                )
            val rc = CmpCommand().run(listOf("/a", "-"), ctx)
            assertEquals(1, rc.exitCode, "expected exit 1 (differ/EOF), stderr=`${err.readString()}`")
            assertTrue(
                infinite.bytesServed < 8 * 1024,
                "cmp pulled ${infinite.bytesServed} bytes from infinite stdin — should bail early",
            )
        }

    @Test fun cmpDetectsMismatchOnFirstByteBetweenTwoFiles() =
        runTest {
            // Sanity for the streaming path: two 1-byte files that differ.
            val fs = InMemoryFs()
            writeFile(fs, "/a", byteArrayOf(0x01))
            writeFile(fs, "/b", byteArrayOf(0x02))

            val out = Buffer()
            val err = Buffer()
            val ctx =
                bareCommandContext(
                    fs = fs,
                    env = mutableMapOf(),
                    cwd = "/",
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                )
            val rc = CmpCommand().run(listOf("/a", "/b"), ctx)
            assertEquals(1, rc.exitCode)
            val stdout = out.readString()
            assertTrue(stdout.contains("differ"), "expected `differ`, got `$stdout`")
        }
}
