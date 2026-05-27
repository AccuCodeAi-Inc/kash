package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.blobSha
import com.accucodeai.kash.tools.git.plumbing.diffStat
import com.accucodeai.kash.tools.git.plumbing.renderShortStat
import com.accucodeai.kash.tools.git.plumbing.renderStat
import com.accucodeai.kash.tools.git.plumbing.unifiedDiff

/**
 * `git diff` — three modes by selectors:
 *  - default (no rev args): **work tree vs index**.
 *  - `--cached` / `--staged`: **index vs HEAD**.
 *  - `<rev>`: **work tree vs <rev>**.
 *  - `<rev> <rev>` / `<rev>..<rev>`: **rev-A vs rev-B**.
 *
 * Header format matches real git's (`diff --git a/<p> b/<p>`, mode and
 * sha lines, then the unified hunks). v1 prints the index line with
 * abbreviated SHAs (7 chars).
 */
public fun gitDiffSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "diff"

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

            var cached = false
            var nameOnly = false
            var stat = false
            var shortStat = false
            var context = 3
            val positional = mutableListOf<String>()
            val pathsAfterDashDash = mutableListOf<String>()
            var endOpts = false
            var ii = 0
            while (ii < args.size) {
                val a = args[ii]
                when {
                    endOpts -> {
                        pathsAfterDashDash += a
                        ii++
                    }

                    a == "--cached" || a == "--staged" -> {
                        cached = true
                        ii++
                    }

                    a == "--name-only" -> {
                        nameOnly = true
                        ii++
                    }

                    a == "--stat" -> {
                        stat = true
                        ii++
                    }

                    a == "--shortstat" -> {
                        shortStat = true
                        ii++
                    }

                    a == "-U" -> {
                        if (ii + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"-U\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        context = args[ii + 1].toIntOrNull()?.coerceAtLeast(0)
                            ?: run {
                                ctx.stderr.writeUtf8("error: bad -U value '${args[ii + 1]}'\n")
                                return CommandResult(exitCode = 129)
                            }
                        ii += 2
                    }

                    a.startsWith("-U") -> {
                        context = a.substring(2).toIntOrNull()?.coerceAtLeast(0)
                            ?: run {
                                ctx.stderr.writeUtf8("error: bad -U value '${a.substring(2)}'\n")
                                return CommandResult(exitCode = 129)
                            }
                        ii++
                    }

                    a.startsWith("--unified=") -> {
                        context = a.substringAfter("=").toIntOrNull()?.coerceAtLeast(0)
                            ?: run {
                                ctx.stderr.writeUtf8("error: bad --unified value\n")
                                return CommandResult(exitCode = 129)
                            }
                        ii++
                    }

                    a == "--" -> {
                        endOpts = true
                        ii++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git diff: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                        ii++
                    }
                }
            }

            // Disambiguate revs from paths. Anything after `--` is a path.
            // Otherwise: positionals that resolve as a revspec are revs;
            // the rest are path filters. Range syntax `A..B` splits.
            val revArgs = mutableListOf<String>()
            val pathFilter = mutableListOf<String>()
            pathFilter += pathsAfterDashDash
            for (p in positional) {
                when {
                    p.contains("..") && !ctx.fs.exists(absJoin(env.cwd, p)) -> {
                        val (a, b) = p.split("..", limit = 2)
                        revArgs += a
                        revArgs += b
                    }

                    revArgs.size < 2 && resolveRev(repo, p) != null && !ctx.fs.exists(absJoin(env.cwd, p)) -> {
                        revArgs += p
                    }

                    else -> {
                        pathFilter += p
                    }
                }
            }

            val mode =
                when {
                    revArgs.isEmpty() && cached -> {
                        Mode.IndexVsRev("HEAD")
                    }

                    revArgs.isEmpty() -> {
                        Mode.WorkTreeVsIndex
                    }

                    revArgs.size == 1 && cached -> {
                        Mode.IndexVsRev(revArgs[0])
                    }

                    revArgs.size == 1 -> {
                        Mode.WorkTreeVsRev(revArgs[0])
                    }

                    revArgs.size == 2 -> {
                        Mode.RevVsRev(revArgs[0], revArgs[1])
                    }

                    else -> {
                        ctx.stderr.writeUtf8("git diff: too many arguments\n")
                        return CommandResult(exitCode = 129)
                    }
                }

            val plan =
                buildPlan(repo, ctx.fs, mode, cached)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: bad revision in diff\n")
                        return CommandResult(exitCode = 128)
                    }

            val allPaths =
                (plan.left.keys + plan.right.keys)
                    .filter { matchesPath(it, pathFilter) }
                    .sorted()
            val statEntries = mutableListOf<com.accucodeai.kash.tools.git.plumbing.DiffStatEntry>()
            for (p in allPaths) {
                val l = plan.left[p]
                val r = plan.right[p]
                if (l == null && r == null) continue
                val lBytes = l?.bytes ?: ByteArray(0)
                val rBytes = r?.bytes ?: ByteArray(0)
                if (lBytes.contentEquals(rBytes) && (l?.mode == r?.mode || l == null || r == null)) continue
                if (stat || shortStat) {
                    statEntries += diffStat(lBytes, rBytes, p)
                    continue
                }
                if (nameOnly) {
                    ctx.stdout.writeUtf8("$p\n")
                    continue
                }
                emitDiffHeader(ctx, p, l, r)
                val diff =
                    unifiedDiff(
                        oldBytes = lBytes,
                        newBytes = rBytes,
                        oldLabel = if (l == null) "/dev/null" else "a/$p",
                        newLabel = if (r == null) "/dev/null" else "b/$p",
                        contextLines = context,
                    )
                if (diff.isNotEmpty()) ctx.stdout.writeUtf8(diff)
            }
            if (stat) {
                ctx.stdout.writeUtf8(renderStat(statEntries))
            } else if (shortStat) {
                ctx.stdout.writeUtf8(renderShortStat(statEntries))
            }
            return CommandResult(exitCode = 0)
        }
    }

private fun absJoin(
    cwd: String,
    rel: String,
): String =
    when {
        rel.startsWith("/") -> rel
        cwd == "/" -> "/$rel"
        else -> "$cwd/$rel"
    }

private sealed class Mode {
    object WorkTreeVsIndex : Mode()

    class IndexVsRev(
        val rev: String,
    ) : Mode()

    class WorkTreeVsRev(
        val rev: String,
    ) : Mode()

    class RevVsRev(
        val a: String,
        val b: String,
    ) : Mode()
}

private class Side(
    val bytes: ByteArray,
    val sha: String,
    val mode: FileMode,
)

private class DiffPlan(
    val left: Map<String, Side>,
    val right: Map<String, Side>,
)

private suspend fun buildPlan(
    repo: GitRepo,
    fs: FileSystem,
    mode: Mode,
    @Suppress("UNUSED_PARAMETER") cached: Boolean,
): DiffPlan? {
    return when (mode) {
        is Mode.WorkTreeVsIndex -> {
            val index = repo.readIndex()
            val left = mutableMapOf<String, Side>()
            for (e in index.entries) {
                val bytes = repo.objects.readBlob(e.sha)
                left[e.path] = Side(bytes, e.sha, e.mode)
            }
            val right = mutableMapOf<String, Side>()
            for (p in collectWork(fs, repo.layout.workTree)) {
                val abs = absOf(repo.layout.workTree, p)
                val bytes = fs.readBytes(abs)
                right[p] = Side(bytes, blobSha(bytes), inferMode(fs, abs))
            }
            // Untracked-only paths are not reported by plain `git diff`.
            val tracked = left.keys
            DiffPlan(left, right.filterKeys { it in tracked })
        }

        is Mode.IndexVsRev -> {
            val sha = resolveRev(repo, mode.rev) ?: return null
            val tree = repo.objects.readCommit(sha).tree
            val left = flatTreeBytes(repo, tree)
            val index = repo.readIndex()
            val right = mutableMapOf<String, Side>()
            for (e in index.entries) {
                val bytes = repo.objects.readBlob(e.sha)
                right[e.path] = Side(bytes, e.sha, e.mode)
            }
            DiffPlan(left, right)
        }

        is Mode.WorkTreeVsRev -> {
            val sha = resolveRev(repo, mode.rev) ?: return null
            val tree = repo.objects.readCommit(sha).tree
            val left = flatTreeBytes(repo, tree)
            val right = mutableMapOf<String, Side>()
            for (p in collectWork(fs, repo.layout.workTree)) {
                val abs = absOf(repo.layout.workTree, p)
                val bytes = fs.readBytes(abs)
                right[p] = Side(bytes, blobSha(bytes), inferMode(fs, abs))
            }
            DiffPlan(left, right)
        }

        is Mode.RevVsRev -> {
            val a = resolveRev(repo, mode.a) ?: return null
            val b = resolveRev(repo, mode.b) ?: return null
            val tA = repo.objects.readCommit(a).tree
            val tB = repo.objects.readCommit(b).tree
            DiffPlan(flatTreeBytes(repo, tA), flatTreeBytes(repo, tB))
        }
    }
}

private suspend fun flatTreeBytes(
    repo: GitRepo,
    treeSha: String,
): Map<String, Side> {
    val out = mutableMapOf<String, Side>()
    walkTree(repo, treeSha, "", out)
    return out
}

private suspend fun walkTree(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
    out: MutableMap<String, Side>,
) {
    for (e in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        when (e.mode) {
            FileMode.TREE -> {
                walkTree(repo, e.sha, p, out)
            }

            else -> {
                val bytes = repo.objects.readBlob(e.sha)
                out[p] = Side(bytes, e.sha, e.mode)
            }
        }
    }
}

private fun collectWork(
    fs: FileSystem,
    workTree: String,
): Set<String> {
    val out = mutableSetOf<String>()
    walkWork(fs, workTree, "", out)
    return out
}

private fun walkWork(
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
            if (fs.exists("$sub/.git")) continue // nested-repo boundary
            walkWork(fs, sub, subRel, out)
        } else {
            out += subRel
        }
    }
}

private fun absOf(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"

private fun inferMode(
    fs: FileSystem,
    abs: String,
): FileMode {
    val s =
        try {
            fs.stat(abs)
        } catch (_: Throwable) {
            return FileMode.REGULAR
        }
    return if ((s.mode and 0b001_001_001) != 0) FileMode.EXECUTABLE else FileMode.REGULAR
}

private suspend fun emitDiffHeader(
    ctx: CommandContext,
    path: String,
    left: Side?,
    right: Side?,
) {
    ctx.stdout.writeUtf8("diff --git a/$path b/$path\n")
    when {
        left == null && right != null -> {
            ctx.stdout.writeUtf8("new file mode ${right.mode.wire}\n")
            ctx.stdout.writeUtf8("index 0000000..${right.sha.substring(0, 7)}\n")
        }

        right == null && left != null -> {
            ctx.stdout.writeUtf8("deleted file mode ${left.mode.wire}\n")
            ctx.stdout.writeUtf8("index ${left.sha.substring(0, 7)}..0000000\n")
        }

        left != null && right != null -> {
            if (left.mode != right.mode) {
                ctx.stdout.writeUtf8("old mode ${left.mode.wire}\n")
                ctx.stdout.writeUtf8("new mode ${right.mode.wire}\n")
            }
            ctx.stdout.writeUtf8(
                "index ${left.sha.substring(0, 7)}..${right.sha.substring(0, 7)} ${right.mode.wire}\n",
            )
        }

        else -> {}
    }
}
