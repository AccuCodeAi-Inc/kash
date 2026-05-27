package com.accucodeai.kash.signals

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * Cooperative coroutine "stop gate" — bash's `SIGTSTP` → process-stop →
 * `SIGCONT` → process-resume cycle, expressed at the Kotlin coroutine
 * level. Wrapped by [StopGateDispatcher] which gates every coroutine
 * `dispatch()` call: while the gate is paused, continuations are
 * registered on a [CompletableJob] and resumed only when the gate
 * reopens. This is the kash-coroutine equivalent of the kernel's
 * "schedule this process only at SIGCONT" decision.
 *
 * The gate is a single [CompletableJob] that swaps between "completed"
 * (open) and "non-completed" (paused) states:
 *  - **Open** (initial): dispatches forward immediately to the inner.
 *  - **Paused**: dispatches register `invokeOnCompletion` on the gate;
 *    when the gate completes (via [resume]), every callback fires and
 *    forwards to the inner.
 *
 * Why a `CompletableJob` over a `Mutex` / `Channel<Runnable>`:
 *  - `Job.invokeOnCompletion` is **thread-safe and non-blocking** — safe
 *    to register from the JVM signal-handler thread, which cannot
 *    `suspend`. `pause()` and [resume] are likewise non-suspending.
 *  - Zero allocation on the open-path (one `isCompleted` check + delegate).
 *  - `complete()` fires every registered callback synchronously on the
 *    caller's thread; from there we forward to the inner dispatcher, so
 *    the original `CoroutineDispatcher.dispatch` contract — "the block
 *    runs on the dispatcher's thread" — is preserved.
 *
 * Lifecycle: [close] cancels the gate, releasing every pending callback
 * (they fire with the cancellation cause and dispatch into inner one
 * last time, where structured cancellation from the owning scope will
 * unwind them). The wrapper holds — but does NOT own the lifecycle of —
 * the inner dispatcher; [close] never closes inner.
 */
public class StopGate {
    /**
     * The current gate Job. Open = completed; paused = non-completed.
     * Swapped by [pause]; completed in place by [resume].
     *
     * Concurrency model: this class is multiplatform (commonMain). On
     * wasmJs / JS there's a single execution thread so no locking is
     * needed. On JVM, [pause] / [resume] / [close] *can* be called
     * from a signal-handler thread that cannot suspend; we accept the
     * race because every transition is idempotent — racing pauses
     * leak at most one freshly-allocated `Job()` that has nothing
     * registered on it, and `Job.complete()` / `Job.cancel(...)` are
     * themselves thread-safe.
     */
    internal var gate: CompletableJob = Job().apply { complete() }
        private set

    /**
     * Set once by [close] to make further [pause] / [resume] calls
     * no-ops, and to bias [isOpen] so a closed gate looks "open"
     * (forwards everything to inner; structured cancellation from the
     * owning scope handles the rest).
     */
    private var closed: Boolean = false

    /** True when the gate is currently open (dispatches forward immediately). */
    public val isOpen: Boolean
        get() = closed || gate.isCompleted

    /**
     * Move to the paused state. If already paused, no-op. Synchronous
     * and non-suspending so a signal-handler / non-coroutine caller
     * can invoke it.
     */
    public fun pause() {
        if (closed) return
        if (gate.isCompleted) {
            gate = Job()
        }
    }

    /**
     * Move to the open state, firing every callback registered while
     * paused. Synchronous and non-suspending. Idempotent: resuming an
     * open gate is a no-op (the underlying Job is already completed,
     * `complete()` returns false).
     */
    public fun resume() {
        if (closed) return
        gate.complete()
    }

    /**
     * Suspend until the gate is open. Not used by the dispatcher
     * (which gates via `invokeOnCompletion`); exposed for explicit-
     * yield callers that want a suspension primitive parked on the
     * gate's lifecycle.
     */
    public suspend fun awaitOpen() {
        gate.join()
    }

    /**
     * Release every pending callback and put the gate into a
     * permanently-open state. Idempotent.
     *
     * The cancellation cause fires every registered `invokeOnCompletion`
     * callback exactly once; the callback's body forwards to the inner
     * dispatcher, which will then resume the continuation into a scope
     * that is itself being cancelled — structured cancellation unwinds
     * it cleanly. No continuation is pinned by the gate after [close].
     */
    public fun close() {
        if (closed) return
        closed = true
        gate.cancel(CancellationException("StopGate closed"))
    }
}

/**
 * [CoroutineDispatcher] that interposes a [StopGate] between the
 * coroutine machinery and an inner dispatcher.
 *
 * Override audit (against `CoroutineDispatcher` API):
 *  - `dispatch`: required (abstract). Gates the dispatch hop.
 *  - `isDispatchNeeded`: must override. Open → delegate to [inner];
 *    paused → force `true` so a dispatch hop fires and the paused gate
 *    can register the callback.
 *  - `limitedParallelism`: must override. The default produces a
 *    `LimitedDispatcher` view that wraps `this` and bypasses the gate.
 *    The correct override wraps `inner.limitedParallelism(...)` in a
 *    sibling [StopGateDispatcher] sharing the SAME [gate], so the gate
 *    survives any user-code `.limitedParallelism(N)` call.
 *  - [Delay] (`scheduleResumeAfterDelay` / `invokeOnTimeout`): delegate
 *    to [inner] if it implements [Delay]; otherwise no-op fall through
 *    to the platform default delay. Without this, `delay()` /
 *    `withTimeout()` calls inside a coroutine running on our wrapper
 *    would fall back to `DefaultDelay` (real time) — perturbing
 *    virtual-time test schedulers and breaking any timing test that
 *    happens to suspend through our wrapper. Implementing [Delay]
 *    makes our wrapper feature-equivalent to a `TestDispatcher` or
 *    `Dispatchers.Default` for the purpose of scheduled execution.
 *  - `interceptContinuation` / `releaseInterceptedContinuation`: NOT
 *    overridden. The base default's `interceptContinuation` wraps each
 *    continuation so every future `resumeWith` calls `this.dispatch(...)`
 *    — that's the load-bearing mechanism that routes every coroutine
 *    suspension back through our gate. Delegating to [inner] would
 *    bypass the gate, silently breaking suspend/resume.
 *
 * The wrapper does NOT own the lifecycle of [inner] — never closes it.
 * [gate]'s lifecycle is owned by whoever constructed this dispatcher
 * (typically the [com.accucodeai.kash.interpreter.Interpreter]).
 */
public open class StopGateDispatcher(
    public val inner: CoroutineDispatcher,
    public val gate: StopGate,
) : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        if (gate.isOpen) {
            inner.dispatch(context, block)
        } else {
            gate.gate.invokeOnCompletion {
                inner.dispatch(context, block)
            }
        }
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        if (gate.isOpen) {
            inner.isDispatchNeeded(context)
        } else {
            // Force a dispatch hop so the paused gate can park us.
            true
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun limitedParallelism(
        parallelism: Int,
        name: String?,
    ): CoroutineDispatcher = createStopGateDispatcher(inner.limitedParallelism(parallelism, name), gate)
}

/**
 * Platform factory: returns a [StopGateDispatcher] that participates
 * in the test scheduler's virtual clock where available.
 *
 * On JVM, returns a subclass that ALSO implements
 * [kotlinx.coroutines.Delay] and delegates
 * `scheduleResumeAfterDelay` / `invokeOnTimeout` to [inner] — this
 * is what lets `delay()` inside a coroutine on our wrapper observe
 * the inner dispatcher's `TestCoroutineScheduler` virtual time
 * (essential for the conformance test suite's virtual-time
 * scheduling).
 *
 * On wasmJs, returns the base [StopGateDispatcher] without
 * implementing `Delay` — the `Delay` interface is
 * `@InternalCoroutinesApi` and triggers a Kotlin/Wasm code-gen
 * bug ("wasm validation error: type mismatch (ref null N) but
 * expected (ref null M)") when implemented by a user class.
 * The wasm runtime doesn't have a `TestCoroutineScheduler`
 * anyway — `delay()` falls back to the platform `DefaultDelay`,
 * which is correct for production wasm execution.
 */
internal expect fun createStopGateDispatcher(
    inner: CoroutineDispatcher,
    gate: StopGate,
): CoroutineDispatcher
