@file:JsModule("fflate")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.shared.fflate

// ES-module imports from the `fflate` npm package (MIT). Kotlin/Wasm
// supports ES modules only — `js("require('fflate')")` works under the
// Node test runner but throws `require is not a function` in the browser,
// so the browser-safe path is @JsModule named imports that webpack
// resolves at bundle time.
//
// Binary crosses as JsAny (a browser-built Uint8Array); options is a JsAny
// object literal. See WasmBytes.kt for the marshaling helpers and each
// tool's codec for opts construction.

public external fun gzipSync(
    data: JsAny,
    opts: JsAny,
): JsAny

public external fun gunzipSync(data: JsAny): JsAny

public external fun zipSync(data: JsAny): JsAny

public external fun unzipSync(data: JsAny): JsAny
