package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.hash.sha1Hex
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git shortlog` — group commits by author (or committer) and summarize.
 *
 * Supported flags (clean-room parity with `/usr/bin/git`):
 *  - `-n` / `--numbered`: sort groups by descending commit count (ties
 *    broken by name, matching real git's stable secondary order).
 *  - `-s` / `--summary`: suppress the per-commit subject list; print only
 *    `   <count>\t<author>`.
 *  - `-e` / `--email`: append ` <email>` to each author identity.
 *  - `-c` / `--committer`: group by committer identity instead of author.
 *  - `<revision-range>`: any revspec the shared walk understands (single
 *    rev, `A..B`, `^X`); default is `HEAD`.
 *  - `-- <path>...`: restrict to commits touching those paths.
 *
 * Default (no `-n`) ordering is case-insensitive alphabetical by the
 * author identity string, matching real git.
 *
 * OUT OF SCOPE: reading commit summaries from stdin (real git's
 * `git shortlog` with no repo / piped `git log` output). kash always
 * walks the object store.
 */
public fun gitShortlogSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "shortlog"

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

            var numbered = false
            var summary = false
            var email = false
            var byCommitter = false
            val includes = mutableListOf<String>()
            val excludes = mutableListOf<String>()
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
                    a == "-n" || a == "--numbered" -> {
                        numbered = true
                        i++
                    }

                    a == "-s" || a == "--summary" -> {
                        summary = true
                        i++
                    }

                    a == "-e" || a == "--email" -> {
                        email = true
                        i++
                    }

                    a == "-c" || a == "--committer" -> {
                        byCommitter = true
                        i++
                    }

                    // Combined short flags like `-ns`, `-se`, `-nse`.
                    a.length > 1 && a[0] == '-' && a[1] != '-' && a.drop(1).all { it in "nsec" } -> {
                        for (ch in a.drop(1)) {
                            when (ch) {
                                'n' -> numbered = true
                                's' -> summary = true
                                'e' -> email = true
                                'c' -> byCommitter = true
                            }
                        }
                        i++
                    }

                    a == "--" -> {
                        afterDoubleDash = true
                        i++
                    }

                    a.startsWith("^") -> {
                        excludes += a.substring(1)
                        i++
                    }

                    a.contains("..") -> {
                        val (lo, hi) = a.split("..", limit = 2)
                        if (lo.isNotEmpty()) excludes += lo
                        includes += hi.ifEmpty { "HEAD" }
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git shortlog: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        // First non-flag positional is a revision unless it
                        // resolves only as a path; subsequent ones are paths.
                        if (resolveRev(repo, a) != null) {
                            includes += a
                        } else {
                            paths += a
                        }
                        i++
                    }
                }
            }

            if (includes.isEmpty()) {
                val head =
                    repo.refs.resolveHead() ?: run {
                        // No commits yet — real git prints nothing, exit 0.
                        return CommandResult(exitCode = 0)
                    }
                includes += head
            }

            val excludeShas = mutableSetOf<String>()
            for (e in excludes) {
                val s = resolveRev(repo, e) ?: continue
                collectAncestorSet(repo, s, excludeShas)
            }

            val visited = mutableSetOf<String>()
            val ordered = mutableListOf<String>()
            for (start in includes) {
                val startSha =
                    resolveRev(repo, start) ?: run {
                        ctx.stderr.writeUtf8("fatal: ambiguous argument '$start'\n")
                        return CommandResult(exitCode = 128)
                    }
                walkAncestry(repo, startSha, excludeShas, visited, ordered)
            }

            // Group commits by identity, preserving first-seen subject order.
            data class Group(
                val ident: String,
                val subjects: MutableList<String> = mutableListOf(),
            )

            // Real git lists subjects oldest-first within each group, so
            // walk the newest-first ancestry in reverse.
            val groups = LinkedHashMap<String, Group>()
            for (sha in ordered.asReversed()) {
                if (paths.isNotEmpty() && !commitTouchesAnyOf(repo, sha, paths)) continue
                val commit = repo.objects.readCommit(sha)
                val person: PersonStamp = if (byCommitter) commit.committer else commit.author
                val ident =
                    if (email) "${person.name} <${person.email}>" else person.name
                val g = groups.getOrPut(ident) { Group(ident) }
                g.subjects += commit.message.substringBefore('\n').trim()
            }

            val sorted =
                if (numbered) {
                    groups.values.sortedWith(
                        compareByDescending<Group> { it.subjects.size }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.ident },
                    )
                } else {
                    groups.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.ident })
                }

            val sb = StringBuilder()
            if (summary) {
                for (g in sorted) {
                    // Real git pads the count to width 6 in summary mode.
                    sb
                        .append(
                            g.subjects.size
                                .toString()
                                .padStart(6),
                        ).append('\t')
                        .append(g.ident)
                        .append('\n')
                }
            } else {
                for (g in sorted) {
                    sb
                        .append(g.ident)
                        .append(" (")
                        .append(g.subjects.size)
                        .append("):\n")
                    for (subject in g.subjects) {
                        sb.append("      ").append(subject).append('\n')
                    }
                    // Real git prints a trailing blank line after every
                    // group, including the last.
                    sb.append('\n')
                }
            }
            ctx.stdout.writeUtf8(sb.toString())
            return CommandResult(exitCode = 0)
        }
    }

/**
 * `git cherry [-v] <upstream> [<head> [<limit>]]` — show commits on
 * `<head>` (default HEAD) that are not in `<upstream>`, classified by
 * whether an equivalent patch already exists upstream.
 *
 *  - `+ <sha>`: no upstream commit has an equivalent patch.
 *  - `- <sha>`: an upstream commit has an equivalent patch (patch-id match).
 *  - `-v`: append the commit subject after the sha.
 *
 * Equivalence is by **patch-id** — a clean-room stable hash of the
 * commit's diff with line numbers and blob hashes normalized away (see
 * [computePatchId]). `<limit>` bounds the walk to commits after `<limit>`
 * (exclusive), like real git.
 */
public fun gitCherrySubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "cherry"

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

            var verbose = false
            val positionals = mutableListOf<String>()
            for (a in args) {
                when {
                    a == "-v" || a == "--verbose" -> {
                        verbose = true
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git cherry: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positionals += a
                    }
                }
            }

            if (positionals.isEmpty()) {
                ctx.stderr.writeUtf8("usage: git cherry [-v] <upstream> [<head> [<limit>]]\n")
                return CommandResult(exitCode = 129)
            }

            val upstreamRev = positionals[0]
            val headRev = positionals.getOrNull(1) ?: "HEAD"
            val limitRev = positionals.getOrNull(2)

            val upstreamSha =
                resolveRev(repo, upstreamRev) ?: run {
                    ctx.stderr.writeUtf8("fatal: Unknown commit $upstreamRev\n")
                    return CommandResult(exitCode = 128)
                }
            val headSha =
                resolveRev(repo, headRev) ?: run {
                    ctx.stderr.writeUtf8("fatal: Unknown commit $headRev\n")
                    return CommandResult(exitCode = 128)
                }

            // Commits to consider: reachable from head, not from upstream
            // (and not from limit, if given). Real git applies the
            // symmetric "head not upstream" set then walks oldest-first.
            val excludeShas = mutableSetOf<String>()
            collectAncestorSet(repo, upstreamSha, excludeShas)
            limitRev?.let { lim ->
                resolveRev(repo, lim)?.let { collectAncestorSet(repo, it, excludeShas) }
            }

            val headSet = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            walkAncestry(repo, headSha, excludeShas, seen, headSet)
            // Oldest-first, like real git's output order.
            val candidates = headSet.asReversed()

            // Patch-ids of the upstream-only commits (upstream not in head),
            // used to decide which candidates have an equivalent upstream.
            val headAncestors = mutableSetOf<String>()
            collectAncestorSet(repo, headSha, headAncestors)
            val upstreamOnly = mutableListOf<String>()
            val upSeen = mutableSetOf<String>()
            walkAncestry(repo, upstreamSha, headAncestors, upSeen, upstreamOnly)

            val upstreamPatchIds = mutableSetOf<String>()
            for (sha in upstreamOnly) {
                upstreamPatchIds += computePatchId(repo, sha)
            }

            val sb = StringBuilder()
            for (sha in candidates) {
                val pid = computePatchId(repo, sha)
                val sign = if (pid in upstreamPatchIds) "-" else "+"
                if (verbose) {
                    val subject =
                        repo.objects
                            .readCommit(sha)
                            .message
                            .substringBefore('\n')
                            .trim()
                    sb
                        .append(sign)
                        .append(' ')
                        .append(sha)
                        .append(' ')
                        .append(subject)
                        .append('\n')
                } else {
                    sb
                        .append(sign)
                        .append(' ')
                        .append(sha)
                        .append('\n')
                }
            }
            ctx.stdout.writeUtf8(sb.toString())
            return CommandResult(exitCode = 0)
        }
    }

/**
 * `git whatchanged [<log-opts>] [<rev>]` — like `git log` but shows a
 * diff per commit by default. We delegate to the shared log renderer with
 * patch output forced on (`-p` parity). The historical default of
 * `whatchanged` is the raw `:<srcmode> <dstmode> <srcsha> <dstsha>
 * <status>\t<path>` line; we emit a clean-room approximation of those raw
 * lines too, then fall through to the same machinery `git log` uses.
 *
 * GAP: when neither `-p` nor `--raw` is requested, real git's exact
 * raw-format column widths and rename/copy detection (`Rxxx`, `Cxxx`)
 * are not fully reproduced. We emit `M`/`A`/`D` status with full modes
 * and shas, which covers the common scripted cases; rename detection is
 * not performed (every rename shows as an add+delete pair, matching
 * git's behavior without `-M`).
 */
public fun gitWhatchangedSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "whatchanged"

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

            var pretty: String? = null
            var maxCount = Int.MAX_VALUE
            var start: String? = null
            var patch = false
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
                    a.startsWith("--pretty=") -> {
                        pretty = a.substringAfter("=")
                        i++
                    }

                    a == "-n" || a == "--max-count" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        maxCount = args[i + 1].toIntOrNull() ?: run {
                            ctx.stderr.writeUtf8("fatal: option '$a' expects a numerical value\n")
                            return CommandResult(exitCode = 129)
                        }
                        i += 2
                    }

                    a.startsWith("--max-count=") -> {
                        maxCount = a.substringAfter("=").toIntOrNull() ?: run {
                            ctx.stderr.writeUtf8("fatal: option 'max-count' expects a numerical value\n")
                            return CommandResult(exitCode = 129)
                        }
                        i++
                    }

                    a.length > 2 && a.startsWith("-n") && a.substring(2).all(Char::isDigit) -> {
                        maxCount = a.substring(2).toInt()
                        i++
                    }

                    a == "-p" || a == "-u" || a == "--patch" -> {
                        patch = true
                        i++
                    }

                    a == "--" -> {
                        afterDoubleDash = true
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git whatchanged: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        if (start == null && resolveRev(repo, a) != null) {
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

            val walk = mutableListOf<String>()
            var c: String? = tip
            while (c != null) {
                val commit =
                    try {
                        repo.objects.readCommit(c)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        break
                    }
                walk += c
                c = commit.parents.firstOrNull()
            }

            var emitted = 0
            for (sha in walk) {
                if (emitted >= maxCount) break
                val commit = repo.objects.readCommit(sha)
                if (paths.isNotEmpty() && !commitTouchesAnyOf(repo, sha, paths)) continue

                // Commit header — reuse log's default/medium presentation.
                val format =
                    when {
                        pretty == null -> null
                        pretty.startsWith("format:") -> pretty.removePrefix("format:")
                        prettyPreset(pretty) != null -> prettyPreset(pretty)
                        pretty.startsWith("%") -> pretty
                        else -> null
                    }
                if (format != null) {
                    ctx.stdout.writeUtf8(renderPrettyFormat(format, sha, commit))
                    if (!format.endsWith("\n")) ctx.stdout.writeUtf8("\n")
                } else {
                    ctx.stdout.writeUtf8("commit $sha\n")
                    ctx.stdout.writeUtf8("Author: ${commit.author.name} <${commit.author.email}>\n")
                    ctx.stdout.writeUtf8("Date:   ${gitDateFormat(commit.author.whenSeconds, commit.author.tz)}\n")
                    ctx.stdout.writeUtf8("\n")
                    for (line in commit.message.trimEnd('\n').split('\n')) {
                        ctx.stdout.writeUtf8("    $line\n")
                    }
                    ctx.stdout.writeUtf8("\n")
                }

                if (patch) {
                    ctx.stdout.writeUtf8(commitDiffPatch(repo, sha, paths))
                    ctx.stdout.writeUtf8("\n")
                } else {
                    // Default whatchanged: raw diff lines.
                    val raw = commitRawDiff(repo, sha, paths)
                    if (raw.isNotEmpty()) {
                        ctx.stdout.writeUtf8(raw)
                        ctx.stdout.writeUtf8("\n")
                    }
                }
                emitted++
            }
            return CommandResult(exitCode = 0)
        }
    }

/**
 * Render `whatchanged`'s default raw diff lines for [commitSha] vs its
 * first parent: `:<srcmode> <dstmode> <srcsha> <dstsha> <status>\t<path>`.
 * Modes use the 6-digit zero-padded form git's raw output uses (`040000`
 * for trees, `000000` for the absent side). No rename detection.
 */
internal suspend fun commitRawDiff(
    repo: GitRepo,
    commitSha: String,
    pathFilter: List<String> = emptyList(),
): String {
    val commit = repo.objects.readCommit(commitSha)
    val newSide = flattenTree(repo, commit.tree)
    val oldSide =
        commit.parents.firstOrNull()?.let { p ->
            flattenTree(repo, repo.objects.readCommit(p).tree)
        } ?: emptyMap()

    // Real git's raw output abbreviates blob shas to 7 chars by default
    // and renders the absent side as a 7-char run of zeros.
    val zeroSha = "0".repeat(7)
    val sb = StringBuilder()
    for (path in (oldSide.keys + newSide.keys).sorted()) {
        if (!matchesPath(path, pathFilter)) continue
        val l = oldSide[path]
        val r = newSide[path]
        when {
            l == null && r != null -> {
                sb.append(":000000 ${rawMode(r.mode.wire)} $zeroSha ${r.sha.take(7)} A\t$path\n")
            }

            r == null && l != null -> {
                sb.append(":${rawMode(l.mode.wire)} 000000 ${l.sha.take(7)} $zeroSha D\t$path\n")
            }

            l != null && r != null -> {
                if (l.sha == r.sha && l.mode == r.mode) continue
                sb.append(
                    ":${rawMode(l.mode.wire)} ${rawMode(r.mode.wire)} " +
                        "${l.sha.take(7)} ${r.sha.take(7)} M\t$path\n",
                )
            }
        }
    }
    return sb.toString()
}

/** Zero-pad a tree-entry wire mode to git's 6-digit raw-format width. */
private fun rawMode(wire: String): String = wire.padStart(6, '0')

/**
 * Compute a stable patch-id for [commitSha] — a clean-room hash that is
 * equal for two commits whose diffs are textually equivalent modulo line
 * numbers and blob shas. Mirrors `git patch-id`'s normalization: we strip
 * the `@@ -a,b +c,d @@` line numbers and the `index <old>..<new>` blob
 * shas, keeping only the structural diff text (file headers + +/- lines).
 *
 * This is NOT git's exact byte algorithm (git hashes a specific canonical
 * stream), but it is internally consistent: equivalent patches hash equal,
 * which is all `git cherry` needs to classify `+`/`-`.
 */
internal suspend fun computePatchId(
    repo: GitRepo,
    commitSha: String,
): String {
    val patch = commitDiffPatch(repo, commitSha)
    val sb = StringBuilder()
    for (line in patch.split('\n')) {
        when {
            // Drop hunk line-number content; keep the marker so structure
            // is preserved without anchoring to positions.
            line.startsWith("@@") -> {
                sb.append("@@\n")
            }

            // Blob shas vary across history; ignore them.
            line.startsWith("index ") -> {}

            // File-header object shas also vary; the a/ b/ paths are kept
            // via the `diff --git` line, so the index line is redundant.
            else -> {
                sb.append(line).append('\n')
            }
        }
    }
    return sha1Hex(sb.toString().encodeToByteArray())
}

/** Collect [start] and all its ancestors into [into]. */
private suspend fun collectAncestorSet(
    repo: GitRepo,
    start: String,
    into: MutableSet<String>,
) {
    if (start in into) return
    val stack = ArrayDeque<String>()
    stack.addLast(start)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        if (!into.add(cur)) continue
        val c =
            try {
                repo.objects.readCommit(cur)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                continue
            }
        for (p in c.parents) {
            if (p !in into) stack.addLast(p)
        }
    }
}

/**
 * First-parent-priority DFS from [start], skipping anything in [excludes]
 * or already [visited]. Appends newest-first to [out]. Shared by shortlog
 * and cherry so their commit selection matches the log/rev-list engine.
 */
private suspend fun walkAncestry(
    repo: GitRepo,
    start: String,
    excludes: Set<String>,
    visited: MutableSet<String>,
    out: MutableList<String>,
) {
    if (start in excludes || start in visited) return
    val stack = ArrayDeque<String>()
    stack.addLast(start)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        if (cur in visited || cur in excludes) continue
        visited += cur
        out += cur
        val c =
            try {
                repo.objects.readCommit(cur)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                continue
            }
        for (p in c.parents.asReversed()) {
            if (p !in visited && p !in excludes) stack.addLast(p)
        }
    }
}
