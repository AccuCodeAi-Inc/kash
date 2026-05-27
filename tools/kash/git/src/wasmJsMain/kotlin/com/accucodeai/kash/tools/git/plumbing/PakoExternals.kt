@file:JsModule("pako")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.git.plumbing

// ES-module imports from the `pako` npm package (MIT + Zlib). Browser-safe
// (webpack resolves these) unlike `js("require('pako')")`, which throws
// `require is not a function` outside the Node test runner.
//
// pako is retained for git (not migrated to fflate) because the pack-file
// parser needs the consumed-byte count after a partial inflate — exposed
// here via Inflate().strm.total_in. fflate has no equivalent (fflate#221,
// closed not-planned). See Zlib.wasmJs.kt for usage.

public external fun deflate(
    data: JsAny,
    opts: JsAny,
): JsAny

public external fun inflate(data: JsAny): JsAny

// pako's streaming inflate. After `push(data, false)`, `result` holds the
// decompressed Uint8Array, `err` is nonzero on failure, and `strm` carries
// `total_in` (bytes consumed) — the field we can't get any other way.
public external class Inflate {
    public fun push(
        data: JsAny,
        flush: Boolean,
    )

    public val result: JsAny
    public val err: Int
    public val strm: JsAny
}
