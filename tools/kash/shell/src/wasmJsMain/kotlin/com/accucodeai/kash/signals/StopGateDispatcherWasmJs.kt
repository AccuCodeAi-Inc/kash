package com.accucodeai.kash.signals

import kotlinx.coroutines.CoroutineDispatcher

/**
 * wasmJs flavor: just the base [StopGateDispatcher], no `Delay`
 * implementation. The Kotlin/Wasm code generator produces invalid
 * wasm bytecode ("wasm validation error: type mismatch") when a
 * user class implements `kotlinx.coroutines.Delay` (an
 * `@InternalCoroutinesApi` interface). The wasm runtime has no
 * `TestCoroutineScheduler` to integrate with, so falling back to
 * the platform `DefaultDelay` is the right behavior.
 */
internal actual fun createStopGateDispatcher(
    inner: CoroutineDispatcher,
    gate: StopGate,
): CoroutineDispatcher = StopGateDispatcher(inner, gate)
