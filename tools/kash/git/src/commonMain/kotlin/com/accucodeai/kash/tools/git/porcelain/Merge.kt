package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.IndexEntry
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.MergeResult
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.encodeCommit
import com.accucodeai.kash.tools.git.plumbing.isAncestor
import com.accucodeai.kash.tools.git.plumbing.mergeBase
import com.accucodeai.kash.tools.git.plumbing.mergeLines

/**
 * `git merge` — fold the supplied branch's history into the current
 * one. v1 surface:
 *  - Fast-forward when `HEAD` is an ancestor of [theirs]. Just advance
 *    the current branch (no merge commit).
 *  - 3-way merge otherwise: compute the merge base, run per-file
 *    [mergeLines] for paths that diverged, write the result into the
 *    work tree + index, write `.git/MERGE_HEAD`/`MERGE_MSG`/`ORIG_HEAD`.
 *  - On clean merge: auto-create the merge commit (unless
 *    `--no-commit`).
 *  - On conflicts: leave the repo in the half-merged state; the LLM
 *    resolves and runs `git commit` (which reads `MERGE_HEAD` and
 *    writes a two-parent merge commit).
 *  - `git merge --abort`: restore `ORIG_HEAD`, clear merge state.
 */
public fun gitMergeSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "merge"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }

            var noCommit = false
            var abort = false
            var continueAfter = false
            var customMsg: String? = null
            val positional = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--abort" -> {
                        abort = true
                        i++
                    }

                    a == "--continue" -> {
                        continueAfter = true
                        i++
                    }

                    a == "--no-commit" -> {
                        noCommit = true
                        i++
                    }

                    a == "-m" || a == "--message" -> {
                        customMsg = args.getOrNull(i + 1)
                        i += 2
                    }

                    a.startsWith("--message=") -> {
                        customMsg = a.substringAfter("=")
                        i++
                    }

                    a == "--" -> {
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git merge: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                        i++
                    }
                }
            }

            if (abort) return doAbort(repo, ctx)
            if (continueAfter) {
                // commit -m '...' against MERGE_HEAD does the rest.
                ctx.stderr.writeUtf8(
                    "merge --continue: stage your resolution and run 'git commit' to finalize\n",
                )
                return CommandResult(exitCode = 1)
            }

            if (positional.size != 1) {
                ctx.stderr.writeUtf8("git merge: expected one branch/revspec argument\n")
                return CommandResult(exitCode = 129)
            }
            val theirsSpec = positional[0]
            val theirsSha =
                resolveRev(repo, theirsSpec) ?: run {
                    ctx.stderr.writeUtf8("merge: $theirsSpec - not something we can merge\n")
                    return CommandResult(exitCode = 1)
                }
            val oursSha =
                repo.refs.resolveHead() ?: run {
                    ctx.stderr.writeUtf8("fatal: refusing to merge unrelated histories with no HEAD\n")
                    return CommandResult(exitCode = 128)
                }
            if (oursSha == theirsSha) {
                ctx.stdout.writeUtf8("Already up to date.\n")
                return CommandResult(exitCode = 0)
            }
            if (isAncestor(repo, oursSha, theirsSha)) {
                // Fast-forward.
                return doFastForward(repo, ctx, oursSha, theirsSha, theirsSpec)
            }
            if (isAncestor(repo, theirsSha, oursSha)) {
                ctx.stdout.writeUtf8("Already up to date.\n")
                return CommandResult(exitCode = 0)
            }

            val baseSha = mergeBase(repo, oursSha, theirsSha)
            if (baseSha == null) {
                ctx.stderr.writeUtf8("fatal: refusing to merge unrelated histories\n")
                return CommandResult(exitCode = 128)
            }

            return do3WayMerge(repo, ctx, theirsSpec, oursSha, theirsSha, baseSha, noCommit, customMsg)
        }
    }

private suspend fun doFastForward(
    repo: GitRepo,
    ctx: CommandContext,
    oursSha: String,
    theirsSha: String,
    theirsLabel: String,
): CommandResult {
    val head = repo.refs.readHead()
    val branchRef =
        when (head) {
            is RefStore.Head.Symbolic -> head.target
            else -> "HEAD"
        }

    // Replace the working tree + index with theirs' tree.
    val theirsCommit = repo.objects.readCommit(theirsSha)
    val newFlat = flatFromTree(repo, theirsCommit.tree)
    materializeIntoWorkTree(repo, ctx.fs, newFlat)
    repo.writeIndex(IndexFile(version = 2, entries = newFlat.values.toList()))

    val now = nowReflogTime(ctx)
    val ffMsg = "merge $theirsLabel: Fast-forward"
    if (head is RefStore.Head.Symbolic) {
        repo.refs.writeRef(branchRef, theirsSha)
        recordReflog(repo, branchRef, oursSha, theirsSha, null, now.first, now.second, ffMsg)
    } else {
        repo.refs.writeHeadDetached(theirsSha)
        recordReflog(repo, "HEAD", oursSha, theirsSha, null, now.first, now.second, ffMsg)
    }
    ctx.stdout.writeUtf8(
        "Updating ${oursSha.substring(0, 7)}..${theirsSha.substring(0, 7)}\nFast-forward\n",
    )
    return CommandResult(exitCode = 0)
}

private suspend fun do3WayMerge(
    repo: GitRepo,
    ctx: CommandContext,
    theirsLabel: String,
    oursSha: String,
    theirsSha: String,
    baseSha: String,
    noCommit: Boolean,
    customMsg: String?,
): CommandResult {
    val baseTree = repo.objects.readCommit(baseSha).tree
    val oursTree = repo.objects.readCommit(oursSha).tree
    val theirsTree = repo.objects.readCommit(theirsSha).tree

    val baseFlat = flatFromTree(repo, baseTree)
    val oursFlat = flatFromTree(repo, oursTree)
    val theirsFlat = flatFromTree(repo, theirsTree)

    val paths = (baseFlat.keys + oursFlat.keys + theirsFlat.keys).sorted()
    val newIndex = mutableListOf<IndexEntry>()
    val conflictPaths = mutableListOf<String>()

    // Read diff3 style off the repo config.
    val useDiff3 = readDiff3Style(repo)

    for (p in paths) {
        val b = baseFlat[p]
        val o = oursFlat[p]
        val t = theirsFlat[p]
        when {
            o == null && t == null -> {}

            // both deleted
            o == null && b == null -> {
                // added only on theirs → take it
                newIndex += t!!
                writeWorkFile(repo, ctx.fs, p, t)
            }

            t == null && b == null -> {
                // added only on ours → keep it
                newIndex += o!!
                writeWorkFile(repo, ctx.fs, p, o)
            }

            o != null && t != null && o.sha == t.sha && o.mode == t.mode -> {
                newIndex += o
                writeWorkFile(repo, ctx.fs, p, o)
            }

            b != null && t == null && o != null && o.sha == b.sha -> {
                // theirs deleted, ours unchanged → take deletion
                val abs = absOf(repo.layout.workTree, p)
                if (ctx.fs.exists(abs)) ctx.fs.remove(abs)
            }

            b != null && o == null && t != null && t.sha == b.sha -> {
                // ours deleted, theirs unchanged → keep deletion
            }

            o == null && t != null -> {
                // add/something — ours absent, theirs present, both ≠ base
                emitConflict(
                    repo,
                    ctx.fs,
                    p,
                    baseEntry = b,
                    oursEntry = null,
                    theirsEntry = t,
                    label = "modify/delete",
                    newIndex = newIndex,
                    conflictPaths = conflictPaths,
                    useDiff3 = useDiff3,
                )
            }

            t == null && o != null -> {
                emitConflict(
                    repo,
                    ctx.fs,
                    p,
                    baseEntry = b,
                    oursEntry = o,
                    theirsEntry = null,
                    label = "modify/delete",
                    newIndex = newIndex,
                    conflictPaths = conflictPaths,
                    useDiff3 = useDiff3,
                )
            }

            else -> {
                // Both present and differ. 3-way line merge.
                val baseBytes = if (b == null) ByteArray(0) else repo.objects.readBlob(b.sha)
                val ourBytes = repo.objects.readBlob(o!!.sha)
                val theirBytes = repo.objects.readBlob(t!!.sha)
                if (o.sha == b?.sha) {
                    // ours unchanged from base, theirs changed → take theirs
                    newIndex += t
                    writeWorkFile(repo, ctx.fs, p, t)
                } else if (t.sha == b?.sha) {
                    // theirs unchanged, ours changed → keep ours
                    newIndex += o
                    writeWorkFile(repo, ctx.fs, p, o)
                } else {
                    val result =
                        mergeLines(
                            base = baseBytes.decodeToString().splitLinesKeepIndex(),
                            ours = ourBytes.decodeToString().splitLinesKeepIndex(),
                            theirs = theirBytes.decodeToString().splitLinesKeepIndex(),
                            theirsLabel = theirsLabel,
                            includeBaseInMarkers = useDiff3,
                        )
                    val mergedText =
                        result.lines.joinToString("\n").let {
                            // Reattach a trailing newline if either side ended with one.
                            val anyHadNewline =
                                (ourBytes.isNotEmpty() && ourBytes.last() == '\n'.code.toByte()) ||
                                    (theirBytes.isNotEmpty() && theirBytes.last() == '\n'.code.toByte())
                            if (anyHadNewline && !it.endsWith("\n")) "$it\n" else it
                        }
                    val abs = absOf(repo.layout.workTree, p)
                    val parent = abs.substringBeforeLast('/')
                    if (parent.isNotEmpty()) ctx.fs.mkdirs(parent)
                    ctx.fs.writeBytes(abs, mergedText.encodeToByteArray())

                    when (result) {
                        is MergeResult.Clean -> {
                            // Re-add as a fresh blob.
                            val sha = repo.objects.write(ObjectType.BLOB, mergedText.encodeToByteArray())
                            newIndex +=
                                IndexEntry(
                                    mode = o.mode,
                                    size = mergedText.length,
                                    sha = sha,
                                    path = p,
                                )
                        }

                        is MergeResult.Conflict -> {
                            // Stage 1=base, 2=ours, 3=theirs entries.
                            if (b != null) newIndex += b.copy(stage = 1)
                            newIndex += o.copy(stage = 2)
                            newIndex += t.copy(stage = 3)
                            conflictPaths += p
                        }
                    }
                }
            }
        }
    }

    repo.writeIndex(IndexFile(version = 2, entries = newIndex))

    if (conflictPaths.isEmpty()) {
        // Clean merge.
        if (noCommit) {
            writeMergeState(repo, ctx.fs, oursSha, theirsSha, theirsLabel, customMsg)
            ctx.stdout.writeUtf8(
                "Automatic merge went well; stopped before committing as requested\n",
            )
            return CommandResult(exitCode = 0)
        }
        // Create the merge commit ourselves.
        val person = nowPerson(ctx)
        val mergeMsg = customMsg ?: "Merge $theirsLabel into ${currentBranchName(repo)}"
        val newTreeSha = writeTreeFromIndex(repo, repo.readIndex())
        val commit =
            CommitPayload(
                tree = newTreeSha,
                parents = listOf(oursSha, theirsSha),
                author = person,
                committer = person,
                message = if (mergeMsg.endsWith("\n")) mergeMsg else "$mergeMsg\n",
            )
        val sha = repo.objects.write(ObjectType.COMMIT, encodeCommit(commit))
        val branchRef =
            when (val h = repo.refs.readHead()) {
                is RefStore.Head.Symbolic -> h.target
                else -> "HEAD"
            }
        val now = nowReflogTime(ctx)
        val mergeReflog = "merge $theirsLabel: Merge made by the 'recursive' strategy."
        if (branchRef != "HEAD") {
            repo.refs.writeRef(branchRef, sha)
            recordReflog(repo, branchRef, oursSha, sha, null, now.first, now.second, mergeReflog)
        } else {
            repo.refs.writeHeadDetached(sha)
            recordReflog(repo, "HEAD", oursSha, sha, null, now.first, now.second, mergeReflog)
        }
        ctx.stdout.writeUtf8("Merge made by the 'recursive' strategy.\n")
        return CommandResult(exitCode = 0)
    }

    // Conflicts — pause the merge.
    writeMergeState(repo, ctx.fs, oursSha, theirsSha, theirsLabel, customMsg)
    ctx.stderr.writeUtf8("Auto-merging in progress; ${conflictPaths.size} conflict(s)\n")
    for (p in conflictPaths) {
        ctx.stderr.writeUtf8("CONFLICT (content): Merge conflict in $p\n")
    }
    ctx.stderr.writeUtf8("Automatic merge failed; fix conflicts and then commit the result.\n")
    return CommandResult(exitCode = 1)
}

private suspend fun emitConflict(
    repo: GitRepo,
    fs: FileSystem,
    path: String,
    baseEntry: IndexEntry?,
    oursEntry: IndexEntry?,
    theirsEntry: IndexEntry?,
    label: String,
    newIndex: MutableList<IndexEntry>,
    conflictPaths: MutableList<String>,
    useDiff3: Boolean,
) {
    // Just stage the conflicting versions; for modify/delete, write
    // the surviving side into the work tree with conflict markers.
    if (baseEntry != null) newIndex += baseEntry.copy(stage = 1)
    if (oursEntry != null) newIndex += oursEntry.copy(stage = 2)
    if (theirsEntry != null) newIndex += theirsEntry.copy(stage = 3)
    val survivor = oursEntry ?: theirsEntry
    if (survivor != null) {
        val bytes = repo.objects.readBlob(survivor.sha)
        val abs = absOf(repo.layout.workTree, path)
        val parent = abs.substringBeforeLast('/')
        if (parent.isNotEmpty()) fs.mkdirs(parent)
        fs.writeBytes(abs, bytes)
    }
    conflictPaths += path
}

private suspend fun writeMergeState(
    repo: GitRepo,
    fs: FileSystem,
    oursSha: String,
    theirsSha: String,
    theirsLabel: String,
    customMsg: String?,
) {
    fs.mkdirs(repo.layout.gitDir)
    fs.writeBytes("${repo.layout.gitDir}/ORIG_HEAD", "$oursSha\n".encodeToByteArray())
    fs.writeBytes("${repo.layout.gitDir}/MERGE_HEAD", "$theirsSha\n".encodeToByteArray())
    val msg = customMsg ?: "Merge $theirsLabel"
    fs.writeBytes(
        "${repo.layout.gitDir}/MERGE_MSG",
        if (msg.endsWith("\n")) msg.encodeToByteArray() else "$msg\n".encodeToByteArray(),
    )
    fs.writeBytes("${repo.layout.gitDir}/MERGE_MODE", ByteArray(0))
}

private suspend fun doAbort(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    val origHeadPath = "${repo.layout.gitDir}/ORIG_HEAD"
    if (!ctx.fs.exists(origHeadPath)) {
        ctx.stderr.writeUtf8("fatal: There is no merge to abort (ORIG_HEAD missing).\n")
        return CommandResult(exitCode = 128)
    }
    val origHead =
        ctx.fs
            .readBytes(origHeadPath)
            .decodeToString()
            .trim()
    // Reset --hard to ORIG_HEAD.
    val commit = repo.objects.readCommit(origHead)
    val flat = flatFromTree(repo, commit.tree)
    materializeIntoWorkTree(repo, ctx.fs, flat)
    repo.writeIndex(IndexFile(version = 2, entries = flat.values.toList()))
    val prior = repo.refs.resolveHead()
    val now = nowReflogTime(ctx)
    val msg = "merge: aborted"
    when (val h = repo.refs.readHead()) {
        is RefStore.Head.Symbolic -> {
            repo.refs.writeRef(h.target, origHead)
            recordReflog(repo, h.target, prior, origHead, null, now.first, now.second, msg)
        }

        else -> {
            repo.refs.writeHeadDetached(origHead)
            recordReflog(repo, "HEAD", prior, origHead, null, now.first, now.second, msg)
        }
    }
    // Clear merge state files.
    for (name in listOf("MERGE_HEAD", "MERGE_MSG", "MERGE_MODE", "ORIG_HEAD")) {
        val p = "${repo.layout.gitDir}/$name"
        if (ctx.fs.exists(p)) ctx.fs.remove(p)
    }
    return CommandResult(exitCode = 0)
}

private suspend fun currentBranchName(repo: GitRepo): String =
    when (val h = repo.refs.readHead()) {
        is RefStore.Head.Symbolic -> h.target.removePrefix("refs/heads/")
        else -> "HEAD"
    }

private suspend fun readDiff3Style(repo: GitRepo): Boolean {
    if (!repo.fs.exists(repo.layout.configFile)) return false
    val text = repo.fs.readBytes(repo.layout.configFile).decodeToString()
    return text.contains(Regex("conflictStyle\\s*=\\s*diff3"))
}

private fun absOf(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"

private suspend fun flatFromTree(
    repo: GitRepo,
    treeSha: String,
): MutableMap<String, IndexEntry> {
    val out = mutableMapOf<String, IndexEntry>()
    walkAsEntries2(repo, treeSha, "", out)
    return out
}

private suspend fun walkAsEntries2(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
    out: MutableMap<String, IndexEntry>,
) {
    for (e in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        when (e.mode) {
            FileMode.TREE -> {
                walkAsEntries2(repo, e.sha, p, out)
            }

            else -> {
                val bytes = repo.objects.readBlob(e.sha)
                out[p] = IndexEntry(mode = e.mode, size = bytes.size, sha = e.sha, path = p)
            }
        }
    }
}

private suspend fun materializeIntoWorkTree(
    repo: GitRepo,
    fs: FileSystem,
    flat: Map<String, IndexEntry>,
) {
    for ((p, e) in flat) {
        writeWorkFile(repo, fs, p, e)
    }
}

private suspend fun writeWorkFile(
    repo: GitRepo,
    fs: FileSystem,
    path: String,
    entry: IndexEntry,
) {
    val bytes = repo.objects.readBlob(entry.sha)
    val abs = absOf(repo.layout.workTree, path)
    val parent = abs.substringBeforeLast('/')
    if (parent.isNotEmpty()) fs.mkdirs(parent)
    val mode =
        if (entry.mode == FileMode.EXECUTABLE) 0b111_101_101 else 0b110_100_100
    fs.writeBytes(abs, bytes, mode = mode)
}

private fun String.splitLinesKeepIndex(): List<String> {
    if (isEmpty()) return emptyList()
    val body = if (endsWith("\n")) substring(0, length - 1) else this
    return body.split('\n')
}
