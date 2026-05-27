package com.accucodeai.kash.tools.git.http

import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitObjectResolver
import com.accucodeai.kash.api.git.GitPushApplier
import com.accucodeai.kash.api.git.GitPushOutcome
import com.accucodeai.kash.api.git.GitPushRequest
import com.accucodeai.kash.api.git.GitRefResolver
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.git.SyntheticCommit
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.net.KashKtorClient
import com.accucodeai.kash.net.parseAuthority

/**
 * Common-source HTTP-only git host driver: implements `git fetch
 * <url>` / `git clone <url>` against any smart-HTTP-v0 git server,
 * using [KashKtorClient] for transport. Works on both JVM and
 * wasmJs — there are no JVM-only types in this module, so the
 * browser app can use exactly the same code path as the JVM app.
 *
 * Compare with `JGitHostAdapter` (JVM-only, full read+write, talks any
 * git transport JGit supports): this driver is intentionally smaller —
 * **fetch-only**, **http(s) only**, no push. It exists so the wasmJs
 * build of kash can clone real repos in the browser. JVM apps should
 * keep using `JGitHostAdapter` as the default.
 *
 * Implementation notes:
 *  - The kash session has its own in-memory object DB keyed by sha;
 *    fetched objects land there via the [GitObjectResolver].
 *  - `git fetch origin <branch>` is implemented end-to-end via
 *    [SmartHttpFetch]; we cache the result so subsequent `git log` /
 *    `git diff` / `git checkout` operations don't re-hit the network.
 *  - Push is rejected by default — call sites surface the standard
 *    "no network transport configured" error path. Add it when
 *    smart-HTTP receive-pack is needed.
 *
 * Network gating: [networkPolicy] mirrors the JGit adapter's semantics —
 * null defers to the session [com.accucodeai.kash.api.KashMachine]'s
 * policy. The [KashKtorClient] supplied at construction enforces the
 * effective policy before any wire I/O; passing a policy here only
 * provides the per-adapter override.
 *
 * ### v1 limitations (intentional)
 *
 *  - **Fetch-only.** `git push` over HTTP is not implemented; the
 *    [GitPushApplier] returns [GitPushOutcome.Rejected] for both the
 *    local and URL paths. Adding it requires the smart-HTTP
 *    `git-receive-pack` flow + a packfile *writer* (we currently only
 *    have a reader).
 *  - **HTTP/HTTPS only.** SSH (`ssh://`, `git@host:path`) and the
 *    legacy `git://` daemon protocol are not supported. The
 *    [isHttpUrlAllowed] gate refuses non-http(s) URLs without even
 *    consulting the policy.
 *  - **No sideband-64k.** We deliberately don't negotiate
 *    `side-band-64k`, so the upload-pack response is the simple
 *    `NAK\n` + raw PACK stream. Progress lines and stderr proxying
 *    aren't surfaced; servers that *require* sideband (rare) will
 *    fail at the "expected NAK" parse step.
 *  - **No HTTP auth.** [KashKtorClient] has no credential surface in
 *    v1, so this adapter only works against public repos (or
 *    pre-authenticated hosts that don't need an `Authorization`
 *    header). Add basic/token auth once the network client grows the
 *    knob.
 *  - **Shallow & partial clone supported, `deepen-since`/`deepen-not`
 *    not.** `--depth=N` and `--filter=<spec>` flow through
 *    [defaultScope] (or, in code, [FetchScope]); kash emits the
 *    `shallow`/`filter` capabilities and the `deepen`/`filter`
 *    request lines, and parses the resulting `shallow`/`unshallow`
 *    response section. The cutoff variants `deepen-since` and
 *    `deepen-not` are NOT wired — `deepen <N>` is the only depth knob.
 *  - **`.git/shallow` file not auto-written.** Shallow boundary SHAs
 *    are exposed via [shallowCommits] but kash doesn't persist them
 *    onto the session VFS as a `.git/shallow` file (the real git CLI
 *    does, so `git log` knows to stop walking at the boundary). Hosts
 *    that need that parity can read [shallowCommits] and write the
 *    file themselves.
 *  - **Sha-1 only.** `objectSha` and the packfile reader are wired
 *    for git's SHA-1 object IDs. SHA-256 repositories (still very
 *    rare in the wild) aren't supported.
 */
public class HttpGitHostAdapter(
    private val httpClient: KashKtorClient,
    public override val workTreePath: String = "/work",
    private val defaultBranch: String = "main",
    public override val identity: GitIdentity = GitIdentity("kash", "kash@localhost"),
    public override val networkPolicy: NetworkPolicy? = null,
    /**
     * Session-wide shallow / partial-clone defaults. Every
     * [GitRefResolver.fetchFromUrl] call inherits this scope. Common
     * shapes:
     *  - `FetchScope(depth = 1)` — `git clone --depth=1` equivalent;
     *    great for browser sessions that only need the tip.
     *  - `FetchScope(filter = "blob:none")` — treeless / blobless
     *    partial clone; blobs fetched on demand if at all.
     *  - `FetchScope(depth = 50, filter = "blob:limit=1m")` — recent
     *    history without huge binary blobs.
     */
    public val defaultScope: FetchScope = FetchScope.FULL,
) : GitHostAdapter {
    /**
     * SHAs the most recent shallow fetch marked as boundary commits
     * (their parents are NOT in the local object DB). Tools that emit
     * a `.git/shallow` file for git-CLI parity can read this. Empty
     * for non-shallow fetches.
     */
    public val shallowCommits: Set<String> get() = shallowCommitsBacking.toSet()
    private val shallowCommitsBacking: MutableSet<String> = mutableSetOf()

    // In-memory cache of every framed object the HTTP fetch has
    // delivered so far. Keyed by sha; values are the same
    // `<type> <len>\0<payload>` byte shape the kash ObjectStore consumes.
    private val objectCache: MutableMap<String, ByteArray> = mutableMapOf()

    // Ref cache: ref-name → sha. Populated by `fetchFromUrl` and read by
    // `resolveRef`. Independent from the underlying VFS .git/refs/ that
    // kash maintains — kash mirrors writes here onto the VFS as part of
    // its normal `git fetch` ref-update path.
    private val refCache: MutableMap<String, String> = mutableMapOf()

    private val objectResolver: GitObjectResolver =
        GitObjectResolver { sha -> objectCache[sha] }

    private val refResolverImpl: GitRefResolver = HttpRefResolver()

    private val pushApplierImpl: GitPushApplier =
        object : GitPushApplier {
            override suspend fun apply(req: GitPushRequest): GitPushOutcome =
                GitPushOutcome.Rejected("HttpGitHostAdapter is fetch-only; push not implemented")

            override suspend fun applyToUrl(
                url: String,
                req: GitPushRequest,
                policy: NetworkPolicy,
            ): GitPushOutcome = GitPushOutcome.Rejected("HttpGitHostAdapter is fetch-only; push not implemented")
        }

    /**
     * An OnDemand seed pointing at our in-memory caches. The horizon
     * is empty — the session starts with `git init`-style state and
     * the LLM populates it via `git fetch <url>`.
     */
    override val repoSeed: GitRepoSeed =
        GitRepoSeed.OnDemand(
            horizon =
                GitRepoSeed.Synthetic(
                    workTree = emptyMap(),
                    history = listOf(SyntheticCommit.singleSnapshot()),
                    head = "refs/heads/$defaultBranch",
                ),
            resolver = objectResolver,
            refResolver = refResolverImpl,
            pushApplier = pushApplierImpl,
        )

    private inner class HttpRefResolver : GitRefResolver {
        override suspend fun resolveRef(ref: String): String? = refCache[ref]

        override suspend fun fetchFromUrl(
            url: String,
            refs: List<String>,
            policy: NetworkPolicy,
            scope: com.accucodeai.kash.api.git.GitFetchScope,
        ): Map<String, String> {
            if (refs.isEmpty()) return emptyMap()
            // Pre-flight: gate the URL against the resolved policy
            // BEFORE issuing the HTTP request. KashKtorClient also
            // applies its own policy, but that policy comes from the
            // client's constructor — if the caller has handed us a
            // stricter effective policy at the call site, we honor it
            // here. Failures throw with a clear message that the kash
            // fetch porcelain surfaces on stderr (matching the rest of
            // this method's "no silent empty" stance).
            assertHttpUrlAllowed(url, policy)
            val fetcher = SmartHttpFetch(httpClient)
            // Deliberately do NOT swallow exceptions here. Browser
            // CORS failures, server 4xx/5xx, malformed pkt-line
            // responses, and zlib errors all need to be visible to
            // the caller — otherwise `git clone` reports "Already up
            // to date." against an empty repo and the user has no
            // way to tell what went wrong. The kash command runner
            // turns the propagated throwable into a non-zero exit +
            // stderr message.
            // Per-invocation scope wins over the constructor-time
            // `defaultScope` — that's how `git fetch --depth=1`
            // overrides a session-wide "full clone" setting.
            val effectiveScope =
                if (scope.depth != null || scope.filter != null) scope else defaultScope
            val result =
                fetcher.fetch(
                    baseUrl = url,
                    wants = refs,
                    haveBase = { sha -> objectCache[sha] },
                    scope = effectiveScope,
                )
            // Land objects + refs in our in-memory caches; the kash
            // `ObjectStore` and `RefStore` read through from these via
            // the SAMs above.
            for ((sha, framed) in result.objects) objectCache[sha] = framed
            for ((ref, sha) in result.refToSha) refCache[ref] = sha
            // Track the shallow boundary so callers that care
            // (`HttpGitHostAdapter.shallowCommits`) can emit a
            // `.git/shallow` file for git-CLI parity. We additively
            // merge across multiple fetches and drop anything that
            // became unshallow this round.
            shallowCommitsBacking += result.shallowCommits
            shallowCommitsBacking -= result.unshallowCommits
            return result.refToSha
        }
    }
}

/**
 * URL-policy check for the http git adapter. Throws with a specific
 * diagnostic message on failure rather than returning a bool — the
 * kash fetch porcelain catches and surfaces the message on stderr, so
 * any silent-empty result here would land the user in the
 * "Already up to date." against-an-empty-repo trap.
 *
 * Reasons (each gets its own message):
 *  - Unparseable URL
 *  - Non-http(s) scheme (ssh, git://, file://, scp-style …) — these
 *    aren't speakable by [SmartHttpFetch]; on JVM, [JGitHostAdapter]
 *    handles them.
 *  - Policy denial (`NetworkPolicy.DenyAll` / allowlist miss)
 */
internal fun assertHttpUrlAllowed(
    url: String,
    policy: NetworkPolicy,
) {
    val authority =
        parseAuthority(url)
            ?: throw IllegalStateException(
                "smart-http: cannot parse URL '$url'. Expected scheme://host[:port][/path].",
            )
    val (scheme, host, port) = authority
    if (scheme != "http" && scheme != "https") {
        throw IllegalStateException(
            "smart-http: scheme '$scheme://' is not supported by HttpGitHostAdapter " +
                "(URL: '$url'). This driver only speaks http(s). " +
                "For ssh:// / git@host:path / git:// use JGitHostAdapter on the JVM; " +
                "for file:// use a local checkout instead.",
        )
    }
    if (!policy.allows(host.lowercase(), port, scheme)) {
        throw IllegalStateException(
            "smart-http: NetworkPolicy refused $scheme://$host:$port (URL: '$url'). " +
                "Adjust the session's NetworkPolicy (or the adapter's networkPolicy " +
                "override) to allow this host.",
        )
    }
}
