package com.accucodeai.kash.tools.git.seed

import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitObjectResolver
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.FlatFile
import com.accucodeai.kash.tools.git.plumbing.ObjectStore
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.RepoLayout
import com.accucodeai.kash.tools.git.plumbing.encodeIndex
import com.accucodeai.kash.tools.git.plumbing.indexFromFlatTree
import com.accucodeai.kash.tools.git.plumbing.zlibDeflate

/**
 * One-stop session bootstrap. Hosts call this when initializing a kash
 * session that's wired with a [GitHostAdapter] — it materializes the
 * adapter's [GitRepoSeed] onto [fs] so the LLM walks into an
 * already-set-up `.git/` and working tree.
 *
 * For all seed shapes, the post-condition is a real-looking `.git/`
 * that real git would accept (a `git fsck` passes). The difference is
 * what's *in* it on day zero:
 *  - [GitRepoSeed.Empty]: empty repo (`git init`-equivalent).
 *  - [GitRepoSeed.Synthetic]: full history fabricated up front.
 *  - [GitRepoSeed.RealGit]: objects copied verbatim from the host's
 *    snapshot.
 *  - [GitRepoSeed.OnDemand]: just the horizon is materialized;
 *    misses past it are resolved through the adapter at runtime via
 *    [GitObjectResolver]. The dispatch wiring is handled by
 *    [resolverFor] — `GitCommand` reads from that helper when opening
 *    a repo.
 */
public suspend fun materializeSeed(
    adapter: GitHostAdapter,
    fs: FileSystem,
) {
    val layout = RepoLayout(adapter.workTreePath)
    when (val seed = adapter.repoSeed) {
        is GitRepoSeed.Empty -> {
            writeEmpty(layout, fs, adapter.identity)
        }

        is GitRepoSeed.Synthetic -> {
            SyntheticMaterializer(layout, fs, adapter.identity).materialize(seed)
        }

        is GitRepoSeed.OnDemand -> {
            SyntheticMaterializer(layout, fs, adapter.identity).materialize(seed.horizon)
            // Stamp every local branch with a matching `refs/remotes/origin/<branch>`
            // so `git push` knows what the host's upstream tip was at session
            // start. The push path walks from the local tip back to this ref
            // (exclusive) to figure out which commits are new.
            writeOriginTrackingRefs(layout, fs)
        }

        is GitRepoSeed.RealGit -> {
            writeRealGit(seed, layout, fs, adapter.identity)
        }

        is GitRepoSeed.Subtree -> {
            error("GitRepoSeed.Subtree materialization not yet implemented")
        }
    }
}

/**
 * The runtime read path consults this to decide whether an
 * [ObjectStore] should fall back to a host resolver on cache miss.
 * Returns null for seed shapes that don't have one — local-only,
 * RealGit, Synthetic, and Empty all return null.
 */
public fun resolverFor(adapter: GitHostAdapter?): GitObjectResolver? =
    when (val seed = adapter?.repoSeed) {
        is GitRepoSeed.OnDemand -> seed.resolver
        else -> null
    }

/**
 * `git push` reaches for this to apply the LLM's commits back to the
 * host's upstream. Returns null for adapter shapes that have no notion
 * of a push target — bare local mode, [GitRepoSeed.Synthetic], etc. —
 * and the push command surfaces the "no remote configured" error.
 */
public fun pushApplierFor(adapter: GitHostAdapter?): com.accucodeai.kash.api.git.GitPushApplier? =
    when (val seed = adapter?.repoSeed) {
        is GitRepoSeed.OnDemand -> seed.pushApplier
        else -> null
    }

/**
 * Pull the ref resolver off an [GitRepoSeed.OnDemand] seed for use by
 * `git fetch`. Null for adapter shapes without one.
 */
public fun refResolverFor(adapter: GitHostAdapter?): com.accucodeai.kash.api.git.GitRefResolver? =
    when (val seed = adapter?.repoSeed) {
        is GitRepoSeed.OnDemand -> seed.refResolver
        else -> null
    }

private suspend fun writeEmpty(
    layout: RepoLayout,
    fs: FileSystem,
    identity: GitIdentity,
) {
    fs.mkdirs(layout.gitDir)
    fs.mkdirs(layout.objectsDir)
    fs.mkdirs("${layout.objectsDir}/info")
    fs.mkdirs(layout.packDir)
    fs.mkdirs(layout.refsDir)
    fs.mkdirs(layout.headsDir)
    fs.mkdirs(layout.tagsDir)
    fs.mkdirs(layout.hooksDir)
    fs.mkdirs(layout.infoDir)
    RefStore(layout, fs).writeHeadSymbolic("refs/heads/main")
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

/**
 * Stage a [GitRepoSeed.RealGit] verbatim. Loose objects come in raw
 * (uncompressed framed) — we zlib-deflate them on the way down. Refs,
 * HEAD, packed-refs, config, hooks all land as supplied.
 */
private suspend fun writeRealGit(
    seed: GitRepoSeed.RealGit,
    layout: RepoLayout,
    fs: FileSystem,
    identity: GitIdentity,
) {
    fs.mkdirs(layout.gitDir)
    fs.mkdirs(layout.objectsDir)
    fs.mkdirs("${layout.objectsDir}/info")
    fs.mkdirs(layout.packDir)
    fs.mkdirs(layout.hooksDir)
    fs.mkdirs(layout.infoDir)
    fs.mkdirs(layout.refsDir)
    fs.mkdirs(layout.headsDir)

    val store = ObjectStore(layout, fs)
    for ((_, framed) in seed.objects) {
        store.writeFramed(framed)
    }
    for ((idx, pack) in seed.packs.withIndex()) {
        val name = "pack-host-$idx.pack"
        fs.writeBytes("${layout.packDir}/$name", pack)
    }

    val refs = RefStore(layout, fs)
    for ((name, sha) in seed.refs) refs.writeRef(name, sha)
    if (seed.head.startsWith("refs/")) {
        refs.writeHeadSymbolic(seed.head)
    } else {
        refs.writeHeadDetached(seed.head)
    }
    seed.packedRefs?.let { fs.writeBytes(layout.packedRefsFile, it) }
    fs.writeBytes(layout.configFile, (seed.configIni ?: defaultConfig(identity)).encodeToByteArray())
    for ((name, bytes) in seed.hooks) fs.writeBytes("${layout.hooksDir}/$name", bytes)

    // Reconstruct the working tree from HEAD's commit tree.
    val headSha = refs.resolveHead() ?: return
    val commit = store.readCommit(headSha)
    materializeTree(store, fs, layout, commit.tree, "")

    // Write the index to match HEAD so `git status`/`git diff` on the
    // untouched work tree report clean (a real checkout leaves such an index).
    val flat = mutableMapOf<String, FlatFile>()
    collectFlatFromTree(store, commit.tree, "", flat)
    fs.writeBytes(layout.indexFile, encodeIndex(indexFromFlatTree(flat)))
}

/** Walk a tree into a [FlatTree] (path -> bytes + exec bit) for index building. */
private suspend fun collectFlatFromTree(
    store: ObjectStore,
    treeSha: String,
    prefix: String,
    out: MutableMap<String, FlatFile>,
) {
    for (entry in store.readTree(treeSha)) {
        val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
        when (entry.mode) {
            FileMode.TREE -> collectFlatFromTree(store, entry.sha, path, out)
            FileMode.EXECUTABLE -> out[path] = FlatFile(store.readBlob(entry.sha), executable = true)
            else -> out[path] = FlatFile(store.readBlob(entry.sha), executable = false)
        }
    }
}

private suspend fun materializeTree(
    store: ObjectStore,
    fs: FileSystem,
    layout: RepoLayout,
    treeSha: String,
    prefix: String,
) {
    val tree = store.readTree(treeSha)
    for (entry in tree) {
        val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
        when (entry.mode) {
            com.accucodeai.kash.tools.git.plumbing.FileMode.TREE -> {
                materializeTree(store, fs, layout, entry.sha, path)
            }

            else -> {
                val bytes = store.readBlob(entry.sha)
                val abs = if (layout.workTree == "/") "/$path" else "${layout.workTree}/$path"
                val parent = abs.substringBeforeLast('/')
                if (parent.isNotEmpty()) fs.mkdirs(parent)
                val mode =
                    if (entry.mode == com.accucodeai.kash.tools.git.plumbing.FileMode.EXECUTABLE) {
                        0b111_101_101
                    } else {
                        0b110_100_100
                    }
                fs.writeBytes(abs, bytes, mode = mode)
            }
        }
    }
}

private fun defaultConfig(identity: GitIdentity): String =
    buildString {
        appendLine("[core]")
        appendLine("\trepositoryformatversion = 0")
        appendLine("\tfilemode = true")
        appendLine("\tbare = false")
        appendLine("\tlogallrefupdates = true")
        appendLine("[user]")
        appendLine("\tname = ${identity.name}")
        appendLine("\temail = ${identity.email}")
    }

@Suppress("UnusedReceiverParameter", "unused")
private fun ByteArray.deflated(): ByteArray = zlibDeflate(this)

/**
 * Copy every local `refs/heads/<name>` to `refs/remotes/origin/<name>`
 * after a fresh materialization. Acts as the snapshot of "what
 * upstream looked like when the session started" — `git push origin`
 * uses these as the cutoff for "which commits are new."
 */
private suspend fun writeOriginTrackingRefs(
    layout: RepoLayout,
    fs: FileSystem,
) {
    val refs = RefStore(layout, fs)
    for ((name, sha) in refs.listRefs("refs/heads")) {
        val branchName = name.removePrefix("refs/heads/")
        refs.writeRef("refs/remotes/origin/$branchName", sha)
    }
}
