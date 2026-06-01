@file:OptIn(ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.ui

import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.api.sandbox.SandboxPolicy
import com.accucodeai.kash.snapshot.SnapshotPayload
import kotlinx.browser.window
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * KashFrame — secure cross-origin embedding protocol.
 *
 * Lets a host page (popup opener or `<iframe>` parent) push a snapshot
 * into a running kash and pull the current workspace back out, over a
 * `postMessage`/`MessagePort` channel. The whole feature is **disabled
 * unless an explicit embedder allowlist is supplied** via the page URL —
 * secure by default.
 *
 * ## Intended use
 * The driving use case is **debugging agentic workflows**, which are
 * snapshot-based rather than live: a harness captures the machine state an
 * agent ran against, then a developer reloads that snapshot here to inspect
 * or replay it. So the expected flow is `connect → configure → load-snapshot`
 * (set the sandbox posture, then drop in the agent's snapshot), not a user
 * building a live session by hand. Two design choices follow from this:
 *  - `configure` rebuilds the VM — that's fine, there's no precious live
 *    state; you're about to load a snapshot anyway.
 *  - a debug harness loads snapshots in a loop, so listing the host under
 *    `?trustedEmbedders=` (skip the per-load confirm) is the intended knob
 *    for that case.
 *
 * ## Configuration (URL query)
 *  - `?embedders=<csv of exact origins>` — origins permitted to connect.
 *    Each is matched by **exact string** against `event.origin` (no
 *    wildcards, no regex — those are the documented foot-guns).
 *  - `?trustedEmbedders=<csv>` — subset that may act **without** a user
 *    confirmation prompt (e.g. a first-party shell hosting kash). Trusted
 *    origins are implicitly allowed.
 *
 * Example embed:
 * ```
 * https://kash.app/?embedders=https%3A%2F%2Fdocs.example.com
 * ```
 *
 * ## Handshake (the MessagePort pattern)
 * Per the cross-window security guidance, we do exactly one origin-checked
 * `postMessage` to bootstrap, then move all traffic onto a private channel:
 *
 *  1. On load, kash posts `{type:"kashframe:hello", version}` to its parent
 *     and/or opener, once per allowed origin, using that origin as the
 *     **exact** `targetOrigin`. (Lets a host that loaded kash first know to
 *     (re)send its connect.)
 *  2. The host creates a `MessageChannel`, keeps `port1`, and transfers
 *     `port2`:
 *     ```
 *     kashIframe.contentWindow.postMessage(
 *         {type:"kashframe:connect", version:1},
 *         "https://kash.app",            // exact targetOrigin, never "*"
 *         [channel.port2],
 *     )
 *     ```
 *  3. kash validates `event.origin` against the allowlist (NOT
 *     `event.source` — a hijacked iframe can forge the window ref but not
 *     the origin), adopts `event.ports[0]`, and replies
 *     `{type:"kashframe:ready", payload:<version>}` over the port.
 *  4. Because a `MessagePort` only carries messages between its two ends,
 *     no other frame can see or inject into it — so subsequent messages
 *     need no per-message origin re-check.
 *
 * ## Messages over the port
 * Host → kash:
 *  - `kashframe:configure` `{payload:<policy JSON>}` — set the embed's
 *    security posture (network / sandbox / usable commands). Rebuilds the
 *    VM; send it right after connect, before any snapshot. Payload shape:
 *    ```
 *    { "network": { "mode":"none"|"deny-all"|"allowlist",
 *                   "hosts":[...], "ports":[...], "schemes":[...] },
 *      "sandbox": "TRUSTED"|"CONSTRAINED"|"UNTRUSTED"|"SAFE",
 *      "commands": ["ls","cat", ...] }      // omit = all tools
 *    ```
 *    Omitted fields keep the embedded defaults (deny-all / SAFE / all).
 *  - `kashframe:load-snapshot` `{payload:<snapshot JSON>}` — replace the
 *    session. Confirm-gated unless the origin is trusted.
 *  - `kashframe:request-snapshot` `{payload:"full"|"fs"}` — export the
 *    workspace. Confirm-gated (it's exfiltration otherwise).
 *
 * kash → host:
 *  - `kashframe:ready` `{payload:<version>}`
 *  - `kashframe:ack` `{replyId}` — load applied.
 *  - `kashframe:snapshot` `{replyId, payload:<snapshot JSON>}` — export.
 *  - `kashframe:error` `{replyId, payload:<reason>}` — `invalid-snapshot`,
 *    `capture-failed`, or `declined`.
 *
 * Every payload is treated as untrusted: snapshots are parsed through
 * [BrowserSnapshotStore.decodeFromFile] (which rejects anything missing the
 * `kash-snapshot` format tag), and restored state lives only in the
 * in-memory VFS, so the blast radius of a hostile snapshot is the sandbox.
 */
internal object KashFrameConfig {
    /** Origins permitted to connect (trusted origins are implicitly allowed). */
    fun allowedEmbedders(): List<String> = (queryOrigins("embedders") + queryOrigins("trustedEmbedders")).distinct()

    /** Origins allowed to skip the user-confirmation prompt. */
    fun trustedEmbedders(): List<String> = queryOrigins("trustedEmbedders")

    private fun queryOrigins(key: String): List<String> {
        val search = window.location.search
        if (search.isBlank()) return emptyList()
        for (pair in search.removePrefix("?").split("&")) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            if (pair.substring(0, eq) != key) continue
            val raw = jsDecodeURIComponent(pair.substring(eq + 1))
            return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return emptyList()
    }
}

/** A pending KashFrame operation awaiting (or skipping) user confirmation. */
internal sealed interface KashFrameRequest {
    val origin: String
    val replyId: String

    /** Host wants to replace the live session with [payload]. */
    data class Restore(
        override val origin: String,
        override val replyId: String,
        val name: String,
        val payload: SnapshotPayload,
    ) : KashFrameRequest

    /** Host wants a copy of the current workspace. */
    data class Export(
        override val origin: String,
        override val replyId: String,
        val fsOnly: Boolean,
    ) : KashFrameRequest
}

// ---- Policy configuration (kashframe:configure) ------------------------
//
// The host can set the embed's security posture: outbound network, the
// language-engine sandbox, and which tools are usable. Unspecified fields
// fall back to the mode default (the embedded default is no-network /
// SAFE / all-commands). Applying a config rebuilds the VM, so it's meant to
// be sent right after connect, before pushing a snapshot.

@Serializable
private data class FrameNetworkDto(
    val mode: String = "deny-all", // "none" | "deny-all" | "allowlist"
    val hosts: List<String> = emptyList(),
    val ports: List<Int> = emptyList(),
    val schemes: List<String> = emptyList(),
)

@Serializable
private data class FramePolicyDto(
    val network: FrameNetworkDto? = null,
    val sandbox: String? = null, // SandboxPolicy name: TRUSTED|CONSTRAINED|UNTRUSTED|SAFE
    val commands: List<String>? = null, // null = all tools
)

private val frameJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Parse a `kashframe:configure` payload into a [WorkspacePolicy], using
 * [base] (the mode default) for any field the host omits. Returns null on
 * malformed JSON or an unknown sandbox name.
 */
internal fun parseFramePolicy(
    payload: String,
    base: WorkspacePolicy,
): WorkspacePolicy? {
    val dto =
        try {
            frameJson.decodeFromString(FramePolicyDto.serializer(), payload)
        } catch (_: Throwable) {
            return null
        }
    val sandbox =
        if (dto.sandbox == null) {
            base.sandbox
        } else {
            runCatching { SandboxPolicy.valueOf(dto.sandbox.uppercase()) }.getOrNull() ?: return null
        }
    val network = dto.network?.let(::mapFrameNetwork) ?: base.network
    val commands = dto.commands?.toSet() ?: base.allowedCommands
    return WorkspacePolicy(network = network, sandbox = sandbox, allowedCommands = commands)
}

private fun mapFrameNetwork(dto: FrameNetworkDto): NetworkPolicy =
    when (dto.mode.lowercase()) {
        "none", "allow-all" -> NetworkPolicy.None
        "allowlist" -> NetworkPolicy.Allowlist(dto.hosts.toSet(), dto.ports.toSet(), dto.schemes.toSet())
        else -> NetworkPolicy.DenyAll
    }

/**
 * Install the KashFrame message listener and announce readiness to the
 * host. [onConnect] fires (once) with the validated embedder origin after a
 * successful handshake; [onMessage] fires per port message as
 * `(type, replyId, payload)`. Both run on the main thread, so the callbacks
 * may touch Compose state directly.
 */
internal fun installKashFrame(
    allowedCsv: String,
    onConnect: (String) -> Unit,
    onMessage: (String, String, String) -> Unit,
) {
    jsInstallKashFrame(allowedCsv, onConnect, onMessage)
    jsKashFrameAnnounce(allowedCsv)
}

/**
 * True when kash is running inside another page — an `<iframe>`
 * (`window.parent !== window`) or a popup that has an opener. The workspace
 * uses this to suppress persistent autosave so an embedded instance never
 * reads from or writes to the first-party snapshot store. That makes the
 * isolation kash-enforced rather than relying on the browser's third-party
 * storage partitioning.
 */
internal fun isEmbedded(): Boolean = jsIsEmbedded()

private fun jsIsEmbedded(): Boolean = js("(!!(window.parent && window.parent !== window)) || (!!window.opener)")

/** Post a message to the connected host over the private port. No-op if not connected. */
internal fun kashFramePost(
    type: String,
    replyId: String,
    payload: String,
) {
    jsKashFramePost(type, replyId, payload)
}

private fun jsDecodeURIComponent(s: String): String = js("decodeURIComponent(s)")

private fun jsInstallKashFrame(
    allowedCsv: String,
    onConnect: (String) -> Unit,
    onMessage: (String, String, String) -> Unit,
): Unit =
    js(
        """{
            try {
                var allowed = (allowedCsv || '').split(',')
                    .map(function (s) { return s.trim(); })
                    .filter(Boolean);
                window.addEventListener('message', function (event) {
                    try {
                        var d = event.data;
                        if (!d || d.type !== 'kashframe:connect') return;
                        // Exact-origin allowlist. Never validate event.source
                        // (the window ref): a hijacked iframe can forge it.
                        if (allowed.indexOf(event.origin) === -1) return;
                        var port = (event.ports && event.ports[0]) || null;
                        if (!port) return;
                        globalThis.__kashframePort = port;
                        port.onmessage = function (e) {
                            try {
                                var m = e.data || {};
                                var t = (typeof m.type === 'string') ? m.type : '';
                                if (!t) return;
                                var rid = (typeof m.replyId === 'string') ? m.replyId : '';
                                var pl = (typeof m.payload === 'string') ? m.payload : '';
                                onMessage(t, rid, pl);
                            } catch (_) { /* drop malformed */ }
                        };
                        if (port.start) port.start();
                        onConnect(event.origin);
                    } catch (_) { /* nothing */ }
                });
            } catch (_) { /* nothing */ }
        }""",
    )

private fun jsKashFramePost(
    type: String,
    replyId: String,
    payload: String,
): Unit =
    js(
        """{
            try {
                var p = globalThis.__kashframePort;
                if (p) p.postMessage({ type: type, replyId: replyId || '', payload: payload || '' });
            } catch (_) { /* nothing */ }
        }""",
    )

// Announce readiness to parent/opener, once per allowed origin, using the
// origin as an EXACT targetOrigin (never "*" — a hello to "*" would leak our
// presence/version to any window). Lets a host that loaded kash before its
// own listener was ready know to send its connect.
private fun jsKashFrameAnnounce(allowedCsv: String): Unit =
    js(
        """{
            try {
                var allowed = (allowedCsv || '').split(',')
                    .map(function (s) { return s.trim(); })
                    .filter(Boolean);
                var targets = [];
                try { if (window.parent && window.parent !== window) targets.push(window.parent); } catch (_) {}
                try { if (window.opener && window.opener !== window) targets.push(window.opener); } catch (_) {}
                for (var i = 0; i < targets.length; i++) {
                    for (var j = 0; j < allowed.length; j++) {
                        try {
                            targets[i].postMessage({ type: 'kashframe:hello', version: 1 }, allowed[j]);
                        } catch (_) { /* nothing */ }
                    }
                }
            } catch (_) { /* nothing */ }
        }""",
    )
