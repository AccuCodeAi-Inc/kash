package com.accucodeai.kash.terminal.posix

import com.accucodeai.kash.api.terminal.Key
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readString
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drive [PosixTerminalControl]'s byte router with an injected
 * [PosixTerminalControl.ByteReader] so the test never touches host fd 0.
 *
 * The fake [QueueByteReader] blocks on a [LinkedBlockingQueue], matching the
 * real reader's "park in libc.read" behavior — the pump thread parks, the
 * test posts bytes when it wants the router to make a decision, and asserts
 * which downstream channel saw them.
 */
class PosixTerminalControlRouterTest {
    private var control: PosixTerminalControl? = null

    @AfterTest
    fun tearDown() {
        control?.stop()
        control = null
    }

    private fun start(reader: QueueByteReader): PosixTerminalControl {
        val tc = PosixTerminalControl(reader)
        tc.start()
        control = tc
        return tc
    }

    @Test
    fun `cooked bytes flow to the cooked source by default`() =
        runBlocking {
            val feeder = QueueByteReader()
            val tc = start(feeder)
            val src = tc.cookedByteSource()

            feeder.post("hello".encodeToByteArray())

            // Drain until we have 5 bytes — readAtMostTo returns whatever's
            // immediately available after the first block, so we may need
            // multiple calls when bytes arrive in batches.
            val got = drainBytes(src, 5)
            assertEquals("hello", got)
        }

    @Test
    fun `raw bytes go to the decoder and surface as Key events`() =
        runBlocking {
            val feeder = QueueByteReader()
            val tc = start(feeder)
            tc.enterRawMode()

            // "ab" → two Char keys.
            feeder.post("ab".encodeToByteArray())

            val k1 = withTimeout(2000) { tc.readKey() }
            val k2 = withTimeout(2000) { tc.readKey() }
            assertTrue(k1 is Key.Char && k1.codepoint == 'a'.code)
            assertTrue(k2 is Key.Char && k2.codepoint == 'b'.code)
        }

    @Test
    fun `mode transition switches routing - cooked then raw then cooked`() =
        runBlocking {
            val feeder = QueueByteReader()
            val tc = start(feeder)
            val src = tc.cookedByteSource()

            // Cooked: "x" → byte source.
            feeder.post("x".encodeToByteArray())
            assertEquals("x", drainBytes(src, 1))

            // Raw: "y" → decoder.
            tc.enterRawMode()
            // Tiny settle so the routing-mode change is observed by the
            // pump before we post the next byte. The atomic read is fast
            // but there's no happens-before between enterRawMode returning
            // and the pump's next iteration.
            settle()
            feeder.post("y".encodeToByteArray())
            val key = withTimeout(2000) { tc.readKey() }
            assertTrue(key is Key.Char && key.codepoint == 'y'.code)

            // Back to cooked: "z" → byte source.
            tc.exitRawMode()
            settle()
            feeder.post("z".encodeToByteArray())
            assertEquals("z", drainBytes(src, 1))
        }

    @Test
    fun `cooked zero-read surfaces EOF without closing the pump`() =
        runBlocking {
            val feeder = QueueByteReader()
            val tc = start(feeder)
            val src = tc.cookedByteSource()

            // Send some data, then a 0-byte read (Ctrl-D in cooked mode),
            // then more data. Reader should: see "abc", then EOF (-1L),
            // then see "xyz" on the next read — proving the pump survived.
            feeder.post("abc".encodeToByteArray())
            assertEquals("abc", drainBytes(src, 3))

            feeder.postZeroRead()
            // Settle so the pump posts the EOF marker before we receive.
            settle()
            val eofBuf = Buffer()
            val n = withTimeout(2000) { src.readAtMostTo(eofBuf, 16L) }
            assertEquals(-1L, n)

            feeder.post("xyz".encodeToByteArray())
            assertEquals("xyz", drainBytes(src, 3))
        }

    @Test
    fun `cooked source returns -1 on pump shutdown`() =
        runBlocking {
            val feeder = QueueByteReader()
            val tc = start(feeder)
            val src = tc.cookedByteSource()

            // Signal EOF: returning ≤0 from the byte reader stops the pump,
            // which closes both downstream channels.
            feeder.postEof()

            // Give the pump a moment to drain and close.
            settle()

            val sink = Buffer()
            val n = src.readAtMostTo(sink, 16L)
            assertEquals(-1L, n)
        }

    /**
     * Block-read [count] bytes off [src] across however many
     * `readAtMostTo` calls it takes — the source returns "whatever's
     * immediately ready" after blocking for the first byte, mirroring
     * read(2) on a pipe.
     */
    private suspend fun drainBytes(
        src: com.accucodeai.kash.api.io.SuspendSource,
        count: Int,
    ): String {
        val sink = Buffer()
        var remaining = count.toLong()
        withTimeout(2000) {
            while (remaining > 0L) {
                val got = src.readAtMostTo(sink, remaining)
                if (got <= 0L) break
                remaining -= got
            }
        }
        return sink.readString()
    }

    private suspend fun settle() {
        kotlinx.coroutines.delay(20)
    }

    /**
     * Test [PosixTerminalControl.ByteReader] backed by a blocking queue.
     * The pump thread parks in `take()`; the test calls `post()` to feed
     * bytes and `postEof()` to signal end-of-stream (returns 0).
     */
    private class QueueByteReader : PosixTerminalControl.ByteReader {
        private val q: LinkedBlockingQueue<Chunk> = LinkedBlockingQueue()

        fun post(bytes: ByteArray) {
            q.put(Chunk.Data(bytes))
        }

        /** Simulate a real fd-close (read returns -1 → pump exits). */
        fun postEof() {
            q.put(Chunk.HardEof)
        }

        /**
         * Simulate a cooked-mode Ctrl-D: read returns 0 once, but the
         * fd stays open. The pump should forward an EOF marker on
         * cookedBytes without closing the channel.
         */
        fun postZeroRead() {
            q.put(Chunk.ZeroRead)
        }

        override fun read(buf: ByteArray): Int {
            val item = q.poll(10, TimeUnit.SECONDS) ?: return -1
            return when (item) {
                Chunk.HardEof -> {
                    -1
                }

                Chunk.ZeroRead -> {
                    0
                }

                is Chunk.Data -> {
                    val n = minOf(item.bytes.size, buf.size)
                    System.arraycopy(item.bytes, 0, buf, 0, n)
                    n
                }
            }
        }

        private sealed interface Chunk {
            data object HardEof : Chunk

            data object ZeroRead : Chunk

            data class Data(
                val bytes: ByteArray,
            ) : Chunk
        }
    }
}
