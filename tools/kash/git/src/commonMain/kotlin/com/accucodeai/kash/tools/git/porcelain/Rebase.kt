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
import com.accucodeai.kash.tools.git.plumbing.mergeBase
import com.accucodeai.kash.tools.git.plumbing.mergeLines

/**
 * `git rebase <upstream>` — replay every commit on the current branch
 * that's not in [upstream] onto [upstream]'s tip. v1 supports:
 *  - linear rebase only (no merge commits in the rebased range).
 *  - conflict pause: writes `.git/rebase-merge/{onto,head-name,
 *    git-rebase-todo,done,stopped-sha}` plus the standard
 *    `MERGE_HEAD`/`MERGE_MSG`. The LLM resolves and runs `git rebase
 *    --continue`.
 *  - `--abort`: restore `ORIG_HEAD`, clear `rebase-merge/`.
 *
 * Not supported: `--interactive`, `--onto <newbase>`, merge commits
 * in the rebased range, `--exec`. The mechanism is in place — these
 * are scope decisions for the next milestone.
 */
public fun gitRebaseSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "rebase"

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

            var abort = false
            var continueAfter = false
            var skip = false
            var autosquash = false
            val positional = mutableListOf<String>()
            for (a in args) {
                when {
                    a == "--abort" -> {
                        abort = true
                    }

                    a == "--continue" -> {
                        continueAfter = true
                    }

                    a == "--skip" -> {
                        skip = true
                    }

                    a == "--autosquash" || a == "-i" -> {
                        // -i is accepted for ergonomics but our autosquash
                        // is the only reorder we perform; we don't open
                        // an editor.
                        autosquash = true
                    }

                    a == "--" -> {}

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git rebase: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                    }
                }
            }

            return when {
                abort -> {
                    doRebaseAbort(repo, ctx)
                }

                continueAfter -> {
                    doRebaseContinue(repo, ctx)
                }

                skip -> {
                    doRebaseSkip(repo, ctx)
                }

                positional.isEmpty() -> {
                    ctx.stderr.writeUtf8("git rebase: <upstream> required\n")
                    CommandResult(exitCode = 129)
                }

                else -> {
                    doRebaseStart(repo, ctx, positional[0], autosquash)
                }
            }
        }
    }

private suspend fun doRebaseStart(
    repo: GitRepo,
    ctx: CommandContext,
    upstreamSpec: String,
    autosquash: Boolean = false,
): CommandResult {
    val upstreamSha =
        resolveRev(repo, upstreamSpec) ?: run {
            ctx.stderr.writeUtf8("fatal: invalid upstream '$upstreamSpec'\n")
            return CommandResult(exitCode = 128)
        }
    val ourSha =
        repo.refs.resolveHead() ?: run {
            ctx.stderr.writeUtf8("fatal: no HEAD to rebase\n")
            return CommandResult(exitCode = 128)
        }
    val base = mergeBase(repo, ourSha, upstreamSha)
    if (base == null) {
        ctx.stderr.writeUtf8("fatal: cannot find merge base with $upstreamSpec\n")
        return CommandResult(exitCode = 128)
    }
    if (base == ourSha) {
        // Nothing to rebase; fast-forward to upstream.
        return rebaseFastForward(repo, ctx, ourSha, upstreamSha)
    }
    if (base == upstreamSha && !autosquash) {
        // Our history already builds on upstream. Without --autosquash
        // there's no work to do; with it, we still want to reorder.
        ctx.stdout.writeUtf8("Current branch is up to date.\n")
        return CommandResult(exitCode = 0)
    }

    // Collect commits to replay: walk from ourSha to base, exclusive of base, first-parent only.
    val shas = mutableListOf<String>()
    var cur: String? = ourSha
    while (cur != null && cur != base) {
        shas += cur
        cur =
            repo.objects
                .readCommit(cur)
                .parents
                .firstOrNull()
    }
    shas.reverse() // oldest first

    val todo =
        if (autosquash) {
            autosquashReorder(repo, shas)
        } else {
            shas.map { "pick" to it }
        }

    val headRef = (repo.refs.readHead() as? RefStore.Head.Symbolic)?.target ?: "HEAD"
    writeRebaseState(repo, ctx.fs, headRef, ourSha, upstreamSha, todo)
    // Move HEAD to upstream (the "onto") and start picking.
    detachToCommit(repo, ctx, upstreamSha)
    return executeTodo(repo, ctx)
}

private suspend fun doRebaseContinue(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    if (!ctx.fs.exists("${repo.layout.gitDir}/rebase-merge")) {
        ctx.stderr.writeUtf8("fatal: No rebase in progress?\n")
        return CommandResult(exitCode = 128)
    }
    val index = repo.readIndex()
    if (index.entries.any { it.stage != 0 }) {
        ctx.stderr.writeUtf8(
            "error: cannot rebase: you have unstaged changes. (resolve and run `git add` first)\n",
        )
        return CommandResult(exitCode = 1)
    }
    // Build the commit for the stopped sha using its original message.
    val stopped =
        readRebaseFile(repo, ctx.fs, "stopped-sha") ?: run {
            ctx.stderr.writeUtf8("fatal: no stopped-sha in rebase-merge\n")
            return CommandResult(exitCode = 128)
        }
    val originalCommit = repo.objects.readCommit(stopped)
    val person = nowPerson(ctx)
    val newTreeSha = writeTreeFromIndex(repo, repo.readIndex())
    val parent = repo.refs.resolveHead() ?: error("no HEAD during rebase --continue")
    val msg = originalCommit.message
    val newCommit =
        CommitPayload(
            tree = newTreeSha,
            parents = listOf(parent),
            author = originalCommit.author,
            committer = person,
            message = if (msg.endsWith("\n")) msg else "$msg\n",
        )
    val newSha = repo.objects.write(ObjectType.COMMIT, encodeCommit(newCommit))
    repo.refs.writeHeadDetached(newSha)
    // Clear MERGE_HEAD/MERGE_MSG (rebase pause flagged them).
    for (n in listOf("MERGE_HEAD", "MERGE_MSG", "MERGE_MODE")) {
        val p = "${repo.layout.gitDir}/$n"
        if (ctx.fs.exists(p)) ctx.fs.remove(p)
    }
    // Append to `done`, return to picking the rest.
    appendDone(repo, ctx.fs, stopped)
    return executeTodo(repo, ctx)
}

private suspend fun doRebaseSkip(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    if (!ctx.fs.exists("${repo.layout.gitDir}/rebase-merge")) {
        ctx.stderr.writeUtf8("fatal: No rebase in progress?\n")
        return CommandResult(exitCode = 128)
    }
    val stopped = readRebaseFile(repo, ctx.fs, "stopped-sha")
    for (n in listOf("MERGE_HEAD", "MERGE_MSG", "MERGE_MODE")) {
        val p = "${repo.layout.gitDir}/$n"
        if (ctx.fs.exists(p)) ctx.fs.remove(p)
    }
    if (stopped != null) appendDone(repo, ctx.fs, stopped)
    return executeTodo(repo, ctx)
}

private suspend fun doRebaseAbort(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    val rebaseDir = "${repo.layout.gitDir}/rebase-merge"
    if (!ctx.fs.exists(rebaseDir)) {
        ctx.stderr.writeUtf8("fatal: No rebase in progress?\n")
        return CommandResult(exitCode = 128)
    }
    val origHead =
        readRebaseFile(repo, ctx.fs, "orig-head") ?: run {
            ctx.stderr.writeUtf8("fatal: rebase-merge/orig-head missing\n")
            return CommandResult(exitCode = 128)
        }
    val headName = readRebaseFile(repo, ctx.fs, "head-name") ?: "HEAD"
    // Reset --hard to orig-head, restore HEAD ref.
    val commit = repo.objects.readCommit(origHead)
    val flat = flatFromTreeForRebase(repo, commit.tree)
    materializeIntoWorkTreeForRebase(repo, ctx.fs, flat)
    repo.writeIndex(IndexFile(version = 2, entries = flat.values.toList()))
    val priorHead = repo.refs.resolveHead()
    val now = nowReflogTime(ctx)
    val abortMsg = "rebase: aborting"
    if (headName.startsWith("refs/")) {
        repo.refs.writeRef(headName, origHead)
        repo.refs.writeHeadSymbolic(headName)
        recordReflog(repo, headName, priorHead, origHead, null, now.first, now.second, abortMsg)
    } else {
        repo.refs.writeHeadDetached(origHead)
        recordReflog(repo, "HEAD", priorHead, origHead, null, now.first, now.second, abortMsg)
    }
    removeRebaseState(repo, ctx.fs)
    return CommandResult(exitCode = 0)
}

private suspend fun executeTodo(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    while (true) {
        val todoText = readRebaseFile(repo, ctx.fs, "git-rebase-todo") ?: return CommandResult(0)
        val remaining = todoText.lineSequence().filter { it.isNotBlank() }.toMutableList()
        if (remaining.isEmpty()) {
            // Finalize: restore symbolic HEAD to the rebased branch.
            val headName = readRebaseFile(repo, ctx.fs, "head-name") ?: "HEAD"
            val origHead = readRebaseFile(repo, ctx.fs, "orig-head")
            val tip = repo.refs.resolveHead() ?: return CommandResult(0)
            if (headName.startsWith("refs/")) {
                val priorBranchSha = repo.refs.resolve(headName)
                repo.refs.writeRef(headName, tip)
                repo.refs.writeHeadSymbolic(headName)
                val now = nowReflogTime(ctx)
                recordReflog(
                    repo,
                    headName,
                    priorBranchSha ?: origHead,
                    tip,
                    null,
                    now.first,
                    now.second,
                    "rebase: finished",
                )
            }
            removeRebaseState(repo, ctx.fs)
            ctx.stdout.writeUtf8("Successfully rebased and updated $headName.\n")
            return CommandResult(exitCode = 0)
        }
        val next = remaining.removeFirst().trim()
        val action = next.substringBefore(' ')
        val sha = next.substringAfter(' ').trim()
        // Write back the trimmed todo before picking, so a conflict pause
        // leaves the right remaining list.
        writeRebaseFile(
            repo,
            ctx.fs,
            "git-rebase-todo",
            remaining.joinToString("\n").let {
                if (it.isEmpty()) {
                    ""
                } else {
                    it +
                        "\n"
                }
            },
        )

        when (val outcome = applyPick(repo, ctx, sha)) {
            PickOutcome.Clean -> {
                if (action == "fixup" || action == "squash") {
                    squashIntoPrevious(repo, ctx, sha, action == "squash")
                }
                appendDone(repo, ctx.fs, sha)
                continue
            }

            PickOutcome.Conflict -> {
                writeRebaseFile(repo, ctx.fs, "stopped-sha", sha + "\n")
                ctx.stderr.writeUtf8("error: could not apply $sha\n")
                ctx.stderr.writeUtf8(
                    "Resolve all conflicts manually, mark them as resolved with \"git add\",\n",
                )
                ctx.stderr.writeUtf8("and then run \"git rebase --continue\".\n")
                return CommandResult(exitCode = 1)
            }

            PickOutcome.NoOp -> {
                appendDone(repo, ctx.fs, sha)
                continue
            }
        }
    }
}

/**
 * After picking a fixup/squash commit onto HEAD, roll its changes
 * into HEAD's parent: build a new commit at HEAD's grandparent with
 * HEAD's tree, point HEAD at it. For `squash`, the new message
 * appends the squashed commit's body (after stripping the `squash! `
 * prefix line). For `fixup`, we keep the parent's message verbatim.
 */
private suspend fun squashIntoPrevious(
    repo: GitRepo,
    ctx: CommandContext,
    @Suppress("UNUSED_PARAMETER") pickedSha: String,
    keepMessage: Boolean,
) {
    val curSha = repo.refs.resolveHead() ?: return
    val cur = repo.objects.readCommit(curSha)
    val prevSha = cur.parents.firstOrNull() ?: return
    val prev = repo.objects.readCommit(prevSha)
    val combinedMsg =
        if (keepMessage) {
            // squash: prev message + the squashed body (drop "squash! " from
            // the first line; keep any subsequent body verbatim).
            val curMsg = cur.message
            val nl = curMsg.indexOf('\n')
            val body =
                if (nl < 0) {
                    ""
                } else {
                    curMsg.substring(nl + 1).trimStart('\n').trimEnd('\n')
                }
            val tail = if (body.isEmpty()) "" else "\n\n$body"
            "${prev.message.trimEnd('\n')}$tail\n"
        } else {
            prev.message
        }
    val newCommit =
        com.accucodeai.kash.tools.git.plumbing.CommitPayload(
            tree = cur.tree,
            parents = prev.parents,
            author = prev.author,
            committer = cur.committer,
            message = combinedMsg,
        )
    val newSha =
        repo.objects.write(
            com.accucodeai.kash.tools.git.plumbing.ObjectType.COMMIT,
            com.accucodeai.kash.tools.git.plumbing
                .encodeCommit(newCommit),
        )
    repo.refs.writeHeadDetached(newSha)
    // Mirror the workTree to the new commit's tree (it's HEAD's tree,
    // which is already on disk from the prior pick, but stay safe).
    val flat = flatFromTreeForRebase(repo, newCommit.tree)
    materializeIntoWorkTreeForRebase(repo, ctx.fs, flat)
    repo.writeIndex(IndexFile(version = 2, entries = flat.values.toList()))
}

private enum class PickOutcome { Clean, Conflict, NoOp }

private suspend fun applyPick(
    repo: GitRepo,
    ctx: CommandContext,
    sha: String,
): PickOutcome {
    val commit = repo.objects.readCommit(sha)
    val parent = commit.parents.firstOrNull()
    val baseTree = parent?.let { repo.objects.readCommit(it).tree }
    val onto = repo.refs.resolveHead() ?: error("no HEAD during rebase pick")
    val ontoTree = repo.objects.readCommit(onto).tree

    val baseFlat = if (baseTree == null) emptyMap() else flatFromTreeForRebase(repo, baseTree)
    val pickFlat = flatFromTreeForRebase(repo, commit.tree)
    val ontoFlat = flatFromTreeForRebase(repo, ontoTree)

    val newIndex = mutableListOf<IndexEntry>()
    val conflictPaths = mutableListOf<String>()
    val paths = (baseFlat.keys + pickFlat.keys + ontoFlat.keys).sorted()

    for (p in paths) {
        val b = baseFlat[p]
        val o = ontoFlat[p]
        val t = pickFlat[p]
        when {
            o == null && t == null -> {}

            // Nothing changed by the pick (t == b): keep onto as-is.
            t == null && o != null && b != null -> {
                // pick deletes p. If onto matches base, take the deletion.
                if (o.sha == b.sha) {
                    val abs = absOfRebase(repo.layout.workTree, p)
                    if (ctx.fs.exists(abs)) ctx.fs.remove(abs)
                } else {
                    // modify/delete conflict
                    newIndex += b.copy(stage = 1)
                    newIndex += o.copy(stage = 2)
                    conflictPaths += p
                }
            }

            t != null && o != null && t.sha == o.sha -> {
                newIndex += t
                writePickFile(repo, ctx.fs, p, t)
            }

            t != null && b == null && o == null -> {
                // Pick adds a brand new file.
                newIndex += t
                writePickFile(repo, ctx.fs, p, t)
            }

            t != null && b != null && t.sha == b.sha -> {
                // Pick didn't touch p; keep onto's version.
                if (o != null) {
                    newIndex += o
                    writePickFile(repo, ctx.fs, p, o)
                }
            }

            else -> {
                // 3-way merge.
                val baseBytes = if (b == null) ByteArray(0) else repo.objects.readBlob(b.sha)
                val ourBytes = if (o == null) ByteArray(0) else repo.objects.readBlob(o.sha)
                val theirBytes = if (t == null) ByteArray(0) else repo.objects.readBlob(t.sha)
                val merged =
                    mergeLines(
                        base = baseBytes.decodeToString().splitLinesKeepIndexRebase(),
                        ours = ourBytes.decodeToString().splitLinesKeepIndexRebase(),
                        theirs = theirBytes.decodeToString().splitLinesKeepIndexRebase(),
                        theirsLabel = sha.substring(0, 7),
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
                val abs = absOfRebase(repo.layout.workTree, p)
                val parentDir = abs.substringBeforeLast('/')
                if (parentDir.isNotEmpty()) ctx.fs.mkdirs(parentDir)
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
                        conflictPaths += p
                    }
                }
            }
        }
    }

    repo.writeIndex(IndexFile(version = 2, entries = newIndex))

    if (conflictPaths.isNotEmpty()) {
        // Flag MERGE_HEAD so status/commit recognize the conflict state.
        ctx.fs.writeBytes("${repo.layout.gitDir}/MERGE_HEAD", "$sha\n".encodeToByteArray())
        val msgBytes = commit.message.encodeToByteArray()
        ctx.fs.writeBytes("${repo.layout.gitDir}/MERGE_MSG", msgBytes)
        for (p in conflictPaths) ctx.stderr.writeUtf8("CONFLICT (content): conflict in $p\n")
        return PickOutcome.Conflict
    }

    val person = nowPerson(ctx)
    val newTreeSha = writeTreeFromIndex(repo, repo.readIndex())
    val parentSha = repo.refs.resolveHead() ?: error("no HEAD during rebase pick")
    if (newTreeSha == repo.objects.readCommit(parentSha).tree) {
        // No-op pick (would-be empty commit). Skip silently — that's
        // what `git rebase` does by default for empties not introduced
        // by `--keep-empty`.
        return PickOutcome.NoOp
    }
    val newCommit =
        CommitPayload(
            tree = newTreeSha,
            parents = listOf(parentSha),
            author = commit.author,
            committer = person,
            message = if (commit.message.endsWith("\n")) commit.message else commit.message + "\n",
        )
    val newSha = repo.objects.write(ObjectType.COMMIT, encodeCommit(newCommit))
    val priorHead = repo.refs.resolveHead()
    repo.refs.writeHeadDetached(newSha)
    val now = nowReflogTime(ctx)
    recordReflog(
        repo,
        "HEAD",
        priorHead,
        newSha,
        null,
        now.first,
        now.second,
        "rebase: ${commit.message.substringBefore('\n')}",
    )
    return PickOutcome.Clean
}

private suspend fun rebaseFastForward(
    repo: GitRepo,
    ctx: CommandContext,
    ourSha: String,
    upstreamSha: String,
): CommandResult {
    val flat = flatFromTreeForRebase(repo, repo.objects.readCommit(upstreamSha).tree)
    materializeIntoWorkTreeForRebase(repo, ctx.fs, flat)
    repo.writeIndex(IndexFile(version = 2, entries = flat.values.toList()))
    val now = nowReflogTime(ctx)
    val ffMsg = "rebase finished: returning to upstream (fast-forward)"
    when (val h = repo.refs.readHead()) {
        is RefStore.Head.Symbolic -> {
            repo.refs.writeRef(h.target, upstreamSha)
            recordReflog(repo, h.target, ourSha, upstreamSha, null, now.first, now.second, ffMsg)
        }

        else -> {
            repo.refs.writeHeadDetached(upstreamSha)
            recordReflog(repo, "HEAD", ourSha, upstreamSha, null, now.first, now.second, ffMsg)
        }
    }
    ctx.stdout.writeUtf8("Fast-forwarded to $upstreamSha\n")
    return CommandResult(exitCode = 0)
}

private suspend fun writeRebaseState(
    repo: GitRepo,
    fs: FileSystem,
    headName: String,
    origHead: String,
    onto: String,
    todo: List<Pair<String, String>>,
) {
    val dir = "${repo.layout.gitDir}/rebase-merge"
    fs.mkdirs(dir)
    fs.writeBytes("$dir/head-name", "$headName\n".encodeToByteArray())
    fs.writeBytes("$dir/orig-head", "$origHead\n".encodeToByteArray())
    fs.writeBytes("$dir/onto", "$onto\n".encodeToByteArray())
    fs.writeBytes(
        "$dir/git-rebase-todo",
        todo
            .joinToString("\n") { (action, sha) -> "$action $sha" }
            .let { if (it.isEmpty()) "" else "$it\n" }
            .encodeToByteArray(),
    )
    fs.writeBytes("$dir/done", ByteArray(0))
}

/**
 * Reorder a list of commits-to-replay for `--autosquash`. For each
 * commit whose subject starts with `fixup! ` or `squash! `, the
 * suffix is matched against earlier commits' subjects; the
 * fixup/squash commit is moved to be the next entry after its target
 * and tagged with its action. Commits we can't match stay in place
 * with `pick`.
 */
private suspend fun autosquashReorder(
    repo: GitRepo,
    shas: List<String>,
): List<Pair<String, String>> {
    val subjects =
        shas.associateWith {
            repo.objects
                .readCommit(it)
                .message
                .substringBefore('\n')
        }
    val planned = mutableListOf<Pair<String, String>>()
    val deferred = mutableListOf<Pair<String, String>>()
    for (sha in shas) {
        val subj = subjects[sha]!!
        val action =
            when {
                subj.startsWith("fixup! ") -> "fixup" to subj.removePrefix("fixup! ")
                subj.startsWith("squash! ") -> "squash" to subj.removePrefix("squash! ")
                else -> null
            }
        if (action == null) {
            planned += "pick" to sha
            continue
        }
        deferred += action.first to sha
    }
    // Insert each deferred fixup/squash right after its target.
    for ((act, sha) in deferred) {
        val originalSubject = subjects[sha]!!.removePrefix(if (act == "fixup") "fixup! " else "squash! ")
        val idx = planned.indexOfLast { subjects[it.second]?.startsWith(originalSubject) == true }
        if (idx < 0) {
            // No match — fall back to a normal pick.
            planned += "pick" to sha
        } else {
            planned.add(idx + 1, act to sha)
        }
    }
    return planned
}

private suspend fun removeRebaseState(
    repo: GitRepo,
    fs: FileSystem,
) {
    val dir = "${repo.layout.gitDir}/rebase-merge"
    if (!fs.exists(dir)) return
    for (name in fs.list(dir)) {
        fs.remove("$dir/$name")
    }
    fs.remove(dir)
}

private suspend fun readRebaseFile(
    repo: GitRepo,
    fs: FileSystem,
    name: String,
): String? {
    val p = "${repo.layout.gitDir}/rebase-merge/$name"
    if (!fs.exists(p)) return null
    return fs.readBytes(p).decodeToString().trim()
}

private suspend fun writeRebaseFile(
    repo: GitRepo,
    fs: FileSystem,
    name: String,
    contents: String,
) {
    val p = "${repo.layout.gitDir}/rebase-merge/$name"
    fs.writeBytes(p, contents.encodeToByteArray())
}

private suspend fun appendDone(
    repo: GitRepo,
    fs: FileSystem,
    sha: String,
    action: String = "pick",
) {
    val p = "${repo.layout.gitDir}/rebase-merge/done"
    val existing = if (fs.exists(p)) fs.readBytes(p).decodeToString() else ""
    fs.writeBytes(p, (existing + "$action $sha\n").encodeToByteArray())
}

private suspend fun detachToCommit(
    repo: GitRepo,
    ctx: CommandContext,
    sha: String,
) {
    val flat = flatFromTreeForRebase(repo, repo.objects.readCommit(sha).tree)
    materializeIntoWorkTreeForRebase(repo, ctx.fs, flat)
    repo.writeIndex(IndexFile(version = 2, entries = flat.values.toList()))
    repo.refs.writeHeadDetached(sha)
}

private fun absOfRebase(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"

private suspend fun flatFromTreeForRebase(
    repo: GitRepo,
    treeSha: String,
): MutableMap<String, IndexEntry> {
    val out = mutableMapOf<String, IndexEntry>()
    walkRebase(repo, treeSha, "", out)
    return out
}

private suspend fun walkRebase(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
    out: MutableMap<String, IndexEntry>,
) {
    for (e in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        when (e.mode) {
            FileMode.TREE -> {
                walkRebase(repo, e.sha, p, out)
            }

            else -> {
                val bytes = repo.objects.readBlob(e.sha)
                out[p] = IndexEntry(mode = e.mode, size = bytes.size, sha = e.sha, path = p)
            }
        }
    }
}

private suspend fun materializeIntoWorkTreeForRebase(
    repo: GitRepo,
    fs: FileSystem,
    flat: Map<String, IndexEntry>,
) {
    for ((p, e) in flat) writePickFile(repo, fs, p, e)
}

private suspend fun writePickFile(
    repo: GitRepo,
    fs: FileSystem,
    path: String,
    entry: IndexEntry,
) {
    val bytes = repo.objects.readBlob(entry.sha)
    val abs = absOfRebase(repo.layout.workTree, path)
    val parent = abs.substringBeforeLast('/')
    if (parent.isNotEmpty()) fs.mkdirs(parent)
    val mode = if (entry.mode == FileMode.EXECUTABLE) 0b111_101_101 else 0b110_100_100
    fs.writeBytes(abs, bytes, mode = mode)
}

private fun String.splitLinesKeepIndexRebase(): List<String> {
    if (isEmpty()) return emptyList()
    val body = if (endsWith("\n")) substring(0, length - 1) else this
    return body.split('\n')
}
