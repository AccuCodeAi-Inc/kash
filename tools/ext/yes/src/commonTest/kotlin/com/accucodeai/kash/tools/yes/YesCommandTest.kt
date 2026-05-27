package com.accucodeai.kash.tools.yes

import com.accucodeai.kash.api.io.BrokenPipeException
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stdout that captures the first [maxWrites] blocks then throws
 * [BrokenPipeException] — simulates a downstream `head` closing its source.
 */
private class BoundedSink(
    private val maxWrites: Int,
) : SuspendSink {
    val captured: Buffer = Buffer()
    private var writes: Int = 0

    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        if (writes >= maxWrites) throw BrokenPipeException()
        captured.write(source, byteCount)
        writes++
    }

    override suspend fun flush() {}

    override fun close() {}
}

private suspend fun runYes(
    vararg args: String,
    blocks: Int = 1,
): Triple<Int, String, String> {
    val out = BoundedSink(maxWrites = blocks)
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            env = mutableMapOf(),
            cwd = "/",
            stdin = Buffer().asSuspendSource(),
            stdout = out,
            stderr = err.asSuspendSink(),
        )
    val res = YesCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.captured.readString(), err.readString())
}

class YesCommandTest {
    @Test fun defaultRepeatsY() =
        runTest {
            val (rc, out, _) = runYes(blocks = 1)
            assertEquals(0, rc)
            assertTrue(out.startsWith("y\ny\ny\n"), "expected 'y' lines, got: ${out.take(30)}")
            assertTrue(out.lines().filter { it == "y" }.size > 100, "block should pack many lines")
        }

    @Test fun argsJoinedWithSpace() =
        runTest {
            val (rc, out, _) = runYes("hello", "world", blocks = 1)
            assertEquals(0, rc)
            assertTrue(out.startsWith("hello world\nhello world\n"))
        }

    @Test fun brokenPipeExitsZero() =
        runTest {
            // BoundedSink throws after the first write — exit must be 0, not error.
            val (rc, _, err) = runYes(blocks = 1)
            assertEquals(0, rc)
            assertEquals("", err)
        }

    @Test fun multipleBlocksWrittenBeforeClose() =
        runTest {
            val (rc, out, _) = runYes(blocks = 3)
            assertEquals(0, rc)
            // Three ~8 KiB blocks ≈ 24 KiB of "y\n".
            assertTrue(out.length > 16_000, "expected >16k bytes after 3 blocks, got ${out.length}")
        }

    @Test fun helpArgIsRepeatedLiterally() =
        runTest {
            // Real bash/zsh `yes --help` repeats "--help" forever — there are
            // no flags. Make sure we don't shadow that with a help screen.
            val (rc, out, _) = runYes("--help", blocks = 1)
            assertEquals(0, rc)
            assertTrue(out.startsWith("--help\n--help\n"), "expected '--help' lines, got: ${out.take(40)}")
        }
}
