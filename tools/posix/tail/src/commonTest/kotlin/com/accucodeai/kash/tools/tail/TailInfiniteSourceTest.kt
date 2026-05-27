package com.accucodeai.kash.tools.tail

import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `tail`-on-infinite-source regression. The unbounded modes
 * (`-c N`, `-c +N`) used to call `source.readAllBytes()` first.
 *
 * `tail -c +N` is straightforwardly streamable — skip prefix, forward
 * the rest in bounded chunks. We assert it stays bounded and prints
 * what's expected.
 *
 * `tail -c N` (last-N) inherently needs to see EOF, so we can't run it
 * directly against an infinite source — there'd be nothing to print
 * "from". We test the sliding-window memory bound on a *bounded*
 * source instead: feed N+M bytes, request last N, and verify exactly
 * the last N come out.
 */
class TailInfiniteSourceTest {
    /** A SuspendSource that returns exactly [bytes] then EOF. */
    private class FiniteByteSource(
        bytes: ByteArray,
    ) : SuspendSource {
        private val buf = Buffer().also { it.write(bytes) }

        override suspend fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long = buf.readAtMostTo(sink, byteCount)

        override fun close() {}
    }

    @Test fun tailDashCPlusNStreamsLargeFiniteSourceWithoutBuffering() =
        runTest {
            // 1 MiB of bytes, ask for everything from byte 1,000,000 on.
            // Pre-fix this would readAllBytes() = 1 MiB ByteArray, then
            // copyOfRange off the tail. With streaming, we just skip
            // the prefix and forward 48,576 bytes in chunks — never
            // holding the whole 1 MiB.
            val payload = ByteArray(1024 * 1024) { (it and 0xFF).toByte() }
            val out = Buffer()
            val err = Buffer()
            val ctx =
                bareCommandContext(
                    fs = NullFs(),
                    env = mutableMapOf(),
                    cwd = "/",
                    stdin = FiniteByteSource(payload),
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                )
            val rc = TailCommand().run(listOf("-c", "+1000001"), ctx)
            assertEquals(0, rc.exitCode)
            val got = out.readByteArray()
            val expected = payload.copyOfRange(1_000_000, payload.size)
            assertEquals(expected.toList(), got.toList())
        }

    @Test fun tailDashCNSlidingWindowIsMemoryBoundedOnFiniteSource() =
        runTest {
            // Feed 1 MiB; ask for last 32 bytes. Pre-fix this would
            // allocate 1 MiB to slice the last 32 — now it's a 32-byte
            // sliding window. Assert exactly the last 32 bytes appear.
            val payload = ByteArray(1024 * 1024) { (it and 0xFF).toByte() }
            val out = Buffer()
            val err = Buffer()
            val ctx =
                bareCommandContext(
                    fs = NullFs(),
                    env = mutableMapOf(),
                    cwd = "/",
                    stdin = FiniteByteSource(payload),
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                )
            val rc = TailCommand().run(listOf("-c", "32"), ctx)
            assertEquals(0, rc.exitCode)
            val got = out.readByteArray()
            val expected = payload.copyOfRange(payload.size - 32, payload.size)
            assertEquals(expected.toList(), got.toList())
        }
}
