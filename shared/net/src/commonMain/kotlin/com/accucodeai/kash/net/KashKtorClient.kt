package com.accucodeai.kash.net

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.sandbox.NetworkAccessDenied
import com.accucodeai.kash.api.sandbox.NetworkPolicy

/**
 * One outbound HTTP(S) request. Plain Kotlin types so the common surface
 * does not leak any ktor (or browser-Fetch) type. Tools build a request,
 * hand it to [KashKtorClient.execute], and consume the response via the
 * supplied [StreamingHttpResponse] callback.
 *
 * @property url full URL incl. scheme and (optional) port — parsed by the
 *   client to look up host/port/scheme for the [NetworkPolicy] check.
 * @property method uppercase HTTP method (`GET`, `POST`, …). No validation
 *   beyond what the underlying engine enforces.
 * @property headers ordered list of `(name, value)`. Duplicates are
 *   preserved (some servers care about ordering of `Set-Cookie` etc).
 * @property body raw request body, or `null` for no body.
 * @property followRedirects whether the client should follow 3xx
 *   responses transparently. Mirrors `curl -L`.
 */
public data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
    val followRedirects: Boolean = false,
)

/**
 * Streaming view of an HTTP response. The instance is **valid only inside
 * the [KashKtorClient.execute] callback** — the underlying connection /
 * channel is released as soon as the block returns. Callers that need
 * the body after the block exits must buffer it themselves via
 * [readAllBytes].
 *
 * Sized for shell-tool use. The point of streaming is so that
 * `curl -O big.iso` doesn't hold the whole download in JVM heap before
 * touching disk — the JVM actual pulls 8 KiB at a time off ktor's
 * `ByteReadChannel` and pushes straight into the supplied sink. The
 * wasmJs actual buffers internally (the Fetch API delivers the response
 * as a single `ArrayBuffer`) but exposes the same streaming contract so
 * callers don't fork their code paths.
 */
public interface StreamingHttpResponse {
    /** HTTP status code. */
    public val status: Int

    /** Response headers in wire order. Multi-valued names appear once per value. */
    public val headers: List<Pair<String, String>>

    /** Conventional status text for [status] (`"OK"`, `"Not Found"`, …); empty if unknown. */
    public val statusText: String
        get() = defaultStatusText(status)

    /**
     * Stream the entire body into [sink]. Returns the total number of
     * bytes written. May be called at most once per response — after the
     * first call (or after [readAllBytes] / [discard]) the body channel
     * is consumed and further reads return 0.
     */
    public suspend fun copyTo(sink: SuspendSink): Long

    /**
     * Buffer the entire body in memory. Convenience for small responses
     * (`-i`-style header echo, JSON APIs). For large downloads prefer
     * [copyTo] — see this interface's doc for why.
     */
    public suspend fun readAllBytes(): ByteArray

    /**
     * Drop the body without reading it. The JVM actual drains the
     * underlying channel so the connection can be reused; the wasmJs
     * actual is a no-op (the body is already fully in memory).
     */
    public suspend fun discard()
}

internal fun defaultStatusText(code: Int): String =
    when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> ""
    }

/**
 * Outbound HTTP(S) client used by network-aware tools (today: `curl`).
 *
 * Every request is screened by the [NetworkPolicy] supplied at
 * construction *before* any wire I/O happens. If the policy refuses, the
 * client throws [NetworkAccessDenied]; otherwise it dispatches through
 * the per-platform engine:
 *
 *  - **JVM** — ktor's CIO engine. The response body is streamed straight
 *    from the wire into whatever sink the caller passes to
 *    [StreamingHttpResponse.copyTo], so a multi-GB download never sits
 *    in heap.
 *  - **wasmJs** — the browser Fetch API. The browser delivers the body
 *    as a single buffer; we expose the same streaming interface for
 *    API parity.
 *
 * Construction is cheap (the engine spins up lazily on first request).
 * [close] should be called when the client is no longer needed to
 * release any pooled connections.
 */
public expect class KashKtorClient(
    policy: NetworkPolicy,
    corsProxy: String? = null,
) {
    public val policy: NetworkPolicy

    /**
     * Browser-only CORS-proxy prefix. When non-null, the wasmJs actual
     * prepends `corsProxy + originalUrl` to every outbound request —
     * the standard "relay through a server that *does* set
     * Access-Control-Allow-Origin" workaround for cross-origin git /
     * git-host fetches. The JVM actual ignores this knob (CIO has no
     * same-origin policy). `null` (default) = no proxy.
     */
    public val corsProxy: String?

    /**
     * Send [request] and invoke [block] with the response. The redirect
     * loop is internal — by the time [block] fires, the response is the
     * terminal one (the last 2xx/4xx/5xx, after [HttpRequest.followRedirects]
     * has been honored). Throws [NetworkAccessDenied] if [policy] refuses
     * the initial URL or any redirect hop; engine-level errors (DNS,
     * refused connection, timeout) surface as their native exception
     * types — callers should `try`/`catch` broadly.
     */
    public suspend fun <T> execute(
        request: HttpRequest,
        block: suspend (StreamingHttpResponse) -> T,
    ): T

    /** Release pooled connections. Idempotent. */
    public fun close()
}

/**
 * Parse [url] into `(scheme, host, port)` for the [NetworkPolicy] check.
 * Returns `null` if the URL is malformed or scheme-less; callers should
 * treat null as a policy-refusal of an unparseable URL.
 *
 * Public because the per-platform [KashKtorClient] actuals call into it
 * to keep the policy check identical across engines.
 */
public fun parseAuthority(url: String): Triple<String, String, Int>? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = url.substring(0, schemeEnd).lowercase()
    val rest = url.substring(schemeEnd + 3)
    val authorityEnd =
        rest
            .indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    if (authority.isEmpty()) return null
    // strip userinfo
    val hostPart = authority.substringAfter('@', authority)
    // IPv6 literal: `[::1]:8080`
    val (host, portStr) =
        if (hostPart.startsWith("[")) {
            val close = hostPart.indexOf(']')
            if (close < 0) return null
            val h = hostPart.substring(1, close)
            val pStr =
                if (close + 1 < hostPart.length &&
                    hostPart[close + 1] == ':'
                ) {
                    hostPart.substring(close + 2)
                } else {
                    ""
                }
            h to pStr
        } else {
            val colon = hostPart.indexOf(':')
            if (colon < 0) hostPart to "" else hostPart.substring(0, colon) to hostPart.substring(colon + 1)
        }
    if (host.isEmpty()) return null
    val port =
        when {
            portStr.isNotEmpty() -> portStr.toIntOrNull() ?: return null
            scheme == "http" -> 80
            scheme == "https" -> 443
            else -> return null
        }
    return Triple(scheme, host, port)
}
