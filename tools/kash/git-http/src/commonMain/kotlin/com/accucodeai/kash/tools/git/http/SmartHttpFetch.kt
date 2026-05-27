package com.accucodeai.kash.tools.git.http

import com.accucodeai.kash.net.HttpRequest
import com.accucodeai.kash.net.KashKtorClient

/**
 * One advertised ref from a smart-HTTP info/refs response.
 */
public data class AdvertisedRef(
    val sha: String,
    val name: String,
)

/**
 * Result of a successful smart-HTTP fetch: every framed object the
 * server delivered (the same `<type> <len>\0<payload>` shape kash's
 * `GitObjectResolver` returns), keyed by sha; plus the advertised
 * tips for the refs we asked for.
 *
 * For shallow fetches, [shallowCommits] holds the SHAs the server
 * declared as shallow boundaries (commits whose parents are NOT in
 * the pack and should be treated as if they have no parents).
 * [unshallowCommits] holds SHAs that were previously shallow but are
 * now full because the requested depth reached them — kash callers
 * use this to update an on-disk `.git/shallow` file if they want git
 * CLI parity.
 */
public data class SmartHttpFetchResult(
    val objects: Map<String, ByteArray>,
    val refToSha: Map<String, String>,
    val shallowCommits: Set<String> = emptySet(),
    val unshallowCommits: Set<String> = emptySet(),
)

/**
 * Re-export so existing call sites that imported [FetchScope] from
 * this package keep compiling. The canonical definition lives in
 * `:tools:kash:git`'s [com.accucodeai.kash.tools.git.FetchScope] so
 * `JGitHostAdapter` can share it without depending on this module.
 */
public typealias FetchScope = com.accucodeai.kash.tools.git.FetchScope

/**
 * Minimal smart-HTTP-v0 fetch client. Implements the two RPCs:
 *
 *   1. `GET <url>/info/refs?service=git-upload-pack` — advertised refs
 *      come back as pkt-lines. The first frame is the service banner
 *      (`# service=git-upload-pack\n`), then a flush, then one frame
 *      per ref. The first ref-frame carries the server's capability
 *      list after a NUL; subsequent frames are bare `<sha> <name>\n`.
 *
 *   2. `POST <url>/git-upload-pack` — body is `want <sha>\n` pkt-lines
 *      (with `multi_ack_detailed side-band-64k ofs-delta` capabilities
 *      requested on the first want), a flush, then `0009done\n`. The
 *      response is `NAK\n` (since we sent no haves) followed by the
 *      raw packfile bytes — no sideband mux, to keep parsing simple.
 *
 * The packfile gets parsed by [PackfileReader] into framed objects;
 * caller is responsible for inserting them into the local object DB.
 *
 * Supports `--depth=N` (shallow) and `--filter=<spec>` (partial clone)
 * via the optional [FetchScope] parameter. Out of scope: sideband-64k
 * progress, multi_ack negotiation (we send no haves so every fetch is
 * a full reachability fetch from the requested tips), `deepen-since` /
 * `deepen-not` (only `deepen <N>` is wired), and HTTP/auth nuances
 * beyond what [KashKtorClient] surfaces.
 */
public class SmartHttpFetch(
    private val httpClient: KashKtorClient,
) {
    public suspend fun fetch(
        baseUrl: String,
        wants: List<String>,
        haveBase: (sha: String) -> ByteArray? = { null },
        scope: FetchScope = FetchScope.FULL,
    ): SmartHttpFetchResult {
        require(wants.isNotEmpty()) { "smart-http fetch: at least one ref to fetch" }
        // Many smart-HTTP servers (notably github.com) serve HTML at
        // the bare `<user>/<repo>/` URL and only the smart-HTTP
        // advertisement at `<user>/<repo>.git/info/refs`. Real git
        // tries both — we'll try with `.git` first if the user
        // didn't include it. The probe is idempotent so callers can
        // pass either form.
        val canonical = canonicalizeBaseUrl(baseUrl)
        val advertised = lsRefs(canonical)
        val refToSha = HashMap<String, String>(wants.size)
        val wantShas = LinkedHashSet<String>()
        for (ref in wants) {
            val ad = advertised.firstOrNull { it.name == ref } ?: continue
            refToSha[ref] = ad.sha
            wantShas += ad.sha
        }
        if (wantShas.isEmpty()) {
            return SmartHttpFetchResult(emptyMap(), emptyMap())
        }
        val (shallowSet, unshallowSet, pack) = uploadPack(canonical, wantShas.toList(), scope)
        val entries = PackfileReader(pack, haveBase).readAll()
        return SmartHttpFetchResult(
            objects = entries.toMap(),
            refToSha = refToSha,
            shallowCommits = shallowSet,
            unshallowCommits = unshallowSet,
        )
    }

    private suspend fun lsRefs(baseUrl: String): List<AdvertisedRef> {
        val url = joinUrl(baseUrl, "info/refs") + "?service=git-upload-pack"
        val body =
            httpClient.execute(
                HttpRequest(
                    url = url,
                    method = "GET",
                    headers =
                        listOf(
                            "Accept" to "application/x-git-upload-pack-advertisement",
                            "Git-Protocol" to "version=1",
                        ),
                    followRedirects = true,
                ),
            ) { resp ->
                require(resp.status in 200..299) {
                    "smart-http info/refs: HTTP ${resp.status} ${resp.statusText} ($url)"
                }
                resp.readAllBytes()
            }
        // Cheap guard: if the response is HTML (server served the
        // project landing page at the bare URL), give a much clearer
        // diagnostic than the "missing service banner" pkt-line error.
        if (looksLikeHtml(body)) {
            error(
                "smart-http info/refs: server returned HTML, not pkt-line " +
                    "advertisement. URL='$url'. If this is a GitHub/GitLab-style " +
                    "host, append '.git' to the clone URL. In a browser, also " +
                    "check that the host sets Access-Control-Allow-Origin — most " +
                    "git hosts don't, so direct cross-origin fetch is blocked.",
            )
        }
        return parseAdvertisedRefs(body)
    }

    private suspend fun uploadPack(
        baseUrl: String,
        wantShas: List<String>,
        scope: FetchScope,
    ): Triple<Set<String>, Set<String>, ByteArray> {
        val reqBody = buildUploadPackBody(wantShas, scope)
        val url = joinUrl(baseUrl, "git-upload-pack")
        val responseBytes =
            httpClient.execute(
                HttpRequest(
                    url = url,
                    method = "POST",
                    headers =
                        listOf(
                            "Accept" to "application/x-git-upload-pack-result",
                            "Content-Type" to "application/x-git-upload-pack-request",
                            "Git-Protocol" to "version=1",
                        ),
                    body = reqBody,
                    followRedirects = true,
                ),
            ) { resp ->
                require(resp.status in 200..299) {
                    "smart-http upload-pack: HTTP ${resp.status} ${resp.statusText}"
                }
                resp.readAllBytes()
            }
        return extractPackfileFromBody(
            responseBytes,
            shallowExpected = scope.depth != null,
            sideband = true,
        )
    }
}

/**
 * Parse the body of a smart-HTTP info/refs response into the list of
 * advertised refs. The body looks like:
 *
 *   001e# service=git-upload-pack\n
 *   0000
 *   00bb<sha> <ref>\0<capabilities>\n
 *   003f<sha> <ref>\n
 *   ...
 *   0000
 *
 * The capabilities ride on the first ref-frame after a NUL.
 */
internal fun parseAdvertisedRefs(body: ByteArray): List<AdvertisedRef> {
    val reader = PktLineReader(body)
    val banner = reader.next() ?: error("smart-http info/refs: empty body")
    val bannerText = banner.payload?.decodeToString()
    // Servers can return an `ERR <msg>` pkt-line at the top instead of
    // the service banner — typical for "Repository not found",
    // auth-required, or filter-not-allowed. Surface the verbatim
    // server message rather than reporting "missing service banner".
    if (bannerText != null && bannerText.startsWith("ERR ")) {
        error("smart-http info/refs: server reported error: ${bannerText.removePrefix("ERR ").trimEnd('\n')}")
    }
    require(bannerText != null && bannerText.startsWith("# service=git-upload-pack")) {
        "smart-http info/refs: missing service banner (got '$bannerText')"
    }
    // After the banner there's a flush, then the ref advertisement.
    val sep = reader.next()
    require(sep != null && sep.isFlush) { "smart-http info/refs: missing flush after banner" }
    val out = mutableListOf<AdvertisedRef>()
    while (true) {
        val frame = reader.next() ?: break
        if (frame.isFlush) break
        val raw = frame.payload ?: continue
        // First ref carries capabilities after a NUL — strip them.
        val nul = raw.indexOf(0)
        val line = if (nul >= 0) raw.decodeToString(0, nul) else raw.decodeToString().trimEnd('\n')
        val parts = line.trimEnd('\n').split(' ', limit = 2)
        if (parts.size != 2) continue
        // Git 2.41+ empty-repo sentinel: `0000…0 capabilities^{}`.
        // Servers emit this to carry their capability list when no real
        // refs exist yet. It is NOT an advertised ref — surfacing it
        // would make `git clone` try to fetch sha 000…0 (no such object).
        if (parts[0] == ZERO_OID && parts[1] == "capabilities^{}") continue
        out += AdvertisedRef(sha = parts[0], name = parts[1])
    }
    return out
}

private const val ZERO_OID = "0000000000000000000000000000000000000000"

/**
 * Build the `git-upload-pack` request body. Wire format
 * (`Documentation/technical/pack-protocol.txt`, "upload-request"):
 *
 * ```
 * <want-list>           ;; one or more `want <sha>[ caps]\n` pkt-lines
 * <deepen-line?>        ;; optional `deepen <N>\n`
 * <filter-line?>        ;; optional `filter <spec>\n`
 * 0000                  ;; flush
 * 0009done\n
 * ```
 *
 * Capabilities echoed on the first want line:
 *  - `ofs-delta`     — so the server can use OFS_DELTA instead of
 *    being forced to ship every base by SHA. [PackfileReader] handles
 *    both.
 *  - `side-band-64k` — packfile bytes are delivered as pkt-line frames
 *    where the first payload byte is the channel: `0x01` = pack data
 *    (accumulate), `0x02` = progress (discard), `0x03` = fatal error
 *    (throw). Some servers (notably gitlab + gitolite-behind-nginx
 *    configs) sideband unconditionally, so always negotiating it is
 *    safer than the "no demux" path.
 *  - `no-progress`   — we don't surface server progress, so ask the
 *    server not to bother sending channel-2 frames.
 *  - `filter`        — when [FetchScope.filter] is set. Required for
 *    the `filter <spec>` line below to be honored.
 *  - `agent=…`       — informational.
 *
 * `shallow` is intentionally NOT echoed: per the protocol-capabilities
 * spec it's a server-advertised cap only — the client signals shallow
 * by sending a `deepen <N>` pkt-line, which is what we do below.
 */
internal fun buildUploadPackBody(
    wantShas: List<String>,
    scope: FetchScope = FetchScope.FULL,
): ByteArray {
    val capsList = mutableListOf("ofs-delta", "side-band-64k", "no-progress")
    if (scope.filter != null) capsList += "filter"
    capsList += "agent=kash/0.1"
    val caps = capsList.joinToString(" ")
    val out = ArrayList<ByteArray>(wantShas.size + 4)
    wantShas.forEachIndexed { i, sha ->
        val line = if (i == 0) "want $sha $caps\n" else "want $sha\n"
        out += PktLine.encodeText(line)
    }
    // Local copies — `scope.depth` / `scope.filter` are public API
    // properties from `:api` so the compiler refuses to smart-cast
    // them. Capture once and use the local.
    val depth = scope.depth
    val filter = scope.filter
    if (depth != null) {
        require(depth > 0) { "smart-http: deepen depth must be > 0 (got $depth)" }
        out += PktLine.encodeText("deepen $depth\n")
    }
    if (filter != null) {
        require(filter.isNotBlank()) { "smart-http: filter spec must be non-blank" }
        out += PktLine.encodeText("filter $filter\n")
    }
    out += PktLine.flush
    out += PktLine.encodeText("done\n")
    var total = 0
    for (b in out) total += b.size
    val joined = ByteArray(total)
    var pos = 0
    for (b in out) {
        b.copyInto(joined, pos)
        pos += b.size
    }
    return joined
}

/**
 * Parse the `git-upload-pack` response into its three pieces:
 *  - the **shallow-update** section (only when [shallowExpected] is
 *    true): zero-or-more `shallow <sha>\n` and `unshallow <sha>\n`
 *    pkt-lines, terminated by a flush.
 *  - the `NAK\n` (or `ACK …`) pkt-line — the negotiation verdict.
 *  - the packfile bytes. When [sideband] is true (default — we ask
 *    for `side-band-64k`), pack bytes arrive as pkt-line frames
 *    whose first payload byte is the channel:
 *      * `0x01` = pack data — accumulate
 *      * `0x02` = progress — discard (we negotiate `no-progress`)
 *      * `0x03` = fatal — throw verbatim
 *    The stream ends at a flush-pkt. When [sideband] is false the
 *    remainder of the body is the raw PACK bytes (legacy/test mode).
 *
 * Returned as `(shallow set, unshallow set, packBytes)`.
 */
internal fun extractPackfileFromBody(
    body: ByteArray,
    shallowExpected: Boolean = false,
    sideband: Boolean = false,
): Triple<Set<String>, Set<String>, ByteArray> {
    val reader = PktLineReader(body)
    val shallow = mutableSetOf<String>()
    val unshallow = mutableSetOf<String>()
    if (shallowExpected) {
        while (true) {
            val frame = reader.next() ?: error("smart-http upload-pack: truncated shallow-update")
            if (frame.isFlush) break
            val text = frame.payload?.decodeToString()?.trimEnd('\n') ?: continue
            when {
                text.startsWith("ERR ") -> {
                    error("smart-http upload-pack: server reported error: ${text.removePrefix("ERR ")}")
                }

                text.startsWith("shallow ") -> {
                    shallow += text.removePrefix("shallow ")
                }

                text.startsWith("unshallow ") -> {
                    unshallow += text.removePrefix("unshallow ")
                }

                text == "NAK" || text.startsWith("ACK ") -> {
                    // Some servers emit NAK / ACK without a trailing
                    // flush for empty shallow-update sections — fall
                    // through and read the pack stream from here.
                    return Triple(shallow, unshallow, readPackBytes(body, reader, sideband))
                }

                else -> {
                    error("smart-http upload-pack: unexpected shallow-update line '$text'")
                }
            }
        }
    }
    val verdict = reader.next() ?: error("smart-http upload-pack: empty body")
    val verdictText = verdict.payload?.decodeToString()?.trimEnd('\n')
    // `ERR <msg>` instead of NAK / ACK: server is telling us why it
    // refused to ship a pack (auth, filter not allowed, "not our ref",
    // …). Surface verbatim. `ACK <oid>` is also a valid verdict — some
    // servers emit one even when we sent no haves.
    when {
        verdictText == null -> {
            error("smart-http upload-pack: empty verdict frame")
        }

        verdictText.startsWith("ERR ") -> {
            error("smart-http upload-pack: server reported error: ${verdictText.removePrefix("ERR ")}")
        }

        verdictText == "NAK" || verdictText.startsWith("ACK ") -> {}

        // ok
        else -> {
            error("smart-http upload-pack: expected NAK or ACK, got '$verdictText'")
        }
    }
    return Triple(shallow, unshallow, readPackBytes(body, reader, sideband))
}

/**
 * Pull the packfile bytes off the tail of an upload-pack response.
 *
 *  - **raw** ([sideband] = false): everything from the cursor onward
 *    is the literal PACK stream. One slice, no demux.
 *  - **side-band-64k** ([sideband] = true): the tail is a sequence of
 *    pkt-line frames where the first payload byte identifies the
 *    channel: `0x01` = pack data (accumulate), `0x02` = progress
 *    (discard — we negotiate `no-progress`), `0x03` = fatal error
 *    (throw verbatim). The sideband stream terminates with a
 *    flush-pkt; a channel-3 frame also ends it.
 */
private fun readPackBytes(
    body: ByteArray,
    reader: PktLineReader,
    sideband: Boolean,
): ByteArray {
    if (!sideband) {
        return body.copyOfRange(reader.offset, body.size)
    }
    // Worst-case = remaining bytes; every sideband frame adds at most
    // 5 bytes of overhead so the pack is strictly smaller.
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    while (true) {
        val frame = reader.next() ?: break
        if (frame.isFlush) break
        val payload = frame.payload ?: continue
        if (payload.isEmpty()) continue
        when (val channel = payload[0].toInt() and 0xff) {
            1 -> {
                val slice = payload.copyOfRange(1, payload.size)
                chunks += slice
                total += slice.size
            }

            2 -> {
                // Progress on stderr-equivalent. We negotiated
                // `no-progress` so this should be quiet, but tolerate it.
            }

            3 -> {
                val msg = payload.decodeToString(1, payload.size).trimEnd('\n')
                error("smart-http upload-pack: fatal sideband channel-3: $msg")
            }

            else -> {
                error("smart-http upload-pack: unknown sideband channel $channel")
            }
        }
    }
    val out = ByteArray(total)
    var pos = 0
    for (chunk in chunks) {
        chunk.copyInto(out, pos)
        pos += chunk.size
    }
    return out
}

private fun joinUrl(
    base: String,
    suffix: String,
): String {
    val b = base.trimEnd('/')
    val s = suffix.trimStart('/')
    return "$b/$s"
}

private fun ByteArray.indexOf(b: Int): Int {
    val target = b.toByte()
    for (i in indices) if (this[i] == target) return i
    return -1
}

/**
 * Backwards-compat shim — the actual implementation now lives in
 * `:tools:kash:git`'s [com.accucodeai.kash.tools.git.canonicalizeGitUrl]
 * so [JGitHostAdapter][com.accucodeai.kash.tools.git.jgit.JGitHostAdapter]
 * can use the same rules without duplicating logic. Kept here as an
 * `internal` alias so the existing tests continue to import the
 * symbol from this package.
 */
internal fun canonicalizeBaseUrl(url: String): String =
    com.accucodeai.kash.tools.git
        .canonicalizeGitUrl(url)

/**
 * Cheap HTML-vs-pkt-line discriminator for the info/refs response.
 * pkt-line bodies always start with four ASCII hex digits (the length
 * prefix). HTML bodies start with `<` after any leading whitespace.
 */
internal fun looksLikeHtml(body: ByteArray): Boolean {
    var i = 0
    while (i < body.size && isAsciiWhitespace(body[i])) i++
    if (i >= body.size) return false
    return body[i] == '<'.code.toByte()
}

private fun isAsciiWhitespace(b: Byte): Boolean =
    b == 0x20.toByte() ||
        b == 0x09.toByte() ||
        b == 0x0a.toByte() ||
        b == 0x0d.toByte()
