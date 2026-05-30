package com.accucodeai.kash

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.sandbox.NetworkPolicy
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
    // Registries are network-policy-scoped (the policy is baked into the
    // HTTP client used by curl/git/http). Memoize per policy so rebuilding
    // the VM — New Machine, or a KashFrame host reconfiguring the embed —
    // reuses the already-loaded Pyodide engine instead of constructing a
    // fresh one each time. Standalone only ever needs the allow-all entry.
    val registryCache = HashMap<NetworkPolicy, CommandRegistry>()
    val registryFactory: (NetworkPolicy) -> CommandRegistry = { policy ->
        registryCache.getOrPut(policy) { standardRegistry(networkPolicy = policy) }
    }
    ComposeViewport(document.body!!) {
        KashWorkspace(registryFactory = registryFactory)
    }
}
