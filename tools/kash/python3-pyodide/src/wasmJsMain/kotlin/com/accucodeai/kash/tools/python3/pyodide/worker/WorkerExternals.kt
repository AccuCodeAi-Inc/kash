@file:Suppress("ktlint:standard:filename")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.python3.pyodide.worker

/**
 * Slice of the DOM `Worker` API + the `crossOriginIsolated` capability check
 * we use to decide whether the worker stdin path is viable.
 *
 * Kotlin/Wasm doesn't bundle DOM externals for `Worker`; we declare just
 * what the client uses. The matching JS-side type is `globalThis.Worker`.
 */
public external class Worker(
    scriptURL: JsString,
) : JsAny {
    public var onmessage: ((MessageEvent) -> Unit)?
    public var onerror: ((JsAny) -> Unit)?

    public fun postMessage(message: JsAny)

    public fun postMessage(
        message: JsAny,
        transfer: JsArray<JsAny>,
    )

    public fun terminate()
}

public external interface MessageEvent : JsAny {
    public val data: JsAny?
}

/**
 * `globalThis.crossOriginIsolated` is `true` only when the page was served
 * with COOP `same-origin` + COEP `require-corp`. Without isolation,
 * SharedArrayBuffer construction throws — we check first and degrade to
 * the in-process Pyodide path.
 */
internal fun isCrossOriginIsolated(): Boolean = jsIsCrossOriginIsolated()

private fun jsIsCrossOriginIsolated(): Boolean =
    js("(typeof crossOriginIsolated !== 'undefined') && crossOriginIsolated === true")

/**
 * `typeof SharedArrayBuffer === 'function'` — true even outside an
 * isolated context on some browsers (a no-op SAB shim), so combine with
 * [isCrossOriginIsolated] for the real capability check.
 */
internal fun hasSharedArrayBufferConstructor(): Boolean = jsHasSharedArrayBuffer()

private fun jsHasSharedArrayBuffer(): Boolean = js("typeof SharedArrayBuffer === 'function'")
