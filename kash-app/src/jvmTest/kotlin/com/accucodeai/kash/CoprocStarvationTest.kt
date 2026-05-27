package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: prove the coroutine-vs-thread back-pressure fix.
 *
 * Pre-refactor, `AsyncPipe.ChannelSink.write` did
 * `runBlocking { channel.send(...) }`, so every pipeline / coproc stage
 * parked an entire dispatcher thread while waiting for the downstream to
 * drain. Two parties on a small fixed pool would deadlock: the producer's
 * write parked the only thread before the consumer ever got a chance to
 * `receive`.
 *
 * These tests pin a worst-case shape — coproc with a tight ring of
 * round-trips on a **single-threaded** dispatcher — that v1 cannot pass.
 * Post-refactor every `send` / `receive` is a coroutine suspension; the
 * single thread interleaves both halves and the run terminates.
 *
 * If anyone regresses `AsyncPipe` back to a `runTest`-style bridge,
 * these tests hang and time out within 10 s.
 */
class CoprocStarvationTest {
    /**
     * Single coproc round-trip on a one-thread dispatcher.
     *
     * Pre-refactor outcome: the child's `cat` parks its writer on
     * `runBlocking { channel.send }`; the parent's `read` blocks on
     * `runBlocking { channel.receive }`; only one thread exists; deadlock.
     *
     * Post-refactor: both halves suspend at the coroutine level; the single
     * thread alternates between them and the script completes.
     */
    @Test fun coprocRoundTrip_completesOnSingleThreadDispatcher() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = exec.asCoroutineDispatcher()
            runBlocking(dispatcher) {
                withTimeout(10_000) {
                    val r =
                        Kash(registry = standardRegistry()).exec(
                            """
                            coproc REFLECT { cat - ; }
                            echo payload >&${'$'}{REFLECT[1]}
                            read LINE <&${'$'}{REFLECT[0]}
                            echo "GOT: ${'$'}LINE"
                            kill ${'$'}REFLECT_PID 2>/dev/null
                            wait ${'$'}REFLECT_PID 2>/dev/null
                            """.trimIndent(),
                        )
                    assertTrue(
                        r.stdout.contains("GOT: payload"),
                        "expected coproc round-trip to complete on one thread; " +
                            "stdout=${r.stdout} stderr=${r.stderr}",
                    )
                }
            }
        } finally {
            exec.shutdownNow()
        }
    }

    /**
     * Saturated pipeline (`yes | head -n N`) on a tiny dispatcher.
     *
     * `yes` produces faster than `head` can drain; with a bounded
     * `AsyncPipe` (capacity 64) every send must back-pressure many times.
     * Pre-refactor each blocked send pinned a thread; with a 2-thread pool
     * and a 3-stage pipeline this hangs. Post-refactor it terminates.
     */
    @Test fun saturatedPipeline_terminatesOnTwoThreadPool() {
        val exec = Executors.newFixedThreadPool(2)
        try {
            val dispatcher = exec.asCoroutineDispatcher()
            runBlocking(dispatcher) {
                withTimeout(15_000) {
                    val r =
                        Kash(registry = standardRegistry()).exec(
                            "yes y | head -n 1000 | wc -l",
                        )
                    assertEquals(0, r.exitCode, "stderr=${r.stderr}")
                    // wc -l output may have leading whitespace; trim before compare.
                    assertEquals("1000", r.stdout.trim())
                }
            }
        } finally {
            exec.shutdownNow()
        }
    }

    /**
     * Many sequential coproc round-trips on a single thread. Catches the
     * subtle case where `send` happens to succeed once because the channel
     * has slack, but the second send (which fills capacity) parks. v1's
     * runBlocking would only fail on the second message; this test forces
     * us through ~100 send/receive cycles on a single thread.
     */
    @Test fun manyCoprocRoundTrips_singleThread() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = exec.asCoroutineDispatcher()
            runBlocking(dispatcher) {
                withTimeout(20_000) {
                    val r =
                        Kash(registry = standardRegistry()).exec(
                            """
                            coproc ECHO { cat -; }
                            i=0
                            out=""
                            while [ ${'$'}i -lt 50 ]; do
                              echo "msg-${'$'}i" >&${'$'}{ECHO[1]}
                              read got <&${'$'}{ECHO[0]}
                              out="${'$'}out|${'$'}got"
                              i=${'$'}((i+1))
                            done
                            echo "${'$'}out"
                            kill ${'$'}ECHO_PID 2>/dev/null
                            wait ${'$'}ECHO_PID 2>/dev/null
                            """.trimIndent(),
                        )
                    // Output is `|msg-0|msg-1|...|msg-49\n` — count the
                    // pipe separators to confirm all 50 round-trips landed.
                    val count = r.stdout.count { it == '|' }
                    assertEquals(
                        50,
                        count,
                        "expected 50 round-trips on one thread; got count=$count; " +
                            "stdout=${r.stdout} stderr=${r.stderr}",
                    )
                }
            }
        } finally {
            exec.shutdownNow()
        }
    }

    /**
     * Baseline that has always worked: the same shape on `Dispatchers.IO`
     * (elastic). If this passes but the single-thread variants above hang,
     * the failure mode is exactly the thread-starvation we're guarding
     * against — confirms it isn't a different bug in the script body.
     */
    @Test fun sanityCheck_sameScriptPassesOnElasticPool() =
        runBlocking(Dispatchers.IO) {
            withTimeout(10_000) {
                val r =
                    Kash(registry = standardRegistry()).exec(
                        """
                        coproc REFLECT { cat - ; }
                        echo payload >&${'$'}{REFLECT[1]}
                        read LINE <&${'$'}{REFLECT[0]}
                        echo "GOT: ${'$'}LINE"
                        kill ${'$'}REFLECT_PID 2>/dev/null
                        wait ${'$'}REFLECT_PID 2>/dev/null
                        """.trimIndent(),
                    )
                assertTrue(r.stdout.contains("GOT: payload"), "stderr=${r.stderr}")
            }
        }
}
