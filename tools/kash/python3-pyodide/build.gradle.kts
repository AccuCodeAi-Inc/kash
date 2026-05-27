plugins {
    id("kash.kmp")
}

description =
    "kash `python3` Pyodide engine — Kotlin/Wasm backend for kash python3 that drives Pyodide (CPython compiled to WebAssembly) via a dedicated Web Worker."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(project(":tools:kash:python3"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
        // Pyodide is intentionally NOT declared as an npm dependency. It
        // dynamically imports Node-only modules (`node:vm`, `node:fs`, ...)
        // that webpack can't resolve when targeting the browser. The
        // hosting page is expected to pull Pyodide from the CDN via a
        // `<script src="https://cdn.jsdelivr.net/pyodide/.../pyodide.js">`
        // tag, which exposes `globalThis.loadPyodide`. The externals in
        // `PyodideExternals.kt` access it through that global.
    }
}

// `PyodideEngine` is the wasmJs `PythonEngine` implementation; the
// :kash-app-web entry point constructs one and threads it into
// `python3Commands(...)` alongside the rest of the standard catalog.
