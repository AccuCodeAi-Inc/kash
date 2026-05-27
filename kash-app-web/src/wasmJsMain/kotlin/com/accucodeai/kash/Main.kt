package com.accucodeai.kash

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.accucodeai.kash.app.standardRegistry
import com.accucodeai.kash.ui.KashWorkspace
import kotlinx.browser.document

/**
 * wasmJs entry point. Boots the Koin graph (which transitively wires the
 * Pyodide-backed `python3` engine), constructs a `KashMachine` against an
 * in-memory FS, and hands both to the Compose terminal pane.
 *
 * The pane spawns the `kash` command as PID 1's child the same way the
 * JVM `:kash-app` `Main.kt` does — the only differences here are
 *  - no host terminal, no termios, no signal bridge
 *  - no snapshot persistence (Phase 3 polish: persist to IndexedDB)
 *  - stdio pipes are wired to Compose state instead of OS file descriptors
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun main() {
    val registry = standardRegistry()
    ComposeViewport(document.body!!) {
        KashWorkspace(registry = registry)
    }
}
