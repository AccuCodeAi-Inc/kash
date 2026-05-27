package com.accucodeai.kash.signals

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * JVM-flavored [StopGateDispatcher] that also implements
 * [kotlinx.coroutines.Delay] so that `delay(N)` / `withTimeout(...)`
 * calls in a coroutine on this dispatcher participate in [inner]'s
 * scheduling (notably `kotlinx.coroutines.test.TestCoroutineScheduler`'s
 * virtual clock during the conformance test suite). Lives in jvmMain
 * because the [Delay] interface — though commonMain-declared — uses
 * platform features that trip the Kotlin/Wasm code generator and
 * produce a runtime "wasm validation error: type mismatch" when
 * implemented by a user class on wasmJs.
 */
@OptIn(InternalCoroutinesApi::class)
internal class JvmStopGateDispatcher(
    inner: CoroutineDispatcher,
    gate: StopGate,
) : StopGateDispatcher(inner, gate),
    Delay {
    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val innerDelay = inner as? Delay
        if (innerDelay != null) {
            innerDelay.scheduleResumeAfterDelay(timeMillis, continuation)
        } else {
            // Inner doesn't support Delay. The continuation contract says
            // we must still resume eventually; fall back to immediate
            // resume (equivalent to delay(0)).
            continuation.resumeWith(Result.success(Unit))
        }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle {
        val innerDelay = inner as? Delay
        return if (innerDelay != null) {
            innerDelay.invokeOnTimeout(timeMillis, block, context)
        } else {
            DisposableHandle { /* no-op */ }
        }
    }
}

internal actual fun createStopGateDispatcher(
    inner: CoroutineDispatcher,
    gate: StopGate,
): CoroutineDispatcher = JvmStopGateDispatcher(inner, gate)
