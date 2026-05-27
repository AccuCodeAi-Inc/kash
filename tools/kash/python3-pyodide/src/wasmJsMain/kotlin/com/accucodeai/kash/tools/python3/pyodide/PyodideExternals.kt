@file:Suppress("ktlint:standard:filename")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.python3.pyodide

import kotlin.js.Promise

// Kotlin/Wasm external declarations for the Pyodide JS API.
//
// Surface taken from https://pyodide.org/en/stable/usage/api/js-api.html.
// Only the slice [PyodideEngine] and [KashEmscriptenFs] use is declared —
// adding methods later is cheap, but importing the full Pyodide surface
// would bloat the wasm imports closure.
//
// `loadPyodide` is the top-level entry, imported from the `pyodide` npm
// package as an ES module. An equivalent CDN-loaded path
// (`<script src=".../pyodide.js"></script>` + `globalThis.loadPyodide`)
// is available as a fallback if the npm bundle's webpack config trips on
// the wasm + zip assets it ships.

/**
 * The hosting page is expected to include
 * `<script src="…/pyodide.js"></script>`, which assigns
 * `globalThis.loadPyodide`. We declare it as a plain external top-level
 * function — no annotation needed; external declarations resolve against
 * the JS global scope by default.
 *
 * Pyodide accepts an optional options object — see
 * [loadPyodideWithIndexUrl] for the call site that supplies `indexURL`
 * (the on-disk location of `pyodide.asm.wasm` + `python_stdlib.zip`).
 */
public external fun loadPyodide(): Promise<PyodideAPI>

/**
 * Two-argument loader that lets the host pin `indexURL` to a relative path
 * served alongside our own bundle. The JS-side fn signature is
 * `loadPyodide({ indexURL })`; we build the options literal with `js("…")`.
 */
public fun loadPyodideWithIndexUrl(indexUrl: String): Promise<PyodideAPI> = jsLoadPyodideWithIndexUrl(indexUrl)

private fun jsLoadPyodideWithIndexUrl(indexUrl: String): Promise<PyodideAPI> = js("loadPyodide({ indexURL: indexUrl })")

/**
 * Pyodide runtime handle returned from `loadPyodide()`. Holds the Python
 * interpreter, its globals, the Emscripten FS, and the low-level wasm
 * module.
 *
 * The JS-side stdin/stdout/stderr setters take untagged plain objects; we
 * model them as [JsAny] and build them with `js("...")` literals at the
 * call site instead of declaring strongly-typed external interfaces.
 */
public external interface PyodideAPI : JsAny {
    /**
     * Run [code] as a Python program. Returns a promise resolving to whatever
     * the expression evaluates to (or `undefined` for statement-only scripts).
     * Top-level `await` inside Python is honored.
     */
    public fun runPythonAsync(code: JsString): Promise<JsAny?>

    /** Synchronous variant — no top-level `await`. Reserved for fast paths. */
    public fun runPython(code: JsString): JsAny?

    /** Replace the stdin handler. `options` is an object with `stdin`, `isatty`, … */
    public fun setStdin(options: JsAny)

    /** Replace the stdout handler. */
    public fun setStdout(options: JsAny)

    /** Replace the stderr handler. */
    public fun setStderr(options: JsAny)

    /** Emscripten FS handle. JS-side name is upper-case `FS`. */
    @Suppress("ktlint:standard:property-naming", "PropertyName")
    public val FS: EmscriptenFS

    /**
     * Low-level wasm module. Reserved for `setInterruptBuffer` etc.
     * JS-side name has a leading underscore (`_module`).
     */
    @Suppress("ktlint:standard:backing-property-naming", "PropertyName")
    public val _module: JsAny
}

/**
 * Subset of the Emscripten FS API that Pyodide exposes (per
 * `https://emscripten.org/docs/api_reference/Filesystem-API.html`).
 *
 * Only the methods [KashEmscriptenFs] uses are declared.
 */
public external interface EmscriptenFS : JsAny {
    /**
     * Mount a filesystem implementation at [mountpoint]. [type] is the
     * FS *type* object — typically one of `FS.filesystems.MEMFS` etc., or
     * a custom plugin. [opts] is opaque per-FS configuration.
     */
    public fun mount(
        type: JsAny,
        opts: JsAny,
        mountpoint: JsString,
    )

    public fun mkdir(path: JsString)

    public fun mkdirTree(path: JsString)

    public fun writeFile(
        path: JsString,
        data: JsAny,
    )

    public fun readFile(path: JsString): JsAny

    public fun unlink(path: JsString)

    public fun chdir(path: JsString)
}
