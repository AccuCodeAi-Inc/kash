package com.accucodeai.kash.tools.git.jgit

import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitObjectBundle
import com.accucodeai.kash.api.git.GitObjectResolver
import com.accucodeai.kash.api.git.GitPushApplier
import com.accucodeai.kash.api.git.GitPushOutcome
import com.accucodeai.kash.api.git.GitPushRequest
import com.accucodeai.kash.api.git.GitRefResolver
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.git.SyntheticCommit
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.RepoLayout
import com.accucodeai.kash.tools.git.plumbing.framedObject
import com.accucodeai.kash.tools.git.plumbing.parseFramedObject
import com.accucodeai.kash.tools.git.seed.materializeSeed
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.ReceiveCommand

/**
 * Host driver that pipes the kash git tool through a real
 * [org.eclipse.jgit.lib.Repository]. Works with any JGit Repository —
 * the on-disk `FileRepository` returned by `Git.open(...)` or the
 * test-only `InMemoryRepository`. JVM-only.
 *
 * Wiring:
 *  - [GitObjectResolver] serves framed git objects out of JGit's
 *    [ObjectDatabase][org.eclipse.jgit.lib.ObjectDatabase] (loose +
 *    packed both work; JGit normalizes them).
 *  - [GitRefResolver] reads refs via [Repository.exactRef] so `git
 *    fetch origin <branch>` learns the upstream tip without us
 *    re-implementing pack-protocol negotiation.
 *  - [GitPushApplier] takes the LLM's commit bundle, walks the
 *    objects into JGit via an [ObjectInserter][org.eclipse.jgit.lib.ObjectInserter],
 *    then advances the ref via [RefUpdate][org.eclipse.jgit.lib.RefUpdate]
 *    with `expectedOldObjectId` set — that's the API JGit gives us
 *    for atomic compare-and-set.
 *
 * The kash session is bootstrapped via [JGitHostAdapter.bootstrap]
 * (rather than the generic [materializeSeed]) so we can lay down an
 * empty `.git/` skeleton and then point `refs/heads/<branch>` at the
 * REAL upstream sha, instead of a fabricated synthetic-snapshot one.
 * This keeps the LLM-visible SHA identical to JGit's view of the
 * world, so `git push` and `HEAD@{1}` semantics line up.
 *
 * License: this module depends on Eclipse JGit (EDL 1.0, BSD-3-Clause).
 * See NOTICE for attribution.
 */
public class JGitHostAdapter(
    private val repository: Repository,
    public override val workTreePath: String = "/work",
    private val defaultBranch: String = repository.branch ?: "main",
    public override val identity: GitIdentity = readJGitIdentity(repository),
    /**
     * Per-adapter override of the outbound-network gate. `null`
     * (default) means "defer to the session
     * [com.accucodeai.kash.api.KashMachine]'s networkPolicy", which
     * kash core resolves at the call site. Set explicitly to
     * [NetworkPolicy.DenyAll] / [NetworkPolicy.Allowlist] when you
     * want the adapter to tighten the session-wide policy. Local
     * schemes (`file://`) bypass the check — they're not network
     * access.
     */
    public override val networkPolicy: NetworkPolicy? = null,
    /**
     * Session-wide shallow / partial-clone defaults. Per-invocation
     * `git fetch --depth=N` / `--filter=<spec>` on the command line
     * overrides this. Mirrors `HttpGitHostAdapter.defaultScope`.
     */
    public val defaultScope: com.accucodeai.kash.api.git.GitFetchScope =
        com.accucodeai.kash.api.git.GitFetchScope.FULL,
) : GitHostAdapter {
    private val objectResolver: GitObjectResolver = ResolverImpl(repository)
    private val refResolverImpl: GitRefResolver = RefResolverImpl(repository, defaultScope)
    private val pushApplierImpl: GitPushApplier = PushApplier(repository, defaultBranch)

    // OnDemand seed: the generic materializer lays down a valid `.git/`
    // with an empty Synthetic. We post-process in [bootstrap] to make
    // refs/heads/<branch> point at JGit's real HEAD sha and to pre-cache
    // the HEAD commit object so the first `git log` doesn't hit the
    // resolver. After that the resolver feeds the rest lazily.
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

    /**
     * Bootstrap a kash session into [fs] backed by this adapter's
     * underlying JGit repository. Lays down `.git/`, then patches the
     * local branch + remote-tracking ref to the real upstream sha and
     * materializes the work tree from JGit's HEAD commit's tree.
     *
     * Call this once at session start, instead of the generic
     * [materializeSeed].
     */
    public suspend fun bootstrap(fs: FileSystem) {
        val layout = RepoLayout(workTreePath)
        val refStore = RefStore(layout, fs)

        // 1. Lay down a fresh `.git/` skeleton. We don't go through
        //    `materializeSeed` here because the Synthetic seed always
        //    writes one fabricated commit at HEAD — that commit's sha
        //    doesn't exist in JGit, so any later object resolution that
        //    starts from HEAD blows up against the resolver. The
        //    skeleton we lay down is the byte-for-byte equivalent of
        //    `git init -b <branch>` against [workTreePath].
        writeInitSkeleton(fs, layout, defaultBranch, identity)

        // 2. If JGit has a HEAD ref, point our refs/heads/<branch> at
        //    the real upstream sha and pre-cache the commit/tree/blobs.
        //    Otherwise leave the session empty — the LLM can `git init`,
        //    `git fetch origin <url>`, etc. to populate it.
        val branchRef = "refs/heads/$defaultBranch"
        val upstreamHead = repository.exactRef(branchRef)?.objectId ?: return
        val upstreamSha = upstreamHead.name
        refStore.writeRef(branchRef, upstreamSha)
        refStore.writeRef("refs/remotes/origin/$defaultBranch", upstreamSha)

        // Mirror every other local branch and tag JGit knows about so the
        // LLM sees the same set of refs at session start that `git branch`
        // /`git tag` would show on the host. Each branch gets a parallel
        // refs/remotes/origin/<branch> tracking entry so `git push` knows
        // upstream's view.
        for (ref in repository.refDatabase.getRefsByPrefix("refs/heads/")) {
            if (ref.name == branchRef) continue
            val sha = ref.objectId?.name ?: continue
            refStore.writeRef(ref.name, sha)
            val branchName = ref.name.removePrefix("refs/heads/")
            refStore.writeRef("refs/remotes/origin/$branchName", sha)
        }
        for (ref in repository.refDatabase.getRefsByPrefix("refs/tags/")) {
            val sha = ref.objectId?.name ?: continue
            refStore.writeRef(ref.name, sha)
        }

        // 3. Pre-cache HEAD's commit, tree, and blobs into loose objects so
        //    the first `git log`/`git show` runs without a resolver round
        //    trip. Anything past the immediate commit stays lazy.
        val store =
            com.accucodeai.kash.tools.git.plumbing
                .ObjectStore(layout, fs, objectResolver)
        val commitBytes = objectResolver.resolve(upstreamSha)
        if (commitBytes != null) {
            store.writeFramed(commitBytes)
            val parsed = parseFramedObject(commitBytes)
            if (parsed.type == ObjectType.COMMIT) {
                val commit =
                    com.accucodeai.kash.tools.git.plumbing
                        .decodeCommit(parsed.payload)
                pullTreeRecursive(store, objectResolver, commit.tree, fs, layout)
            }
        }
    }

    /**
     * Helper: walk JGit's HEAD tree into the work tree on [fs].
     * Recursive on subtrees; blob bytes go to `<workTree>/<path>`.
     */
    private suspend fun pullTreeRecursive(
        store: com.accucodeai.kash.tools.git.plumbing.ObjectStore,
        resolver: GitObjectResolver,
        treeSha: String,
        fs: FileSystem,
        layout: RepoLayout,
        prefix: String = "",
    ) {
        val raw = resolver.resolve(treeSha) ?: return
        store.writeFramed(raw)
        val parsed = parseFramedObject(raw)
        if (parsed.type != ObjectType.TREE) return
        for (entry in com.accucodeai.kash.tools.git.plumbing
            .decodeTree(parsed.payload)) {
            val sub = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            when (entry.mode) {
                com.accucodeai.kash.tools.git.plumbing.FileMode.TREE -> {
                    pullTreeRecursive(store, resolver, entry.sha, fs, layout, sub)
                }

                else -> {
                    val blobFramed = resolver.resolve(entry.sha) ?: continue
                    store.writeFramed(blobFramed)
                    val abs = layout.workPath(sub)
                    val parent = abs.substringBeforeLast('/').ifEmpty { "/" }
                    if (!fs.exists(parent)) fs.mkdirs(parent)
                    val blobParsed = parseFramedObject(blobFramed)
                    if (blobParsed.type == ObjectType.BLOB) fs.writeBytes(abs, blobParsed.payload)
                }
            }
        }
    }
}

/**
 * Lay down a bare-bones `.git/` at [layout] mirroring what `git init
 * -b <branch>` writes — HEAD, config, objects/ + objects/info/ +
 * objects/pack/, refs/heads + refs/tags, info/exclude, hooks/. No
 * synthetic commit; the directory is fully equivalent to a freshly-
 * initialized repo.
 */
private suspend fun writeInitSkeleton(
    fs: FileSystem,
    layout: RepoLayout,
    branch: String,
    identity: GitIdentity,
) {
    fs.mkdirs(layout.gitDir)
    fs.mkdirs(layout.objectsDir)
    fs.mkdirs("${layout.objectsDir}/info")
    fs.mkdirs(layout.packDir)
    fs.mkdirs(layout.refsDir)
    fs.mkdirs(layout.headsDir)
    fs.mkdirs(layout.tagsDir)
    fs.mkdirs(layout.infoDir)
    fs.mkdirs(layout.hooksDir)
    fs.writeBytes(layout.headFile, "ref: refs/heads/$branch\n".encodeToByteArray())
    if (!fs.exists(layout.configFile)) {
        fs.writeBytes(
            layout.configFile,
            buildString {
                appendLine("[core]")
                appendLine("\trepositoryformatversion = 0")
                appendLine("\tfilemode = true")
                appendLine("\tbare = false")
                appendLine("\tlogallrefupdates = true")
                appendLine("[user]")
                appendLine("\tname = ${identity.name}")
                appendLine("\temail = ${identity.email}")
            }.encodeToByteArray(),
        )
    }
    if (!fs.exists(layout.descriptionFile)) {
        fs.writeBytes(
            layout.descriptionFile,
            "Unnamed repository; edit this file 'description' to name the repository.\n".encodeToByteArray(),
        )
    }
    if (!fs.exists(layout.excludeFile)) {
        fs.writeBytes(
            layout.excludeFile,
            "# git ls-files --others --exclude-from=.git/info/exclude\n".encodeToByteArray(),
        )
    }
}

/**
 * Read author identity from JGit's config: `user.name` / `user.email`.
 * Falls back to a kash sentinel when neither is set (matches what
 * `git commit` would surface in the same situation).
 */
internal fun readJGitIdentity(repository: Repository): GitIdentity {
    val cfg = repository.config
    return GitIdentity(
        name = cfg.getString("user", null, "name") ?: "kash",
        email = cfg.getString("user", null, "email") ?: "kash@localhost",
    )
}

/**
 * [GitObjectResolver] implementation that reads from JGit's object
 * database. Returns the framed (`<type> <len>\0<payload>`) byte form
 * the [ObjectStore][com.accucodeai.kash.tools.git.plumbing.ObjectStore]
 * expects — we re-frame each time because JGit hands us the unframed
 * payload + a separate type code.
 */
private class ResolverImpl(
    private val repository: Repository,
) : GitObjectResolver {
    override suspend fun resolve(sha: String): ByteArray? {
        if (sha.length != 40) return null
        val id = ObjectId.fromString(sha)
        return repository.newObjectReader().use { reader ->
            if (!reader.has(id)) return null
            val loader = reader.open(id)
            val payload = loader.cachedBytes
            framedObject(typeFromJGit(loader.type), payload)
        }
    }
}

/**
 * [GitRefResolver] over JGit that also implements [GitRefResolver.fetchFromUrl]
 * by opening a JGit [Transport][org.eclipse.jgit.transport.Transport] to
 * the URL and running a real fetch — that's what makes `git fetch
 * origin main`, `git pull`, and `git clone <url>` actually talk to a
 * remote when the kash session has one configured.
 *
 * Returned shas are then read out of the local repo (Transport.fetch
 * lands every object it needs into our InMemoryRepository), so the
 * adapter's [GitObjectResolver] stays a pure local-lookup once the
 * fetch is done.
 */
private class RefResolverImpl(
    private val repository: Repository,
    private val defaultScope: com.accucodeai.kash.api.git.GitFetchScope,
) : GitRefResolver {
    override suspend fun resolveRef(ref: String): String? = repository.exactRef(ref)?.objectId?.name

    override suspend fun discoverDefaultBranch(
        url: String,
        policy: NetworkPolicy,
    ): String? {
        if (!isUrlAllowedByPolicy(url, policy)) return null
        val canonical =
            com.accucodeai.kash.tools.git
                .canonicalizeGitUrl(url)
        val urlish =
            org.eclipse.jgit.transport
                .URIish(canonical)
        val transport: org.eclipse.jgit.transport.Transport =
            runCatching {
                org.eclipse.jgit.transport.Transport
                    .open(repository, urlish)
            }.getOrNull() ?: return null
        return try {
            val conn = transport.openFetch()
            try {
                // openFetch's advertisement has a symbolic HEAD ref;
                // its target is the upstream's default branch.
                val head = conn.getRef(org.eclipse.jgit.lib.Constants.HEAD) ?: return null
                val target = head.target ?: return null
                val name = target.name
                if (name.startsWith("refs/heads/")) name.removePrefix("refs/heads/") else null
            } finally {
                conn.close()
            }
        } catch (_: Throwable) {
            null
        } finally {
            transport.close()
        }
    }

    override suspend fun fetchFromUrl(
        url: String,
        refs: List<String>,
        policy: NetworkPolicy,
        scope: com.accucodeai.kash.api.git.GitFetchScope,
    ): Map<String, String> {
        if (refs.isEmpty()) return emptyMap()
        if (!isUrlAllowedByPolicy(url, policy)) return emptyMap()
        // Canonicalize the same way the HttpGitHostAdapter does — strip
        // GitHub-style web fragments (`/pull/N`, `/tree/…`, …) so
        // `git clone https://github.com/foo/bar/pull/83` lands at the
        // repo's smart-HTTP endpoint instead of asking JGit to fetch
        // from a PR-page URL (which silently returns no refs).
        val canonical =
            com.accucodeai.kash.tools.git
                .canonicalizeGitUrl(url)
        val urlish =
            org.eclipse.jgit.transport
                .URIish(canonical)
        // Refspec must mirror upstream into our local refs/heads/* so
        // exactRef() picks them up afterward. For tag refs the refspec
        // is `refs/tags/X:refs/tags/X`.
        val specs =
            refs.map { ref ->
                org.eclipse.jgit.transport
                    .RefSpec("+$ref:$ref")
            }
        // Deliberately do NOT swallow Transport exceptions. The kash
        // fetch porcelain wraps this call and writes a `fatal: …`
        // line to stderr — silent emptyMap returns landed users in
        // the "Already up to date." against-empty-repo trap.
        val transport: org.eclipse.jgit.transport.Transport =
            try {
                org.eclipse.jgit.transport.Transport
                    .open(repository, urlish)
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "JGit transport open failed for '$canonical': ${t.message ?: t::class.simpleName}",
                    t,
                )
            }
        // Translate FetchScope into JGit transport-level knobs:
        //  - depth → `Transport.setDepth(int)`. JGit emits the smart-
        //    HTTP `deepen <N>` line under the hood, same as kash's
        //    HttpGitHostAdapter does manually.
        //  - filter → `Transport.setFilterSpec(FilterSpec)`. JGit
        //    parses the wire spec ("blob:none", "tree:0",
        //    "blob:limit=1m") and emits the matching `filter` line.
        // Per-invocation scope wins over constructor-time default.
        val effectiveScope = if (scope.depth != null || scope.filter != null) scope else defaultScope
        // Local copies — public-API properties from `:api` are
        // not smart-cast-friendly across module boundaries.
        val effectiveDepth = effectiveScope.depth
        val effectiveFilter = effectiveScope.filter
        if (effectiveDepth != null) {
            require(effectiveDepth > 0) { "JGit fetch: --depth must be > 0 (got $effectiveDepth)" }
            transport.setDepth(effectiveDepth)
        }
        if (effectiveFilter != null) {
            require(effectiveFilter.isNotBlank()) { "JGit fetch: --filter spec must be non-blank" }
            transport.filterSpec =
                org.eclipse.jgit.transport.FilterSpec
                    .fromFilterLine(effectiveFilter)
        }
        return try {
            transport.fetch(org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE, specs)
            // After fetch, the local refs we requested via `+ref:ref`
            // refspec are populated. Look them up locally rather than
            // peeking at the advertised-ref set (which doesn't always
            // expose what the refspec wrote).
            val out = mutableMapOf<String, String>()
            for (ref in refs) {
                val sha = repository.exactRef(ref)?.objectId?.name
                if (sha != null) out[ref] = sha
            }
            out
        } catch (t: Throwable) {
            throw IllegalStateException(
                "JGit fetch from '$canonical' failed: ${t.message ?: t::class.simpleName}",
                t,
            )
        } finally {
            transport.close()
        }
    }
}

private fun typeFromJGit(jgitType: Int): ObjectType =
    when (jgitType) {
        Constants.OBJ_BLOB -> ObjectType.BLOB
        Constants.OBJ_TREE -> ObjectType.TREE
        Constants.OBJ_COMMIT -> ObjectType.COMMIT
        Constants.OBJ_TAG -> ObjectType.TAG
        else -> error("jgit returned unknown object type $jgitType")
    }

private fun jgitTypeOf(t: ObjectType): Int =
    when (t) {
        ObjectType.BLOB -> Constants.OBJ_BLOB
        ObjectType.TREE -> Constants.OBJ_TREE
        ObjectType.COMMIT -> Constants.OBJ_COMMIT
        ObjectType.TAG -> Constants.OBJ_TAG
    }

/**
 * Take the LLM's push bundle and replay it into the underlying JGit
 * Repository. Steps:
 *
 * 1. For each bundle, insert its commit/tree/blob objects via an
 *    [ObjectInserter]. Insertions are idempotent on sha — JGit detects
 *    the duplicate and short-circuits.
 * 2. Advance `refs/heads/<branch>` via [RefUpdate.update] with
 *    `expectedOldObjectId = req.expectedOldSha`. JGit performs the
 *    compare-and-set atomically (returns `RefUpdate.Result.LOCK_FAILURE`
 *    if upstream moved underneath us — surfaces as `Rejected`).
 */
private class PushApplier(
    private val repository: Repository,
    private val defaultBranch: String,
) : GitPushApplier {
    override suspend fun apply(req: GitPushRequest): GitPushOutcome {
        // Insert all objects first; flush before the ref update so JGit
        // sees the new commit graph atomically.
        val inserter = repository.newObjectInserter()
        try {
            for (bundle in req.newCommits) {
                insertBundle(inserter, bundle)
            }
            // Annotated tag objects ride alongside the commit bundles.
            for ((_, framed) in req.tagObjects) {
                val parsed = parseFramedObject(framed)
                inserter.insert(jgitTypeOf(parsed.type), parsed.payload)
            }
            inserter.flush()
        } finally {
            inserter.close()
        }

        // `branch` may be a fully-qualified ref (refs/heads/<x> or
        // refs/tags/<x>) or a bare branch name. Bare names default to
        // refs/heads/ to match the legacy single-branch API.
        val branchRef =
            when {
                req.branch.startsWith("refs/") -> req.branch
                req.branch.isEmpty() -> "refs/heads/$defaultBranch"
                else -> "refs/heads/${req.branch}"
            }
        val newId = ObjectId.fromString(req.tipSha)
        val update = repository.updateRef(branchRef)
        update.setNewObjectId(newId)
        update.setRefLogMessage("push from kash", false)
        if (req.expectedOldSha != null) {
            update.setExpectedOldObjectId(ObjectId.fromString(req.expectedOldSha))
        } else {
            // Creating a new branch — JGit needs the zero-id sentinel.
            update.setExpectedOldObjectId(ObjectId.zeroId())
        }
        return when (update.update()) {
            org.eclipse.jgit.lib.RefUpdate.Result.NEW,
            org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD,
            org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE,
            org.eclipse.jgit.lib.RefUpdate.Result.FORCED,
            -> {
                GitPushOutcome.Accepted(req.tipSha)
            }

            org.eclipse.jgit.lib.RefUpdate.Result.LOCK_FAILURE -> {
                GitPushOutcome.Rejected("upstream moved while we were pushing")
            }

            org.eclipse.jgit.lib.RefUpdate.Result.REJECTED,
            org.eclipse.jgit.lib.RefUpdate.Result.REJECTED_CURRENT_BRANCH,
            org.eclipse.jgit.lib.RefUpdate.Result.REJECTED_MISSING_OBJECT,
            org.eclipse.jgit.lib.RefUpdate.Result.REJECTED_OTHER_REASON,
            -> {
                GitPushOutcome.Rejected("ref update rejected by jgit")
            }

            else -> {
                GitPushOutcome.Rejected("ref update returned ${update.result}")
            }
        }
    }

    /**
     * Network push: insert objects into the local in-memory repo first
     * (so the Transport's local object source knows about them), then
     * open a [Transport][org.eclipse.jgit.transport.Transport] to [url]
     * and ship a `+<localRef>:<remoteRef>` refspec. Returns the
     * receive-pack's verdict mapped onto [GitPushOutcome].
     */
    override suspend fun applyToUrl(
        url: String,
        req: GitPushRequest,
        policy: NetworkPolicy,
    ): GitPushOutcome {
        if (!isUrlAllowedByPolicy(url, policy)) {
            return GitPushOutcome.Rejected("network access denied by policy: $url")
        }
        // Stage objects locally so they're available to the Transport.
        val inserter = repository.newObjectInserter()
        try {
            for (bundle in req.newCommits) insertBundle(inserter, bundle)
            for ((_, framed) in req.tagObjects) {
                val parsed = parseFramedObject(framed)
                inserter.insert(jgitTypeOf(parsed.type), parsed.payload)
            }
            inserter.flush()
        } finally {
            inserter.close()
        }

        // Advance the local ref to the new tip so the transport ships
        // the right blob set under standard "deltify from local refs"
        // semantics.
        val branchRef =
            when {
                req.branch.startsWith("refs/") -> req.branch
                req.branch.isEmpty() -> "refs/heads/$defaultBranch"
                else -> "refs/heads/${req.branch}"
            }
        val update = repository.updateRef(branchRef)
        update.setNewObjectId(ObjectId.fromString(req.tipSha))
        if (req.expectedOldSha != null) {
            update.setExpectedOldObjectId(ObjectId.fromString(req.expectedOldSha))
        } else {
            update.setExpectedOldObjectId(ObjectId.zeroId())
        }
        update.forceUpdate()

        val urlish =
            try {
                org.eclipse.jgit.transport
                    .URIish(url)
            } catch (_: Throwable) {
                return GitPushOutcome.Rejected("invalid URL: $url")
            }
        val refSpec =
            org.eclipse.jgit.transport
                .RefSpec("$branchRef:$branchRef")
        val transport: org.eclipse.jgit.transport.Transport =
            try {
                org.eclipse.jgit.transport.Transport
                    .open(repository, urlish)
            } catch (t: Throwable) {
                return GitPushOutcome.Rejected("transport open failed: ${t.message}")
            }
        return try {
            val pushResult =
                transport.push(
                    org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE,
                    listOf(
                        org.eclipse.jgit.transport.RemoteRefUpdate(
                            repository,
                            branchRef,
                            branchRef,
                            // forceUpdate =
                            true,
                            null,
                            req.expectedOldSha?.let(ObjectId::fromString),
                        ),
                    ),
                )
            val rru = pushResult.getRemoteUpdate(branchRef)
            when (rru?.status) {
                org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK,
                org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE,
                -> {
                    GitPushOutcome.Accepted(req.tipSha)
                }

                org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> {
                    GitPushOutcome.Rejected("non-fast-forward")
                }

                org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_NODELETE -> {
                    GitPushOutcome.Rejected("delete refused")
                }

                org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> {
                    GitPushOutcome.Rejected("upstream moved")
                }

                org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON -> {
                    GitPushOutcome.Rejected(rru.message ?: "rejected")
                }

                else -> {
                    GitPushOutcome.Rejected("status=${rru?.status} message=${rru?.message}")
                }
            }.also {
                @Suppress("UNUSED_VARIABLE")
                val ignored = refSpec
            }
        } catch (t: Throwable) {
            GitPushOutcome.Rejected("transport push failed: ${t.message}")
        } finally {
            transport.close()
        }
    }

    private fun insertBundle(
        inserter: org.eclipse.jgit.lib.ObjectInserter,
        bundle: GitObjectBundle,
    ) {
        // Blobs first (trees reference them), then trees, then the commit.
        for ((_, framed) in bundle.blobs) {
            val parsed = parseFramedObject(framed)
            inserter.insert(jgitTypeOf(parsed.type), parsed.payload)
        }
        for ((_, framed) in bundle.trees) {
            val parsed = parseFramedObject(framed)
            inserter.insert(jgitTypeOf(parsed.type), parsed.payload)
        }
        val commitParsed = parseFramedObject(bundle.commit)
        inserter.insert(jgitTypeOf(commitParsed.type), commitParsed.payload)
    }
}

/**
 * Gate a git transport URL against the session's [NetworkPolicy].
 *
 *  - `file://` URLs always pass: they're local-FS access, not network.
 *  - `ssh://` / `git+ssh://` / `git://` map to the conventional default
 *    ports (22 / 22 / 9418) when not explicit.
 *  - `http://` / `https://` use the standard ports.
 *  - Anything else (including `git@host:path` SCP-style or unknown
 *    schemes) is treated as network with port 0; strict policies reject
 *    these, [NetworkPolicy.None] permits them.
 */
private fun isUrlAllowedByPolicy(
    url: String,
    policy: NetworkPolicy,
): Boolean {
    if (policy is NetworkPolicy.None) return true
    val schemeEnd = url.indexOf("://")
    val scheme = if (schemeEnd > 0) url.substring(0, schemeEnd).lowercase() else null
    if (scheme == "file") return true
    if (scheme == null) {
        // SCP-style `user@host:path` — treat host as authority. Refuse
        // if the policy can't reason about ports.
        val at = url.indexOf('@')
        val colon = url.indexOf(':', startIndex = (at + 1).coerceAtLeast(0))
        if (at >= 0 && colon > at) {
            val host = url.substring(at + 1, colon)
            return policy.allows(host.lowercase(), 22, "ssh")
        }
        return policy.allows("", 0, "")
    }
    val rest = url.substring(schemeEnd + 3)
    val authorityEnd =
        rest
            .indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    if (authority.isEmpty()) return policy.allows("", 0, scheme)
    val hostPart = authority.substringAfter('@', authority)
    val (host, portStr) =
        if (hostPart.startsWith("[")) {
            val close = hostPart.indexOf(']')
            if (close < 0) return policy.allows("", 0, scheme)
            val h = hostPart.substring(1, close)
            val pStr =
                if (close + 1 < hostPart.length && hostPart[close + 1] == ':') {
                    hostPart.substring(close + 2)
                } else {
                    ""
                }
            h to pStr
        } else {
            val colon = hostPart.indexOf(':')
            if (colon < 0) hostPart to "" else hostPart.substring(0, colon) to hostPart.substring(colon + 1)
        }
    val port =
        when {
            portStr.isNotEmpty() -> portStr.toIntOrNull() ?: 0
            scheme == "http" -> 80
            scheme == "https" -> 443
            scheme == "ssh" || scheme == "git+ssh" -> 22
            scheme == "git" -> 9418
            else -> 0
        }
    return policy.allows(host.lowercase(), port, scheme)
}

// Force a transitive reference so the import isn't flagged as unused;
// ReceiveCommand will become relevant when we add multi-ref push.
@Suppress("UNUSED")
private val unused: Class<*> = ReceiveCommand::class.java
