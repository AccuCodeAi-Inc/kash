package com.accucodeai.kash.api.sandbox

/**
 * Session-level outbound-network capability gate. Consulted by tools that
 * make HTTP(S)/socket requests (today: `curl` via `KashKtorClient`) before
 * any wire I/O happens.
 *
 * Like [SandboxPolicy], this is pure code — NOT serialized in snapshots and
 * must be re-supplied at session restore. Subshells inherit the policy; the
 * intent is one-way tightening, mirroring [SandboxPolicy]'s convention.
 *
 * The "default" is [None] (allow everything). Embedders that need to
 * restrict outbound traffic supply a stricter policy at session
 * construction.
 */
public sealed class NetworkPolicy {
    /**
     * `true` iff a request to [host] (lowercase, no port) on [port] using
     * [scheme] (`"http"` / `"https"`) is permitted. Implementations should
     * be cheap — this is called once per request before connection setup.
     */
    public abstract fun allows(
        host: String,
        port: Int,
        scheme: String,
    ): Boolean

    /** No restriction — every request is permitted. The default. */
    public object None : NetworkPolicy() {
        override fun allows(
            host: String,
            port: Int,
            scheme: String,
        ): Boolean = true

        override fun toString(): String = "NetworkPolicy.None"
    }

    /** Refuses every request. Useful for offline / air-gapped sessions. */
    public object DenyAll : NetworkPolicy() {
        override fun allows(
            host: String,
            port: Int,
            scheme: String,
        ): Boolean = false

        override fun toString(): String = "NetworkPolicy.DenyAll"
    }

    /**
     * Allowlist by host pattern. Each [hostPatterns] entry is matched
     * case-insensitively against the request host:
     *  - exact match: `"api.example.com"` permits only that host.
     *  - leading-dot wildcard: `".example.com"` permits any sub-domain
     *    (including the bare `example.com`).
     *  - `"*"` permits any host (port/scheme still checked).
     *
     * If [allowedPorts] is non-empty, the request port must be in the set.
     * If [allowedSchemes] is non-empty, the scheme must be in the set
     * (compared case-insensitively).
     */
    public data class Allowlist(
        val hostPatterns: Set<String>,
        val allowedPorts: Set<Int> = emptySet(),
        val allowedSchemes: Set<String> = emptySet(),
    ) : NetworkPolicy() {
        private val normalizedHosts = hostPatterns.map { it.lowercase() }
        private val normalizedSchemes = allowedSchemes.map { it.lowercase() }.toSet()

        override fun allows(
            host: String,
            port: Int,
            scheme: String,
        ): Boolean {
            if (allowedPorts.isNotEmpty() && port !in allowedPorts) return false
            if (normalizedSchemes.isNotEmpty() && scheme.lowercase() !in normalizedSchemes) return false
            val h = host.lowercase()
            return normalizedHosts.any { pat ->
                when {
                    pat == "*" -> {
                        true
                    }

                    pat.startsWith(".") -> {
                        val bare = pat.drop(1)
                        h == bare || h.endsWith(pat)
                    }

                    else -> {
                        h == pat
                    }
                }
            }
        }
    }
}

/**
 * Thrown by a [com.accucodeai.kash.api.sandbox.NetworkPolicy]-aware client
 * when the active policy refuses a request. Tools should translate this
 * into a user-facing error (typically a non-zero exit + stderr message).
 */
public class NetworkAccessDenied(
    public val host: String,
    public val port: Int,
    public val scheme: String,
    public val policy: NetworkPolicy,
) : RuntimeException("network access denied: $scheme://$host:$port (policy=$policy)")
