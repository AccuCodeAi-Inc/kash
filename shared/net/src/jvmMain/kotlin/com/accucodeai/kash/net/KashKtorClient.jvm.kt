@file:Suppress("ktlint:standard:filename")

package com.accucodeai.kash.net

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.sandbox.NetworkAccessDenied
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import io.ktor.client.statement.HttpResponse as KtorResponse

/**
 * JVM actual: ktor `HttpClient(CIO)`. We disable ktor's built-in redirect
 * handling and re-implement it manually so each hop is re-screened by the
 * [NetworkPolicy] — a Location header pointing somewhere the policy
 * forbids must NOT be silently followed.
 *
 * Body streaming: the terminal response is delivered to the caller's
 * block as a [StreamingHttpResponse] whose [StreamingHttpResponse.copyTo]
 * pulls 8 KiB at a time off ktor's `ByteReadChannel` and pushes straight
 * into the supplied sink. `curl -O big.iso` therefore touches disk while
 * the download is still in flight rather than buffering the whole
 * payload in the JVM heap.
 *
 * Header-shaping decisions:
 *
 *  - **User-Agent**: CIO injects `User-Agent: ktor-client` when missing.
 *    We seed `KashKtorClient/1.0` via [DefaultRequest] so that fallback
 *    never fires; per-request UAs (curl's `-A`) override via the normal
 *    header builder (see the wipe-then-append loop below).
 *  - **Accept / Accept-Charset / Accept-Encoding**: NOT auto-added. No
 *    `HttpPlainText` / `ContentEncoding` plugin installed.
 *  - **Content-Type**: NOT auto-added for `ByteArray` bodies.
 *  - **Content-Length / Host / Transfer-Encoding**: managed by the wire
 *    writer; not user-controllable. Same constraint as every HTTP lib.
 */
public actual class KashKtorClient actual constructor(
    public actual val policy: NetworkPolicy,
    public actual val corsProxy: String?,
) {
    // corsProxy is browser-only; CIO on the JVM has no same-origin
    // restriction so we accept-and-ignore the knob. Touch the field
    // so the compiler doesn't flag it as unused.
    @Suppress("UNUSED")
    private val ignoredCorsProxy = corsProxy
    private val client: HttpClient =
        HttpClient(CIO) {
            // Do NOT install `HttpRedirect`. We drive redirects manually
            // in [execute] so every hop is re-screened by [policy]; with
            // the plugin installed, ktor 3.x follows 3xx responses even
            // when [followRedirects] = false.
            install(DefaultRequest) {
                headers.append(HttpHeaders.UserAgent, "KashKtorClient/1.0")
            }
            followRedirects = false
            expectSuccess = false
        }

    public actual suspend fun <T> execute(
        request: HttpRequest,
        block: suspend (StreamingHttpResponse) -> T,
    ): T {
        var current = request
        var hops = 0
        val maxHops = if (request.followRedirects) 10 else 0

        // Identity sentinel — distinguishes "block not yet called" from
        // "block returned null". Using a private object reference rather
        // than a magic value lets the caller's [T] be anything, including
        // `Unit` or `Any?`.
        var captured: Any? = REDIRECT_PENDING

        while (captured === REDIRECT_PENDING) {
            screen(current.url)
            val req = current
            client
                .prepareRequest(req.url) {
                    method = HttpMethod.parse(req.method.uppercase())
                    headers {
                        // On the first occurrence of a name, wipe whatever
                        // [DefaultRequest] seeded for it — that's the user
                        // overriding the client-level default (e.g. curl's
                        // `-A` replacing the `KashKtorClient/1.0` UA).
                        // Subsequent values with the same name then append,
                        // matching `curl -H "Cookie: a" -H "Cookie: b"`
                        // sending both.
                        val firstSeen = mutableSetOf<String>()
                        for ((k, v) in req.headers) {
                            val lk = k.lowercase()
                            if (firstSeen.add(lk)) remove(k)
                            append(k, v)
                        }
                    }
                    req.body?.let { setBody(it) }
                }.execute { resp: KtorResponse ->
                    val status = resp.status.value
                    if (status in 300..399 && hops < maxHops) {
                        val location = resp.headers["Location"]
                        if (!location.isNullOrEmpty()) {
                            hops++
                            val nextUrl = resolveRedirect(req.url, location)
                            val nextHeaders =
                                if (sameOrigin(req.url, nextUrl)) {
                                    req.headers
                                } else {
                                    // Cross-origin redirect: drop credentials
                                    // before re-issuing. Mirrors real curl's
                                    // post-CVE-2022-27776 behavior.
                                    stripCredentialHeaders(req.headers)
                                }
                            current = req.copy(url = nextUrl, headers = nextHeaders)
                            // Returning here lets ktor close + discard the
                            // body; we loop and re-dispatch.
                            return@execute
                        }
                    }
                    val hdrs = mutableListOf<Pair<String, String>>()
                    resp.headers.forEach { name, values ->
                        for (v in values) hdrs += name to v
                    }
                    captured = block(KtorStreamingResponse(resp, status, hdrs))
                }
        }
        @Suppress("UNCHECKED_CAST")
        return captured as T
    }

    public actual fun close() {
        client.close()
    }

    private fun screen(url: String) {
        val parsed = parseAuthority(url) ?: throw NetworkAccessDenied("", 0, "", policy)
        val (scheme, host, port) = parsed
        if (!policy.allows(host, port, scheme)) {
            throw NetworkAccessDenied(host, port, scheme, policy)
        }
    }

    /**
     * Resolve a (possibly-relative) Location header against the request URL.
     * Handles the common cases curl needs:
     *  - absolute URL (`https://other.example/x`)
     *  - protocol-relative (`//other.example/x`)
     *  - root-relative (`/x`)
     *  - relative (`x` → resolved against the request path's directory)
     */
    private fun resolveRedirect(
        base: String,
        location: String,
    ): String {
        if (location.contains("://")) return location
        val schemeEnd = base.indexOf("://")
        val scheme = base.substring(0, schemeEnd)
        val rest = base.substring(schemeEnd + 3)
        val pathStart = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, pathStart)
        val basePath = if (pathStart < rest.length) rest.substring(pathStart) else "/"
        return when {
            location.startsWith("//") -> {
                "$scheme:$location"
            }

            location.startsWith("/") -> {
                "$scheme://$authority$location"
            }

            else -> {
                val dir = basePath.substringBeforeLast('/', "") + "/"
                "$scheme://$authority$dir$location"
            }
        }
    }
}

/**
 * Sentinel for [KashKtorClient.execute]'s redirect loop. Stored in
 * `captured` until the inner block fires with a terminal response. A
 * dedicated object identity (rather than `null`) lets the caller's `T`
 * legitimately be `Unit` / `Any?` / a nullable type.
 */
private val REDIRECT_PENDING = Any()

private class KtorStreamingResponse(
    private val resp: KtorResponse,
    override val status: Int,
    override val headers: List<Pair<String, String>>,
) : StreamingHttpResponse {
    override suspend fun copyTo(sink: SuspendSink): Long {
        val channel: ByteReadChannel = resp.bodyAsChannel()
        val tmp = ByteArray(8 * 1024)
        val buf = Buffer()
        var total = 0L
        while (true) {
            val n = channel.readAvailable(tmp, 0, tmp.size)
            if (n < 0) break
            if (n == 0) continue
            buf.write(tmp, 0, n)
            sink.write(buf, n.toLong())
            // Buffer is drained by `sink.write`; loop reuses it.
            total += n
        }
        sink.flush()
        return total
    }

    override suspend fun readAllBytes(): ByteArray = resp.readRawBytes()

    override suspend fun discard() {
        resp.bodyAsChannel().discard()
    }
}

/**
 * True iff [a] and [b] address the same `(scheme, host, port)`. Used by
 * the redirect loop to decide whether to carry credentials across to the
 * next hop. A URL that fails to parse counts as "not same origin" — we'd
 * rather drop credentials in the face of an ambiguous Location than
 * leak them.
 */
internal fun sameOrigin(
    a: String,
    b: String,
): Boolean {
    val pa = parseAuthority(a) ?: return false
    val pb = parseAuthority(b) ?: return false
    return pa.first == pb.first &&
        pa.second.equals(pb.second, ignoreCase = true) &&
        pa.third == pb.third
}

/**
 * Strip every header that grants access on behalf of the user. Matches
 * the set real curl removes on cross-host 3xx (see CVE-2022-27776 and
 * the `--location-trusted` man page entry).
 */
internal fun stripCredentialHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> {
    val drop = setOf("authorization", "cookie", "proxy-authorization")
    return headers.filterNot { it.first.lowercase() in drop }
}
