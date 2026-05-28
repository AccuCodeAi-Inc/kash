package com.accucodeai.kash

import com.accucodeai.kash.signals.StopGate
import com.accucodeai.kash.signals.StopGateDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit-level coverage of [StopGate] and [StopGateDispatcher]. These
 * tests are isolated from the interpreter — they exercise the gate +
 * dispatcher contract directly against a minimal inner dispatcher.
 */
class StopGateDispatcherTest {
    /**
     * Minimal inner dispatcher that records every `dispatch` call,
     * runs blocks synchronously on the calling thread, and exposes
     * `isDispatchNeededOverride` so we can verify wrapper delegation.
     */
    private class RecordingDispatcher(
        val isDispatchNeededOverride: Boolean = true,
    ) : CoroutineDispatcher() {
        val dispatched: MutableList<Runnable> = mutableListOf()
        var dispatchCount: Int = 0

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatchCount++
            dispatched += block
            block.run()
        }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = isDispatchNeededOverride
    }

    @Test fun dispatchWhenOpenForwardsToInner() {
        val inner = RecordingDispatcher()
        val gate = StopGate()
        val d = StopGateDispatcher(inner, gate)
        var ran = false
        d.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { ran = true })
        assertTrue(ran)
        assertEquals(1, inner.dispatchCount)
    }

    @Test fun dispatchWhenPausedQueuesUntilResume() {
        val inner = RecordingDispatcher()
        val gate = StopGate()
        val d = StopGateDispatcher(inner, gate)
        gate.pause()
        val ran = BooleanArray(3)
        repeat(3) { i ->
            d.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { ran[i] = true })
        }
        // Nothing ran while paused.
        assertEquals(0, inner.dispatchCount)
        assertTrue(ran.none { it })
        // Resume drains all three into inner.
        gate.resume()
        assertEquals(3, inner.dispatchCount)
        assertTrue(ran.all { it })
    }

    @Test fun pauseIsIdempotent() {
        // Observable contract: pausing twice in a row, dispatching
        // between, then a single resume must drain everything. If the
        // second pause created a new Job, the first dispatch's
        // invokeOnCompletion would be orphaned on the original Job
        // and never fire — `ran` would be incomplete after resume.
        val inner = RecordingDispatcher()
        val gate = StopGate()
        val d = StopGateDispatcher(inner, gate)
        gate.pause()
        var ran1 = false
        d.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { ran1 = true })
        gate.pause()
        var ran2 = false
        d.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { ran2 = true })
        gate.resume()
        assertTrue(ran1 && ran2, "single resume after double-pause must drain both dispatches")
    }

    @Test fun resumeIsIdempotent() {
        val gate = StopGate()
        // Resume on an open gate is a no-op (Job is already completed).
        gate.resume()
        assertTrue(gate.isOpen)
        // Pause then double-resume: first resumes, second is no-op.
        gate.pause()
        gate.resume()
        assertTrue(gate.isOpen)
        gate.resume()
        assertTrue(gate.isOpen)
    }

    @Test fun concurrentPauseAndDispatchIsSafe() =
        runTest {
            // Single-threaded test scheduler — we can still exercise the
            // gate's API under sequential interleaving since the gate's
            // pause/resume/dispatch are all synchronized. A real multi-
            // threaded stress lives in jvmTest territory if needed; here
            // we verify the API contract under tight interleaving.
            val inner = RecordingDispatcher()
            val gate = StopGate()
            val d = StopGateDispatcher(inner, gate)
            val ran = mutableListOf<Int>()
            for (i in 0 until 50) {
                if (i % 3 == 0) gate.pause()
                d.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { ran += i })
                if (i % 3 == 0) gate.resume()
            }
            // Every dispatch eventually ran (the resume right after each
            // paused dispatch drains the queued callback immediately).
            assertEquals(50, ran.size)
        }

    @Test fun pauseFromSignalHandlerThreadDoesNotDeadlock() {
        // Smoke test: pause() must be synchronous (no `suspend`) so it
        // can be called from the JVM signal-handler thread. Constructing
        // a fresh gate and calling pause/resume in tight succession
        // verifies no blocking primitives are on the path.
        val gate = StopGate()
        repeat(100) {
            gate.pause()
            gate.resume()
        }
        assertTrue(gate.isOpen)
    }

    @Test fun isDispatchNeededDelegatesWhenOpenForcesTrueWhenPaused() {
        val innerFalse = RecordingDispatcher(isDispatchNeededOverride = false)
        val gate = StopGate()
        val d = StopGateDispatcher(innerFalse, gate)
        // Open: delegate to inner — false here, so the wrapper returns
        // false too. (Inner is an Unconfined-style dispatcher.)
        assertFalse(d.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext))
        gate.pause()
        // Paused: force true so the dispatch hop fires and the gate
        // gets a chance to register the invokeOnCompletion callback.
        assertTrue(d.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun limitedParallelismWrapsLimitedInnerWithSameGate() {
        val inner = RecordingDispatcher()
        val gate = StopGate()
        val d = StopGateDispatcher(inner, gate)
        val limited = d.limitedParallelism(2)
        assertTrue(limited is StopGateDispatcher, "limitedParallelism must return a StopGateDispatcher")
        // Same gate instance — pausing the parent affects the limited view.
        gate.pause()
        var ran = false
        limited.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { ran = true })
        assertFalse(ran, "paused gate must also gate the limited view")
        gate.resume()
        assertTrue(ran)
    }

    @Test fun closeReleasesPendingCallbacks() {
        val inner = RecordingDispatcher()
        val gate = StopGate()
        val d = StopGateDispatcher(inner, gate)
        gate.pause()
        val ran = BooleanArray(5)
        repeat(5) { i ->
            d.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { ran[i] = true })
        }
        assertEquals(0, inner.dispatchCount, "paused gate must not have dispatched yet")
        gate.close()
        // The cancellation fires every registered invokeOnCompletion
        // callback exactly once; each callback forwards to inner.dispatch.
        assertEquals(5, inner.dispatchCount)
        assertTrue(ran.all { it })
    }

    @Test fun closeIsIdempotent() {
        val gate = StopGate()
        gate.pause()
        gate.close()
        // Second close must not throw.
        gate.close()
        assertTrue(gate.isOpen, "closed gate must report open so subsequent dispatches forward")
    }

    @Test fun doNotCloseInnerDispatcher() {
        // Use a sentinel that flips when close() is called. Real
        // [kotlinx.coroutines.CloseableCoroutineDispatcher] would be
        // ideal but pulls in JVM-only newSingleThreadContext; the
        // assertion is the same: our wrapper never invokes any close
        // path on the inner.
        var innerClosed = false
        val inner =
            object : CoroutineDispatcher(), AutoCloseable {
                var dispatchCount = 0

                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) {
                    dispatchCount++
                    block.run()
                }

                override fun close() {
                    innerClosed = true
                }
            }
        val gate = StopGate()
        val d = StopGateDispatcher(inner, gate)
        gate.close()
        assertFalse(innerClosed, "StopGate.close() must NOT close the inner dispatcher")
    }

    @Test fun dispatchAfterCloseStillForwardsToInner() {
        // Post-close, the gate is permanently open. Dispatches must
        // forward to inner (not get lost). Structured cancellation from
        // the owning scope handles the rest.
        val inner = RecordingDispatcher()
        val gate = StopGate()
        val d = StopGateDispatcher(inner, gate)
        gate.close()
        var ran = false
        d.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { ran = true })
        assertTrue(ran)
        assertEquals(1, inner.dispatchCount)
    }
}
