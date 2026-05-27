package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.IndexEntry
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.MergeResult
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.encodeCommit
import com.accucodeai.kash.tools.git.plumbing.mergeLines

/**
 * `git stash` — save the current work-tree state and revert to HEAD.
 *
 * v1 surface:
 *  - `git stash` / `git stash push [-m <msg>]`: save the current work
 *    tree + index, restore to HEAD's content.
 *  - `git stash list`: print stash entries (one per line).
 *  - `git stash pop`: apply the most recent stash and drop it.
 *  - `git stash drop`: drop the most recent stash without applying.
 *  - `git stash show`: summary of the most recent stash.
 *
 * Real git represents the stash as a merge commit referencing the
 * worktree state, HEAD, and the index. We use a simpler shape: one
 * commit per stash whose tree is the worktree snapshot, parent =
 * HEAD. Multiple stashes form a chain (each new stash's parent is the
 * previous stash). `refs/stash` points at the tip; `refs/stash@{N}` is
 * not currently spelled but reachable by walking parents.
 */
public fun gitStashSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "stash"

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

            val sub = args.firstOrNull() ?: "push"
            val rest = args.drop(if (args.firstOrNull() in setOf("push", "pop", "list", "drop", "show")) 1 else 0)
            return when (sub) {
                "push", "save" -> stashPush(repo, ctx, rest)
                "pop" -> stashPop(repo, ctx, drop = true)
                "apply" -> stashPop(repo, ctx, drop = false)
                "drop" -> stashDrop(repo, ctx)
                "list" -> stashList(repo, ctx)
                "show" -> stashShow(repo, ctx)
                else -> stashPush(repo, ctx, args.toList())
            }
        }
    }

private suspend fun stashPush(
    repo: GitRepo,
    ctx: CommandContext,
    args: List<String>,
): CommandResult {
    var msg: String? = null
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "-m" || a == "--message" -> {
                msg = args.getOrNull(i + 1)
                i += 2
            }

            a.startsWith("--message=") -> {
                msg = a.substringAfter("=")
                i++
            }

            else -> {
                i++
            }
        }
    }
    val headSha =
        repo.refs.resolveHead() ?: run {
            ctx.stderr.writeUtf8("fatal: nothing to stash; no HEAD yet\n")
            return CommandResult(exitCode = 128)
        }
    val headTreeSha = repo.objects.readCommit(headSha).tree

    // Snapshot the work tree as a tree object.
    val workEntries = mutableMapOf<String, IndexEntry>()
    walkWorkForStash(repo, ctx.fs, repo.layout.workTree, "", workEntries)
    val workTreeSha = treeFromEntries(repo, workEntries)
    if (workTreeSha == headTreeSha) {
        ctx.stdout.writeUtf8("No local changes to save\n")
        return CommandResult(exitCode = 0)
    }

    val person = nowPerson(ctx)
    val branch =
        when (val h = repo.refs.readHead()) {
            is com.accucodeai.kash.tools.git.plumbing.RefStore.Head.Symbolic -> {
                h.target.removePrefix("refs/heads/")
            }

            else -> {
                "HEAD"
            }
        }
    val finalMsg =
        msg ?: "WIP on $branch: ${headSha.substring(0, 7)} ${
            repo.objects.readCommit(headSha).message.substringBefore('\n')
        }"
    val parent =
        listOf(headSha) +
            (repo.refs.resolve("refs/stash")?.let { listOf(it) } ?: emptyList())
    val stashCommit =
        CommitPayload(
            tree = workTreeSha,
            parents = parent,
            author = person,
            committer = person,
            message = if (finalMsg.endsWith("\n")) finalMsg else "$finalMsg\n",
        )
    val stashSha = repo.objects.write(ObjectType.COMMIT, encodeCommit(stashCommit))
    val priorStash = parent.getOrNull(1)
    repo.refs.writeRef("refs/stash", stashSha)
    val now = nowReflogTime(ctx)
    recordReflog(
        repo,
        "refs/stash",
        priorStash,
        stashSha,
        null,
        now.first,
        now.second,
        finalMsg.substringBefore('\n'),
    )

    // Restore working tree to HEAD.
    val headFlat = flatFromTreeStash(repo, headTreeSha)
    // Remove tracked-but-not-in-HEAD files we created locally.
    val currentIndex = repo.readIndex()
    for (e in currentIndex.entries) {
        if (e.path !in headFlat) {
            val abs = absOfStash(repo.layout.workTree, e.path)
            if (ctx.fs.exists(abs)) ctx.fs.remove(abs)
        }
    }
    materializeIntoWorkTreeStash(repo, ctx.fs, headFlat)
    repo.writeIndex(IndexFile(version = 2, entries = headFlat.values.toList()))

    ctx.stdout.writeUtf8("Saved working directory and index state $finalMsg\n")
    return CommandResult(exitCode = 0)
}

private suspend fun stashPop(
    repo: GitRepo,
    ctx: CommandContext,
    drop: Boolean,
): CommandResult {
    val tip =
        repo.refs.resolve("refs/stash") ?: run {
            ctx.stderr.writeUtf8("error: No stash entries found.\n")
            return CommandResult(exitCode = 1)
        }
    // Apply: 3-way merge with the stash's parent[0] (head-at-stash-time) as
    // base, current HEAD as ours, stash tree as theirs.
    val stashCommit = repo.objects.readCommit(tip)
    val stashTree = stashCommit.tree
    val baseSha = stashCommit.parents.firstOrNull() ?: error("malformed stash commit")
    val baseTree = repo.objects.readCommit(baseSha).tree
    val ourSha = repo.refs.resolveHead() ?: error("no HEAD")
    val ourTree = repo.objects.readCommit(ourSha).tree

    val baseFlat = flatFromTreeStash(repo, baseTree)
    val ourFlat = flatFromTreeStash(repo, ourTree)
    val stashFlat = flatFromTreeStash(repo, stashTree)
    val newIndex = mutableListOf<IndexEntry>()
    val conflicts = mutableListOf<String>()
    val paths = (baseFlat.keys + ourFlat.keys + stashFlat.keys).sorted()
    for (p in paths) {
        val b = baseFlat[p]
        val o = ourFlat[p]
        val t = stashFlat[p]
        when {
            t == null && o == null -> {}

            t != null && o == null && b == null -> {
                newIndex += t
                writeWorkFileStash(repo, ctx.fs, p, t)
            }

            o != null && t == null -> {
                newIndex += o
                writeWorkFileStash(repo, ctx.fs, p, o)
            }

            o != null && t != null && o.sha == t.sha -> {
                newIndex += t
                writeWorkFileStash(repo, ctx.fs, p, t)
            }

            else -> {
                val baseBytes = if (b == null) ByteArray(0) else repo.objects.readBlob(b.sha)
                val ourBytes = if (o == null) ByteArray(0) else repo.objects.readBlob(o.sha)
                val stashBytes = if (t == null) ByteArray(0) else repo.objects.readBlob(t.sha)
                val merged =
                    mergeLines(
                        base = baseBytes.decodeToString().splitLinesKeepIndexStash(),
                        ours = ourBytes.decodeToString().splitLinesKeepIndexStash(),
                        theirs = stashBytes.decodeToString().splitLinesKeepIndexStash(),
                        theirsLabel = "stash",
                    )
                val text =
                    merged.lines.joinToString("\n").let {
                        if ((
                                ourBytes.lastOrNull() == '\n'.code.toByte() ||
                                    stashBytes.lastOrNull() == '\n'.code.toByte()
                            ) && !it.endsWith("\n")
                        ) {
                            "$it\n"
                        } else {
                            it
                        }
                    }
                val abs = absOfStash(repo.layout.workTree, p)
                val parent = abs.substringBeforeLast('/')
                if (parent.isNotEmpty()) ctx.fs.mkdirs(parent)
                ctx.fs.writeBytes(abs, text.encodeToByteArray())
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
                        conflicts += p
                    }
                }
            }
        }
    }
    repo.writeIndex(IndexFile(version = 2, entries = newIndex))
    if (conflicts.isNotEmpty()) {
        ctx.stderr.writeUtf8("error: conflicts applying stash; not dropping\n")
        for (p in conflicts) ctx.stderr.writeUtf8("CONFLICT (content): conflict in $p\n")
        return CommandResult(exitCode = 1)
    }
    if (drop) {
        // Move refs/stash to its parent[1] (the next stash, if any).
        val next = stashCommit.parents.getOrNull(1)
        if (next != null) {
            repo.refs.writeRef("refs/stash", next)
        } else {
            val path = repo.layout.refFile("refs/stash")
            if (ctx.fs.exists(path)) ctx.fs.remove(path)
        }
        ctx.stdout.writeUtf8("Dropped refs/stash (${tip.substring(0, 7)})\n")
    }
    return CommandResult(exitCode = 0)
}

private suspend fun stashDrop(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    val tip = repo.refs.resolve("refs/stash") ?: return CommandResult(exitCode = 1)
    val next =
        repo.objects
            .readCommit(tip)
            .parents
            .getOrNull(1)
    if (next != null) {
        repo.refs.writeRef("refs/stash", next)
    } else {
        val path = repo.layout.refFile("refs/stash")
        if (ctx.fs.exists(path)) ctx.fs.remove(path)
    }
    ctx.stdout.writeUtf8("Dropped refs/stash (${tip.substring(0, 7)})\n")
    return CommandResult(exitCode = 0)
}

private suspend fun stashList(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    var cur = repo.refs.resolve("refs/stash")
    var i = 0
    while (cur != null) {
        val c = repo.objects.readCommit(cur)
        val short = cur.substring(0, 7)
        val first = c.message.substringBefore('\n')
        ctx.stdout.writeUtf8("stash@{$i}: $first ($short)\n")
        cur = c.parents.getOrNull(1)
        i++
    }
    return CommandResult(exitCode = 0)
}

private suspend fun stashShow(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    val tip =
        repo.refs.resolve("refs/stash") ?: run {
            ctx.stderr.writeUtf8("error: No stash entries found.\n")
            return CommandResult(exitCode = 1)
        }
    val c = repo.objects.readCommit(tip)
    ctx.stdout.writeUtf8("${c.message.substringBefore('\n')}\n")
    return CommandResult(exitCode = 0)
}

private suspend fun walkWorkForStash(
    repo: GitRepo,
    fs: com.accucodeai.kash.fs.FileSystem,
    abs: String,
    rel: String,
    out: MutableMap<String, IndexEntry>,
) {
    for (name in fs.list(abs)) {
        if (name == ".git") continue
        val sub = if (abs.endsWith("/")) "$abs$name" else "$abs/$name"
        val subRel = if (rel.isEmpty()) name else "$rel/$name"
        if (fs.isDirectory(sub)) {
            walkWorkForStash(repo, fs, sub, subRel, out)
        } else {
            val bytes = fs.readBytes(sub)
            val sha = repo.objects.write(ObjectType.BLOB, bytes)
            out[subRel] =
                IndexEntry(
                    mode = FileMode.REGULAR,
                    size = bytes.size,
                    sha = sha,
                    path = subRel,
                )
        }
    }
}

private suspend fun treeFromEntries(
    repo: GitRepo,
    entries: Map<String, IndexEntry>,
): String = writeTreeFromIndex(repo, IndexFile(version = 2, entries = entries.values.toList()))

private suspend fun flatFromTreeStash(
    repo: GitRepo,
    treeSha: String,
): MutableMap<String, IndexEntry> {
    val out = mutableMapOf<String, IndexEntry>()
    walkStash(repo, treeSha, "", out)
    return out
}

private suspend fun walkStash(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
    out: MutableMap<String, IndexEntry>,
) {
    for (e in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        when (e.mode) {
            FileMode.TREE -> {
                walkStash(repo, e.sha, p, out)
            }

            else -> {
                val bytes = repo.objects.readBlob(e.sha)
                out[p] = IndexEntry(mode = e.mode, size = bytes.size, sha = e.sha, path = p)
            }
        }
    }
}

private suspend fun materializeIntoWorkTreeStash(
    repo: GitRepo,
    fs: com.accucodeai.kash.fs.FileSystem,
    flat: Map<String, IndexEntry>,
) {
    for ((p, e) in flat) writeWorkFileStash(repo, fs, p, e)
}

private suspend fun writeWorkFileStash(
    repo: GitRepo,
    fs: com.accucodeai.kash.fs.FileSystem,
    path: String,
    entry: IndexEntry,
) {
    val bytes = repo.objects.readBlob(entry.sha)
    val abs = absOfStash(repo.layout.workTree, path)
    val parent = abs.substringBeforeLast('/')
    if (parent.isNotEmpty()) fs.mkdirs(parent)
    val mode = if (entry.mode == FileMode.EXECUTABLE) 0b111_101_101 else 0b110_100_100
    fs.writeBytes(abs, bytes, mode = mode)
}

private fun absOfStash(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"

private fun String.splitLinesKeepIndexStash(): List<String> {
    if (isEmpty()) return emptyList()
    val body = if (endsWith("\n")) substring(0, length - 1) else this
    return body.split('\n')
}
