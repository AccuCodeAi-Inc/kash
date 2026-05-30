package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git rev-list` — list commit shas reachable from the given tips,
 * minus those reachable from any excluded tip. Output is in git's
 * default commit-date order (newest first) unless `--reverse` flips it.
 *
 * Flags:
 *  - `<rev>` / `<from>..<to>` / `<a>...<b>` / `^<rev>` — history selection
 *  - `--all` / `--branches` / `--tags` / `--remotes` — ref globs
 *  - `--max-count=<N>` / `-n <N>` / `-<N>` — limit
 *  - `--skip=<N>` — drop the first N
 *  - `--reverse` — emit oldest-first
 *  - `--count` — emit a single integer count
 *  - `--no-merges` / `--merges` / `--first-parent`
 *  - `--author=<pat>` / `--committer=<pat>` / `--grep=<pat>` /
 *    `-i` / `--all-match` / `--invert-grep`
 *  - `--since`/`--after` / `--until`/`--before`
 */
public fun gitRevListSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "rev-list"

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
            var maxCount = Int.MAX_VALUE
            var skip = 0
            var reverse = false
            var countOnly = false

            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
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

                    a.length > 2 && a.startsWith("-n") && a.substring(2).all(Char::isDigit) -> {
                        maxCount = a.substring(2).toInt()
                        i++
                    }

                    a.length > 1 && a[0] == '-' && a.substring(1).all(Char::isDigit) -> {
                        maxCount = a.substring(1).toInt()
                        i++
                    }

                    a.startsWith("--skip=") -> {
                        skip = a.substringAfter("=").toIntOrNull() ?: 0
                        i++
                    }

                    a == "--reverse" -> {
                        reverse = true
                        i++
                    }

                    a == "--count" -> {
                        countOnly = true
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

                    a == "--first-parent" -> {
                        sel.firstParent = true
                        i++
                    }

                    a == "--all" -> {
                        addRefGlob(repo, "refs/heads", sel)
                        addRefGlob(repo, "refs/remotes", sel)
                        addRefGlob(repo, "refs/tags", sel)
                        i++
                    }

                    a == "--branches" -> {
                        addRefGlob(repo, "refs/heads", sel)
                        i++
                    }

                    a == "--tags" -> {
                        addRefGlob(repo, "refs/tags", sel)
                        i++
                    }

                    a == "--remotes" -> {
                        addRefGlob(repo, "refs/remotes", sel)
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

                    a.startsWith("^") -> {
                        sel.excludes += a.substring(1)
                        i++
                    }

                    a.contains("...") -> {
                        val (lo, hi) = a.split("...", limit = 2)
                        sel.symmetric += (lo.ifEmpty { "HEAD" }) to (hi.ifEmpty { "HEAD" })
                        i++
                    }

                    a.contains("..") -> {
                        val (lo, hi) = a.split("..", limit = 2)
                        if (lo.isNotEmpty()) sel.excludes += lo
                        sel.includes += hi.ifEmpty { "HEAD" }
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git rev-list: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        sel.includes += a
                        i++
                    }
                }
            }

            val walk =
                computeWalk(repo, sel) {
                    ctx.stderr.writeUtf8("fatal: ambiguous argument '$it'\n")
                }
                    ?: return CommandResult(exitCode = 128)

            var ordered =
                walk
                    .filter { (_, c) -> filters.matches(c) }
                    .map { it.first }
            if (skip > 0) ordered = ordered.drop(skip)
            if (maxCount != Int.MAX_VALUE) ordered = ordered.take(maxCount)
            if (reverse) ordered = ordered.reversed()

            if (countOnly) {
                ctx.stdout.writeUtf8("${ordered.size}\n")
            } else {
                for (s in ordered) ctx.stdout.writeUtf8("$s\n")
            }
            return CommandResult(exitCode = 0)
        }
    }

/** History-selection inputs shared by `git log` and `git rev-list`. */
internal class WalkOptions {
    val includes = mutableListOf<String>()
    val excludes = mutableListOf<String>()
    val symmetric = mutableListOf<Pair<String, String>>()
    var firstParent = false
}

/** Commit-content filters shared by `git log` and `git rev-list`. */
internal class CommitFilters {
    val authors = mutableListOf<String>()
    val committers = mutableListOf<String>()
    val greps = mutableListOf<String>()
    var ignoreCase = false
    var allMatch = false
    var invertGrep = false
    var noMerges = false
    var mergesOnly = false
    var since: Long? = null
    var until: Long? = null

    fun hasGrepFilters(): Boolean = authors.isNotEmpty() || committers.isNotEmpty() || greps.isNotEmpty()

    fun matches(c: CommitPayload): Boolean {
        if (noMerges && c.parents.size > 1) return false
        if (mergesOnly && c.parents.size <= 1) return false
        since?.let { if (commitInstant(c.committer.whenSeconds) < it) return false }
        until?.let { if (commitInstant(c.committer.whenSeconds) > it) return false }

        if (!hasGrepFilters()) return true

        // Each predicate group is an OR over its patterns. git's
        // `--all-match` ANDs the groups (and grep patterns); default ORs
        // all of them together (any match keeps the commit). The author/
        // committer/grep distinction is by *which field* the pattern
        // tests, but they still participate in the same AND/OR combination.
        fun contains(
            hay: String,
            needle: String,
        ): Boolean =
            if (ignoreCase) {
                Regex(Regex.escape(needle), RegexOption.IGNORE_CASE).containsMatchIn(hay) ||
                    runCatching { Regex(needle, RegexOption.IGNORE_CASE).containsMatchIn(hay) }.getOrDefault(false)
            } else {
                runCatching { Regex(needle).containsMatchIn(hay) }.getOrDefault(hay.contains(needle))
            }

        val authorField = "${c.author.name} <${c.author.email}>"
        val committerField = "${c.committer.name} <${c.committer.email}>"

        // git semantics:
        //  - author / committer constraints are always ANDed with the rest;
        //    each is an OR over its own patterns.
        //  - the --grep set is OR'd internally by default, AND'd under
        //    --all-match, then ANDed with author/committer.
        //  - --invert-grep flips the grep group's verdict.
        val authorOk = authors.isEmpty() || authors.any { contains(authorField, it) }
        val committerOk = committers.isEmpty() || committers.any { contains(committerField, it) }
        val grepRaw =
            when {
                greps.isEmpty() -> true
                allMatch -> greps.all { contains(c.message, it) }
                else -> greps.any { contains(c.message, it) }
            }
        val grepOk = if (invertGrep && greps.isNotEmpty()) !grepRaw else grepRaw

        return authorOk && committerOk && grepOk
    }
}

/**
 * Resolve [sel] to an ordered list of (sha, commit), newest-first by
 * commit (committer) date — git's default ordering. Honors excludes,
 * symmetric `a...b`, and `--first-parent`. Calls [onAmbiguous] and
 * returns null if an include/exclude rev fails to resolve.
 */
internal suspend fun computeWalk(
    repo: GitRepo,
    sel: WalkOptions,
    onAmbiguous: suspend (String) -> Unit,
): List<Pair<String, CommitPayload>>? {
    val includes = sel.includes.toMutableList()
    val excludes = sel.excludes.toMutableList()

    // a...b: symmetric difference — commits reachable from exactly one of
    // a or b. Implemented as includes {a, b} minus their merge base set.
    for ((a, b) in sel.symmetric) {
        includes += a
        includes += b
        val sa =
            resolveRev(repo, a) ?: run {
                onAmbiguous(a)
                return null
            }
        val sb =
            resolveRev(repo, b) ?: run {
                onAmbiguous(b)
                return null
            }
        for (mb in mergeBases(repo, sa, sb)) excludes += mb
    }

    if (includes.isEmpty()) {
        val head =
            repo.refs.resolveHead() ?: run {
                onAmbiguous("HEAD")
                return null
            }
        includes += head
    }

    val excludeShas = mutableSetOf<String>()
    for (e in excludes) {
        val s = resolveRev(repo, e) ?: continue
        collectAncestors(repo, s, excludeShas, sel.firstParent)
    }

    // Priority-queue traversal that reproduces git's default commit-date
    // order: always emit the queued commit with the newest committer date;
    // ties resolve by insertion order (FIFO), so equal-timestamp histories
    // fall back to the tip→root / first-parent-before-second-parent order
    // git produces. Parents are queued first-parent-first so a merge's
    // mainline precedes its merged-in side when dates tie.
    val resolvedTips = mutableListOf<String>()
    for (start in includes) {
        val s =
            resolveRev(repo, start) ?: run {
                onAmbiguous(start)
                return null
            }
        resolvedTips += s
    }

    data class QEntry(
        val sha: String,
        val date: Long,
        val seq: Long,
    )

    var seqCounter = 0L
    val queued = mutableSetOf<String>()
    // A simple list acting as a priority queue; histories scripts log over
    // are small enough that the linear scan is irrelevant.
    val queue = mutableListOf<QEntry>()
    val payloads = mutableMapOf<String, CommitPayload>()

    suspend fun enqueue(sha: String) {
        if (sha in excludeShas || sha in queued) return
        val c =
            try {
                repo.objects.readCommit(sha)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                return
            }
        queued += sha
        payloads[sha] = c
        queue += QEntry(sha, c.committer.whenSeconds, seqCounter++)
    }

    for (t in resolvedTips) enqueue(t)

    val ordered = mutableListOf<Pair<String, CommitPayload>>()
    while (queue.isNotEmpty()) {
        // Pop newest date; tie → smallest insertion seq (FIFO).
        var bestIdx = 0
        for (k in 1 until queue.size) {
            val q = queue[k]
            val b = queue[bestIdx]
            if (q.date > b.date || (q.date == b.date && q.seq < b.seq)) bestIdx = k
        }
        val cur = queue.removeAt(bestIdx)
        val c = payloads.getValue(cur.sha)
        ordered += cur.sha to c
        val parents = if (sel.firstParent) c.parents.take(1) else c.parents
        for (p in parents) enqueue(p)
    }
    return ordered
}

private suspend fun addRefGlob(
    repo: GitRepo,
    prefix: String,
    sel: WalkOptions,
) {
    for ((_, sha) in repo.refs.listRefs(prefix)) {
        sel.includes += sha
    }
}

private suspend fun mergeBases(
    repo: GitRepo,
    a: String,
    b: String,
): Set<String> {
    val ancA = mutableSetOf<String>()
    collectAncestors(repo, a, ancA, firstParent = false)
    val ancB = mutableSetOf<String>()
    collectAncestors(repo, b, ancB, firstParent = false)
    val common = ancA intersect ancB
    // Best-effort: return all common ancestors. For symmetric-difference
    // exclusion this is sufficient (excluding a superset of the true
    // merge base still yields the correct symmetric set because those
    // commits are reachable from both sides anyway).
    return common
}

internal suspend fun collectAncestors(
    repo: GitRepo,
    start: String,
    into: MutableSet<String>,
    firstParent: Boolean,
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
        val parents = if (firstParent) c.parents.take(1) else c.parents
        for (p in parents) {
            if (p !in into) stack.addLast(p)
        }
    }
}
