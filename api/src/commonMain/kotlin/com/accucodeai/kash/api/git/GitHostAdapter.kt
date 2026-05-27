package com.accucodeai.kash.api.git

import kotlin.time.Instant

/**
 * Host-supplied integration for the optional `git` tool. Wired by the JVM
 * app entry-point via `gitCommands(adapter)`; absent it, the `git` builtin
 * is not registered.
 *
 * The shape exists so that the LLM driving kash gets a *real-looking*
 * `git` CLI against a repo whose contents and history are owned by the
 * host. See `docs/GIT_ADAPTER_PLAN.md`.
 */
public interface GitHostAdapter {
    /** Snapshot the runtime materializes into the VFS on attach. */
    public val repoSeed: GitRepoSeed

    /** Default `user.name` / `user.email` written into `.git/config`. */
    public val identity: GitIdentity

    /** Where to mount the working tree (absolute VFS path). */
    public val workTreePath: String

    /**
     * Branch whose commits are interpreted as the "I'm done, sync me back"
     * signal. Defaults to `main`. A commit on this branch triggers
     * [onCommit] with `isSyncBranch = true`; the host can return
     * [GitCommitAck.Done] to terminate the task.
     */
    public val syncBranch: String get() = "main"

    /** Named remotes (`origin`, …). May be empty. */
    public val remotes: Map<String, GitRemoteAdapter> get() = emptyMap()

    /**
     * Unbypassable host-side validator run on every `git commit` after the
     * staged set is known but before the commit object is written. Returns
     * null to opt out. See `docs/GIT_ADAPTER_PLAN.md`'s "Pre-commit gate".
     */
    public val preCommitValidator: PreCommitValidator? get() = null

    /**
     * Fires after every successful commit. `isSyncBranch = true` flags the
     * commits the host actually cares about. Default no-op accepts every
     * commit silently.
     */
    public val onCommit: GitCommitListener get() = GitCommitListener { GitCommitAck.Accepted }

    /**
     * Optional per-adapter override of the outbound-network gate for
     * URL-form `git fetch` / `git push`. `null` (the default) means
     * "defer to the session [com.accucodeai.kash.api.KashMachine]'s
     * networkPolicy" — kash core resolves the effective policy at
     * call time. Set it explicitly when you want the adapter to
     * tighten (or loosen) the session-wide policy.
     */
    public val networkPolicy: com.accucodeai.kash.api.sandbox.NetworkPolicy?
        get() = null
}

public data class GitIdentity(
    val name: String,
    val email: String,
)

/**
 * What the host hands us to materialize as `.git/` + working tree.
 *
 * Three shapes by source-of-truth:
 *  - [RealGit] — host has a real `.git/` to forward; we write its bytes.
 *  - [Synthetic] — host fabricates history; we synthesize a real `.git/`
 *    whose content matches that fabrication.
 *  - [Subtree] — host points at a subdirectory of a real repo; we filter
 *    its history down to that subtree, computing real new SHAs.
 *  - [Empty] — `git init` with no commits.
 */
public sealed interface GitRepoSeed {
    /**
     * Host hands us a real `.git/` snapshot — objects (sha → raw,
     * uncompressed bytes), optional packs (raw .pack bytes), refs, HEAD,
     * optional packed-refs, optional config, optional hooks. We write the
     * bytes onto the VFS verbatim (loose objects are zlib-compressed on
     * the way down).
     */
    public class RealGit(
        public val objects: Map<String, ByteArray>,
        public val packs: List<ByteArray> = emptyList(),
        public val refs: Map<String, String>,
        public val head: String,
        public val packedRefs: ByteArray? = null,
        public val configIni: String? = null,
        public val hooks: Map<String, ByteArray> = emptyMap(),
    ) : GitRepoSeed

    /**
     * Host gives us a working tree + a synthetic history; we fabricate a
     * `.git/` that looks like that history actually happened. The bytes
     * are real git (real SHAs, real object encoding) — only the history's
     * *content* is fabricated.
     *
     * Common shapes:
     *  - Bare folder: pass [workTree], leave [history] defaulted → one
     *    commit "Initial commit" with that tree.
     *  - Multi-commit linear: list of [SyntheticCommit]s, each referencing
     *    the previous by tag.
     *  - Branched (for fabricated conflict scenarios): multiple commits
     *    sharing a parent tag, plus [extraRefs] naming each branch tip.
     */
    public class Synthetic(
        public val workTree: Map<String, ByteArray>,
        public val executable: Set<String> = emptySet(),
        public val history: List<SyntheticCommit> = listOf(SyntheticCommit.singleSnapshot()),
        public val extraRefs: Map<String, String> = emptyMap(),
        public val head: String = "refs/heads/main",
        public val identity: GitIdentity? = null,
        public val hooks: Map<String, ByteArray> = emptyMap(),
        public val configIni: String? = null,
    ) : GitRepoSeed

    /**
     * Filter-branch an upstream repo down to a subdirectory. Resulting
     * `.git/` has rewritten SHAs whose trees are [upstream]'s commits
     * with [subdir] re-rooted.
     */
    public class Subtree(
        public val upstream: RealGit,
        public val subdir: String,
        public val keepEmpty: Boolean = false,
    ) : GitRepoSeed

    /**
     * API-backed repo (the GitLab case): host has piecemeal access via
     * a REST API but no real `.git/`. We materialize [horizon] up front
     * (typically just the tip commit, its tree, and the blobs the LLM
     * is expected to touch); anything past it is resolved on demand
     * through [resolver] and cached as a loose object once seen.
     *
     * Untouched by `OnDemand`: the LLM sees a fully-real `.git/` for
     * everything that has been materialized so far. Misses are
     * indistinguishable from a slow disk.
     *
     * See `docs/GIT_ADAPTER_PLAN.md`'s "API-backed hosts" section.
     */
    public class OnDemand(
        public val horizon: Synthetic,
        public val resolver: GitObjectResolver,
        public val refResolver: GitRefResolver? = null,
        public val pushApplier: GitPushApplier? = null,
    ) : GitRepoSeed

    /** `git init` with no HEAD target. */
    public object Empty : GitRepoSeed
}

/**
 * Resolve a git object by sha when the local object DB doesn't have it.
 * Returns the **uncompressed framed object** — the bytes git would
 * zlib-compress into a loose object file: `<type> <size>\0<payload>`.
 * (i.e. what `parseFramedObject` consumes; what `framedObject(type,
 * payload)` produces.) The runtime verifies the sha matches what was
 * asked for, then stores it as a loose object so future lookups stay
 * local.
 *
 * Return `null` to signal "this sha genuinely does not exist upstream"
 * — surfaces the same error path as a corrupt local repo missing the
 * object.
 */
public fun interface GitObjectResolver {
    public suspend fun resolve(sha: String): ByteArray?
}

/**
 * Look up the current sha for a fully-qualified ref name (e.g.
 * `refs/heads/main`). Returns null if the ref doesn't exist upstream.
 * Used by `git fetch`/`git ls-remote`-shaped operations against an
 * API-backed host.
 */
public fun interface GitRefResolver {
    public suspend fun resolveRef(ref: String): String?

    /**
     * Optional network-aware fetch. Given a remote [url] and a list of
     * fully-qualified refs the caller wants, the host performs whatever
     * ls-remote + object-fetch dance its transport supports and returns
     * `ref → sha` for each ref that exists upstream.
     *
     * Hosts that override this MUST also populate the local object
     * cache (the [GitObjectResolver]'s backing store) with everything
     * the returned shas transitively need. After a successful return
     * the caller assumes `git log <returnedSha>` will Just Work without
     * any more network round-trips.
     *
     * Default returns empty — kash falls back to local-only
     * [resolveRef] resolution.
     */
    public suspend fun fetchFromUrl(
        url: String,
        refs: List<String>,
        policy: com.accucodeai.kash.api.sandbox.NetworkPolicy = com.accucodeai.kash.api.sandbox.NetworkPolicy.None,
        scope: GitFetchScope = GitFetchScope.FULL,
    ): Map<String, String> = emptyMap()

    /**
     * Optional ls-remote shaped query that returns the short branch
     * name [url]'s `HEAD` symref points at (e.g. `"master"` for
     * `octocat/Hello-World`, `"main"` for most modern repos).
     *
     * Used by `git clone <url>` to pick which branch to fetch +
     * check out when the user didn't specify one. Real git does the
     * same probe via the smart-HTTP/SSH ref advertisement's HEAD
     * line. Returning `null` (the default) makes kash fall back to
     * `"main"`, which is wrong for repos with a different default —
     * see the `octocat/Hello-World` clone bug.
     */
    public suspend fun discoverDefaultBranch(
        url: String,
        policy: com.accucodeai.kash.api.sandbox.NetworkPolicy = com.accucodeai.kash.api.sandbox.NetworkPolicy.None,
    ): String? = null
}

/**
 * Shallow / partial-clone request shape. Mirrors the
 * `com.accucodeai.kash.tools.git.FetchScope` data class — duplicated
 * here so the `:api` module (which can't depend on `:tools:kash:git`)
 * has a name for the SAM parameter. Tool modules typealias / convert
 * between the two.
 *
 *  - [depth] non-null → shallow clone (e.g. `1` = tip-only).
 *  - [filter] non-null → partial clone wire spec (`"blob:none"`,
 *    `"blob:limit=1m"`, `"tree:0"`, …).
 */
public data class GitFetchScope(
    val depth: Int? = null,
    val filter: String? = null,
) {
    public companion object {
        public val FULL: GitFetchScope = GitFetchScope()
    }
}

/**
 * Apply a push to the API-backed upstream. Host receives the full delta
 * (new commits, their trees, the blobs they introduce) and translates
 * it into whatever the upstream expects — a GitLab `POST /commits`
 * call, a code-review submission, a real git push. Returns the
 * resulting tip sha (may differ if the host rebased/squashed/merged).
 */
public fun interface GitPushApplier {
    public suspend fun apply(req: GitPushRequest): GitPushOutcome

    /**
     * Optional network-aware push. The host opens a transport to [url]
     * and ships [req]'s objects + ref update; returns the upstream's
     * acceptance or rejection. Default returns
     * [GitPushOutcome.Rejected] with a generic reason so kash surfaces
     * "no transport configured" rather than silently swallowing the
     * push.
     */
    public suspend fun applyToUrl(
        url: String,
        req: GitPushRequest,
        policy: com.accucodeai.kash.api.sandbox.NetworkPolicy = com.accucodeai.kash.api.sandbox.NetworkPolicy.None,
    ): GitPushOutcome = GitPushOutcome.Rejected("no network transport configured")
}

public class GitPushRequest(
    /**
     * Fully-qualified ref being updated (`refs/heads/<branch>` or
     * `refs/tags/<name>`). For backward compatibility, callers may pass
     * a bare branch name and the host treats it as `refs/heads/<name>`.
     */
    public val branch: String,
    public val expectedOldSha: String?,
    public val newCommits: List<GitObjectBundle>,
    public val tipSha: String,
    /**
     * Optional annotated-tag object framing that goes with this push.
     * Sha → framed bytes (`tag <len>\0<payload>`). Used when [branch]
     * is a `refs/tags/...` ref pointing at an annotated tag — the host
     * inserts these objects before advancing the ref.
     */
    public val tagObjects: Map<String, ByteArray> = emptyMap(),
)

/** One commit's worth of objects in transit. */
public class GitObjectBundle(
    public val commitSha: String,
    public val commit: ByteArray,
    public val trees: Map<String, ByteArray>,
    public val blobs: Map<String, ByteArray>,
)

public sealed interface GitPushOutcome {
    public data class Accepted(
        val newTipSha: String,
    ) : GitPushOutcome

    public data class Rejected(
        val reason: String,
    ) : GitPushOutcome
}

/**
 * One entry in a [GitRepoSeed.Synthetic] history. The runtime computes
 * each commit's tree by applying [changes] to its parent's tree (or to
 * the seed's `workTree` if [parents] is empty and this is the only
 * commit).
 */
public data class SyntheticCommit(
    val tag: String,
    val parents: List<String> = emptyList(),
    val message: String = "",
    val author: GitIdentity? = null,
    val authorDate: Instant? = null,
    val changes: List<TreeChange> = emptyList(),
) {
    public companion object {
        public fun singleSnapshot(message: String = "Initial commit"): SyntheticCommit =
            SyntheticCommit(tag = "HEAD", message = message)
    }
}

public sealed interface TreeChange {
    public class Write(
        public val path: String,
        public val bytes: ByteArray,
        public val executable: Boolean = false,
    ) : TreeChange

    public data class Delete(
        val path: String,
    ) : TreeChange

    public data class Rename(
        val from: String,
        val to: String,
    ) : TreeChange
}

/** Network-backed operations on a named remote. */
public interface GitRemoteAdapter {
    public suspend fun lsRemote(): List<RemoteRef>

    /**
     * Negotiate a fetch. Returns a packfile and the resolved tips for the
     * requested refs.
     */
    public suspend fun fetch(
        wants: List<String>,
        haves: List<String>,
    ): FetchResult

    public suspend fun push(
        updates: List<RefUpdate>,
        packBytes: ByteArray,
    ): PushResult
}

public data class RemoteRef(
    val name: String,
    val sha: String,
)

public data class FetchResult(
    val packBytes: ByteArray,
    val tips: Map<String, String>,
)

public data class RefUpdate(
    val ref: String,
    val oldSha: String?,
    val newSha: String,
)

public sealed interface PushResult {
    public object Accepted : PushResult

    public data class Rejected(
        val ref: String,
        val reason: String,
    ) : PushResult
}

/** Unbypassable host pre-commit gate. */
public fun interface PreCommitValidator {
    public suspend fun validate(req: PreCommitRequest): PreCommitResult
}

public data class PreCommitRequest(
    val branch: String,
    val message: String,
    val author: GitIdentity,
    val parentSha: String?,
    val staged: List<StagedFile>,
)

public data class StagedFile(
    val path: String,
    val mode: Int,
    val sha: String,
    val newBytes: ByteArray?,
)

public sealed interface PreCommitResult {
    public object Accept : PreCommitResult

    public data class Reject(
        val message: String,
        val details: List<String> = emptyList(),
    ) : PreCommitResult
}

/** Fires after every successful commit. */
public fun interface GitCommitListener {
    public suspend fun onCommit(event: GitCommitEvent): GitCommitAck
}

public data class GitCommitEvent(
    val sha: String,
    val branch: String,
    val parentSha: String?,
    val message: String,
    val author: GitIdentity,
    val isSyncBranch: Boolean,
    val changedFiles: List<ChangedFile>,
)

public data class ChangedFile(
    val path: String,
    val change: Change,
) {
    public enum class Change { ADDED, MODIFIED, DELETED, RENAMED }
}

public sealed interface GitCommitAck {
    /** Continue silently. */
    public object Accepted : GitCommitAck

    /** Print `warning: ${message}` to stderr; commit stays. */
    public data class Warn(
        val message: String,
    ) : GitCommitAck

    /**
     * "Task done" — only meaningful on the sync branch. Treated as
     * [Accepted] on non-sync branches (document).
     */
    public object Done : GitCommitAck
}
