package com.accucodeai.kash.app

import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.defaultCommandSpecs
import com.accucodeai.kash.net.KashKtorClient
import com.accucodeai.kash.tools.ai.all.aiCommands
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.http.HttpGitHostAdapter
import com.accucodeai.kash.tools.python3.pyodide.PyodideEngine
import com.accucodeai.kash.tools.python3.python3Commands

/**
 * wasmJs-app (browser) command catalog: the standard kash catalog with
 *  - `python3` backed by Pyodide
 *  - `git` backed by [HttpGitHostAdapter] (smart-HTTP fetch via
 *    `:shared:net` + `pako` for zlib — see :tools:kash:git build.gradle.kts
 *    for why git keeps pako while gzip/tar/zip moved to fflate). The
 *    browser session starts with
 *    no commits; the LLM populates it via `git fetch <url>` /
 *    `git clone <url>`.
 *
 * The HTTP git client is constructed with [NetworkPolicy.None] by
 * default — embedders that want to restrict where `git fetch` can talk
 * can pass a stricter policy to [standardRegistry]. The adapter's own
 * `networkPolicy` stays null so the per-session [com.accucodeai.kash.api.KashMachine]
 * policy (configured by the page hosting the kash session) is the
 * source of truth.
 *
 * Browser CORS reality: github.com / gitlab.com / bitbucket.org do
 * NOT set `Access-Control-Allow-Origin` on smart-HTTP endpoints, so a
 * direct `git clone https://github.com/<u>/<r>.git` from the browser
 * will fail with a CORS error. The fix lives in [KashKtorClient]'s
 * wasmJs actual — pass [httpCorsProxy] (a URL prefix that gets
 * prepended to every outbound request) to relay through a server that
 * does set the right headers. JVM kash has no such limitation and
 * uses `JGitHostAdapter` anyway.
 */
public fun standardRegistry(
    networkPolicy: NetworkPolicy = NetworkPolicy.None,
    httpCorsProxy: String? = null,
): CommandRegistry {
    val httpClient = KashKtorClient(networkPolicy, corsProxy = httpCorsProxy)
    val gitAdapter = HttpGitHostAdapter(httpClient = httpClient)
    val specs =
        defaultCommandSpecs().map { spec ->
            if (spec.name == "git") {
                object : com.accucodeai.kash.api.CommandSpec by spec {
                    override val command = GitCommand(gitAdapter as GitHostAdapter)
                }
            } else {
                spec
            }
        } + python3Commands(PyodideEngine()) + aiCommands
    return CommandRegistry(specs)
}
