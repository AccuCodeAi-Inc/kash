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
import com.accucodeai.kash.tools.git.plumbing.mergeLines

/**
 * `git cherry-pick <commit>` — apply the diff between `<commit>^` and
 * `<commit>` onto HEAD, producing a new commit with the picked commit's
 * message + author (committer becomes the current user/timestamp).
 *
 * Implemented as a 3-way merge: base = picked-commit's parent's tree,
 * ours = HEAD's tree, theirs = picked-commit's tree. On clean apply we
 * write a new commit and advance HEAD; on conflicts we write
 * `MERGE_HEAD`/`CHERRY_PICK_HEAD` and stage 1/2/3 entries, just like a
 * paused merge or rebase.
 *
 * v1 surface: one commit at a time. `cherry-pick a..b` (range) is
 * deferred.
 */
public fun gitCherryPickSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "cherry-pick"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult = doPickOrRevert(args, ctx, env, revert = false)
    }

/**
 * `git revert <commit>` — produce the inverse of a commit on top of HEAD.
 * Same machinery as cherry-pick with base/theirs swapped: base = the
 * commit's tree, theirs = its parent's tree, ours = HEAD.
 */
public fun gitRevertSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "revert"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult = doPickOrRevert(args, ctx, env, revert = true)
    }

private suspend fun doPickOrRevert(
    args: List<String>,
    ctx: CommandContext,
    env: GitEnv,
    revert: Boolean,
): CommandResult {
    val repo =
        GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
            ?: run {
                ctx.stderr.writeUtf8("fatal: not a git repository\n")
                return CommandResult(exitCode = 128)
            }

    val verb = if (revert) "revert" else "cherry-pick"
    var noCommit = false
    val positional = mutableListOf<String>()
    for (a in args) {
        when {
            a == "--no-commit" || a == "-n" -> {
                noCommit = true
            }

            a == "--" -> {}

            a.startsWith("-") -> {
                ctx.stderr.writeUtf8("git $verb: unsupported option '$a'\n")
                return CommandResult(exitCode = 129)
            }

            else -> {
                positional += a
            }
        }
    }
    if (positional.size != 1) {
        ctx.stderr.writeUtf8("git $verb: exactly one commit required\n")
        return CommandResult(exitCode = 129)
    }
    val targetSha =
        resolveRev(repo, positional[0]) ?: run {
            ctx.stderr.writeUtf8("fatal: bad revision '${positional[0]}'\n")
            return CommandResult(exitCode = 128)
        }
    val targetCommit = repo.objects.readCommit(targetSha)
    val parentSha = targetCommit.parents.firstOrNull()
    if (parentSha == null) {
        ctx.stderr.writeUtf8("fatal: cannot $verb root commit\n")
        return CommandResult(exitCode = 128)
    }

    val ourSha =
        repo.refs.resolveHead() ?: run {
            ctx.stderr.writeUtf8("fatal: $verb requires HEAD to be set\n")
            return CommandResult(exitCode = 128)
        }

    // Set up base / ours / theirs trees.
    val (baseSha, theirsSha) =
        if (revert) targetSha to parentSha else parentSha to targetSha
    val baseTree = repo.objects.readCommit(baseSha).tree
    val theirsTree = repo.objects.readCommit(theirsSha).tree
    val oursTree = repo.objects.readCommit(ourSha).tree

    val newIndex = mutableListOf<IndexEntry>()
    val conflictPaths = mutableListOf<String>()
    threeWayApply(
        repo,
        ctx.fs,
        baseTree,
        oursTree,
        theirsTree,
        revLabel = if (revert) "revert-${targetSha.substring(0, 7)}" else targetSha.substring(0, 7),
        newIndex = newIndex,
        conflictPaths = conflictPaths,
    )

    repo.writeIndex(IndexFile(version = 2, entries = newIndex))

    if (conflictPaths.isNotEmpty()) {
        // Conflict pause. Cherry-pick uses CHERRY_PICK_HEAD; revert uses
        // REVERT_HEAD — real git's standard markers.
        val markerFile = if (revert) "REVERT_HEAD" else "CHERRY_PICK_HEAD"
        ctx.fs.writeBytes("${repo.layout.gitDir}/$markerFile", "$targetSha\n".encodeToByteArray())
        ctx.fs.writeBytes("${repo.layout.gitDir}/MERGE_HEAD", "$targetSha\n".encodeToByteArray())
        val msg =
            if (revert) {
                "Revert \"${targetCommit.message.substringBefore('\n')}\"\n\n" +
                    "This reverts commit $targetSha.\n"
            } else {
                targetCommit.message
            }
        ctx.fs.writeBytes("${repo.layout.gitDir}/MERGE_MSG", msg.encodeToByteArray())
        ctx.stderr.writeUtf8("error: could not apply $targetSha\n")
        for (p in conflictPaths) ctx.stderr.writeUtf8("CONFLICT (content): conflict in $p\n")
        return CommandResult(exitCode = 1)
    }

    if (noCommit) {
        ctx.stdout.writeUtf8("$verb: applied to index (no commit made due to -n)\n")
        return CommandResult(exitCode = 0)
    }

    // Build the new commit.
    val person = nowPerson(ctx)
    val newTreeSha = writeTreeFromIndex(repo, repo.readIndex())
    val parentTreeSha = repo.objects.readCommit(ourSha).tree
    if (newTreeSha == parentTreeSha) {
        ctx.stderr.writeUtf8("error: $verb produced empty change against HEAD\n")
        return CommandResult(exitCode = 1)
    }
    val msg =
        if (revert) {
            "Revert \"${targetCommit.message.substringBefore('\n')}\"\n\n" +
                "This reverts commit $targetSha.\n"
        } else {
            if (targetCommit.message.endsWith("\n")) targetCommit.message else targetCommit.message + "\n"
        }
    val author = if (revert) person else targetCommit.author
    val newCommit =
        CommitPayload(
            tree = newTreeSha,
            parents = listOf(ourSha),
            author = author,
            committer = person,
            message = msg,
        )
    val newSha = repo.objects.write(ObjectType.COMMIT, encodeCommit(newCommit))
    val branchRef =
        when (val h = repo.refs.readHead()) {
            is RefStore.Head.Symbolic -> h.target
            else -> null
        }
    if (branchRef != null) repo.refs.writeRef(branchRef, newSha) else repo.refs.writeHeadDetached(newSha)
    ctx.stdout.writeUtf8(
        "[${branchRef?.removePrefix("refs/heads/") ?: "HEAD detached"} ${newSha.substring(0, 7)}] " +
            "${msg.substringBefore('\n')}\n",
    )
    return CommandResult(exitCode = 0)
}

private suspend fun threeWayApply(
    repo: GitRepo,
    fs: FileSystem,
    baseTreeSha: String,
    oursTreeSha: String,
    theirsTreeSha: String,
    revLabel: String,
    newIndex: MutableList<IndexEntry>,
    conflictPaths: MutableList<String>,
) {
    val baseFlat = flatFromAnyTree(repo, baseTreeSha)
    val oursFlat = flatFromAnyTree(repo, oursTreeSha)
    val theirsFlat = flatFromAnyTree(repo, theirsTreeSha)

    val paths = (baseFlat.keys + oursFlat.keys + theirsFlat.keys).sorted()
    for (p in paths) {
        val b = baseFlat[p]
        val o = oursFlat[p]
        val t = theirsFlat[p]
        when {
            o == null && t == null -> {}

            o != null && t != null && o.sha == t.sha -> {
                newIndex += o
                writeAnyFile(repo, fs, p, o)
            }

            t != null && o == null && b == null -> {
                newIndex += t
                writeAnyFile(repo, fs, p, t)
            }

            t == null && b != null && o != null && o.sha == b.sha -> {
                val abs = absOfAny(repo.layout.workTree, p)
                if (fs.exists(abs)) fs.remove(abs)
            }

            t != null && b != null && t.sha == b.sha -> {
                // No change on theirs side, keep ours.
                if (o != null) {
                    newIndex += o
                    writeAnyFile(repo, fs, p, o)
                }
            }

            t != null && o != null && b != null && o.sha == b.sha -> {
                // Ours unchanged, theirs changed → take theirs.
                newIndex += t
                writeAnyFile(repo, fs, p, t)
            }

            else -> {
                val baseBytes = if (b == null) ByteArray(0) else repo.objects.readBlob(b.sha)
                val ourBytes = if (o == null) ByteArray(0) else repo.objects.readBlob(o.sha)
                val theirBytes = if (t == null) ByteArray(0) else repo.objects.readBlob(t.sha)
                val merged =
                    mergeLines(
                        base = baseBytes.decodeToString().splitLinesKeepIndexAny(),
                        ours = ourBytes.decodeToString().splitLinesKeepIndexAny(),
                        theirs = theirBytes.decodeToString().splitLinesKeepIndexAny(),
                        theirsLabel = revLabel,
                    )
                val text =
                    merged.lines.joinToString("\n").let {
                        if ((
                                ourBytes.lastOrNull() == '\n'.code.toByte() ||
                                    theirBytes.lastOrNull() == '\n'.code.toByte()
                            ) && !it.endsWith("\n")
                        ) {
                            "$it\n"
                        } else {
                            it
                        }
                    }
                val abs = absOfAny(repo.layout.workTree, p)
                val parent = abs.substringBeforeLast('/')
                if (parent.isNotEmpty()) fs.mkdirs(parent)
                fs.writeBytes(abs, text.encodeToByteArray())
                when (merged) {
                    is MergeResult.Clean -> {
                        val newBlob = repo.objects.write(ObjectType.BLOB, text.encodeToByteArray())
                        val mode = (t ?: o)!!.mode
                        newIndex += IndexEntry(mode = mode, size = text.length, sha = newBlob, path = p)
                    }

                    is MergeResult.Conflict -> {
                        if (b != null) newIndex += b.copy(stage = 1)
                        if (o != null) newIndex += o.copy(stage = 2)
                        if (t != null) newIndex += t.copy(stage = 3)
                        conflictPaths += p
                    }
                }
            }
        }
    }
}

private suspend fun flatFromAnyTree(
    repo: GitRepo,
    treeSha: String,
): Map<String, IndexEntry> {
    val out = mutableMapOf<String, IndexEntry>()
    walkAny(repo, treeSha, "", out)
    return out
}

private suspend fun walkAny(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
    out: MutableMap<String, IndexEntry>,
) {
    for (e in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        when (e.mode) {
            FileMode.TREE -> {
                walkAny(repo, e.sha, p, out)
            }

            else -> {
                val bytes = repo.objects.readBlob(e.sha)
                out[p] = IndexEntry(mode = e.mode, size = bytes.size, sha = e.sha, path = p)
            }
        }
    }
}

private suspend fun writeAnyFile(
    repo: GitRepo,
    fs: FileSystem,
    path: String,
    entry: IndexEntry,
) {
    val bytes = repo.objects.readBlob(entry.sha)
    val abs = absOfAny(repo.layout.workTree, path)
    val parent = abs.substringBeforeLast('/')
    if (parent.isNotEmpty()) fs.mkdirs(parent)
    val mode = if (entry.mode == FileMode.EXECUTABLE) 0b111_101_101 else 0b110_100_100
    fs.writeBytes(abs, bytes, mode = mode)
}

private fun absOfAny(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"

private fun String.splitLinesKeepIndexAny(): List<String> {
    if (isEmpty()) return emptyList()
    val body = if (endsWith("\n")) substring(0, length - 1) else this
    return body.split('\n')
}
