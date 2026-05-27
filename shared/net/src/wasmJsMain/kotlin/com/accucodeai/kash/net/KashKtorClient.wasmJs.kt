@file:Suppress("ktlint:standard:filename")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.net

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.sandbox.NetworkAccessDenied
import kotlinx.coroutines.await
import kotlinx.io.Buffer
import kotlin.js.Promise

/**
 * wasmJs actual: browser Fetch API. The policy is screened *before* the
 * `fetch(...)` call dispatches, so refused requests never hit the
 * network.
 *
 * Limitations vs. the JVM/CIO actual:
 *  - CORS still applies. The browser will refuse cross-origin requests
 *    that the target server hasn't opted into via `Access-Control-Allow-*`
 *    headers; that refusal is reported as a generic network error.
 *  - Some request headers are **forbidden** by the Fetch spec and
 *    silently dropped by the browser, no matter what we put in
 *    [HttpRequest.headers]: `User-Agent` (browser's UA is sent),
 *    `Accept-Encoding`, `Accept-Language`, `Cookie`, `Connection`,
 *    `Content-Length`, `Host`, `Origin`, `Referer` (set by the document
 *    URL), and the `Sec-*` / `Proxy-*` families. Callers like the curl
 *    tool still pass them — the JS glue tries to `headers.append(...)`
 *    them and the browser rejects without throwing.
 *  - The browser additionally injects `Accept` (default `&#42;/&#42;`)
 *    unless overridden, `Accept-Encoding: gzip, deflate, br`,
 *    `Accept-Language` from the user's locale, and a UA reflecting the
 *    actual browser. No way to suppress these from JS.
 *  - Redirects: when [HttpRequest.followRedirects] is `true` we let the
 *    browser follow (`redirect: "follow"`), so intermediate hops are NOT
 *    re-screened by the policy. This matches what the JVM actual does in
 *    spirit (the user opted in), but the browser may follow across the
 *    allowlist boundary. If that matters, run with `followRedirects =
 *    false` and walk redirects manually.
 *  - There is no equivalent of `close()` — the underlying connection
 *    pool is browser-managed. [close] is a no-op.
 */
public actual class KashKtorClient actual constructor(
    public actual val policy: com.accucodeai.kash.api.sandbox.NetworkPolicy,
    public actual val corsProxy: String?,
) {
    public actual suspend fun <T> execute(
        request: HttpRequest,
        block: suspend (StreamingHttpResponse) -> T,
    ): T {
        val parsed =
            parseAuthority(request.url)
                ?: throw NetworkAccessDenied("", 0, "", policy)
        val (scheme, host, port) = parsed
        if (!policy.allows(host, port, scheme)) {
            throw NetworkAccessDenied(host, port, scheme, policy)
        }

        // Encode body as base64 so the JS glue can decode it back to a
        // Uint8Array without us having to push a Kotlin ByteArray across
        // the wasm/JS boundary as JsAny.
        val bodyB64 = request.body?.let { encodeBase64(it) } ?: ""
        // Headers travel as a flat alternating-key/value JS-string. The JS
        // glue splits on `\n` (records) and `\t` (key/value) — neither
        // appears in a well-formed HTTP header.
        val headersFlat =
            buildString {
                for ((k, v) in request.headers) {
                    if (length > 0) append('\n')
                    append(k).append('\t').append(v)
                }
            }
        val redirect = if (request.followRedirects) "follow" else "manual"
        // Apply the CORS-proxy prefix (browser-only). The original URL
        // is preserved for the diagnostic / NetworkPolicy gate above —
        // the proxy is just a transport rewrite.
        val effectiveUrl = if (corsProxy != null) "$corsProxy${request.url}" else request.url

        val resultJson: JsString =
            try {
                jsFetchToJson(
                    effectiveUrl,
                    request.method.uppercase(),
                    headersFlat,
                    bodyB64,
                    redirect,
                ).await()
            } catch (t: Throwable) {
                // Browser fetch failures don't distinguish CORS rejection
                // from DNS failure / refused-connection in the JS-side
                // error object — both surface as a `TypeError`. Throw a
                // user-readable message kash's command runner shows on
                // the terminal's stderr; CORS is the most common cause
                // for cross-origin requests so we name it.
                val msg = t.message ?: t::class.simpleName ?: "unknown fetch error"
                throw IllegalStateException(
                    "HTTP request to '${request.url}' failed: $msg. " +
                        (
                            if (corsProxy == null) {
                                "Likely a CORS violation — the target host probably " +
                                    "doesn't set Access-Control-Allow-Origin for cross-" +
                                    "origin browser requests. Configure a CORS proxy via " +
                                    "KashKtorClient(corsProxy=…) (or, for the web app, " +
                                    "standardRegistry(httpCorsProxy=…)) and retry."
                            } else {
                                "(routed through corsProxy='$corsProxy' → '$effectiveUrl' — " +
                                    "check that the proxy is reachable, healthy, and " +
                                    "forwarding the response headers.)"
                            }
                        ),
                    t,
                )
            }

        // The Fetch API delivers the full body up front; we wrap it in a
        // [StreamingHttpResponse] for API parity with the JVM actual so
        // callers don't fork their code paths. The body is already in
        // memory — [BufferedStreamingResponse.copyTo] just hands it to
        // the sink in one chunk.
        val parsed2 = parseResponseFromJson(resultJson.toString())
        return block(BufferedStreamingResponse(parsed2.first, parsed2.second, parsed2.third))
    }

    public actual fun close() {
        // No-op — Fetch has no per-client lifecycle in the browser.
    }
}

private class BufferedStreamingResponse(
    override val status: Int,
    override val headers: List<Pair<String, String>>,
    private val bytes: ByteArray,
) : StreamingHttpResponse {
    private var consumed = false

    override suspend fun copyTo(sink: SuspendSink): Long {
        if (consumed) return 0L
        consumed = true
        if (bytes.isEmpty()) {
            sink.flush()
            return 0L
        }
        val buf = Buffer()
        buf.write(bytes)
        sink.write(buf, bytes.size.toLong())
        sink.flush()
        return bytes.size.toLong()
    }

    override suspend fun readAllBytes(): ByteArray {
        if (consumed) return ByteArray(0)
        consumed = true
        return bytes
    }

    override suspend fun discard() {
        consumed = true
    }
}

/**
 * JS glue. Implemented as a top-level `js("...")` block so the Kotlin
 * compiler emits an external function that the wasmJs runtime can call.
 *
 * Protocol:
 *  - `headersFlat`: records joined by `\n`, each `name\tvalue`.
 *  - `bodyB64`: base64 of the raw body bytes, or `""` for no body.
 *  - Returns a JSON-encoded `{status, headers: [[k,v],…], bodyB64}`
 *    string. We pass bytes back as base64 for the same reason we pass
 *    them in — no need to thread Uint8Array across the boundary.
 */
private fun jsFetchToJson(
    url: String,
    method: String,
    headersFlat: String,
    bodyB64: String,
    redirect: String,
): Promise<JsString> =
    js(
        """
        (async () => {
          const headers = new Headers();
          if (headersFlat.length > 0) {
            for (const rec of headersFlat.split("\n")) {
              const tab = rec.indexOf("\t");
              if (tab > 0) headers.append(rec.substring(0, tab), rec.substring(tab + 1));
            }
          }
          let body = undefined;
          if (bodyB64.length > 0) {
            const bin = atob(bodyB64);
            const arr = new Uint8Array(bin.length);
            for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
            body = arr;
          }
          const init = { method: method, headers: headers, redirect: redirect };
          if (body !== undefined && method !== "GET" && method !== "HEAD") init.body = body;
          const resp = await fetch(url, init);
          const buf = new Uint8Array(await resp.arrayBuffer());
          let s = "";
          for (let i = 0; i < buf.length; i++) s += String.fromCharCode(buf[i]);
          const respB64 = btoa(s);
          const hdrs = [];
          resp.headers.forEach((v, k) => hdrs.push([k, v]));
          return JSON.stringify({ status: resp.status, headers: hdrs, bodyB64: respB64 });
        })()
        """,
    )

/**
 * Parse the JS-glue payload into `(status, headers, body)`. We control
 * both sides so we don't need a general JSON parser — just extract
 * `status` (int), `headers` (array of `[k, v]` strings), and `bodyB64`
 * (base64 string). Returns a raw triple rather than an `HttpResponse`
 * data class — the wasmJs actual wraps the triple in a
 * [BufferedStreamingResponse] for the caller.
 */
private fun parseResponseFromJson(jsonStr: String): Triple<Int, List<Pair<String, String>>, ByteArray> {
    val status = extractInt(jsonStr, "\"status\":") ?: 0
    val bodyB64 = extractQuoted(jsonStr, "\"bodyB64\":\"") ?: ""
    val headers = extractHeaders(jsonStr)
    return Triple(status, headers, decodeBase64(bodyB64))
}

private fun extractInt(
    s: String,
    key: String,
): Int? {
    val i = s.indexOf(key)
    if (i < 0) return null
    var j = i + key.length
    while (j < s.length && s[j] == ' ') j++
    val start = j
    while (j < s.length && s[j].isDigit()) j++
    return s.substring(start, j).toIntOrNull()
}

private fun extractQuoted(
    s: String,
    key: String,
): String? {
    val i = s.indexOf(key)
    if (i < 0) return null
    val start = i + key.length
    // Find closing quote. Our JS side only base64s the body, and headers
    // come through JSON.stringify which escapes — for the headers we
    // parse via a different path. For bodyB64 the alphabet is safe.
    val end = s.indexOf('"', start)
    if (end < 0) return null
    return s.substring(start, end)
}

private fun extractHeaders(s: String): List<Pair<String, String>> {
    val key = "\"headers\":["
    val start = s.indexOf(key)
    if (start < 0) return emptyList()
    // Find matching closing ']'. Headers can contain escaped quotes; we
    // walk the structure with a tiny state machine instead of regex.
    var i = start + key.length
    val out = mutableListOf<Pair<String, String>>()
    while (i < s.length) {
        // skip whitespace and commas
        while (i < s.length && (s[i] == ' ' || s[i] == ',')) i++
        if (i >= s.length || s[i] == ']') break
        if (s[i] != '[') {
            i++
            continue
        }
        i++
        val k = readJsonString(s, i) ?: break
        i = k.second
        while (i < s.length && (s[i] == ' ' || s[i] == ',')) i++
        val v = readJsonString(s, i) ?: break
        i = v.second
        while (i < s.length && s[i] != ']') i++
        if (i < s.length) i++ // consume ']'
        out += k.first to v.first
    }
    return out
}

private fun readJsonString(
    s: String,
    from: Int,
): Pair<String, Int>? {
    if (from >= s.length || s[from] != '"') return null
    var i = from + 1
    val sb = StringBuilder()
    while (i < s.length) {
        val c = s[i]
        if (c == '"') return sb.toString() to (i + 1)
        if (c == '\\' && i + 1 < s.length) {
            when (val n = s[i + 1]) {
                '"', '\\', '/' -> {
                    sb.append(n)
                }

                'n' -> {
                    sb.append('\n')
                }

                'r' -> {
                    sb.append('\r')
                }

                't' -> {
                    sb.append('\t')
                }

                'b' -> {
                    sb.append('\b')
                }

                'f' -> {
                    sb.append('\u000C')
                }

                'u' -> {
                    if (i + 5 >= s.length) return null
                    val hex = s.substring(i + 2, i + 6)
                    sb.append(hex.toInt(16).toChar())
                    i += 4
                }

                else -> {
                    sb.append(n)
                }
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return null
}

private val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private fun encodeBase64(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 3 <= bytes.size) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = bytes[i + 1].toInt() and 0xff
        val b2 = bytes[i + 2].toInt() and 0xff
        sb.append(B64_ALPHABET[b0 shr 2])
        sb.append(B64_ALPHABET[((b0 and 0x3) shl 4) or (b1 shr 4)])
        sb.append(B64_ALPHABET[((b1 and 0xf) shl 2) or (b2 shr 6)])
        sb.append(B64_ALPHABET[b2 and 0x3f])
        i += 3
    }
    val rem = bytes.size - i
    if (rem == 1) {
        val b0 = bytes[i].toInt() and 0xff
        sb.append(B64_ALPHABET[b0 shr 2])
        sb.append(B64_ALPHABET[(b0 and 0x3) shl 4])
        sb.append("==")
    } else if (rem == 2) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = bytes[i + 1].toInt() and 0xff
        sb.append(B64_ALPHABET[b0 shr 2])
        sb.append(B64_ALPHABET[((b0 and 0x3) shl 4) or (b1 shr 4)])
        sb.append(B64_ALPHABET[(b1 and 0xf) shl 2])
        sb.append('=')
    }
    return sb.toString()
}

private fun decodeBase64(s: String): ByteArray {
    if (s.isEmpty()) return ByteArray(0)
    val lookup = IntArray(128) { -1 }
    for (i in B64_ALPHABET.indices) lookup[B64_ALPHABET[i].code] = i
    val padCount = s.count { it == '=' }
    val outLen = (s.length / 4) * 3 - padCount
    val out = ByteArray(outLen)
    var oi = 0
    var i = 0
    while (i < s.length) {
        val c0 = lookup[s[i].code]
        val c1 = lookup[s[i + 1].code]
        val c2 = if (s[i + 2] == '=') -1 else lookup[s[i + 2].code]
        val c3 = if (s[i + 3] == '=') -1 else lookup[s[i + 3].code]
        out[oi++] = ((c0 shl 2) or (c1 shr 4)).toByte()
        if (c2 != -1) out[oi++] = (((c1 and 0xf) shl 4) or (c2 shr 2)).toByte()
        if (c3 != -1) out[oi++] = (((c2 and 0x3) shl 6) or c3).toByte()
        i += 4
    }
    return out
}
