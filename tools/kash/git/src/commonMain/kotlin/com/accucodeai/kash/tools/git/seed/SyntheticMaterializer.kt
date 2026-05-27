package com.accucodeai.kash.tools.git.seed

import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.git.SyntheticCommit
import com.accucodeai.kash.api.git.TreeChange
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.FlatFile
import com.accucodeai.kash.tools.git.plumbing.FlatTree
import com.accucodeai.kash.tools.git.plumbing.ObjectStore
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.RepoLayout
import com.accucodeai.kash.tools.git.plumbing.encodeCommit

/**
 * Materialize a [GitRepoSeed.Synthetic] into a real-looking `.git/` plus
 * the working tree onto [fs]. The result is byte-for-byte valid git —
 * `/usr/bin/git fsck` against the extracted directory passes.
 *
 * History semantics:
 *  - "Single-snapshot" mode (the default `SyntheticCommit.singleSnapshot()`
 *    — one entry, no parents, no [TreeChange]s): the commit's tree is
 *    [GitRepoSeed.Synthetic.workTree] directly. One commit, no chain.
 *  - "Multi-commit" mode (anything else): each commit's tree is computed
 *    by applying its [SyntheticCommit.changes] on top of its parent's
 *    tree (or on top of the empty tree if no parents). The HEAD
 *    commit's tree must equal [GitRepoSeed.Synthetic.workTree] — we
 *    enforce so the host can't silently lie about the final state.
 *
 * Commit metadata:
 *  - `author` / `committer` default to [GitRepoSeed.Synthetic.identity]
 *    or the [hostIdentity] fallback (passed by the runtime once the
 *    adapter is known).
 *  - `authorDate` defaults to deterministic backfill dates one second
 *    apart, anchored to [defaultEpochSeconds]. Pinning each commit's
 *    `authorDate` overrides this.
 *  - Timezone is fixed at `+0000` — we don't need to fabricate locale
 *    drift on the host's behalf.
 */
public class SyntheticMaterializer(
    public val layout: RepoLayout,
    public val fs: FileSystem,
    public val hostIdentity: GitIdentity,
    public val defaultEpochSeconds: Long = 1700000000L,
) {
    public suspend fun materialize(seed: GitRepoSeed.Synthetic) {
        val store = ObjectStore(layout, fs)
        val refs = RefStore(layout, fs)
        val identity = seed.identity ?: hostIdentity

        // 1. Compute each tag's tree (FlatTree) and write blobs + tree objects.
        val seedFlat: FlatTree =
            seed.workTree.mapValues { (path, bytes) ->
                FlatFile(bytes, executable = path in seed.executable)
            }
        val treeShaByTag = mutableMapOf<String, String>()
        val commitShaByTag = mutableMapOf<String, String>()

        val isSingleSnapshot =
            seed.history.size == 1 &&
                seed.history[0].parents.isEmpty() &&
                seed.history[0].changes.isEmpty()

        if (isSingleSnapshot) {
            val only = seed.history[0]
            val rootTreeSha = if (seedFlat.isEmpty()) writeEmptyTree(store) else writeTreeFromFlat(store, seedFlat)
            val commitSha =
                writeCommit(store, only, parents = emptyList(), treeSha = rootTreeSha, identity, indexInHistory = 0)
            treeShaByTag[only.tag] = rootTreeSha
            commitShaByTag[only.tag] = commitSha
        } else {
            val treeByTag = mutableMapOf<String, FlatTree>()
            for ((i, commit) in seed.history.withIndex()) {
                require(commit.parents.all { it in commitShaByTag }) {
                    "synthetic commit '${commit.tag}' references unknown parent: ${commit.parents}"
                }
                val baseTree: FlatTree =
                    if (commit.parents.isEmpty()) {
                        emptyMap()
                    } else {
                        // Multi-parent merge: start from the *first* parent's
                        // tree; honoring the merge result is the host's job
                        // (subsequent changes can encode it). This matches
                        // how `git read-tree --merge` defaults.
                        treeByTag[commit.parents.first()] ?: emptyMap()
                    }
                val newTree = applyChanges(baseTree, commit.changes)
                treeByTag[commit.tag] = newTree
                val treeSha = if (newTree.isEmpty()) writeEmptyTree(store) else writeTreeFromFlat(store, newTree)
                treeShaByTag[commit.tag] = treeSha
                val parentShas = commit.parents.map { commitShaByTag.getValue(it) }
                val commitSha = writeCommit(store, commit, parentShas, treeSha, identity, indexInHistory = i)
                commitShaByTag[commit.tag] = commitSha
            }

            // Validate: HEAD commit's tree must match workTree.
            val headRef = seed.head
            val headTag =
                seed.extraRefs[headRef]
                    ?: seed.history.last().tag
            val headTree = treeByTag.getValue(headTag)
            require(headTree.flattenForCompare() == seedFlat.flattenForCompare()) {
                "synthetic seed: HEAD commit '$headTag' tree does not match workTree (the chain doesn't sum to the declared final state)"
            }
        }

        // 2. Write refs.
        val tipTag =
            seed.extraRefs[seed.head]
                ?: seed.history.last().tag
        val tipCommitSha = commitShaByTag.getValue(tipTag)
        if (seed.head.startsWith("refs/")) {
            refs.writeRef(seed.head, tipCommitSha)
            refs.writeHeadSymbolic(seed.head)
        } else {
            refs.writeHeadDetached(tipCommitSha)
        }
        for ((refName, commitTag) in seed.extraRefs) {
            if (refName == seed.head) continue
            refs.writeRef(refName, commitShaByTag.getValue(commitTag))
        }

        // 3. Write the working tree to disk: the final HEAD commit's tree
        //    bytes onto the VFS.
        val headTreeFlat: FlatTree =
            if (isSingleSnapshot) seedFlat else inferFlatFromStore(store, treeShaByTag.getValue(tipTag))
        materializeWorkTree(headTreeFlat)

        // 4. .git/config, hooks, description, info/exclude.
        writeRepoMetadata(seed, identity)
    }

    private suspend fun writeCommit(
        store: ObjectStore,
        spec: SyntheticCommit,
        parents: List<String>,
        treeSha: String,
        adapterIdentity: GitIdentity,
        indexInHistory: Int,
    ): String {
        val id = spec.author ?: adapterIdentity
        val whenSec = spec.authorDate?.epochSeconds ?: (defaultEpochSeconds + indexInHistory.toLong())
        val author = PersonStamp(id.name, id.email, whenSec, "+0000")
        val message = if (spec.message.endsWith('\n') || spec.message.isEmpty()) spec.message else spec.message + "\n"
        val payload =
            CommitPayload(
                tree = treeSha,
                parents = parents,
                author = author,
                committer = author,
                message = if (message.isEmpty()) "Initial commit\n" else message,
            )
        return store.write(ObjectType.COMMIT, encodeCommit(payload))
    }

    private suspend fun writeRepoMetadata(
        seed: GitRepoSeed.Synthetic,
        identity: GitIdentity,
    ) {
        fs.mkdirs(layout.gitDir)
        fs.mkdirs(layout.hooksDir)
        fs.mkdirs(layout.infoDir)
        fs.mkdirs(layout.packDir)

        val config =
            seed.configIni
                ?: defaultConfig(identity, seed.head)
        fs.writeBytes(layout.configFile, config.encodeToByteArray())
        fs.writeBytes(
            layout.descriptionFile,
            "Unnamed repository; edit this file 'description' to name the repository.\n".encodeToByteArray(),
        )
        fs.writeBytes(
            layout.excludeFile,
            (
                "# git ls-files --others --exclude-from=.git/info/exclude\n" +
                    "# Lines that start with '#' are comments.\n" +
                    "# For a project mostly in C, the following would be a good set of\n" +
                    "# exclude patterns (uncomment them if you want to use them):\n" +
                    "# *.[oa]\n" +
                    "# *~\n"
            ).encodeToByteArray(),
        )

        for ((name, bytes) in seed.hooks) {
            fs.writeBytes("${layout.hooksDir}/$name", bytes, mode = 0b111_101_101)
        }
    }

    private fun defaultConfig(
        identity: GitIdentity,
        head: String,
    ): String {
        val initialBranch =
            if (head.startsWith("refs/heads/")) head.removePrefix("refs/heads/") else "main"
        return buildString {
            appendLine("[core]")
            appendLine("\trepositoryformatversion = 0")
            appendLine("\tfilemode = true")
            appendLine("\tbare = false")
            appendLine("\tlogallrefupdates = true")
            appendLine("[user]")
            appendLine("\tname = ${identity.name}")
            appendLine("\temail = ${identity.email}")
            appendLine("[init]")
            appendLine("\tdefaultBranch = $initialBranch")
        }
    }

    private suspend fun materializeWorkTree(flat: FlatTree) {
        fs.mkdirs(layout.workTree)
        for ((path, file) in flat) {
            val full = layout.workPath(path)
            val parent = full.substringBeforeLast('/')
            if (parent.isNotEmpty()) fs.mkdirs(parent)
            val mode = if (file.executable) 0b111_101_101 else 0b110_100_100
            fs.writeBytes(full, file.bytes, mode = mode)
        }
    }

    private suspend fun writeEmptyTree(store: ObjectStore): String = store.write(ObjectType.TREE, ByteArray(0))

    private suspend fun writeTreeFromFlat(
        store: ObjectStore,
        flat: FlatTree,
    ): String =
        com.accucodeai.kash.tools.git.plumbing
            .writeFlatTree(store, flat)

    private suspend fun inferFlatFromStore(
        store: ObjectStore,
        rootTreeSha: String,
    ): FlatTree {
        val out = mutableMapOf<String, FlatFile>()
        walkTree(store, rootTreeSha, "", out)
        return out
    }

    private suspend fun walkTree(
        store: ObjectStore,
        treeSha: String,
        prefix: String,
        out: MutableMap<String, FlatFile>,
    ) {
        for (entry in store.readTree(treeSha)) {
            val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            when (entry.mode) {
                com.accucodeai.kash.tools.git.plumbing.FileMode.REGULAR -> {
                    out[path] = FlatFile(store.readBlob(entry.sha), executable = false)
                }

                com.accucodeai.kash.tools.git.plumbing.FileMode.EXECUTABLE -> {
                    out[path] = FlatFile(store.readBlob(entry.sha), executable = true)
                }

                com.accucodeai.kash.tools.git.plumbing.FileMode.TREE -> {
                    walkTree(store, entry.sha, path, out)
                }

                else -> {
                    error("unsupported tree entry mode at $path: ${entry.mode}")
                }
            }
        }
    }

    private fun applyChanges(
        base: FlatTree,
        changes: List<TreeChange>,
    ): FlatTree {
        val out = base.toMutableMap()
        for (c in changes) {
            when (c) {
                is TreeChange.Write -> {
                    out[c.path] = FlatFile(c.bytes, c.executable)
                }

                is TreeChange.Delete -> {
                    out.remove(c.path)
                }

                is TreeChange.Rename -> {
                    val existing = out.remove(c.from) ?: continue
                    out[c.to] = existing
                }
            }
        }
        return out
    }

    /** ByteArray equality + executable-bit comparison, structured for `==`. */
    private fun FlatTree.flattenForCompare(): Map<String, Pair<Int, List<Byte>>> =
        mapValues { (_, f) -> (if (f.executable) 1 else 0) to f.bytes.toList() }

    private val kotlin.time.Instant?.epochSeconds: Long?
        get() = this?.epochSeconds
}
