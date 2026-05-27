package com.accucodeai.kash.tools.dd

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.test.BoundedSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for: `dd if=/dev/urandom count=4 bs=8` used to OOM because
 * the read path did `ctx.stdin.readAllBytes()` regardless of `count`.
 * With `count` set, dd now reads at most `count * ibs` bytes from the
 * source.
 */
class DdInfiniteSourceTest {
    @Test fun ddWithCountStopsAfterRequestedBytesOnInfiniteStdin() =
        runTest {
            val out = Buffer()
            val err = Buffer()
            val infinite = BoundedSuspendSource()
            val ctx =
                bareCommandContext(
                    fs = NullFs(),
                    env = mutableMapOf(),
                    cwd = "/",
                    stdin = infinite,
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                )
            // dd bs=8 count=4 → wants 32 bytes total.
            val rc = DdCommand().run(listOf("bs=8", "count=4"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(32, out.readByteArray().size)
            assertTrue(
                infinite.bytesServed < 8 * 1024,
                "dd pulled ${infinite.bytesServed} bytes for `bs=8 count=4` — should be ≤ one chunk",
            )
        }
}
