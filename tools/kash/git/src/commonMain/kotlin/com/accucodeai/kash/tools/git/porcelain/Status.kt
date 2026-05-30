package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.blobSha
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git status` — compare HEAD vs index vs work tree and report.
 *
 * Supported flags:
 *  - `--porcelain` (alias of `--porcelain=v1`): the stable
 *    machine-readable format with `XY path` per line.
 *  - `--porcelain=v2`: the harder-to-parse v2 layout (we fill XY + path;
 *    the mode/hash columns are stubbed with zeros).
 *  - `-s` / `--short`: same as porcelain v1 but without the no-color
 *    guarantee. We emit the same bytes either way.
 *  - `-b` / `--branch`: prepend the `## branch...upstream` header to the
 *    short/porcelain output.
 *  - `-z`: NUL-terminate (rather than newline-terminate) porcelain records.
 *  - `--ignored[=traditional|matching]`: also list `.gitignore`d paths.
 *  - `--no-renames`: accepted (we never detect renames, so it's a no-op).
 *  - default: long-form output, matched byte-for-byte against real git.
 *
 * Not implemented: rename/copy detection (long + porcelain both treat a
 * rename as a delete + add), submodule status detail.
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
            var nulTerminate = false
            var showIgnored = false
            // Untracked-files mode. kash always enumerates individual untracked
            // files (no cheap "normal"/"all" distinction here), so the only
            // mode that changes output is "no" (suppress untracked).
            var showUntracked = true
            var colorMode: com.accucodeai.kash.api.ansi.ColorMode? = null
            for (a in args) {
                when {
                    a == "--porcelain" || a == "--porcelain=v1" || a == "-s" || a == "--short" -> {
                        format = StatusFormat.PORCELAIN
                    }

                    a == "--porcelain=v2" -> {
                        format = StatusFormat.PORCELAIN_V2
                    }

                    a == "--long" -> {
                        format = StatusFormat.LONG
                    }

                    a == "--branch" || a == "-b" -> {
                        showBranch = true
                    }

                    a == "-z" -> {
                        nulTerminate = true
                    }

                    a == "--ignored" || a == "--ignored=traditional" || a == "--ignored=matching" -> {
                        showIgnored = true
                    }

                    a == "--ignored=no" -> {
                        showIgnored = false
                    }

                    // `-u`/`--untracked-files[=<mode>]`: no|normal|all (default all).
                    // Only "no" changes our output (suppress untracked).
                    a == "-u" || a == "--untracked-files" -> {
                        showUntracked = true
                    }

                    a.startsWith("-u") || a.startsWith("--untracked-files=") -> {
                        val mode = if (a.startsWith("--")) a.substringAfter("=") else a.removePrefix("-u")
                        when (mode) {
                            "no" -> {
                                showUntracked = false
                            }

                            "normal", "all" -> {
                                showUntracked = true
                            }

                            else -> {
                                ctx.stderr.writeUtf8("git status: invalid untracked-files mode '$mode'\n")
                                return CommandResult(exitCode = 129)
                            }
                        }
                    }

                    a == "--no-color" -> {
                        colorMode = com.accucodeai.kash.api.ansi.ColorMode.NEVER
                    }

                    a == "--color" -> {
                        colorMode = com.accucodeai.kash.api.ansi.ColorMode.AUTO
                    }

                    a.startsWith("--color=") -> {
                        val m =
                            com.accucodeai.kash.api.ansi.ColorMode
                                .parse(a.substringAfter("="))
                        if (m == null) {
                            ctx.stderr.writeUtf8("git status: invalid color mode '${a.substringAfter("=")}'\n")
                            return CommandResult(exitCode = 129)
                        }
                        colorMode = m
                    }

                    // Rename detection is never on for us; accept the flags
                    // so scripts that pass them don't get an error.
                    a == "--no-renames" || a == "--renames" || a == "--find-renames" ||
                        a.startsWith("--find-renames=") || a == "--no-ahead-behind" ||
                        a == "--ahead-behind" -> {}

                    a == "--" -> {}

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git status: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {} // pathspec narrowing deferred
                }
            }

            val computed = computeStatus(repo, ctx.fs)
            val status =
                if (showUntracked) {
                    computed
                } else {
                    StatusResult(
                        entries = computed.entries.filter { it.indexCode != '?' },
                        branch = computed.branch,
                        detachedShort = computed.detachedShort,
                        noCommits = computed.noCommits,
                        ignored = computed.ignored,
                    )
                }
            val branchInfo =
                if (showBranch || format == StatusFormat.PORCELAIN_V2 || format == StatusFormat.LONG) {
                    computeBranchInfo(repo, status.branch)
                } else {
                    null
                }
            when (format) {
                StatusFormat.PORCELAIN -> {
                    if (showBranch && branchInfo != null) renderBranchHeader(branchInfo, nulTerminate, ctx)
                    renderPorcelain(status.entries, showIgnored, status.ignored, nulTerminate, ctx)
                }

                StatusFormat.PORCELAIN_V2 -> {
                    if (branchInfo != null) renderBranchHeaderV2(branchInfo, ctx)
                    renderPorcelainV2(status.entries, ctx)
                }

                StatusFormat.LONG -> {
                    val styler = gitColorStyler(ctx, repo, "status", colorMode, env.configOverrides)
                    renderLong(status, branchInfo, showIgnored, styler, ctx)
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
    // Only report an upstream when it actually resolves to an object —
    // a config entry pointing at a missing remote-tracking ref shouldn't
    // produce a phantom "up to date" line.
    return BranchInfo(oid, headLabel, if (upstreamOid != null) upstreamShort else null, ahead, behind)
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                continue
            }
        for (p in c.parents) if (p !in out) queue.addLast(p)
    }
}

private suspend fun renderBranchHeader(
    info: BranchInfo,
    nulTerminate: Boolean,
    ctx: CommandContext,
) {
    val headLabel =
        if (info.oid == null) {
            "No commits yet on ${info.head}"
        } else {
            info.head
        }
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
    ctx.stdout.writeUtf8("## $headLabel$tail" + if (nulTerminate) Ansi.NUL else "\n")
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

private class StatusResult(
    val entries: List<StatusEntry>,
    val branch: String?, // branch name, or null when detached / no head
    val detachedShort: String?, // short HEAD sha when detached
    val noCommits: Boolean,
    val ignored: List<String>,
)

private suspend fun computeStatus(
    repo: GitRepo,
    fs: FileSystem,
): StatusResult {
    val head = repo.refs.readHead()
    val headSha = repo.refs.resolveHead()
    val branch =
        when (head) {
            null -> null
            is RefStore.Head.Detached -> null
            is RefStore.Head.Symbolic -> head.target.removePrefix("refs/heads/")
        }
    val detachedShort =
        if (head is RefStore.Head.Detached || (branch == null && headSha != null)) {
            headSha?.substring(0, 7)
        } else {
            null
        }
    val noCommits = headSha == null

    // HEAD tree → flat path -> sha map.
    val headTree: Map<String, String> =
        if (headSha == null) {
            emptyMap()
        } else {
            val commit = repo.objects.readCommit(headSha)
            flattenTreeShas(repo, commit.tree, "")
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

    return StatusResult(
        entries = entries.sortedBy { it.path },
        branch = branch,
        detachedShort = detachedShort,
        noCommits = noCommits,
        ignored = scan.ignoredFiles.sorted(),
    )
}

private suspend fun flattenTreeShas(
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
                out.putAll(flattenTreeShas(repo, entry.sha, p))
            }
        }
    }
    return out
}

private fun absOf(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"

private suspend fun renderPorcelain(
    entries: List<StatusEntry>,
    showIgnored: Boolean,
    ignored: List<String>,
    nulTerminate: Boolean,
    ctx: CommandContext,
) {
    // Real git emits tracked entries first (alphabetical) and untracked
    // entries last (also alphabetical). We sort here instead of relying
    // on the global path-sort because untracked-vs-tracked order has to
    // be a hard split, not interleaved.
    val term = if (nulTerminate) Ansi.NUL else "\n"
    val tracked = entries.filter { it.indexCode != '?' }.sortedBy { it.path }
    val untracked = entries.filter { it.indexCode == '?' }.sortedBy { it.path }
    for (e in tracked) {
        ctx.stdout.writeUtf8("${e.indexCode}${e.workCode} ${e.path}$term")
    }
    for (e in untracked) {
        ctx.stdout.writeUtf8("?? ${e.path}$term")
    }
    if (showIgnored) {
        for (p in ignored) ctx.stdout.writeUtf8("!! $p$term")
    }
}

private suspend fun renderLong(
    status: StatusResult,
    branchInfo: BranchInfo?,
    showIgnored: Boolean,
    styler: com.accucodeai.kash.api.ansi.AnsiStyler,
    ctx: CommandContext,
) {
    val entries = status.entries
    if (status.branch != null) {
        ctx.stdout.writeUtf8("On branch ${status.branch}\n")
    } else if (status.detachedShort != null) {
        ctx.stdout.writeUtf8("HEAD detached at ${status.detachedShort}\n")
    } else {
        ctx.stdout.writeUtf8("On branch ${status.branch ?: "HEAD detached"}\n")
    }

    // `headerPrinted` tracks whether anything has been emitted after the
    // `On branch <x>` line. Real git separates each subsequent block with
    // a leading blank line *only* once something precedes it; the very
    // first block after a bare `On branch <x>` gets no blank line.
    var headerPrinted = false
    if (status.noCommits) {
        ctx.stdout.writeUtf8("\nNo commits yet\n")
        headerPrinted = true
    } else if (branchInfo != null && branchInfo.upstream != null) {
        ctx.stdout.writeUtf8(upstreamLine(branchInfo))
        headerPrinted = true
    }

    suspend fun blockGap() {
        if (headerPrinted) ctx.stdout.writeUtf8("\n")
        headerPrinted = true
    }

    val staged =
        entries.filter { it.indexCode != ' ' && it.indexCode != '?' }
    val unstaged = entries.filter { it.workCode != ' ' && it.indexCode != '?' && it.workCode != '?' }
    val untracked = entries.filter { it.indexCode == '?' }

    if (staged.isNotEmpty()) {
        blockGap()
        ctx.stdout.writeUtf8("Changes to be committed:\n")
        if (status.noCommits) {
            ctx.stdout.writeUtf8("  (use \"git rm --cached <file>...\" to unstage)\n")
        } else {
            ctx.stdout.writeUtf8("  (use \"git restore --staged <file>...\" to unstage)\n")
        }
        for (e in staged) {
            val entry = styler.style("${longLabel(e.indexCode)}${e.path}", com.accucodeai.kash.api.ansi.Sgr.FG_GREEN)
            ctx.stdout.writeUtf8("\t$entry\n")
        }
    }

    if (unstaged.isNotEmpty()) {
        blockGap()
        ctx.stdout.writeUtf8("Changes not staged for commit:\n")
        val hasDelete = unstaged.any { it.workCode == 'D' }
        if (hasDelete) {
            ctx.stdout.writeUtf8("  (use \"git add/rm <file>...\" to update what will be committed)\n")
        } else {
            ctx.stdout.writeUtf8("  (use \"git add <file>...\" to update what will be committed)\n")
        }
        ctx.stdout.writeUtf8("  (use \"git restore <file>...\" to discard changes in working directory)\n")
        for (e in unstaged) {
            val entry = styler.style("${longLabel(e.workCode)}${e.path}", com.accucodeai.kash.api.ansi.Sgr.FG_RED)
            ctx.stdout.writeUtf8("\t$entry\n")
        }
    }

    if (untracked.isNotEmpty()) {
        blockGap()
        ctx.stdout.writeUtf8("Untracked files:\n")
        ctx.stdout.writeUtf8("  (use \"git add <file>...\" to include in what will be committed)\n")
        for (e in untracked) {
            ctx.stdout.writeUtf8("\t${styler.style(e.path, com.accucodeai.kash.api.ansi.Sgr.FG_RED)}\n")
        }
    }

    if (showIgnored && status.ignored.isNotEmpty()) {
        blockGap()
        ctx.stdout.writeUtf8("Ignored files:\n")
        ctx.stdout.writeUtf8("  (use \"git add -f <file>...\" to include in what will be committed)\n")
        for (p in status.ignored) ctx.stdout.writeUtf8("\t$p\n")
    }

    // Trailing summary. Real git only prints summary text when nothing is
    // staged — staged changes mean there IS something ready to commit, so
    // git stays quiet (but still emits the trailing blank line that would
    // have preceded a summary). When nothing is staged: clean → "nothing
    // to commit"; unstaged changes → "no changes added to commit";
    // untracked only → "nothing added to commit but untracked …".
    val anyStaged = staged.isNotEmpty()
    val anyUnstaged = unstaged.isNotEmpty()
    val anyUntracked = untracked.isNotEmpty()
    when {
        anyStaged -> {
            // No summary text; just the trailing gap.
            ctx.stdout.writeUtf8("\n")
        }

        !anyUnstaged && !anyUntracked -> {
            blockGap()
            if (status.noCommits) {
                ctx.stdout.writeUtf8("nothing to commit (create/copy files and use \"git add\" to track)\n")
            } else {
                ctx.stdout.writeUtf8("nothing to commit, working tree clean\n")
            }
        }

        anyUnstaged -> {
            blockGap()
            ctx.stdout.writeUtf8("no changes added to commit (use \"git add\" and/or \"git commit -a\")\n")
        }

        else -> {
            // untracked only
            blockGap()
            ctx.stdout.writeUtf8("nothing added to commit but untracked files present (use \"git add\" to track)\n")
        }
    }
}

private fun longLabel(code: Char): String =
    when (code) {
        'A' -> "new file:   "
        'M' -> "modified:   "
        'D' -> "deleted:    "
        'R' -> "renamed:    "
        'C' -> "copied:     "
        'T' -> "typechange: "
        else -> "$code:    "
    }

private fun upstreamLine(info: BranchInfo): String {
    val up = info.upstream
    return when {
        info.ahead > 0 && info.behind > 0 -> {
            "Your branch and '$up' have diverged,\n" +
                "and have ${info.ahead} and ${info.behind} different commits each, respectively.\n" +
                "  (use \"git pull\" if you want to integrate the remote branch with yours)\n"
        }

        info.ahead > 0 -> {
            "Your branch is ahead of '$up' by ${info.ahead} ${commitWord(info.ahead)}.\n" +
                "  (use \"git push\" to publish your local commits)\n"
        }

        info.behind > 0 -> {
            "Your branch is behind '$up' by ${info.behind} ${commitWord(info.behind)}, and can be fast-forwarded.\n" +
                "  (use \"git pull\" to update your local branch)\n"
        }

        else -> {
            "Your branch is up to date with '$up'.\n"
        }
    }
}

private fun commitWord(n: Int): String = if (n == 1) "commit" else "commits"
