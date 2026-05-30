package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.DiffStatEntry
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.IndexEntry
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.MergeResult
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.diffStat
import com.accucodeai.kash.tools.git.plumbing.encodeCommit
import com.accucodeai.kash.tools.git.plumbing.mergeLines
import com.accucodeai.kash.tools.git.plumbing.renderStat
import com.accucodeai.kash.tools.git.plumbing.unifiedDiff

/**
 * `git stash` — save the current work-tree state and revert to HEAD.
 *
 * Surface:
 *  - `git stash` / `git stash push [-m <msg>] [-u|-a]`: save the current
 *    work tree + index, restore to HEAD's content. `-u` also stashes
 *    untracked files; `-a` additionally stashes ignored files.
 *  - `git stash list`: print stash entries, one per reflog entry.
 *  - `git stash show [-p] [<stash>]`: diffstat (or patch with `-p`).
 *  - `git stash pop [--index] [<stash>]`: apply + drop.
 *  - `git stash apply [--index] [<stash>]`: apply, keep entry.
 *  - `git stash drop [<stash>]`: drop one entry (default `stash@{0}`).
 *  - `git stash clear`: drop all entries.
 *  - `git stash branch <name> [<stash>]`: branch at the stash base,
 *    apply the stash, drop it.
 *
 * Structure (matches real git so its tooling can read our DB): the
 * stash commit `w` has tree = worktree snapshot and parents
 * `[HEAD, i, (u)]` where `i` is a commit whose tree is the index
 * snapshot and `u` (optional) is a commit whose tree holds the
 * untracked/ignored files. Enumeration of `stash@{N}` is driven by the
 * `refs/stash` reflog exactly as real git does — `refs/stash` itself
 * points at the most-recent entry (`stash@{0}`).
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

            val known =
                setOf("push", "save", "pop", "apply", "list", "drop", "show", "clear", "branch", "create", "store")
            val sub = args.firstOrNull()?.takeIf { it in known } ?: "push"
            val rest = if (args.firstOrNull() in known) args.drop(1) else args.toList()
            return when (sub) {
                "push", "save" -> stashPush(repo, ctx, rest)
                "pop" -> stashApply(repo, ctx, rest, drop = true)
                "apply" -> stashApply(repo, ctx, rest, drop = false)
                "drop" -> stashDrop(repo, ctx, rest)
                "clear" -> stashClear(repo, ctx)
                "list" -> stashList(repo, ctx)
                "show" -> stashShow(repo, ctx, rest)
                "branch" -> stashBranch(repo, ctx, rest)
                else -> stashPush(repo, ctx, rest)
            }
        }
    }

// --- stash entry addressing ---------------------------------------------

/**
 * One stash entry: its index [n] (0 = most recent), the commit [sha] of
 * the `w` (worktree) commit, and the reflog [message].
 */
private data class StashEntry(
    val n: Int,
    val sha: String,
    val message: String,
)

/**
 * Enumerate stash entries newest-first (matching `stash@{N}`). We read
 * the `refs/stash` reflog; entry N is the (count-1-N)th reflog line.
 */
private suspend fun stashEntries(repo: GitRepo): List<StashEntry> {
    if (repo.refs.resolve("refs/stash") == null) return emptyList()
    val log = repo.reflog.read("refs/stash")
    val out = mutableListOf<StashEntry>()
    var n = 0
    for (i in log.indices.reversed()) {
        out += StashEntry(n, log[i].newSha, log[i].message)
        n++
    }
    return out
}

/** Parse a stash spec like `stash@{2}`, `2`, or null (= 0). */
private fun parseStashIndex(spec: String?): Int? {
    if (spec == null) return 0
    val m = Regex("""^stash@\{(\d+)}$""").find(spec)
    if (m != null) return m.groupValues[1].toIntOrNull()
    if (spec.startsWith("stash@{")) return null
    return spec.toIntOrNull() ?: 0.takeIf { spec.isEmpty() }
}

private suspend fun resolveStash(
    repo: GitRepo,
    spec: String?,
): StashEntry? {
    val idx = parseStashIndex(spec) ?: return null
    return stashEntries(repo).firstOrNull { it.n == idx }
}

// --- push ----------------------------------------------------------------

private suspend fun stashPush(
    repo: GitRepo,
    ctx: CommandContext,
    args: List<String>,
): CommandResult {
    var msg: String? = null
    var includeUntracked = false
    var includeIgnored = false
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

            a == "-u" || a == "--include-untracked" -> {
                includeUntracked = true
                i++
            }

            a == "-a" || a == "--all" -> {
                includeUntracked = true
                includeIgnored = true
                i++
            }

            a == "--" -> {
                i = args.size
            }

            else -> {
                i++
            }
        }
    }
    val headSha =
        repo.refs.resolveHead() ?: run {
            ctx.stderr.writeUtf8("fatal: You do not have the initial commit yet\n")
            return CommandResult(exitCode = 128)
        }
    val headTreeSha = repo.objects.readCommit(headSha).tree
    val headFlat = flatFromTreeStash(repo, headTreeSha)

    // Snapshot the index (staged state) and the worktree.
    val currentIndex = repo.readIndex()
    val indexFlat = currentIndex.entries.filter { it.stage == 0 }.associateBy { it.path }
    val indexTreeSha = writeTreeFromIndex(repo, IndexFile(version = 2, entries = indexFlat.values.toList()))

    // Worktree snapshot: every tracked path's on-disk content, plus
    // untracked files when requested.
    val tracked = (indexFlat.keys + headFlat.keys)
    val workEntries = mutableMapOf<String, IndexEntry>()
    val untrackedEntries = mutableMapOf<String, IndexEntry>()
    snapshotWork(repo, ctx, "", tracked, includeUntracked, includeIgnored, workEntries, untrackedEntries)
    val workTreeSha = treeFromEntries(repo, workEntries)

    val nothingTracked = workTreeSha == headTreeSha && indexTreeSha == headTreeSha
    if (nothingTracked && untrackedEntries.isEmpty()) {
        ctx.stdout.writeUtf8("No local changes to save\n")
        return CommandResult(exitCode = 0)
    }

    val person = nowPerson(ctx)
    val branch =
        when (val h = repo.refs.readHead()) {
            is RefStore.Head.Symbolic -> h.target.removePrefix("refs/heads/")
            else -> "(no branch)"
        }
    val headSubject =
        repo.objects
            .readCommit(headSha)
            .message
            .substringBefore('\n')
    val autoMsg = "WIP on $branch: ${headSha.substring(0, 7)} $headSubject"
    val reflogMsg = if (msg != null) "On $branch: $msg" else autoMsg

    // Index commit `i` (parent = HEAD).
    val indexCommit =
        repo.objects.write(
            ObjectType.COMMIT,
            encodeCommit(
                CommitPayload(
                    tree = indexTreeSha,
                    parents = listOf(headSha),
                    author = person,
                    committer = person,
                    message = "index on $branch: ${headSha.substring(0, 7)} $headSubject\n",
                ),
            ),
        )

    val parents = mutableListOf(headSha, indexCommit)
    // Untracked commit `u` (no parent).
    if (untrackedEntries.isNotEmpty()) {
        val uTree = treeFromEntries(repo, untrackedEntries)
        val uCommit =
            repo.objects.write(
                ObjectType.COMMIT,
                encodeCommit(
                    CommitPayload(
                        tree = uTree,
                        parents = emptyList(),
                        author = person,
                        committer = person,
                        message = "untracked files on $branch: ${headSha.substring(0, 7)} $headSubject\n",
                    ),
                ),
            )
        parents += uCommit
    }

    val stashCommit =
        repo.objects.write(
            ObjectType.COMMIT,
            encodeCommit(
                CommitPayload(
                    tree = workTreeSha,
                    parents = parents,
                    author = person,
                    committer = person,
                    message = "$reflogMsg\n",
                ),
            ),
        )

    val prior = repo.refs.resolve("refs/stash")
    repo.refs.writeRef("refs/stash", stashCommit)
    val now = nowReflogTime(ctx)
    recordReflog(repo, "refs/stash", prior, stashCommit, null, now.first, now.second, reflogMsg)

    // Restore working tree + index to HEAD; remove untracked files we stashed.
    val cur = repo.readIndex()
    for (e in cur.entries) {
        if (e.path !in headFlat) {
            val abs = absOfStash(repo.layout.workTree, e.path)
            if (ctx.fs.exists(abs)) ctx.fs.remove(abs)
        }
    }
    if (includeUntracked) {
        for (p in untrackedEntries.keys) {
            val abs = absOfStash(repo.layout.workTree, p)
            if (ctx.fs.exists(abs)) ctx.fs.remove(abs)
        }
    }
    materializeIntoWorkTreeStash(repo, ctx.fs, headFlat)
    repo.writeIndex(IndexFile(version = 2, entries = headFlat.values.toList()))

    ctx.stdout.writeUtf8("Saved working directory and index state $reflogMsg\n")
    return CommandResult(exitCode = 0)
}

// --- apply / pop ---------------------------------------------------------

private suspend fun stashApply(
    repo: GitRepo,
    ctx: CommandContext,
    args: List<String>,
    drop: Boolean,
): CommandResult {
    var restoreIndex = false
    var spec: String? = null
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--index" -> {
                restoreIndex = true
            }

            a == "-q" || a == "--quiet" -> {}

            a == "--" -> {}

            a.startsWith("-") -> {}

            else -> {
                spec = a
            }
        }
        i++
    }

    val entry =
        resolveStash(repo, spec) ?: run {
            if (spec != null) {
                ctx.stderr.writeUtf8("error: $spec is not a valid reference\n")
            } else {
                ctx.stderr.writeUtf8("No stash entries found.\n")
            }
            return CommandResult(exitCode = 1)
        }

    val stashCommit = repo.objects.readCommit(entry.sha)
    val stashTree = stashCommit.tree
    val baseSha = stashCommit.parents.firstOrNull() ?: error("malformed stash commit")
    val baseTree = repo.objects.readCommit(baseSha).tree
    val ourSha = repo.refs.resolveHead() ?: error("no HEAD")
    val ourTree = repo.objects.readCommit(ourSha).tree

    val baseFlat = flatFromTreeStash(repo, baseTree)
    val ourFlat = flatFromTreeStash(repo, ourTree)
    val stashFlat = flatFromTreeStash(repo, stashTree)

    // 3-way merge worktree files.
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

    // Restore untracked files (parent[2]'s tree), if present.
    val untrackedParent = stashCommit.parents.getOrNull(2)
    if (untrackedParent != null) {
        val uTree = repo.objects.readCommit(untrackedParent).tree
        val uFlat = flatFromTreeStash(repo, uTree)
        for ((p, e) in uFlat) writeWorkFileStash(repo, ctx.fs, p, e)
    }

    if (conflicts.isNotEmpty()) {
        repo.writeIndex(IndexFile(version = 2, entries = newIndex))
        for (p in conflicts) ctx.stderr.writeUtf8("CONFLICT (content): Merge conflict in $p\n")
        ctx.stderr.writeUtf8("The stash entry is kept in case you need it again.\n")
        return CommandResult(exitCode = 1)
    }

    // --index: also restore the staged state from parent[1] (index commit).
    if (restoreIndex) {
        val indexParent = stashCommit.parents.getOrNull(1)
        if (indexParent != null) {
            val iTree = repo.objects.readCommit(indexParent).tree
            // Only treat as a meaningful index if it differs from base
            // (otherwise nothing was staged at stash time).
            if (iTree != baseTree) {
                val iFlat = flatFromTreeStash(repo, iTree)
                val merged = newIndex.associateBy { it.path }.toMutableMap()
                for ((p, e) in iFlat) merged[p] = e
                repo.writeIndex(IndexFile(version = 2, entries = merged.values.toList()))
            } else {
                repo.writeIndex(IndexFile(version = 2, entries = newIndex))
            }
        } else {
            repo.writeIndex(IndexFile(version = 2, entries = newIndex))
        }
    } else {
        repo.writeIndex(IndexFile(version = 2, entries = newIndex))
    }

    if (drop) {
        dropEntry(repo, ctx, entry.n)
    }
    return CommandResult(exitCode = 0)
}

// --- drop / clear --------------------------------------------------------

private suspend fun stashDrop(
    repo: GitRepo,
    ctx: CommandContext,
    args: List<String>,
): CommandResult {
    val spec = args.firstOrNull { !it.startsWith("-") }
    val entry =
        resolveStash(repo, spec) ?: run {
            if (spec != null && parseStashIndex(spec) == null) {
                ctx.stderr.writeUtf8("error: $spec is not a valid reference\n")
            } else {
                ctx.stderr.writeUtf8("No stash entries found.\n")
            }
            return CommandResult(exitCode = 1)
        }
    dropEntry(repo, ctx, entry.n)
    return CommandResult(exitCode = 0)
}

/**
 * Drop entry [n] (0 = newest): remove its reflog line, renumber the
 * rest, and either repoint or delete `refs/stash`. Prints
 * `Dropped stash@{n} (<sha>)`.
 */
private suspend fun dropEntry(
    repo: GitRepo,
    ctx: CommandContext,
    n: Int,
) {
    val entries = stashEntries(repo)
    val target = entries.firstOrNull { it.n == n } ?: return
    val log = repo.reflog.read("refs/stash")
    // Reflog line for stash@{n} is at log index (size-1-n).
    val lineIdx = log.size - 1 - n
    if (lineIdx < 0 || lineIdx >= log.size) return
    val remaining = log.toMutableList().also { it.removeAt(lineIdx) }
    if (remaining.isEmpty()) {
        // No stashes left: delete ref + reflog.
        val refPath = repo.layout.refFile("refs/stash")
        if (ctx.fs.exists(refPath)) ctx.fs.remove(refPath)
        val logPath = "${repo.layout.logsDir}/refs/stash"
        if (ctx.fs.exists(logPath)) ctx.fs.remove(logPath)
    } else {
        writeStashReflog(repo, ctx, remaining)
        // refs/stash points at the newest remaining entry.
        repo.refs.writeRef("refs/stash", remaining.last().newSha)
    }
    ctx.stdout.writeUtf8("Dropped stash@{$n} (${target.sha})\n")
}

/** Rewrite the `refs/stash` reflog file from [entries] (chronological). */
private suspend fun writeStashReflog(
    repo: GitRepo,
    ctx: CommandContext,
    entries: List<com.accucodeai.kash.tools.git.plumbing.ReflogStore.Entry>,
) {
    val sb = StringBuilder()
    for (e in entries) {
        sb.append(e.oldSha.padStart(40, '0'))
        sb.append(' ')
        sb.append(e.newSha.padStart(40, '0'))
        sb.append(' ')
        sb.append(e.name)
        sb.append(' ')
        sb.append('<').append(e.email).append('>')
        sb.append(' ')
        sb.append(e.whenSeconds)
        sb.append(' ')
        sb.append(e.tz)
        sb.append('\t')
        sb.append(e.message.trimEnd('\n'))
        sb.append('\n')
    }
    val path = "${repo.layout.logsDir}/refs/stash"
    val parent = path.substringBeforeLast('/')
    if (!ctx.fs.exists(parent)) ctx.fs.mkdirs(parent)
    ctx.fs.writeBytes(path, sb.toString().encodeToByteArray())
}

private suspend fun stashClear(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    val refPath = repo.layout.refFile("refs/stash")
    if (ctx.fs.exists(refPath)) ctx.fs.remove(refPath)
    val logPath = "${repo.layout.logsDir}/refs/stash"
    if (ctx.fs.exists(logPath)) ctx.fs.remove(logPath)
    return CommandResult(exitCode = 0)
}

// --- list ----------------------------------------------------------------

private suspend fun stashList(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    for (e in stashEntries(repo)) {
        ctx.stdout.writeUtf8("stash@{${e.n}}: ${e.message}\n")
    }
    return CommandResult(exitCode = 0)
}

// --- show ----------------------------------------------------------------

private suspend fun stashShow(
    repo: GitRepo,
    ctx: CommandContext,
    args: List<String>,
): CommandResult {
    var patch = false
    var spec: String? = null
    for (a in args) {
        when {
            a == "-p" || a == "--patch" || a == "-u" || a == "--include-untracked" -> {
                patch =
                    patch || (a == "-p" || a == "--patch")
            }

            a == "--stat" -> {}

            a.startsWith("-") -> {}

            else -> {
                spec = a
            }
        }
    }
    val entry =
        resolveStash(repo, spec) ?: run {
            ctx.stderr.writeUtf8("No stash entries found.\n")
            return CommandResult(exitCode = 1)
        }
    val stashCommit = repo.objects.readCommit(entry.sha)
    val baseSha = stashCommit.parents.firstOrNull() ?: error("malformed stash commit")
    val baseTree = repo.objects.readCommit(baseSha).tree
    val stashTree = stashCommit.tree
    val baseFlat = flatFromTreeStash(repo, baseTree)
    val stashFlat = flatFromTreeStash(repo, stashTree)
    val paths = (baseFlat.keys + stashFlat.keys).sorted()

    if (patch) {
        val sb = StringBuilder()
        for (p in paths) {
            val o = baseFlat[p]
            val t = stashFlat[p]
            if (o?.sha == t?.sha) continue
            val oldBytes = if (o == null) ByteArray(0) else repo.objects.readBlob(o.sha)
            val newBytes = if (t == null) ByteArray(0) else repo.objects.readBlob(t.sha)
            sb.append("diff --git a/$p b/$p\n")
            if (o == null) sb.append("new file mode 100644\n")
            if (t == null) sb.append("deleted file mode 100644\n")
            val oldLabel = if (o == null) "/dev/null" else "a/$p"
            val newLabel = if (t == null) "/dev/null" else "b/$p"
            sb.append(unifiedDiff(oldBytes, newBytes, oldLabel, newLabel))
        }
        ctx.stdout.writeUtf8(sb.toString())
        return CommandResult(exitCode = 0)
    }

    // diffstat (default).
    val stats = mutableListOf<DiffStatEntry>()
    for (p in paths) {
        val o = baseFlat[p]
        val t = stashFlat[p]
        if (o?.sha == t?.sha) continue
        val oldBytes = if (o == null) ByteArray(0) else repo.objects.readBlob(o.sha)
        val newBytes = if (t == null) ByteArray(0) else repo.objects.readBlob(t.sha)
        stats += diffStat(oldBytes, newBytes, p)
    }
    ctx.stdout.writeUtf8(renderStat(stats))
    return CommandResult(exitCode = 0)
}

// --- branch --------------------------------------------------------------

private suspend fun stashBranch(
    repo: GitRepo,
    ctx: CommandContext,
    args: List<String>,
): CommandResult {
    val positional = args.filter { !it.startsWith("-") }
    val branchName =
        positional.firstOrNull() ?: run {
            ctx.stderr.writeUtf8("fatal: No branch name specified\n")
            return CommandResult(exitCode = 128)
        }
    val spec = positional.getOrNull(1)
    val entry =
        resolveStash(repo, spec) ?: run {
            ctx.stderr.writeUtf8("No stash entries found.\n")
            return CommandResult(exitCode = 1)
        }
    val stashCommit = repo.objects.readCommit(entry.sha)
    val baseSha = stashCommit.parents.firstOrNull() ?: error("malformed stash commit")

    // Create the branch at the stash base and check it out.
    val ref = "refs/heads/$branchName"
    if (repo.refs.resolve(ref) != null) {
        ctx.stderr.writeUtf8("fatal: a branch named '$branchName' already exists\n")
        return CommandResult(exitCode = 128)
    }
    repo.refs.writeRef(ref, baseSha)
    repo.refs.writeHeadSymbolic(ref)
    val now = nowReflogTime(ctx)
    recordReflog(repo, ref, null, baseSha, null, now.first, now.second, "branch: Created from ${entry.sha}")

    // Materialize the base tree into the worktree + index.
    val baseTree = repo.objects.readCommit(baseSha).tree
    val baseFlat = flatFromTreeStash(repo, baseTree)
    materializeIntoWorkTreeStash(repo, ctx.fs, baseFlat)
    repo.writeIndex(IndexFile(version = 2, entries = baseFlat.values.toList()))

    ctx.stdout.writeUtf8("Switched to a new branch '$branchName'\n")

    // Apply the stash (with --index) and drop it.
    return stashApply(repo, ctx, listOf("--index", "stash@{${entry.n}}"), drop = true)
}

// --- worktree snapshot helpers ------------------------------------------

private suspend fun snapshotWork(
    repo: GitRepo,
    ctx: CommandContext,
    rel: String,
    tracked: Set<String>,
    includeUntracked: Boolean,
    includeIgnored: Boolean,
    workOut: MutableMap<String, IndexEntry>,
    untrackedOut: MutableMap<String, IndexEntry>,
) {
    val abs = if (rel.isEmpty()) repo.layout.workTree else absOfStash(repo.layout.workTree, rel)
    for (name in ctx.fs.list(abs)) {
        if (name == ".git") continue
        val subRel = if (rel.isEmpty()) name else "$rel/$name"
        val subAbs = absOfStash(repo.layout.workTree, subRel)
        if (ctx.fs.isDirectory(subAbs)) {
            snapshotWork(repo, ctx, subRel, tracked, includeUntracked, includeIgnored, workOut, untrackedOut)
        } else {
            val isTracked = subRel in tracked
            if (isTracked) {
                val bytes = ctx.fs.readBytes(subAbs)
                val sha = repo.objects.write(ObjectType.BLOB, bytes)
                workOut[subRel] = IndexEntry(mode = FileMode.REGULAR, size = bytes.size, sha = sha, path = subRel)
            } else if (includeUntracked) {
                // We don't parse .gitignore here; -a vs -u differ only by
                // ignored files, which we treat the same in this VFS.
                val bytes = ctx.fs.readBytes(subAbs)
                val sha = repo.objects.write(ObjectType.BLOB, bytes)
                untrackedOut[subRel] = IndexEntry(mode = FileMode.REGULAR, size = bytes.size, sha = sha, path = subRel)
            }
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
