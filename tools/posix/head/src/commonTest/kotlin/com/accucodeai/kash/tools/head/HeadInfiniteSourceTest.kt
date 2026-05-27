package com.accucodeai.kash.tools.head

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
 * Regression for: `head -c 16 /dev/urandom` used to OOM because
 * `emitBytes` did `source.readAllBytes()` before slicing — draining an
 * infinite source forever. [BoundedSuspendSource] throws once any
 * consumer pulls past 1 MiB, so this test catches the regression in
 * milliseconds instead of hanging until the JVM dies.
 */
class HeadInfiniteSourceTest {
    @Test fun headDashCStopsAfterRequestedBytesOnInfiniteStdin() =
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
            val rc = HeadCommand().run(listOf("-c", "16"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals(16, out.readByteArray().size)
            // The consumer must have stopped early — only one upstream
            // chunk of bytes should have been needed for 16 bytes out.
            assertTrue(
                infinite.bytesServed < 8 * 1024,
                "head pulled ${infinite.bytesServed} bytes from upstream for `-c 16` — should be ≤ one chunk",
            )
        }
}
