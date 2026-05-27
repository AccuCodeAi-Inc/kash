@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.accucodeai.kash.tools.tail

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `tail -f` end-to-end against the InMemoryFs event bus.
 *
 * Synchronization: we deliberately avoid `delay()` for sequencing —
 * `runTest`'s virtual scheduler makes a `delay(N)` only equivalent to
 * "advance virtual time," not a real wait, so a delay-based test is just
 * an obfuscated `runCurrent()` and can be brittle if the producer doesn't
 * happen to be scheduled in that window. We use [TestScope.runCurrent]
 * directly: drain everything that's runnable, perform a mutation, drain
 * again. Each mutation on `InMemoryFs` synchronously emits onto the
 * SharedFlow, so the follower observes the event on the very next drain.
 */
class TailFollowTest {
    private fun ctxFor(fs: InMemoryFs): Pair<CommandContext, Buffer> {
        val out = Buffer()
        val err = Buffer()
        return bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = "/",
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        ) to out
    }

    @Test fun followStreamsAppendedBytes() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/log", "first\n".encodeToByteArray())
            val (ctx, out) = ctxFor(fs)

            val follower =
                async {
                    TailCommand().run(listOf("-f", "/log"), ctx)
                }

            // Let the follower emit the initial tail and reach the
            // suspending `collect` on the watch flow.
            runCurrent()
            fs.appendBytes("/log", "second\n".encodeToByteArray())
            runCurrent()
            fs.appendBytes("/log", "third\n".encodeToByteArray())
            runCurrent()

            follower.cancelAndJoin()
            val text = out.readString()
            assertTrue(text.contains("first"), "expected initial 'first', got: $text")
            assertTrue(text.contains("second"), "expected 'second' after append, got: $text")
            assertTrue(text.contains("third"), "expected 'third' after append, got: $text")
        }

    @Test fun followStopsWhenFileIsDeleted() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/log", "hi\n".encodeToByteArray())
            val (ctx, _) = ctxFor(fs)

            val follower =
                async {
                    TailCommand().run(listOf("-f", "/log"), ctx)
                }
            runCurrent()
            fs.remove("/log")
            // takeWhile stops the collect on Delete → run() returns 0.
            val r = follower.await()
            assertEquals(0, r.exitCode)
        }

    @Test fun followCancellationStopsCleanly() =
        runTest {
            // INT (or any cooperative cancellation) should stop the follow
            // loop at the next suspension point. We model this by cancelling
            // the launched job — same effect as `kill -INT %N` against a
            // backgrounded `tail -f`.
            val fs = InMemoryFs()
            fs.writeBytes("/log", "x\n".encodeToByteArray())
            val (ctx, _) = ctxFor(fs)
            val j =
                launch {
                    TailCommand().run(listOf("-f", "/log"), ctx)
                }
            runCurrent()
            // Should be alive, suspended on the watch flow.
            assertTrue(j.isActive, "follower should still be running")
            j.cancelAndJoin()
            assertEquals(false, j.isActive)
        }

    @Test fun followReportsTruncation() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/log", "abcdefg\n".encodeToByteArray())
            val out = Buffer()
            val err = Buffer()
            val ctx =
                bareCommandContext(
                    fs = fs,
                    env = mutableMapOf(),
                    cwd = "/",
                    stdin = Buffer().asSuspendSource(),
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                )
            val follower =
                async {
                    TailCommand().run(listOf("-f", "/log"), ctx)
                }
            runCurrent()
            // writeBytes with append=false truncates in place.
            fs.writeBytes("/log", "short\n".encodeToByteArray())
            runCurrent()
            follower.cancelAndJoin()
            val errText = err.readString()
            assertTrue(errText.contains("file truncated"), "expected truncation diag, got: $errText")
        }
}
