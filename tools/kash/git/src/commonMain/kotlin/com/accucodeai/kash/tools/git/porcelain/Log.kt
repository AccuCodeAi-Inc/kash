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
 * `git log` — walk commit ancestry from HEAD (or a given revspec)
 * back to the root. v1 flags:
 *  - `--oneline`: `<short-sha> <message-subject>` per commit.
 *  - `--pretty=%s|%H|%h|%B`: format presets scripts love.
 *  - `-n <N>` / `--max-count=<N>`: limit output.
 *  - `-p` / `-u` / `--patch`: inline the patch (commit vs first parent).
 *  - `--stat` / `--shortstat`: per-file or summary line counts.
 *  - `<path>...`: trailing positional path filter; only commits that
 *    touched one of those paths are emitted (and patches are
 *    restricted to those paths).
 *
 * Topology: first-parent walk. Multi-parent merge handling and
 * `--graph` rendering still defer to a later milestone.
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

            var oneline = false
            var pretty: String? = null
            var maxCount = Int.MAX_VALUE
            var start: String? = null
            var patch = false
            var stat = false
            var shortStat = false
            var graph = false
            val paths = mutableListOf<String>()
            var afterDoubleDash = false

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
                        i++
                    }

                    a.startsWith("--pretty=") -> {
                        pretty = a.substringAfter("=")
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

                    // `-n42` shortcut for `-n 42`.
                    a.length > 2 && a.startsWith("-n") && a.substring(2).all(Char::isDigit) -> {
                        maxCount = a.substring(2).toInt()
                        i++
                    }

                    // `-<N>` bare-count shorthand (e.g. `git log -5`).
                    // Real git treats this as a synonym for `-n <N>`. Only
                    // claims digit-only tails so we don't shadow `-p`, `-u`,
                    // etc. above.
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

                    a == "--graph" -> {
                        graph = true
                        i++
                    }

                    a == "--" -> {
                        afterDoubleDash = true
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git log: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        // First positional is the revspec; subsequent ones
                        // are path filters (matches real git's grammar).
                        if (start == null && !looksLikePath(ctx.fs, env.cwd, a)) {
                            start = a
                        } else {
                            paths += a
                        }
                        i++
                    }
                }
            }

            val tip =
                if (start == null) {
                    repo.refs.resolveHead() ?: run {
                        ctx.stderr.writeUtf8("fatal: your current branch does not have any commits yet\n")
                        return CommandResult(exitCode = 128)
                    }
                } else {
                    resolveRev(repo, start) ?: run {
                        ctx.stderr.writeUtf8("fatal: ambiguous argument '$start'\n")
                        return CommandResult(exitCode = 128)
                    }
                }

            val walk: List<String> =
                if (graph) {
                    topoOrder(repo, tip)
                } else {
                    val list = mutableListOf<String>()
                    var c: String? = tip
                    while (c != null) {
                        // Shallow clones (`git clone --depth=N`) only
                        // have the last N commit objects locally — the
                        // deepest commit's parent pointers reference
                        // shas we don't have. Match real git: stop the
                        // walk at the shallow boundary instead of
                        // crashing the whole log command. Read first so
                        // we don't add an unreadable sha to the list
                        // (the render loop below would blow up on it).
                        val commit =
                            try {
                                repo.objects.readCommit(c)
                            } catch (_: Throwable) {
                                break
                            }
                        list += c
                        c = commit.parents.firstOrNull()
                    }
                    list
                }
            val graphRenderer = if (graph) GraphRenderer() else null
            var emitted = 0
            for (sha in walk) {
                if (emitted >= maxCount) break
                val commit = repo.objects.readCommit(sha)
                if (paths.isNotEmpty() && !commitTouchesAnyOf(repo, sha, paths)) {
                    // Still advance the graph so future rows line up.
                    if (graphRenderer != null) {
                        graphRenderer.renderCommit(sha, commit.parents)
                    }
                    continue
                }
                if (graphRenderer != null) {
                    val rows = graphRenderer.renderCommit(sha, commit.parents)
                    // First row is the commit row (prefixed to the rendered
                    // commit lines); follow-ups (merge bar, lane collapse)
                    // print on their own lines.
                    val payload = renderCommitToString(sha, commit, oneline, pretty)
                    val payloadLines = payload.trimEnd('\n').split('\n')
                    val first = rows.firstOrNull() ?: "*"
                    ctx.stdout.writeUtf8("$first ${payloadLines.firstOrNull() ?: ""}\n")
                    val lanePrefix = first.replace('*', '|')
                    for (line in payloadLines.drop(1)) {
                        ctx.stdout.writeUtf8("$lanePrefix $line\n")
                    }
                    for (extra in rows.drop(1)) ctx.stdout.writeUtf8("$extra\n")
                } else {
                    renderCommit(sha, commit, oneline, pretty, ctx)
                    if (stat) {
                        ctx.stdout.writeUtf8(renderStat(commitDiffStat(repo, sha, paths)))
                        if (!patch) ctx.stdout.writeUtf8("\n")
                    } else if (shortStat) {
                        ctx.stdout.writeUtf8(renderShortStat(commitDiffStat(repo, sha, paths)))
                        if (!patch) ctx.stdout.writeUtf8("\n")
                    }
                    if (patch) {
                        ctx.stdout.writeUtf8(commitDiffPatch(repo, sha, paths))
                        ctx.stdout.writeUtf8("\n")
                    }
                }
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
            val format =
                when {
                    pretty == null -> null
                    pretty.startsWith("format:") -> pretty.removePrefix("format:")
                    prettyPreset(pretty) != null -> prettyPreset(pretty)
                    pretty.startsWith("%") -> pretty
                    else -> null
                }
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
                    buildString {
                        append("commit ").append(sha).append('\n')
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
                        append('\n')
                    }
                }
            }
        }

        private fun looksLikePath(
            fs: com.accucodeai.kash.fs.FileSystem,
            cwd: String,
            arg: String,
        ): Boolean {
            // Heuristic: a path is something that exists relative to cwd.
            // We deliberately do NOT treat arbitrary `/`-containing args
            // as paths — refspecs like `origin/main` or `refs/heads/x`
            // contain slashes too. Real git uses `--` to disambiguate
            // (honored via afterDoubleDash above).
            val candidate = if (cwd == "/") "/$arg" else "$cwd/$arg"
            return fs.exists(candidate)
        }

        private suspend fun renderCommit(
            sha: String,
            c: CommitPayload,
            oneline: Boolean,
            pretty: String?,
            ctx: CommandContext,
        ) {
            // Resolve preset names (`--pretty=oneline`, `medium`, etc.) to
            // the equivalent format strings; bare `--pretty=format:<x>`
            // is the most common scripted form.
            val format =
                when {
                    pretty == null -> null

                    pretty.startsWith("format:") -> pretty.removePrefix("format:")

                    prettyPreset(pretty) != null -> prettyPreset(pretty)

                    // Single-placeholder shortcuts kept for back-compat with
                    // tests that pass `--pretty=%s` etc.
                    pretty.startsWith("%") -> pretty

                    else -> null
                }
            when {
                format != null -> {
                    ctx.stdout.writeUtf8(renderPrettyFormat(format, sha, c))
                    if (!format.endsWith("\n")) ctx.stdout.writeUtf8("\n")
                }

                oneline -> {
                    val subject = c.message.substringBefore('\n')
                    ctx.stdout.writeUtf8("${sha.substring(0, 7)} $subject\n")
                }

                else -> {
                    ctx.stdout.writeUtf8("commit $sha\n")
                    ctx.stdout.writeUtf8("Author: ${c.author.name} <${c.author.email}>\n")
                    ctx.stdout.writeUtf8("Date:   ${gitDateFormat(c.author.whenSeconds, c.author.tz)}\n")
                    ctx.stdout.writeUtf8("\n")
                    for (line in c.message.trimEnd('\n').split('\n')) {
                        ctx.stdout.writeUtf8("    $line\n")
                    }
                    ctx.stdout.writeUtf8("\n")
                }
            }
        }
    }

/**
 * Format `epoch + tz-string` as real git's default `Date:` format:
 * `Day Mon DD HH:MM:SS YYYY ±HHMM` (e.g. `Thu Mar 21 14:32:09 2024 -0700`).
 * The tz offset is applied to the displayed wall-clock; the trailing
 * tz string is the original offset, unmodified.
 *
 * Falls back to the raw `<epoch> <tz>` form if anything's off (tz too
 * short, negative epoch beyond the era table) so we never emit a
 * malformed Date line.
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
