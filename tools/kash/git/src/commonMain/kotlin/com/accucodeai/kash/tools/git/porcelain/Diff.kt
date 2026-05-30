package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.DiffStatEntry
import com.accucodeai.kash.tools.git.plumbing.DiffWhitespace
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.blobSha
import com.accucodeai.kash.tools.git.plumbing.diffStat
import com.accucodeai.kash.tools.git.plumbing.renderNumStat
import com.accucodeai.kash.tools.git.plumbing.renderShortStat
import com.accucodeai.kash.tools.git.plumbing.renderStat
import com.accucodeai.kash.tools.git.plumbing.unifiedDiff
import com.accucodeai.kash.tools.git.plumbing.whitespaceEqual
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git diff` — three modes by selectors:
 *  - default (no rev args): **work tree vs index**.
 *  - `--cached` / `--staged`: **index vs HEAD**.
 *  - `<rev>`: **work tree vs <rev>**.
 *  - `<rev> <rev>` / `<rev>..<rev>`: **rev-A vs rev-B**.
 *  - `--no-index <a> <b>`: diff two arbitrary filesystem paths.
 *
 * Header format matches real git's (`diff --git a/<p> b/<p>`, mode and
 * sha lines, then the unified hunks). Index lines use abbreviated SHAs
 * (7 chars).
 */
public fun gitDiffSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "diff"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            // Parse options up front (need --no-index before opening a repo).
            val opts = Options()
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
                        opts.cached = true
                        ii++
                    }

                    a == "--name-only" -> {
                        opts.nameOnly = true
                        ii++
                    }

                    a == "--name-status" -> {
                        opts.nameStatus = true
                        ii++
                    }

                    a == "--numstat" -> {
                        opts.numstat = true
                        ii++
                    }

                    a == "--shortstat" -> {
                        opts.shortStat = true
                        ii++
                    }

                    a == "-R" -> {
                        opts.reverse = true
                        ii++
                    }

                    a == "--exit-code" -> {
                        opts.exitCode = true
                        ii++
                    }

                    a == "--quiet" -> {
                        opts.exitCode = true
                        opts.quiet = true
                        ii++
                    }

                    a == "--no-index" || a == "--no-index=true" -> {
                        opts.noIndex = true
                        ii++
                    }

                    a == "-w" || a == "--ignore-all-space" -> {
                        opts.ignoreAllSpace = true
                        ii++
                    }

                    a == "-b" || a == "--ignore-space-change" -> {
                        opts.ignoreSpaceChange = true
                        ii++
                    }

                    a == "--ignore-space-at-eol" -> {
                        opts.ignoreSpaceAtEol = true
                        ii++
                    }

                    a == "--ignore-blank-lines" -> {
                        opts.ignoreBlankLines = true
                        ii++
                    }

                    a == "--no-color" -> {
                        opts.colorMode = com.accucodeai.kash.api.ansi.ColorMode.NEVER
                        ii++
                    }

                    a == "--color" -> {
                        // Bare `--color` means auto (kash/coreutils convention).
                        opts.colorMode = com.accucodeai.kash.api.ansi.ColorMode.AUTO
                        ii++
                    }

                    a.startsWith("--color=") -> {
                        val m =
                            com.accucodeai.kash.api.ansi.ColorMode
                                .parse(a.substringAfter("="))
                        if (m == null) {
                            ctx.stderr.writeUtf8("fatal: bad --color value '${a.substringAfter("=")}'\n")
                            return CommandResult(exitCode = 129)
                        }
                        opts.colorMode = m
                        ii++
                    }

                    a == "--stat" -> {
                        opts.stat = true
                        ii++
                    }

                    a.startsWith("--stat=") -> {
                        opts.stat = true
                        // git accepts --stat=<width>[,<name-width>[,<count>]]; we honor width.
                        val w = a.substringAfter("=").substringBefore(",").toIntOrNull()
                        if (w == null) {
                            ctx.stderr.writeUtf8("error: bad --stat value '${a.substringAfter("=")}'\n")
                            return CommandResult(exitCode = 129)
                        }
                        opts.statWidth = w
                        ii++
                    }

                    a.startsWith("--diff-filter=") -> {
                        opts.diffFilter = a.substringAfter("=")
                        ii++
                    }

                    a.startsWith("-M") || a.startsWith("--find-renames") -> {
                        // Rename detection not implemented; accept and ignore
                        // the flag so scripts don't break. Renamed files show
                        // up as an add + delete pair (known gap).
                        ii++
                    }

                    a == "-U" -> {
                        if (ii + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"-U\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        opts.context = args[ii + 1].toIntOrNull()?.coerceAtLeast(0)
                            ?: run {
                                ctx.stderr.writeUtf8("error: bad -U value '${args[ii + 1]}'\n")
                                return CommandResult(exitCode = 129)
                            }
                        ii += 2
                    }

                    a.startsWith("-U") -> {
                        opts.context = a.substring(2).toIntOrNull()?.coerceAtLeast(0)
                            ?: run {
                                ctx.stderr.writeUtf8("error: bad -U value '${a.substring(2)}'\n")
                                return CommandResult(exitCode = 129)
                            }
                        ii++
                    }

                    a.startsWith("--unified=") -> {
                        opts.context = a.substringAfter("=").toIntOrNull()?.coerceAtLeast(0)
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

            if (opts.noIndex) {
                // No repo → color resolves from the flag, `-c` override, or tty.
                opts.styler = gitColorStyler(ctx, null, "diff", opts.colorMode, env.configOverrides)
                return runNoIndex(ctx, env, opts, positional + pathsAfterDashDash)
            }

            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }
            opts.styler = gitColorStyler(ctx, repo, "diff", opts.colorMode, env.configOverrides)

            // Disambiguate revs from paths. Anything after `--` is a path.
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
                    revArgs.isEmpty() && opts.cached -> {
                        Mode.IndexVsRev("HEAD")
                    }

                    revArgs.isEmpty() -> {
                        Mode.WorkTreeVsIndex
                    }

                    revArgs.size == 1 && opts.cached -> {
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
                buildPlan(repo, ctx.fs, mode)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: bad revision in diff\n")
                        return CommandResult(exitCode = 128)
                    }

            return emit(ctx, plan, pathFilter, opts)
        }
    }

private class Options {
    var cached = false
    var nameOnly = false
    var nameStatus = false
    var numstat = false
    var stat = false
    var statWidth = 80
    var shortStat = false
    var reverse = false
    var exitCode = false
    var quiet = false
    var noIndex = false
    var diffFilter: String? = null
    var context = 3

    // null = no explicit --color flag; resolve from config/tty. `styler` is
    // set in run() before any emit and used to colorize the diff.
    var colorMode: com.accucodeai.kash.api.ansi.ColorMode? = null
    lateinit var styler: com.accucodeai.kash.api.ansi.AnsiStyler

    var ignoreAllSpace = false
    var ignoreSpaceChange = false
    var ignoreSpaceAtEol = false
    var ignoreBlankLines = false

    fun ws(): DiffWhitespace =
        DiffWhitespace(
            ignoreAllSpace = ignoreAllSpace,
            ignoreSpaceChange = ignoreSpaceChange,
            ignoreSpaceAtEol = ignoreSpaceAtEol,
            ignoreBlankLines = ignoreBlankLines,
        )
}

private enum class ChangeKind(
    val letter: Char,
) {
    ADDED('A'),
    DELETED('D'),
    MODIFIED('M'),
    TYPE_CHANGED('T'),
}

/**
 * `--diff-filter=<spec>`: uppercase letters select which change kinds to
 * include; lowercase letters select kinds to *exclude* (when no uppercase
 * include set is given, everything is included by default). Returns true
 * iff [kind] passes the filter.
 */
private fun passesFilter(
    spec: String?,
    kind: ChangeKind,
): Boolean {
    if (spec.isNullOrEmpty()) return true
    val includes = spec.filter { it.isUpperCase() }
    val excludes = spec.filter { it.isLowerCase() }.uppercase()
    if (excludes.contains(kind.letter)) return false
    if (includes.isEmpty()) return true
    return includes.contains(kind.letter)
}

private suspend fun emit(
    ctx: CommandContext,
    rawPlan: DiffPlan,
    pathFilter: List<String>,
    opts: Options,
): CommandResult {
    // `-R` swaps the two sides (and the a/ b/ header labels).
    val plan = if (opts.reverse) DiffPlan(rawPlan.right, rawPlan.left) else rawPlan
    val ws = opts.ws()

    val allPaths =
        (plan.left.keys + plan.right.keys)
            .filter { matchesPath(it, pathFilter) }
            .sorted()

    val statEntries = mutableListOf<DiffStatEntry>()
    val out = StringBuilder()
    var anyDiff = false

    for (p in allPaths) {
        val l = plan.left[p]
        val r = plan.right[p]
        if (l == null && r == null) continue
        val lBytes = l?.bytes ?: ByteArray(0)
        val rBytes = r?.bytes ?: ByteArray(0)
        val contentSame = whitespaceEqual(lBytes, rBytes, ws)
        val modeSame = l?.mode == r?.mode || l == null || r == null
        if (contentSame && modeSame) continue

        val kind =
            when {
                l == null -> ChangeKind.ADDED

                r == null -> ChangeKind.DELETED

                l.mode != r.mode &&
                    (l.mode == FileMode.SYMLINK || r.mode == FileMode.SYMLINK) -> ChangeKind.TYPE_CHANGED

                else -> ChangeKind.MODIFIED
            }
        if (!passesFilter(opts.diffFilter, kind)) continue

        anyDiff = true
        if (opts.quiet) continue // -q: short-circuit, only exit code matters

        if (opts.stat || opts.shortStat || opts.numstat) {
            statEntries += diffStat(lBytes, rBytes, p, ws)
            continue
        }
        if (opts.nameStatus) {
            out
                .append(kind.letter)
                .append('\t')
                .append(p)
                .append('\n')
            continue
        }
        if (opts.nameOnly) {
            out.append(p).append('\n')
            continue
        }

        appendDiffHeader(out, p, l, r, opts.reverse)
        // Under -R the old side prints as `b/`, the new side as `a/`.
        val oldPrefix = if (opts.reverse) "b/" else "a/"
        val newPrefix = if (opts.reverse) "a/" else "b/"
        val diff =
            unifiedDiff(
                oldBytes = lBytes,
                newBytes = rBytes,
                oldLabel = if (l == null) "/dev/null" else "$oldPrefix$p",
                newLabel = if (r == null) "/dev/null" else "$newPrefix$p",
                contextLines = opts.context,
                ws = ws,
            )
        if (diff.isNotEmpty()) out.append(diff)
    }

    if (!opts.quiet) {
        if (opts.numstat) {
            out.append(renderNumStat(statEntries))
        } else if (opts.stat) {
            out.append(renderStat(statEntries, opts.statWidth))
        } else if (opts.shortStat) {
            out.append(renderShortStat(statEntries))
        }
        if (out.isNotEmpty()) ctx.stdout.writeUtf8(colorizeDiff(out.toString(), opts.styler))
    }

    val exit = if ((opts.exitCode) && anyDiff) 1 else 0
    return CommandResult(exitCode = exit)
}

private suspend fun runNoIndex(
    ctx: CommandContext,
    env: GitEnv,
    opts: Options,
    paths: List<String>,
): CommandResult {
    if (paths.size != 2) {
        ctx.stderr.writeUtf8("usage: git diff --no-index <path> <path>\n")
        return CommandResult(exitCode = 129)
    }
    val (aArg, bArg) = paths
    val aAbs = absJoin(env.cwd, aArg)
    val bAbs = absJoin(env.cwd, bArg)

    val aIsNull = aArg == "/dev/null"
    val bIsNull = bArg == "/dev/null"
    val aBytes = if (aIsNull) ByteArray(0) else readOrNull(ctx.fs, aAbs)
    val bBytes = if (bIsNull) ByteArray(0) else readOrNull(ctx.fs, bAbs)

    if (!aIsNull && aBytes == null) {
        ctx.stderr.writeUtf8("fatal: could not read $aArg\n")
        return CommandResult(exitCode = 128)
    }
    if (!bIsNull && bBytes == null) {
        ctx.stderr.writeUtf8("fatal: could not read $bArg\n")
        return CommandResult(exitCode = 128)
    }
    val la = aBytes ?: ByteArray(0)
    val lb = bBytes ?: ByteArray(0)
    val ws = opts.ws()
    if (whitespaceEqual(la, lb, ws)) {
        return CommandResult(exitCode = 0)
    }

    // git's --no-index header uses the supplied path with the leading
    // slash stripped (so `a/tmp/x`). A /dev/null source uses the other
    // path's name on both header sides with `new file mode`.
    val displayA = if (aIsNull) bArg.trimStart('/') else aArg.trimStart('/')
    val displayB = bArg.trimStart('/')
    val out = StringBuilder()

    if (opts.numstat || opts.stat || opts.shortStat) {
        val entry = diffStat(la, lb, displayB, ws)
        when {
            opts.numstat -> out.append(renderNumStat(listOf(entry)))
            opts.stat -> out.append(renderStat(listOf(entry), opts.statWidth))
            opts.shortStat -> out.append(renderShortStat(listOf(entry)))
        }
        ctx.stdout.writeUtf8(out.toString())
        return CommandResult(exitCode = 1)
    }
    if (opts.nameOnly) {
        ctx.stdout.writeUtf8("$displayB\n")
        return CommandResult(exitCode = 1)
    }
    if (opts.nameStatus) {
        val letter = if (aIsNull) 'A' else 'M'
        ctx.stdout.writeUtf8("$letter\t$displayB\n")
        return CommandResult(exitCode = 1)
    }

    val aSha = blobSha(la).substring(0, 7)
    val bSha = blobSha(lb).substring(0, 7)
    out.append("diff --git a/$displayA b/$displayB\n")
    if (aIsNull) {
        out.append("new file mode 100644\n")
        out.append("index 0000000..$bSha\n")
    } else {
        out.append("index $aSha..$bSha 100644\n")
    }
    val diff =
        unifiedDiff(
            oldBytes = la,
            newBytes = lb,
            oldLabel = if (aIsNull) "/dev/null" else "a/$displayA",
            newLabel = "b/$displayB",
            contextLines = opts.context,
            ws = ws,
        )
    out.append(diff)
    ctx.stdout.writeUtf8(colorizeDiff(out.toString(), opts.styler))
    return CommandResult(exitCode = 1)
}

private suspend fun readOrNull(
    fs: FileSystem,
    abs: String,
): ByteArray? =
    try {
        if (!fs.exists(abs)) null else fs.readBytes(abs)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return FileMode.REGULAR
        }
    return if ((s.mode and 0b001_001_001) != 0) FileMode.EXECUTABLE else FileMode.REGULAR
}

private fun appendDiffHeader(
    out: StringBuilder,
    path: String,
    left: Side?,
    right: Side?,
    reverse: Boolean = false,
) {
    // Under -R the `diff --git` line lists the old (b/) path first.
    if (reverse) {
        out.append("diff --git b/$path a/$path\n")
    } else {
        out.append("diff --git a/$path b/$path\n")
    }
    when {
        left == null && right != null -> {
            out.append("new file mode ${right.mode.wire}\n")
            out.append("index 0000000..${right.sha.substring(0, 7)}\n")
        }

        right == null && left != null -> {
            out.append("deleted file mode ${left.mode.wire}\n")
            out.append("index ${left.sha.substring(0, 7)}..0000000\n")
        }

        left != null && right != null -> {
            if (left.mode != right.mode) {
                out.append("old mode ${left.mode.wire}\n")
                out.append("new mode ${right.mode.wire}\n")
            }
            out.append("index ${left.sha.substring(0, 7)}..${right.sha.substring(0, 7)} ${right.mode.wire}\n")
        }

        else -> {}
    }
}
