package com.accucodeai.kash.api.io

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression tests pinning the contract that [AsyncPipe] is suspend-native.
 *
 * v1 ([AsyncPipe] pre-refactor) implemented [kotlinx.io.RawSink.write] with
 * `runBlocking { channel.send(...) }`, so a producer blocked the dispatcher
 * thread while waiting for the consumer to drain. Two coroutines on a
 * single-thread dispatcher would deadlock: producer parks the only thread on
 * `send`; consumer never gets to call `receive`.
 *
 * Post-refactor every `write` / `readAtMostTo` is `suspend` end-to-end; the
 * channel `send` / `receive` park the *coroutine*, freeing the thread. These
 * tests exercise exactly that scenario — they would hang and time-out on the
 * old code.
 */
class AsyncPipeSuspendTest {
    @Test
    fun producerAndConsumerCompleteOnUnconfinedDispatcher() =
        runTest {
            // Unconfined dispatches everything on the calling thread — one
            // virtual thread total. Pre-refactor this deadlocks: the producer's
            // runBlocking-on-send pins the only thread before the consumer
            // ever gets to receive.
            withTimeout(5_000) {
                val pipe = AsyncPipe(capacity = 4)
                val payload = "the quick brown fox\n".repeat(64)
                coroutineScope {
                    val writer =
                        async(Dispatchers.Unconfined) {
                            val buf = Buffer()
                            buf.writeString(payload)
                            pipe.sink.write(buf, buf.size)
                            pipe.sink.close()
                        }
                    val reader =
                        async(Dispatchers.Unconfined) {
                            val into = Buffer()
                            val tmp = Buffer()
                            while (true) {
                                val n = pipe.source.readAtMostTo(tmp, 8 * 1024L)
                                if (n == -1L) break
                                into.write(tmp, tmp.size)
                            }
                            into.readString()
                        }
                    listOf(writer).awaitAll()
                    assertEquals(payload, reader.await())
                }
            }
        }

    @Test
    fun ringExchangeSurvivesSmallCapacityAndManyHandoffs() =
        runTest {
            // 10_000 round-trips through a tiny channel forces send to block
            // continuously. Pre-refactor: every blocked send freezes a thread.
            // Post-refactor: each block is a coroutine suspension.
            withTimeout(10_000) {
                val pipe = AsyncPipe(capacity = 2)
                val n = 10_000
                coroutineScope {
                    launch(Dispatchers.Unconfined) {
                        val out = Buffer()
                        for (i in 0 until n) {
                            out.writeString("line $i\n")
                            pipe.sink.write(out, out.size)
                        }
                        pipe.sink.close()
                    }
                    var count = 0
                    val into = Buffer()
                    val tmp = Buffer()
                    while (true) {
                        val got = pipe.source.readAtMostTo(tmp, 4 * 1024L)
                        if (got == -1L) break
                        into.write(tmp, tmp.size)
                    }
                    for (line in into.readString().lineSequence()) {
                        if (line.isEmpty()) continue
                        count++
                    }
                    assertEquals(n, count)
                }
            }
        }

    @Test
    fun closingSourceMakesNextWriteThrowBrokenPipe() =
        runTest {
            // SIGPIPE analog: consumer closes its read side; the producer's
            // next send sees ClosedSendChannelException, which AsyncPipe maps
            // to BrokenPipeException.
            withTimeout(5_000) {
                val pipe = AsyncPipe(capacity = 1)
                pipe.source.close()
                // Give cancellation a tick to propagate to the channel.
                yield()
                val buf = Buffer().apply { writeString("doomed") }
                assertFailsWith<BrokenPipeException> {
                    pipe.sink.write(buf, buf.size)
                }
            }
        }

    @Test
    fun closingSinkSurfacesEofAtSource() =
        runTest {
            // Normal shutdown: producer closes the write end; consumer's
            // next receive returns null, source emits -1 (EOF).
            withTimeout(5_000) {
                val pipe = AsyncPipe(capacity = 4)
                val buf = Buffer().apply { writeString("hi") }
                pipe.sink.write(buf, buf.size)
                pipe.sink.close()
                val into = Buffer()
                val n1 = pipe.source.readAtMostTo(into, 1024L)
                assertEquals(2L, n1)
                val n2 = pipe.source.readAtMostTo(into, 1024L)
                assertEquals(-1L, n2)
            }
        }

    @Test
    fun cancellingParentScopeUnwindsBothHalvesPromptly() =
        runTest {
            // Structured-concurrency property: cancellation propagates
            // through both the parked sender (channel.send) and the parked
            // receiver (channel.receive). Pre-refactor, runBlocking-based
            // park is uncancellable from outside — the test would hang.
            withTimeout(5_000) {
                val pipe = AsyncPipe(capacity = 1)
                val parentJob =
                    launch {
                        coroutineScope {
                            launch {
                                val buf = Buffer().apply { writeString("never drained") }
                                // Fill the channel, then this send parks
                                // forever (no consumer).
                                pipe.sink.write(buf, buf.size)
                                pipe.sink.write(buf, buf.size)
                            }
                            launch {
                                // Sit forever waiting for the second message.
                                val into = Buffer()
                                pipe.source.readAtMostTo(into, 1L)
                                delay(60_000)
                            }
                        }
                    }
                // Give both children a tick to park.
                yield()
                yield()
                parentJob.cancel()
                // The cancel must actually finish — runTest will time out if
                // either child is stuck in an uncancellable park.
                assertFailsWith<CancellationException> { parentJob.join().let { throw CancellationException("done") } }
            }
        }

    @Test
    fun closingClosedSourceIsIdempotent() {
        // close() is non-suspending and safe to call twice. Lifecycle
        // contract that fdTable refcount-release relies on.
        val pipe = AsyncPipe()
        pipe.source.close()
        pipe.source.close() // must not throw
    }

    @Test
    fun closingClosedSinkIsIdempotent() {
        val pipe = AsyncPipe()
        pipe.sink.close()
        pipe.sink.close() // must not throw
    }

    @Test
    fun zeroByteWriteIsANoOp() =
        runTest {
            val pipe = AsyncPipe(capacity = 1)
            val empty = Buffer()
            pipe.sink.write(empty, 0L) // must not park or throw
            pipe.sink.close()
            val into = Buffer()
            assertEquals(-1L, pipe.source.readAtMostTo(into, 16L))
        }

    @Test
    fun receiveAfterDrainAndCloseReturnsEof() =
        runTest {
            val pipe = AsyncPipe(capacity = 2)
            val payload = Buffer().apply { writeString("abc") }
            pipe.sink.write(payload, payload.size)
            pipe.sink.close()
            val into = Buffer()
            // Drain in chunks; final read after close must be -1.
            assertEquals(3L, pipe.source.readAtMostTo(into, 1024L))
            assertEquals(-1L, pipe.source.readAtMostTo(into, 1024L))
        }

    @Test
    @Suppress("UNUSED_VARIABLE")
    fun lazyStartedCoroutinesDoNotPinThreadsBeforeRunning() =
        runTest {
            // Confirms producer/consumer can be allocated lazily on a single
            // dispatcher without one starving the other at construction time.
            withTimeout(5_000) {
                val pipe = AsyncPipe(capacity = 1)
                coroutineScope {
                    val writer =
                        async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
                            val b = Buffer().apply { writeString("hi") }
                            pipe.sink.write(b, b.size)
                            pipe.sink.close()
                        }
                    val reader =
                        async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
                            val into = Buffer()
                            while (pipe.source.readAtMostTo(into, 1024L) != -1L) Unit
                            into.readString()
                        }
                    writer.start()
                    val got = reader.await()
                    assertEquals("hi", got)
                }
            }
        }
}
