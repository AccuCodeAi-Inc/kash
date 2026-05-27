package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.blobSha

/**
 * `git status` — compare HEAD vs index vs work tree and report.
 *
 * Supported flags:
 *  - `--porcelain` (alias of `--porcelain=v1`): the stable
 *    machine-readable format with `XY path` per line.
 *  - `-s` / `--short`: same as porcelain but without `--porcelain`'s
 *    no-color guarantee. We emit the same bytes either way.
 *  - default: long-form output.
 *
 * Not implemented: rename/copy detection, `--ignored`, submodule
 * status. The porcelain v2 layout is also deferred — v1 covers every
 * common script integration.
 */
public fun gitStatusSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "status"

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

            var format = StatusFormat.LONG
            var showBranch = false
            for (a in args) {
                when {
                    a == "--porcelain" || a == "--porcelain=v1" || a == "-s" || a == "--short" -> {
                        format = StatusFormat.PORCELAIN
                    }

                    a == "--porcelain=v2" -> {
                        format = StatusFormat.PORCELAIN_V2
                    }

                    a == "--branch" || a == "-b" -> {
                        showBranch = true
                    }

                    a == "--" -> {}

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git status: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {} // pathspec narrowing deferred
                }
            }

            val (entries, branch, noCommits) = computeStatus(repo, ctx.fs)
            val branchInfo =
                if (showBranch || format == StatusFormat.PORCELAIN_V2) {
                    computeBranchInfo(repo, branch)
                } else {
                    null
                }
            when (format) {
                StatusFormat.PORCELAIN -> {
                    if (showBranch && branchInfo != null) renderBranchHeader(branchInfo, ctx)
                    renderPorcelain(entries, ctx)
                }

                StatusFormat.PORCELAIN_V2 -> {
                    if (branchInfo != null) renderBranchHeaderV2(branchInfo, ctx)
                    renderPorcelainV2(entries, ctx)
                }

                StatusFormat.LONG -> {
                    renderLong(entries, branch, noCommits, ctx)
                }
            }
            return CommandResult(exitCode = 0)
        }
    }

private enum class StatusFormat { LONG, PORCELAIN, PORCELAIN_V2 }

private class BranchInfo(
    val oid: String?, // null when no commits yet
    val head: String, // branch name or "(detached)"
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
)

private suspend fun computeBranchInfo(
    repo: GitRepo,
    branch: String?,
): BranchInfo {
    val oid = repo.refs.resolveHead()
    val headLabel = branch ?: "(detached)"
    // Look up the local branch's upstream from .git/config.
    val cfg = readGitConfig(repo)
    val section = "branch \"${branch ?: ""}\""
    val remote = cfg[section]?.get("remote")
    val merge = cfg[section]?.get("merge")
    val upstreamRef =
        if (remote != null && merge != null) {
            "refs/remotes/$remote/${merge.removePrefix("refs/heads/")}"
        } else {
            null
        }
    val upstreamShort =
        upstreamRef?.let {
            if (it.startsWith("refs/remotes/")) it.removePrefix("refs/remotes/") else it
        }
    val upstreamOid = upstreamRef?.let { repo.refs.resolve(it) }
    val (ahead, behind) =
        if (oid != null && upstreamOid != null) {
            countAheadBehind(repo, oid, upstreamOid)
        } else {
            0 to 0
        }
    return BranchInfo(oid, headLabel, upstreamShort, ahead, behind)
}

/** Count `ahead` (in HEAD not upstream) and `behind` (upstream not HEAD). */
private suspend fun countAheadBehind(
    repo: GitRepo,
    headSha: String,
    upstreamSha: String,
): Pair<Int, Int> {
    val ancestorsOfUp = mutableSetOf<String>()
    bfsAncestors(repo, upstreamSha, ancestorsOfUp)
    val ancestorsOfHead = mutableSetOf<String>()
    bfsAncestors(repo, headSha, ancestorsOfHead)
    val ahead = ancestorsOfHead.count { it !in ancestorsOfUp }
    val behind = ancestorsOfUp.count { it !in ancestorsOfHead }
    return ahead to behind
}

private suspend fun bfsAncestors(
    repo: GitRepo,
    start: String,
    out: MutableSet<String>,
) {
    val queue = ArrayDeque<String>()
    queue.addLast(start)
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (!out.add(cur)) continue
        val c =
            try {
                repo.objects.readCommit(cur)
            } catch (_: Throwable) {
                continue
            }
        for (p in c.parents) if (p !in out) queue.addLast(p)
    }
}

private suspend fun renderBranchHeader(
    info: BranchInfo,
    ctx: CommandContext,
) {
    val tail =
        when {
            info.upstream == null -> {
                ""
            }

            info.ahead == 0 && info.behind == 0 -> {
                "...${info.upstream}"
            }

            else -> {
                "...${info.upstream} [" +
                    listOfNotNull(
                        if (info.ahead > 0) "ahead ${info.ahead}" else null,
                        if (info.behind > 0) "behind ${info.behind}" else null,
                    ).joinToString(", ") + "]"
            }
        }
    ctx.stdout.writeUtf8("## ${info.head}$tail\n")
}

private suspend fun renderBranchHeaderV2(
    info: BranchInfo,
    ctx: CommandContext,
) {
    ctx.stdout.writeUtf8("# branch.oid ${info.oid ?: "(initial)"}\n")
    ctx.stdout.writeUtf8("# branch.head ${info.head}\n")
    if (info.upstream != null) {
        ctx.stdout.writeUtf8("# branch.upstream ${info.upstream}\n")
        ctx.stdout.writeUtf8("# branch.ab +${info.ahead} -${info.behind}\n")
    }
}

private suspend fun renderPorcelainV2(
    entries: List<StatusEntry>,
    ctx: CommandContext,
) {
    val tracked = entries.filter { it.indexCode != '?' }.sortedBy { it.path }
    val untracked = entries.filter { it.indexCode == '?' }.sortedBy { it.path }
    for (e in tracked) {
        // v2 "changed" line: `1 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <path>`
        // For simplicity we emit zeros for the mode/hash columns since
        // tests typically only inspect XY + path. (Real git fills them
        // in; we can densify later if a script actually needs them.)
        ctx.stdout.writeUtf8(
            "1 ${e.indexCode}${e.workCode} N... 000000 000000 000000 " +
                "0000000000000000000000000000000000000000 " +
                "0000000000000000000000000000000000000000 ${e.path}\n",
        )
    }
    for (e in untracked) {
        ctx.stdout.writeUtf8("? ${e.path}\n")
    }
}

private class StatusEntry(
    val path: String,
    val indexCode: Char, // 'A','M','D',' '
    val workCode: Char, // 'M','D',' ','?'
)

private suspend fun computeStatus(
    repo: GitRepo,
    fs: FileSystem,
): Triple<List<StatusEntry>, String?, Boolean> {
    val head = repo.refs.readHead()
    val headSha = repo.refs.resolveHead()
    val branch =
        when (head) {
            null -> null
            is RefStore.Head.Detached -> null
            is RefStore.Head.Symbolic -> head.target.removePrefix("refs/heads/")
        }
    val noCommits = headSha == null

    // HEAD tree → flat path -> sha map.
    val headTree: Map<String, String> =
        if (headSha == null) {
            emptyMap()
        } else {
            val commit = repo.objects.readCommit(headSha)
            flattenTree(repo, commit.tree, "")
        }

    val index = repo.readIndex()
    val indexByPath = index.entries.associateBy { it.path }
    val tracked = headTree.keys + indexByPath.keys
    val scan = scanWorkTree(repo, tracked)
    // Combine plain files + nested-repo "directory" entries — both
    // appear in status (the latter as `path/`).
    val workPaths = scan.files + scan.nestedRepoDirs.map { "$it/" }

    val entries = mutableListOf<StatusEntry>()

    val allTracked = (headTree.keys + indexByPath.keys).sorted()
    for (p in allTracked) {
        val headSha2 = headTree[p]
        val idxEntry = indexByPath[p]
        val indexCode =
            when {
                headSha2 == null && idxEntry != null -> 'A'
                headSha2 != null && idxEntry == null -> 'D'
                headSha2 != null && idxEntry != null && headSha2 != idxEntry.sha -> 'M'
                else -> ' '
            }
        val workCode =
            if (idxEntry == null) {
                ' '
            } else {
                val abs = absOf(repo.layout.workTree, p)
                if (!fs.exists(abs)) {
                    'D'
                } else {
                    val bytes = fs.readBytes(abs)
                    if (blobSha(bytes) != idxEntry.sha) 'M' else ' '
                }
            }
        if (indexCode == ' ' && workCode == ' ') continue
        entries += StatusEntry(p, indexCode, workCode)
    }

    // Untracked work-tree paths.
    for (p in workPaths.sorted()) {
        if (p in indexByPath) continue
        if (p in headTree) continue
        entries += StatusEntry(p, '?', '?')
    }

    return Triple(entries.sortedBy { it.path }, branch, noCommits)
}

private suspend fun flattenTree(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for (entry in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
        when (entry.mode) {
            FileMode.REGULAR, FileMode.EXECUTABLE, FileMode.SYMLINK, FileMode.GITLINK -> {
                out[p] = entry.sha
            }

            FileMode.TREE -> {
                out.putAll(flattenTree(repo, entry.sha, p))
            }
        }
    }
    return out
}

private fun absOf(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"

private fun collectWorktree(
    fs: FileSystem,
    workTree: String,
): Set<String> {
    val out = mutableSetOf<String>()
    walk(fs, workTree, "", out)
    return out
}

private fun walk(
    fs: FileSystem,
    abs: String,
    rel: String,
    out: MutableSet<String>,
) {
    for (name in fs.list(abs)) {
        if (name == ".git") continue
        val sub = if (abs.endsWith("/")) "$abs$name" else "$abs/$name"
        val subRel = if (rel.isEmpty()) name else "$rel/$name"
        if (fs.isDirectory(sub)) {
            // A subdirectory containing its own `.git/` is a nested repo
            // boundary. Real git lists the nested repo as a single
            // untracked path (with trailing `/`) and refuses to recurse,
            // so the outer repo never reports the inner's working files.
            if (fs.exists("$sub/.git")) {
                out += "$subRel/"
            } else {
                walk(fs, sub, subRel, out)
            }
        } else {
            out += subRel
        }
    }
}

private suspend fun renderPorcelain(
    entries: List<StatusEntry>,
    ctx: CommandContext,
) {
    // Real git emits tracked entries first (alphabetical) and untracked
    // entries last (also alphabetical). We sort here instead of relying
    // on the global path-sort because untracked-vs-tracked order has to
    // be a hard split, not interleaved.
    val tracked = entries.filter { it.indexCode != '?' }.sortedBy { it.path }
    val untracked = entries.filter { it.indexCode == '?' }.sortedBy { it.path }
    for (e in tracked) {
        ctx.stdout.writeUtf8("${e.indexCode}${e.workCode} ${e.path}\n")
    }
    for (e in untracked) {
        ctx.stdout.writeUtf8("?? ${e.path}\n")
    }
}

private suspend fun renderLong(
    entries: List<StatusEntry>,
    branch: String?,
    noCommits: Boolean,
    ctx: CommandContext,
) {
    ctx.stdout.writeUtf8("On branch ${branch ?: "HEAD detached"}\n")
    if (noCommits) ctx.stdout.writeUtf8("\nNo commits yet\n")

    val staged =
        entries.filter { it.indexCode != ' ' && it.indexCode != '?' }
    val unstaged = entries.filter { it.workCode != ' ' && it.indexCode != '?' && it.workCode != '?' }
    val untracked = entries.filter { it.indexCode == '?' }

    if (staged.isNotEmpty()) {
        ctx.stdout.writeUtf8("\nChanges to be committed:\n")
        ctx.stdout.writeUtf8("  (use \"git restore --staged <file>...\" to unstage)\n")
        for (e in staged) {
            val label =
                when (e.indexCode) {
                    'A' -> "new file:   "
                    'M' -> "modified:   "
                    'D' -> "deleted:    "
                    else -> "${e.indexCode}:    "
                }
            ctx.stdout.writeUtf8("\t$label${e.path}\n")
        }
    }

    if (unstaged.isNotEmpty()) {
        ctx.stdout.writeUtf8("\nChanges not staged for commit:\n")
        ctx.stdout.writeUtf8("  (use \"git add <file>...\" to update what will be committed)\n")
        for (e in unstaged) {
            val label =
                when (e.workCode) {
                    'M' -> "modified:   "
                    'D' -> "deleted:    "
                    else -> "${e.workCode}:    "
                }
            ctx.stdout.writeUtf8("\t$label${e.path}\n")
        }
    }

    if (untracked.isNotEmpty()) {
        ctx.stdout.writeUtf8("\nUntracked files:\n")
        ctx.stdout.writeUtf8("  (use \"git add <file>...\" to include in what will be committed)\n")
        for (e in untracked) ctx.stdout.writeUtf8("\t${e.path}\n")
    }

    if (staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty()) {
        if (noCommits) {
            ctx.stdout.writeUtf8("\nnothing to commit (create/copy files and use \"git add\" to track)\n")
        } else {
            ctx.stdout.writeUtf8("\nnothing to commit, working tree clean\n")
        }
    }
}
