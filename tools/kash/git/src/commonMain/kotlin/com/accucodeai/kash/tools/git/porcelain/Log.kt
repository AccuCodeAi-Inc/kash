package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.renderShortStat
import com.accucodeai.kash.tools.git.plumbing.renderStat
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format.parse
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * `git log` — walk commit ancestry from HEAD (or given revspecs) back to
 * the root(s), emitting commits newest-first in git's default commit-date
 * order.
 *
 * History selection:
 *  - multiple positional `<rev>`; `<a>..<b>` / `<a>...<b>` ranges;
 *    `^<rev>` exclusions; `--all` / `--branches` / `--tags` / `--remotes`
 *  - `--first-parent`, `--no-merges`, `--merges`
 *
 * Filtering:
 *  - `--author=<p>` / `--committer=<p>` / `--grep=<p>` / `-i` /
 *    `--all-match` / `--invert-grep`
 *  - `--since`/`--after` / `--until`/`--before` (committer date)
 *  - `-S<string>` / `-G<regex>` pickaxe (`--pickaxe-regex`,
 *    `--pickaxe-all`)
 *  - trailing `<path>...` path filter
 *
 * Output:
 *  - `--oneline`, `--pretty=<fmt>` / `--format=<fmt>`, `--abbrev-commit`,
 *    `--decorate[=short|full|no]`, `-p`/`--patch`, `--stat`,
 *    `--shortstat`, `--name-only`, `--name-status`
 *  - `-n <N>` / `--max-count`, `--skip=<N>`, `--reverse`, `--graph`
 */
public fun gitLogSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "log"

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

            val nowSec =
                ctx.process.machine.clock
                    .now()
                    .epochSeconds
            val sel = WalkOptions()
            val filters = CommitFilters()

            var oneline = false
            var pretty: String? = null
            var maxCount = Int.MAX_VALUE
            var skip = 0
            var reverse = false
            var patch = false
            var stat = false
            var shortStat = false
            var nameOnly = false
            var nameStatus = false
            var graph = false
            var abbrevCommit = false
            var decorate: String? = null // null = off; "short"/"full"
            val paths = mutableListOf<String>()
            var afterDoubleDash = false
            // Pickaxe: raw needles captured during parse; the Pickaxe is
            // built after the loop so `-i`/`--pickaxe-regex` (which may
            // appear in any order) are already known.
            var pickaxeS: String? = null
            var pickaxeG: String? = null
            var pickaxeRegex = false

            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (afterDoubleDash) {
                    paths += a
                    i++
                    continue
                }
                when {
                    a == "--oneline" -> {
                        oneline = true
                        abbrevCommit = true
                        i++
                    }

                    a.startsWith("--pretty=") -> {
                        pretty = a.substringAfter("=")
                        i++
                    }

                    a == "--pretty" -> {
                        pretty = "medium"
                        i++
                    }

                    a.startsWith("--format=") -> {
                        // `--format=<x>` is an alias for `--pretty=<x>` but a
                        // bare format string is treated as `format:<x>`.
                        val v = a.substringAfter("=")
                        pretty = if (prettyPreset(v) != null || v.startsWith("format:")) v else "format:$v"
                        i++
                    }

                    a == "-n" || a == "--max-count" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        maxCount = args[i + 1].toInt()
                        i += 2
                    }

                    a.startsWith("--max-count=") -> {
                        maxCount = a.substringAfter("=").toInt()
                        i++
                    }

                    a.startsWith("--skip=") -> {
                        skip = a.substringAfter("=").toIntOrNull() ?: 0
                        i++
                    }

                    a.length > 2 && a.startsWith("-n") && a.substring(2).all(Char::isDigit) -> {
                        maxCount = a.substring(2).toInt()
                        i++
                    }

                    a.length > 1 && a[0] == '-' && a.substring(1).all(Char::isDigit) -> {
                        maxCount = a.substring(1).toInt()
                        i++
                    }

                    a == "-p" || a == "-u" || a == "--patch" -> {
                        patch = true
                        i++
                    }

                    a == "--stat" -> {
                        stat = true
                        i++
                    }

                    a == "--shortstat" -> {
                        shortStat = true
                        i++
                    }

                    a == "--name-only" -> {
                        nameOnly = true
                        i++
                    }

                    a == "--name-status" -> {
                        nameStatus = true
                        i++
                    }

                    a == "--abbrev-commit" -> {
                        abbrevCommit = true
                        i++
                    }

                    a == "--no-abbrev-commit" -> {
                        abbrevCommit = false
                        i++
                    }

                    a == "--decorate" -> {
                        decorate = "short"
                        i++
                    }

                    a.startsWith("--decorate=") -> {
                        when (val v = a.substringAfter("=")) {
                            "no" -> {
                                decorate = null
                            }

                            "short", "full" -> {
                                decorate = v
                            }

                            "auto" -> {
                                decorate = null
                            }

                            // no tty in kash differential context
                            else -> {
                                ctx.stderr.writeUtf8("fatal: invalid --decorate option: '$v'\n")
                                return CommandResult(exitCode = 128)
                            }
                        }
                        i++
                    }

                    a == "--no-decorate" -> {
                        decorate = null
                        i++
                    }

                    a == "--reverse" -> {
                        reverse = true
                        i++
                    }

                    a == "--first-parent" -> {
                        sel.firstParent = true
                        i++
                    }

                    a == "--no-merges" -> {
                        filters.noMerges = true
                        i++
                    }

                    a == "--merges" -> {
                        filters.mergesOnly = true
                        i++
                    }

                    a == "--all" -> {
                        addRefGlobInto(repo, "refs/heads", sel)
                        addRefGlobInto(repo, "refs/remotes", sel)
                        addRefGlobInto(repo, "refs/tags", sel)
                        i++
                    }

                    a == "--branches" -> {
                        addRefGlobInto(repo, "refs/heads", sel)
                        i++
                    }

                    a == "--tags" -> {
                        addRefGlobInto(repo, "refs/tags", sel)
                        i++
                    }

                    a == "--remotes" -> {
                        addRefGlobInto(repo, "refs/remotes", sel)
                        i++
                    }

                    a.startsWith("--author=") -> {
                        filters.authors += a.substringAfter("=")
                        i++
                    }

                    a.startsWith("--committer=") -> {
                        filters.committers += a.substringAfter("=")
                        i++
                    }

                    a.startsWith("--grep=") -> {
                        filters.greps += a.substringAfter("=")
                        i++
                    }

                    a == "-S" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"S\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        pickaxeS = args[i + 1]
                        i += 2
                    }

                    a.startsWith("-S") -> {
                        pickaxeS = a.substring(2)
                        i++
                    }

                    a == "-G" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"G\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        pickaxeG = args[i + 1]
                        i += 2
                    }

                    a.startsWith("-G") -> {
                        pickaxeG = a.substring(2)
                        i++
                    }

                    a == "--pickaxe-regex" -> {
                        pickaxeRegex = true
                        i++
                    }

                    a == "--pickaxe-all" -> {
                        // Affects which files a matched changeset displays,
                        // not which commits are selected; accepted as a no-op.
                        i++
                    }

                    a == "-i" || a == "--regexp-ignore-case" -> {
                        filters.ignoreCase = true
                        i++
                    }

                    a == "--all-match" -> {
                        filters.allMatch = true
                        i++
                    }

                    a == "--invert-grep" -> {
                        filters.invertGrep = true
                        i++
                    }

                    a.startsWith("--since=") || a.startsWith("--after=") -> {
                        filters.since = parseGitDate(a.substringAfter("="), nowSec)
                        i++
                    }

                    a.startsWith("--until=") || a.startsWith("--before=") -> {
                        filters.until = parseGitDate(a.substringAfter("="), nowSec)
                        i++
                    }

                    a == "--since" || a == "--after" || a == "--until" || a == "--before" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        val v = parseGitDate(args[i + 1], nowSec)
                        if (a == "--since" || a == "--after") filters.since = v else filters.until = v
                        i += 2
                    }

                    a == "--graph" -> {
                        graph = true
                        i++
                    }

                    a == "--" -> {
                        afterDoubleDash = true
                        i++
                    }

                    a.startsWith("^") -> {
                        sel.excludes += a.substring(1)
                        i++
                    }

                    a.contains("...") && !looksLikePath(ctx.fs, env.cwd, a) -> {
                        val (lo, hi) = a.split("...", limit = 2)
                        sel.symmetric += (lo.ifEmpty { "HEAD" }) to (hi.ifEmpty { "HEAD" })
                        i++
                    }

                    a.contains("..") && !looksLikePath(ctx.fs, env.cwd, a) -> {
                        val (lo, hi) = a.split("..", limit = 2)
                        if (lo.isNotEmpty()) sel.excludes += lo
                        sel.includes += hi.ifEmpty { "HEAD" }
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git log: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        // Positionals: revs until the first arg that looks
                        // like an existing path; everything after is a path.
                        if (paths.isEmpty() && !looksLikePath(ctx.fs, env.cwd, a)) {
                            sel.includes += a
                        } else {
                            paths += a
                        }
                        i++
                    }
                }
            }

            if (graph && sel.includes.isEmpty() && sel.excludes.isEmpty() && sel.symmetric.isEmpty()) {
                // Preserve the existing graph rendering for the simple
                // single-tip case (topo order from HEAD).
                return renderGraph(repo, ctx, oneline, pretty, maxCount, paths)
            }

            // No explicit revs and no HEAD yet → match real git's
            // "branch with no commits" message rather than the generic
            // ambiguous-argument one.
            if (sel.includes.isEmpty() && sel.excludes.isEmpty() && sel.symmetric.isEmpty() &&
                repo.refs.resolveHead() == null
            ) {
                ctx.stderr.writeUtf8(
                    "fatal: your current branch '${currentBranchName(repo)}' does not have any commits yet\n",
                )
                return CommandResult(exitCode = 128)
            }

            if (pickaxeS != null && pickaxeG != null) {
                ctx.stderr.writeUtf8("fatal: cannot use both -S and -G\n")
                return CommandResult(exitCode = 128)
            }
            val pickaxe =
                when {
                    pickaxeS != null -> {
                        Pickaxe(PickaxeMode.S, pickaxeS, pickaxeRegex, filters.ignoreCase)
                    }

                    pickaxeG != null -> {
                        Pickaxe(PickaxeMode.G, pickaxeG, isRegex = true, ignoreCase = filters.ignoreCase)
                    }

                    else -> {
                        null
                    }
                }

            val walk =
                computeWalk(repo, sel) {
                    ctx.stderr.writeUtf8("fatal: ambiguous argument '$it'\n")
                }
                    ?: return CommandResult(exitCode = 128)

            // Apply content filters (pure, no object reads → run first), then
            // the diff-touching filter. A pickaxe already enforces the path
            // filter AND a content change, so it subsumes commitTouchesAnyOf —
            // run only one tree walk, not both.
            var filtered =
                walk.filter { (sha, c) ->
                    filters.matches(c) &&
                        when {
                            pickaxe != null -> commitMatchesPickaxe(repo, sha, paths, pickaxe)
                            paths.isEmpty() -> true
                            else -> commitTouchesAnyOf(repo, sha, paths)
                        }
                }
            if (skip > 0) filtered = filtered.drop(skip)
            if (maxCount != Int.MAX_VALUE) filtered = filtered.take(maxCount)
            if (reverse) filtered = filtered.reversed()

            val abbrevLen = if (abbrevCommit) 7 else 40
            // git auto-enables decoration when the format references %d/%D,
            // even without an explicit --decorate.
            val formatWantsDecoration =
                pretty != null && (pretty.contains("%d") || pretty.contains("%D"))
            val decorations =
                when {
                    decorate != null -> buildDecorations(repo, full = decorate == "full")
                    formatWantsDecoration -> buildDecorations(repo, full = false)
                    else -> emptyMap()
                }

            // Two independent layout rules git applies:
            //  - betweenSeparator: a blank line between consecutive commits
            //    (no trailing blank after the last). Used by the default
            //    format and the named multi-line presets, NOT by user
            //    `--format=`/`%…` strings or `--oneline`.
            //  - precedingBlank: a blank line before a file/stat section.
            //    Used for every non-oneline format; oneline glues it on.
            val betweenSeparator =
                !oneline &&
                    when {
                        pretty == null -> true
                        pretty in setOf("short", "medium", "full", "fuller") -> true
                        else -> false
                    }
            filtered.forEachIndexed { idx, (sha, commit) ->
                val isLast = idx == filtered.lastIndex
                renderCommit(sha, commit, oneline, pretty, abbrevLen, decorations[sha] ?: "", ctx)
                val hasFileSection = stat || shortStat || nameStatus || nameOnly
                if (hasFileSection) {
                    if (!oneline) ctx.stdout.writeUtf8("\n")
                    when {
                        stat -> ctx.stdout.writeUtf8(renderStat(commitDiffStat(repo, sha, paths)))
                        shortStat -> ctx.stdout.writeUtf8(renderShortStat(commitDiffStat(repo, sha, paths)))
                        nameStatus -> ctx.stdout.writeUtf8(renderNameStatus(repo, sha, paths))
                        nameOnly -> ctx.stdout.writeUtf8(renderNameOnly(repo, sha, paths))
                    }
                }
                if (patch) {
                    ctx.stdout.writeUtf8(commitDiffPatch(repo, sha, paths))
                    ctx.stdout.writeUtf8("\n")
                } else if (betweenSeparator && !isLast) {
                    ctx.stdout.writeUtf8("\n")
                }
            }
            return CommandResult(exitCode = 0)
        }

        private suspend fun renderGraph(
            repo: GitRepo,
            ctx: CommandContext,
            oneline: Boolean,
            pretty: String?,
            maxCount: Int,
            paths: List<String>,
        ): CommandResult {
            val tip =
                repo.refs.resolveHead() ?: run {
                    ctx.stderr.writeUtf8("fatal: your current branch does not have any commits yet\n")
                    return CommandResult(exitCode = 128)
                }
            val order = topoOrder(repo, tip)
            val graphRenderer = GraphRenderer()
            var emitted = 0
            for (sha in order) {
                if (emitted >= maxCount) break
                val commit = repo.objects.readCommit(sha)
                if (paths.isNotEmpty() && !commitTouchesAnyOf(repo, sha, paths)) {
                    graphRenderer.renderCommit(sha, commit.parents)
                    continue
                }
                val rows = graphRenderer.renderCommit(sha, commit.parents)
                val payload = renderCommitToString(sha, commit, oneline, pretty)
                val payloadLines = payload.trimEnd('\n').split('\n')
                val first = rows.firstOrNull() ?: "*"
                ctx.stdout.writeUtf8("$first ${payloadLines.firstOrNull() ?: ""}\n")
                val lanePrefix = first.replace('*', '|')
                for (line in payloadLines.drop(1)) {
                    ctx.stdout.writeUtf8("$lanePrefix $line\n")
                }
                for (extra in rows.drop(1)) ctx.stdout.writeUtf8("$extra\n")
                emitted++
            }
            return CommandResult(exitCode = 0)
        }

        private fun renderCommitToString(
            sha: String,
            c: CommitPayload,
            oneline: Boolean,
            pretty: String?,
        ): String {
            val format = resolveFormat(pretty)
            return when {
                format != null -> {
                    renderPrettyFormat(format, sha, c).let {
                        if (it.endsWith("\n")) it else "$it\n"
                    }
                }

                oneline -> {
                    "${sha.substring(0, 7)} ${c.message.substringBefore('\n')}\n"
                }

                else -> {
                    defaultCommitBlock(sha, c, abbrevLen = 40, decoration = "")
                }
            }
        }

        private fun looksLikePath(
            fs: com.accucodeai.kash.fs.FileSystem,
            cwd: String,
            arg: String,
        ): Boolean {
            val candidate = if (cwd == "/") "/$arg" else "$cwd/$arg"
            return fs.exists(candidate)
        }

        private suspend fun renderCommit(
            sha: String,
            c: CommitPayload,
            oneline: Boolean,
            pretty: String?,
            abbrevLen: Int,
            decoration: String,
            ctx: CommandContext,
        ) {
            val format = resolveFormat(pretty)
            when {
                format != null -> {
                    ctx.stdout.writeUtf8(renderPrettyFormat(format, sha, c, abbrevLen, decoration))
                    if (!format.endsWith("\n")) ctx.stdout.writeUtf8("\n")
                }

                oneline -> {
                    val subject = c.message.substringBefore('\n')
                    val deco = if (decoration.isNotEmpty()) " ($decoration)" else ""
                    ctx.stdout.writeUtf8("${sha.substring(0, abbrevLen)}$deco $subject\n")
                }

                else -> {
                    ctx.stdout.writeUtf8(defaultCommitBlock(sha, c, abbrevLen, decoration))
                }
            }
        }

        private fun resolveFormat(pretty: String?): String? =
            when {
                pretty == null -> null

                // `medium` is git's default format; render it via the
                // hand-built block so empty-body commits don't pick up the
                // preset string's spurious blank body line.
                pretty == "medium" -> null

                pretty.startsWith("format:") -> pretty.removePrefix("format:")

                prettyPreset(pretty) != null -> prettyPreset(pretty)

                pretty.startsWith("%") -> pretty

                else -> null
            }

        private fun defaultCommitBlock(
            sha: String,
            c: CommitPayload,
            abbrevLen: Int,
            decoration: String,
        ): String =
            buildString {
                append("commit ").append(sha.substring(0, abbrevLen))
                if (decoration.isNotEmpty()) append(" (").append(decoration).append(')')
                append('\n')
                if (c.parents.size > 1) {
                    append("Merge: ")
                        .append(c.parents.joinToString(" ") { it.substring(0, 7) })
                        .append('\n')
                }
                append("Author: ")
                    .append(c.author.name)
                    .append(" <")
                    .append(c.author.email)
                    .append(">\n")
                append("Date:   ")
                    .append(gitDateFormat(c.author.whenSeconds, c.author.tz))
                    .append('\n')
                append('\n')
                for (line in c.message.trimEnd('\n').split('\n')) {
                    append("    ").append(line).append('\n')
                }
                // No trailing blank: the caller inserts a single blank line
                // between consecutive commits and none after the last.
            }
    }

private suspend fun currentBranchName(repo: GitRepo): String {
    val head = repo.refs.readHead()
    return (head as? com.accucodeai.kash.tools.git.plumbing.RefStore.Head.Symbolic)
        ?.target
        ?.removePrefix("refs/heads/")
        ?: "HEAD"
}

private suspend fun addRefGlobInto(
    repo: GitRepo,
    prefix: String,
    sel: WalkOptions,
) {
    for ((_, sha) in repo.refs.listRefs(prefix)) {
        sel.includes += sha
    }
}

/** `--name-only` body: one path per changed file (vs first parent). */
private suspend fun renderNameOnly(
    repo: GitRepo,
    sha: String,
    paths: List<String>,
): String {
    val sb = StringBuilder()
    for ((path, _) in changedFiles(repo, sha, paths)) sb.append(path).append('\n')
    return sb.toString()
}

/** `--name-status`: `<STATUS>\t<path>` per changed file (vs first parent). */
private suspend fun renderNameStatus(
    repo: GitRepo,
    sha: String,
    paths: List<String>,
): String {
    val sb = StringBuilder()
    for ((path, status) in changedFiles(repo, sha, paths)) {
        sb
            .append(status)
            .append('\t')
            .append(path)
            .append('\n')
    }
    return sb.toString()
}

/**
 * Changed files between [commitSha] and its first parent (root commits
 * diff against empty), as `(path, statusChar)` pairs sorted by path.
 * Status chars: `A` added, `D` deleted, `M` modified.
 */
private suspend fun changedFiles(
    repo: GitRepo,
    commitSha: String,
    pathFilter: List<String>,
): List<Pair<String, Char>> {
    val commit = repo.objects.readCommit(commitSha)
    val newSide = flattenTree(repo, commit.tree)
    val oldSide =
        commit.parents.firstOrNull()?.let { p ->
            flattenTree(repo, repo.objects.readCommit(p).tree)
        } ?: emptyMap<String, FlatEntry>()
    val out = mutableListOf<Pair<String, Char>>()
    for (path in (oldSide.keys + newSide.keys).sorted()) {
        if (!matchesPath(path, pathFilter)) continue
        val l = oldSide[path]
        val r = newSide[path]
        when {
            l == null && r != null -> {
                out += path to 'A'
            }

            r == null && l != null -> {
                out += path to 'D'
            }

            l != null && r != null -> {
                if (!l.bytes.contentEquals(r.bytes) || l.mode != r.mode) out += path to 'M'
            }
        }
    }
    return out
}

/**
 * Format `epoch + tz-string` as real git's default `Date:` format:
 * `Day Mon DD HH:MM:SS YYYY ±HHMM` (e.g. `Thu Mar 21 14:32:09 2024 -0700`).
 */
internal fun gitDateFormat(
    epoch: Long,
    tz: String,
): String {
    val offset =
        runCatching { UtcOffset.Formats.FOUR_DIGITS.parse(tz) }.getOrDefault(UtcOffset.ZERO)
    val ldt = Instant.fromEpochSeconds(epoch).toLocalDateTime(FixedOffsetTimeZone(offset))
    val dow = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[ldt.dayOfWeek.isoDayNumber - 1]
    val mon =
        arrayOf(
            "Jan",
            "Feb",
            "Mar",
            "Apr",
            "May",
            "Jun",
            "Jul",
            "Aug",
            "Sep",
            "Oct",
            "Nov",
            "Dec",
        )[ldt.month.ordinal]
    val hh = ldt.hour.toString().padStart(2, '0')
    val mm = ldt.minute.toString().padStart(2, '0')
    val ss = ldt.second.toString().padStart(2, '0')
    val day = ldt.day.toString().padStart(2, ' ')
    val tzOut = if (tz.length == 5 && (tz[0] == '+' || tz[0] == '-')) tz else "+0000"
    return "$dow $mon $day $hh:$mm:$ss ${ldt.year} $tzOut"
}
