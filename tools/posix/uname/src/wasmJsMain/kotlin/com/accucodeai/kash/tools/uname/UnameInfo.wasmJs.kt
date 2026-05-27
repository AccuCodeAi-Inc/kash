package com.accucodeai.kash.tools.uname

/**
 * wasmJs has no host kernel to interrogate, so we report constant, sensible
 * values describing the kash runtime itself rather than the underlying OS.
 */
internal actual fun unameInfo(): UnameInfo =
    UnameInfo(
        sysname = "kash",
        nodename = "localhost",
        release = "unknown",
        version = "unknown",
        machine = "wasm32",
        processor = "unknown",
        hardwarePlatform = "unknown",
        operatingSystem = "kash",
    )
