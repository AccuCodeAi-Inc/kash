package com.accucodeai.kash.tools.curl

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.sandbox.NetworkAccessDenied
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.net.HttpRequest
import com.accucodeai.kash.net.KashKtorClient
import com.accucodeai.kash.net.StreamingHttpResponse

/**
 * In-process `curl`. Supports the slice of GNU curl that real-world
 * shell scripts and copy-pasted README snippets actually use:
 *
 *  - URL positional (`curl https://x/y`)
 *  - `-X METHOD` / `--request METHOD`
 *  - `-H "Name: Value"` / `--header` (repeatable)
 *  - `-d DATA` / `--data` / `--data-raw` (auto-sets POST + form
 *    content-type unless overridden)
 *  - `--data-binary` (same as `-d` but with no transforms)
 *  - `-o FILE` / `--output FILE` (write body to FILE; `-` = stdout)
 *  - `-O` / `--remote-name` (write body to a file named after the URL's
 *    last path segment)
 *  - `-i` / `--include` (prefix body with the status line + headers)
 *  - `-I` / `--head` (HEAD request; print headers; no body)
 *  - `-s` / `--silent` (accepted; we never print progress anyway)
 *  - `-S` / `--show-error` (accepted; we always print errors to stderr)
 *  - `-L` / `--location` (follow redirects)
 *  - `-A UA` / `--user-agent UA`
 *  - `-e REF` / `--referer REF`
 *  - `-u USER:PASS` / `--user` (HTTP basic; password may be omitted)
 *  - `--fail` / `-f` (exit non-zero on 4xx/5xx without writing the body)
 *  - `-w FORMAT` / `--write-out FORMAT` — supports `%{http_code}`,
 *    `%{url_effective}`, `%{size_download}`, `\n`, `\t`
 *
 * Response bodies stream straight from the wire into the chosen output
 * sink (stdout, `-o file`, or `-O file-from-url`). A multi-GB download
 * therefore touches disk while still in flight rather than buffering
 * the whole payload in memory.
 *
 * Out of scope (rejected with a clear message): cookies, multipart
 * (`-F`), TLS client certs, proxy configuration, retry, custom DNS.
 */
public class CurlCommand(
    /**
     * Per-tool policy override. `null` (the default) means "consult the
     * [com.accucodeai.kash.api.KashMachine.networkPolicy] at run time" —
     * the embedder controls the policy at the machine level once, and
     * every curl invocation inherits. A non-null value pins this curl
     * instance to the supplied policy, overriding the machine's.
     */
    private val policyOverride: NetworkPolicy? = null,
) : Command,
    CommandSpec {
    override val name: String = "curl"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.NETWORK)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts =
            try {
                parseArgs(args)
            } catch (e: CurlArgError) {
                ctx.stderr.writeUtf8("curl: ${e.message}\n")
                return CommandResult(exitCode = 2)
            }
        if (opts.urls.isEmpty()) {
            ctx.stderr.writeUtf8("curl: no URL specified\n")
            ctx.stderr.writeUtf8("curl: try 'curl --help' for more information\n")
            return CommandResult(exitCode = 2)
        }

        val policy = policyOverride ?: ctx.process.machine.networkPolicy
        val client = KashKtorClient(policy)
        try {
            var rc = 0
            for (rawUrl in opts.urls) {
                val url = normalizeUrl(rawUrl)
                val one = fetchOne(client, url, opts, ctx)
                if (one != 0) rc = one
                if (rc != 0 && opts.failOnError) break
            }
            return CommandResult(exitCode = rc)
        } finally {
            client.close()
        }
    }

    private suspend fun fetchOne(
        client: KashKtorClient,
        url: String,
        opts: CurlOpts,
        ctx: CommandContext,
    ): Int {
        val method =
            opts.method ?: when {
                opts.headOnly -> "HEAD"
                opts.body != null -> "POST"
                else -> "GET"
            }
        val headers = mutableListOf<Pair<String, String>>()
        headers.addAll(opts.headers)
        if (opts.body != null && headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
            headers += "Content-Type" to "application/x-www-form-urlencoded"
        }
        // curl-style defaults. Real curl sends `User-Agent: curl/<ver>` and
        // `Accept: */*` on every request when the user didn't supply them.
        // Setting them explicitly here also stops ktor (JVM) from injecting
        // its own `User-Agent: ktor-client` default. In the browser
        // (wasmJs/fetch) `User-Agent` is a forbidden header — the browser's
        // UA always wins; the line below is a no-op there.
        val userAgent = opts.userAgent ?: "curl-kash/1.0"
        if (headers.none { it.first.equals("User-Agent", ignoreCase = true) }) {
            headers += "User-Agent" to userAgent
        }
        if (headers.none { it.first.equals("Accept", ignoreCase = true) }) {
            headers += "Accept" to "*/*"
        }
        if (opts.referer != null && headers.none { it.first.equals("Referer", ignoreCase = true) }) {
            headers += "Referer" to opts.referer
        }
        if (opts.basicAuth != null && headers.none { it.first.equals("Authorization", ignoreCase = true) }) {
            headers += "Authorization" to "Basic ${base64Encode(opts.basicAuth.encodeToByteArray())}"
        }

        val request =
            HttpRequest(
                url = url,
                method = method,
                headers = headers,
                body = opts.body,
                followRedirects = opts.followRedirects,
            )

        return try {
            client.execute(request) { resp ->
                handleResponse(ctx, opts, url, resp)
            }
        } catch (e: NetworkAccessDenied) {
            val where = if (e.host.isEmpty()) url else "${e.scheme}://${e.host}:${e.port}"
            ctx.stderr.writeUtf8("curl: (7) network policy refused $where\n")
            7
        } catch (e: Throwable) {
            ctx.stderr.writeUtf8("curl: (6) ${e.message ?: e::class.simpleName ?: "request failed"}\n")
            6
        }
    }

    /**
     * Per-response handler. Runs inside [KashKtorClient.execute]'s block
     * scope; the [resp] reference becomes invalid as soon as we return,
     * so any body reads must happen here.
     */
    private suspend fun handleResponse(
        ctx: CommandContext,
        opts: CurlOpts,
        url: String,
        resp: StreamingHttpResponse,
    ): Int {
        if (opts.failOnError && resp.status >= 400) {
            resp.discard()
            ctx.stderr.writeUtf8("curl: (22) The requested URL returned error: ${resp.status}\n")
            return 22
        }
        if (opts.includeHeaders || opts.headOnly) writeHeaders(ctx, resp)
        val downloaded =
            if (opts.headOnly) {
                resp.discard()
                0L
            } else {
                streamBody(ctx, opts, url, resp)
            }
        if (opts.writeOut != null) {
            ctx.stdout.writeUtf8(renderWriteOut(opts.writeOut, url, resp.status, resp.headers, downloaded))
        }
        return 0
    }

    /**
     * Real curl assumes `http://` when the argument has no scheme — so
     * `curl google.com` works the same as `curl http://google.com`. The
     * stock build defaults to `http`, and the user expects that.
     */
    private fun normalizeUrl(raw: String): String {
        if (raw.contains("://")) return raw
        return "http://$raw"
    }

    private suspend fun writeHeaders(
        ctx: CommandContext,
        resp: StreamingHttpResponse,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(resp.status)
        val statusText = resp.statusText
        if (statusText.isNotEmpty()) sb.append(' ').append(statusText)
        sb.append("\r\n")
        for ((k, v) in resp.headers) {
            sb
                .append(k)
                .append(": ")
                .append(v)
                .append("\r\n")
        }
        sb.append("\r\n")
        ctx.stdout.writeUtf8(sb.toString())
    }

    /**
     * Stream [resp]'s body into the right sink (stdout / `-o` file /
     * `-O` derived file) and return total bytes written. The sink is
     * closed in a `finally` for file targets; stdout is NOT closed.
     */
    private suspend fun streamBody(
        ctx: CommandContext,
        opts: CurlOpts,
        url: String,
        resp: StreamingHttpResponse,
    ): Long {
        val target =
            when {
                opts.useRemoteName -> {
                    val seg = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
                    if (seg.isEmpty()) "curl-output" else seg
                }

                opts.output != null -> {
                    opts.output
                }

                else -> {
                    "-"
                }
            }
        return if (target == "-") {
            // stdout is owned by the caller; copyTo's `flush()` is fine
            // but we must not call `close()` on it.
            resp.copyTo(StdoutSink(ctx.stdout))
        } else {
            val path = Paths.resolve(ctx.cwd, target)
            val sink = ctx.process.fs.sink(path, append = false)
            try {
                resp.copyTo(sink)
            } finally {
                sink.close()
            }
        }
    }
}

/**
 * Wraps stdout so that [SuspendSink.close] (which [StreamingHttpResponse
 * .copyTo] does NOT call but a paranoid sink wrapper might) is a no-op.
 * `ctx.stdout` is owned by the shell — closing it would break subsequent
 * tools in the same pipeline. Wrapping is cheap and means streamBody
 * doesn't need a separate code path for stdout vs file targets.
 */
private class StdoutSink(
    private val delegate: SuspendSink,
) : SuspendSink {
    override suspend fun write(
        source: kotlinx.io.Buffer,
        byteCount: Long,
    ) {
        delegate.write(source, byteCount)
    }

    override suspend fun flush() {
        delegate.flush()
    }

    override fun close() {
        // Intentionally no-op — see class doc.
    }
}
