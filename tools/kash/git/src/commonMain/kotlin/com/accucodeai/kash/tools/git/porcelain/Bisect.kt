package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.ShellInvocation
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.IndexEntry
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.RefStore

/**
 * `git bisect` — binary search for the commit that introduced a change
 * (typically a bug). The model mirrors real git's:
 *
 *  - `git bisect start [--term-old=<t>] [--term-new=<t>] [<bad> [<good>...]]`
 *  - `git bisect bad|new [<rev>]` — mark a revision as the "new"/bad end
 *  - `git bisect good|old [<rev>]` — mark a revision as a "known good" end
 *  - `git bisect skip [<rev>...]` — exclude untestable revisions
 *  - `git bisect reset [<commit>]` — end the session, return to where we
 *    were (or to `<commit>`)
 *  - `git bisect log` — print the session log (replayable)
 *  - `git bisect replay <logfile>` — re-run a recorded session
 *  - `git bisect run <cmd>...` — drive the search with a test command
 *    (exit 0 = good, 125 = skip, 1..127 = bad, >=128 = abort)
 *  - `git bisect terms [--term-good|--term-bad]` — show the terminology
 *  - `git bisect view|visualize` — list the commits still in range
 *
 * State lives under `.git/`:
 *  - `BISECT_START` — the branch (or detached sha) to return to on reset
 *  - `BISECT_TERMS` — two lines: the bad/new term, then the good/old term
 *  - `BISECT_LOG` — the human-readable, replayable session log
 *  - `BISECT_EXPECTED_REV` — the sha currently checked out for testing
 *  - `refs/bisect/<bad>` — the single known-bad commit
 *  - `refs/bisect/<good>-<sha>` — one ref per known-good commit
 *  - `refs/bisect/skip-<sha>` — one ref per skipped commit
 *
 * The midpoint and "revisions left" estimate match git's algorithm
 * (`find_bisection` / `estimate_bisect_steps`) so output reads the same.
 */
public fun gitBisectSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "bisect"

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

            val sub = args.firstOrNull()
            if (sub == null || sub == "help" || sub == "-h" || sub == "--help") {
                ctx.stdout.writeUtf8(BISECT_USAGE)
                return CommandResult(exitCode = if (sub == null) 1 else 0)
            }
            val rest = args.drop(1)
            val terms = readTerms(repo)

            return when {
                sub == "start" -> {
                    doStart(repo, ctx, rest)
                }

                sub == "reset" -> {
                    doReset(repo, ctx, rest.firstOrNull(), quiet = false)
                }

                sub == "log" -> {
                    doLog(repo, ctx)
                }

                sub == "replay" -> {
                    doReplay(repo, ctx, env, rest)
                }

                sub == "run" -> {
                    doRun(repo, ctx, env, rest)
                }

                sub == "terms" -> {
                    doTerms(repo, ctx, rest, terms)
                }

                sub == "view" || sub == "visualize" -> {
                    doView(repo, ctx)
                }

                sub == "bad" || sub == "new" || sub == terms.bad -> {
                    doMark(repo, ctx, MarkKind.BAD, rest, terms)
                }

                sub == "good" || sub == "old" || sub == terms.good -> {
                    doMark(repo, ctx, MarkKind.GOOD, rest, terms)
                }

                sub == "skip" -> {
                    doSkip(repo, ctx, rest, terms)
                }

                else -> {
                    ctx.stderr.writeUtf8("git bisect: unknown command '$sub'\n")
                    CommandResult(exitCode = 1)
                }
            }
        }
    }

private const val BISECT_USAGE: String =
    "usage: git bisect [help|start|bad|good|new|old|terms|skip|next|reset|" +
        "visualize|view|replay|log|run]\n"

// --- terminology ----------------------------------------------------------

private data class Terms(
    val bad: String,
    val good: String,
)

private suspend fun readTerms(repo: GitRepo): Terms {
    val path = "${repo.layout.gitDir}/BISECT_TERMS"
    if (!repo.fs.exists(path)) return Terms("bad", "good")
    val lines =
        repo.fs
            .readBytes(path)
            .decodeToString()
            .split('\n')
            .filter { it.isNotBlank() }
    return Terms(
        bad = lines.getOrNull(0)?.trim() ?: "bad",
        good = lines.getOrNull(1)?.trim() ?: "good",
    )
}

private suspend fun writeTerms(
    repo: GitRepo,
    terms: Terms,
) {
    repo.fs.writeBytes(
        "${repo.layout.gitDir}/BISECT_TERMS",
        "${terms.bad}\n${terms.good}\n".encodeToByteArray(),
    )
}

// --- bisect mark refs ------------------------------------------------------

private fun badRef(terms: Terms) = "refs/bisect/${terms.bad}"

private fun goodRefPrefix(terms: Terms) = "refs/bisect/${terms.good}-"

private const val SKIP_REF_PREFIX = "refs/bisect/skip-"

private suspend fun readBad(
    repo: GitRepo,
    terms: Terms,
): String? = repo.refs.resolve(badRef(terms))

private suspend fun readGoods(
    repo: GitRepo,
    terms: Terms,
): List<String> {
    val prefix = goodRefPrefix(terms)
    return repo.refs
        .listRefs("refs/bisect")
        .filter { it.first.startsWith(prefix) }
        .map { it.second }
}

private suspend fun readSkips(repo: GitRepo): Set<String> =
    repo.refs
        .listRefs("refs/bisect")
        .filter { it.first.startsWith(SKIP_REF_PREFIX) }
        .map { it.second }
        .toSet()

private enum class MarkKind { BAD, GOOD, SKIP }

private suspend fun writeMark(
    repo: GitRepo,
    kind: MarkKind,
    sha: String,
    terms: Terms,
) {
    when (kind) {
        MarkKind.BAD -> repo.refs.writeRef(badRef(terms), sha)
        MarkKind.GOOD -> repo.refs.writeRef("${goodRefPrefix(terms)}$sha", sha)
        MarkKind.SKIP -> repo.refs.writeRef("$SKIP_REF_PREFIX$sha", sha)
    }
}

// --- start -----------------------------------------------------------------

private suspend fun doStart(
    repo: GitRepo,
    ctx: CommandContext,
    args: List<String>,
): CommandResult {
    var termBad = "bad"
    var termGood = "good"
    val revs = mutableListOf<String>()
    var i = 0
    var afterDashDash = false
    while (i < args.size) {
        val a = args[i]
        when {
            afterDashDash -> {}

            // pathspec — accepted but not used to limit
            a == "--" -> {
                afterDashDash = true
            }

            a == "--term-new" || a == "--term-bad" -> {
                termBad = args.getOrNull(++i) ?: termBad
            }

            a == "--term-old" || a == "--term-good" -> {
                termGood = args.getOrNull(++i) ?: termGood
            }

            a.startsWith("--term-new=") || a.startsWith("--term-bad=") -> {
                termBad = a.substringAfter('=')
            }

            a.startsWith("--term-old=") || a.startsWith("--term-good=") -> {
                termGood = a.substringAfter('=')
            }

            a == "--no-checkout" -> {}

            // accepted; we always update the worktree
            a.startsWith("-") -> {
                ctx.stderr.writeUtf8("git bisect start: unsupported option '$a'\n")
                return CommandResult(exitCode = 1)
            }

            else -> {
                revs += a
            }
        }
        i++
    }

    // Already bisecting? Return to the original ref first, exactly like git.
    if (repo.fs.exists("${repo.layout.gitDir}/BISECT_START")) {
        doReset(repo, ctx, null, quiet = true)
    }

    // Record where to return to.
    val startRef =
        when (val h = repo.refs.readHead()) {
            is RefStore.Head.Symbolic -> {
                h.target.removePrefix("refs/heads/")
            }

            is RefStore.Head.Detached -> {
                h.sha
            }

            null -> {
                ctx.stderr.writeUtf8("fatal: bad HEAD - we need a HEAD\n")
                return CommandResult(exitCode = 128)
            }
        }
    repo.fs.writeBytes("${repo.layout.gitDir}/BISECT_START", "$startRef\n".encodeToByteArray())
    val terms = Terms(termBad, termGood)
    writeTerms(repo, terms)

    // Resolve the optional <bad> and <good...> revs up front.
    val badArg = revs.getOrNull(0)
    val goodArgs = revs.drop(1)

    // Seed the log.
    writeBisectLog(repo, buildString { append("git bisect start") })

    if (badArg != null) {
        val badSha =
            resolveRev(repo, badArg) ?: run {
                ctx.stderr.writeUtf8("fatal: '$badArg' does not appear to be a valid revision\n")
                return CommandResult(exitCode = 128)
            }
        writeMark(repo, MarkKind.BAD, badSha, terms)
        appendBisectLog(repo, "git bisect ${terms.bad} $badSha")
    }
    for (g in goodArgs) {
        val gSha =
            resolveRev(repo, g) ?: run {
                ctx.stderr.writeUtf8("fatal: '$g' does not appear to be a valid revision\n")
                return CommandResult(exitCode = 128)
            }
        writeMark(repo, MarkKind.GOOD, gSha, terms)
        appendBisectLog(repo, "git bisect ${terms.good} $gSha")
    }

    return when (val p = advance(repo, ctx, terms)) {
        is Progress.NeedMore -> CommandResult(exitCode = 0)
        is Progress.Continue -> CommandResult(exitCode = 0)
        is Progress.FirstFound -> CommandResult(exitCode = 0)
        is Progress.OnlySkipped -> CommandResult(exitCode = 0)
        is Progress.Error -> CommandResult(exitCode = p.code)
    }
}

// --- mark (bad / good) -----------------------------------------------------

private suspend fun doMark(
    repo: GitRepo,
    ctx: CommandContext,
    kind: MarkKind,
    args: List<String>,
    terms: Terms,
): CommandResult {
    if (!isBisecting(repo)) {
        ctx.stderr.writeUtf8("You need to start by \"git bisect start\"\n")
        return CommandResult(exitCode = 1)
    }
    val termWord = if (kind == MarkKind.BAD) terms.bad else terms.good
    // `bad` takes at most one rev; `good` takes one or more.
    val targets =
        if (args.isEmpty()) {
            listOf(
                currentRev(repo) ?: run {
                    ctx.stderr.writeUtf8("fatal: bad HEAD\n")
                    return CommandResult(exitCode = 128)
                },
            )
        } else {
            args.map { spec ->
                resolveRev(repo, spec) ?: run {
                    ctx.stderr.writeUtf8("fatal: '$spec' does not appear to be a valid revision\n")
                    return CommandResult(exitCode = 128)
                }
            }
        }
    if (kind == MarkKind.BAD && targets.size > 1) {
        ctx.stderr.writeUtf8("fatal: '$termWord' can take only one argument.\n")
        return CommandResult(exitCode = 1)
    }
    for (sha in targets) {
        writeMark(repo, kind, sha, terms)
        appendBisectLog(repo, "git bisect $termWord $sha")
    }
    return renderProgress(advance(repo, ctx, terms))
}

private suspend fun doSkip(
    repo: GitRepo,
    ctx: CommandContext,
    args: List<String>,
    terms: Terms,
): CommandResult {
    if (!isBisecting(repo)) {
        ctx.stderr.writeUtf8("You need to start by \"git bisect start\"\n")
        return CommandResult(exitCode = 1)
    }
    val targets =
        if (args.isEmpty()) {
            listOf(currentRev(repo) ?: return CommandResult(exitCode = 128))
        } else {
            args.flatMap { spec -> expandSkipRange(repo, spec) }
        }
    for (sha in targets) {
        writeMark(repo, MarkKind.SKIP, sha, terms)
        appendBisectLog(repo, "git bisect skip $sha")
    }
    return renderProgress(advance(repo, ctx, terms))
}

/** Expand a `<a>..<b>` skip range into its constituent commits, else resolve one. */
private suspend fun expandSkipRange(
    repo: GitRepo,
    spec: String,
): List<String> {
    val dots = spec.indexOf("..")
    if (dots < 0) {
        return resolveRev(repo, spec)?.let { listOf(it) } ?: emptyList()
    }
    val from = spec.substring(0, dots)
    val to = spec.substring(dots + 2)
    val fromSha = resolveRev(repo, from) ?: return emptyList()
    val toSha = resolveRev(repo, to) ?: return emptyList()
    // commits reachable from `to` but not from `from` (exclusive of `from`).
    val cache = HashMap<String, CommitPayload>()
    val exclude = collectAncestors(repo, fromSha, cache)
    return collectAncestors(repo, toSha, cache).filter { it !in exclude }
}

private suspend fun renderProgress(p: Progress): CommandResult =
    when (p) {
        is Progress.Error -> CommandResult(exitCode = p.code)
        else -> CommandResult(exitCode = 0)
    }

// --- the core step ---------------------------------------------------------

private sealed interface Progress {
    /** Not enough info yet (need a good and a bad). */
    object NeedMore : Progress

    /** Checked out [rev] for the user to test. */
    data class Continue(
        val rev: String,
    ) : Progress

    /** Found the first bad commit. */
    data class FirstFound(
        val rev: String,
    ) : Progress

    /** Only skipped commits remain; the answer is one of [revs]. */
    data class OnlySkipped(
        val revs: List<String>,
    ) : Progress

    data class Error(
        val code: Int,
    ) : Progress
}

/**
 * Compute the current bisection state and act on it: if we can pick a
 * next commit, check it out and print the "Bisecting:" line; if the
 * search is over, print the first-bad block; otherwise report what's
 * still missing.
 */
private suspend fun advance(
    repo: GitRepo,
    ctx: CommandContext,
    terms: Terms,
): Progress {
    val bad = readBad(repo, terms)
    val goods = readGoods(repo, terms)
    if (bad == null && goods.isEmpty()) return Progress.NeedMore
    if (bad == null) {
        ctx.stdout.writeUtf8(
            "Now you need to give me at least one ${terms.bad} revision.\n" +
                "(You can use \"git bisect ${terms.bad}\" for that.)\n",
        )
        return Progress.NeedMore
    }
    if (goods.isEmpty()) {
        ctx.stdout.writeUtf8(
            "Now you need to give me at least one ${terms.good} revision.\n" +
                "(You can use \"git bisect ${terms.good}\" for that.)\n",
        )
        return Progress.NeedMore
    }

    val skips = readSkips(repo)

    // One decode per commit for the whole computation: collectAncestors,
    // reachesWithin (called once per range commit), and the date sort all
    // read parents/committer-time through this cache, so the graph work is
    // in-memory rather than O(N^2) zlib inflations.
    val cache = HashMap<String, CommitPayload>()

    // The suspect range: ancestors of bad (inclusive), minus anything
    // reachable from a good commit.
    val ancestorsOfGoods = mutableSetOf<String>()
    for (g in goods) ancestorsOfGoods += collectAncestors(repo, g, cache)
    val rangeSet = collectAncestors(repo, bad, cache).filter { it !in ancestorsOfGoods }.toSet()

    // Committer-time sort keys (a suspend lookup can't run inside a
    // comparator lambda, so materialize them up front — from the cache).
    val whenBy = HashMap<String, Long>(rangeSet.size * 2)
    for (c in rangeSet) whenBy[c] = commitOf(repo, c, cache).committer.whenSeconds

    // Testable = range minus the bad commit itself minus skipped commits.
    val candidatesAll = rangeSet.filter { it != bad }
    val testable = candidatesAll.filter { it !in skips }

    if (testable.isEmpty()) {
        return if (candidatesAll.isEmpty()) {
            // bad's parent(s) are all good: bad is the first bad commit.
            printFirstFound(repo, ctx, bad, terms)
            Progress.FirstFound(bad)
        } else {
            // Everything left was skipped.
            ctx.stdout.writeUtf8(
                "There are only 'skip'ped commits left to test.\n" +
                    "The first ${terms.bad} commit could be any of:\n",
            )
            val sorted = candidatesAll.sortedByDescending { whenBy[it] ?: 0L }
            for (s in sorted) ctx.stdout.writeUtf8("$s\n")
            ctx.stdout.writeUtf8("We cannot bisect more!\n")
            Progress.OnlySkipped(sorted)
        }
    }

    // Pick the midpoint: maximize min(reaches, all - reaches), scanning
    // newest-first; skip over skipped/bad commits.
    val all = rangeSet.size
    val ordered = rangeSet.sortedWith(compareByDescending<String> { whenBy[it] ?: 0L }.thenBy { it })
    val reaches = HashMap<String, Int>(all * 2)
    for (c in ordered) reaches[c] = reachesWithin(repo, c, rangeSet, cache)

    var best: String? = null
    var bestWeight = -1
    var bestReaches = 0
    for (c in ordered) {
        if (c == bad || c in skips) continue
        val r = reaches[c] ?: continue
        val w = minOf(r, all - r)
        if (w > bestWeight) {
            bestWeight = w
            best = c
            bestReaches = r
        }
    }
    val chosen = best ?: return Progress.Error(0)

    // Check it out and report.
    val code = materializeCommit(repo, ctx, chosen)
    if (code != 0) return Progress.Error(code)
    repo.refs.writeHeadDetached(chosen)
    repo.fs.writeBytes("${repo.layout.gitDir}/BISECT_EXPECTED_REV", "$chosen\n".encodeToByteArray())
    val now = nowReflogTime(ctx)
    recordReflog(
        repo,
        "HEAD",
        null,
        chosen,
        null,
        now.first,
        now.second,
        "checkout: moving to $chosen",
    )

    val nr = all - bestReaches - 1
    val steps = estimateBisectSteps(all)
    val stepWord = if (steps == 1) "step" else "steps"
    val revWord = if (nr == 1) "revision" else "revisions"
    ctx.stdout.writeUtf8(
        "Bisecting: $nr $revWord left to test after this (roughly $steps $stepWord)\n",
    )
    val subject =
        repo.objects
            .readCommit(chosen)
            .message
            .substringBefore('\n')
    ctx.stdout.writeUtf8("[$chosen] $subject\n")
    return Progress.Continue(chosen)
}

private suspend fun printFirstFound(
    repo: GitRepo,
    ctx: CommandContext,
    sha: String,
    terms: Terms,
) {
    appendBisectLog(
        repo,
        "# first ${terms.bad} commit: [$sha] ${repo.objects.readCommit(sha).message.substringBefore('\n')}",
    )
    ctx.stdout.writeUtf8("$sha is the first ${terms.bad} commit\n")
    val commit = repo.objects.readCommit(sha)
    val fmt = prettyPreset("medium")!!
    ctx.stdout.writeUtf8(renderPrettyFormat(fmt, sha, commit))
}

// --- reset -----------------------------------------------------------------

private suspend fun doReset(
    repo: GitRepo,
    ctx: CommandContext,
    target: String?,
    quiet: Boolean,
): CommandResult {
    if (!isBisecting(repo) && target == null) {
        if (!quiet) ctx.stderr.writeUtf8("We are not bisecting.\n")
        return CommandResult(exitCode = 0)
    }

    val startRef =
        if (repo.fs.exists("${repo.layout.gitDir}/BISECT_START")) {
            repo.fs
                .readBytes("${repo.layout.gitDir}/BISECT_START")
                .decodeToString()
                .trim()
        } else {
            null
        }

    // Decide where to land.
    val landSpec = target ?: startRef
    if (landSpec != null) {
        val isBranch = repo.refs.resolve("refs/heads/$landSpec") != null
        val sha =
            if (isBranch) {
                repo.refs.resolve("refs/heads/$landSpec")
            } else {
                resolveRev(repo, landSpec)
            }
        if (sha == null) {
            if (!quiet) ctx.stderr.writeUtf8("fatal: Could not check out original HEAD '$landSpec'.\n")
            clearBisectState(repo)
            return CommandResult(exitCode = 1)
        }
        val code = materializeCommit(repo, ctx, sha)
        if (code != 0) return CommandResult(exitCode = code)
        if (isBranch) {
            repo.refs.writeHeadSymbolic("refs/heads/$landSpec")
            if (!quiet) ctx.stdout.writeUtf8("Switched to branch '$landSpec'\n")
        } else {
            repo.refs.writeHeadDetached(sha)
            if (!quiet) ctx.stdout.writeUtf8("HEAD is now at ${sha.substring(0, 7)}\n")
        }
    }

    clearBisectState(repo)
    return CommandResult(exitCode = 0)
}

/** Remove every ref under `refs/bisect/` and the `BISECT_` state files. */
private suspend fun clearBisectState(repo: GitRepo) {
    val fs = repo.fs
    val bisectRefsDir = "${repo.layout.gitDir}/refs/bisect"
    if (fs.exists(bisectRefsDir) && fs.isDirectory(bisectRefsDir)) {
        for (name in fs.list(bisectRefsDir)) {
            val p = "$bisectRefsDir/$name"
            if (!fs.isDirectory(p)) fs.remove(p)
        }
        fs.remove(bisectRefsDir)
    }
    for (n in listOf(
        "BISECT_START",
        "BISECT_TERMS",
        "BISECT_LOG",
        "BISECT_EXPECTED_REV",
        "BISECT_NAMES",
        "BISECT_ANCESTORS_OK",
    )) {
        val p = "${repo.layout.gitDir}/$n"
        if (fs.exists(p)) fs.remove(p)
    }
}

// --- log / replay ----------------------------------------------------------

private suspend fun doLog(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    val path = "${repo.layout.gitDir}/BISECT_LOG"
    if (!isBisecting(repo) || !repo.fs.exists(path)) {
        ctx.stderr.writeUtf8("We are not bisecting.\n")
        return CommandResult(exitCode = 1)
    }
    ctx.stdout.writeUtf8(repo.fs.readBytes(path).decodeToString())
    return CommandResult(exitCode = 0)
}

private suspend fun doReplay(
    repo: GitRepo,
    ctx: CommandContext,
    env: GitEnv,
    args: List<String>,
): CommandResult {
    val file = args.firstOrNull()
    if (file == null) {
        ctx.stderr.writeUtf8("usage: git bisect replay <logfile>\n")
        return CommandResult(exitCode = 1)
    }
    val abs = if (file.startsWith("/")) file else "${env.cwd}/$file"
    if (!repo.fs.exists(abs)) {
        ctx.stderr.writeUtf8("cannot read $file for replaying\n")
        return CommandResult(exitCode = 1)
    }
    // Fresh slate before replaying.
    if (isBisecting(repo)) doReset(repo, ctx, null, quiet = true)
    clearBisectState(repo)

    val text = repo.fs.readBytes(abs).decodeToString()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        // Accept both "git bisect <cmd> ..." and "bisect <cmd> ...".
        val toks = line.split(Regex("\\s+"))
        val idx = toks.indexOf("bisect")
        if (idx < 0 || idx + 1 >= toks.size) continue
        val cmd = toks[idx + 1]
        val cmdArgs = toks.drop(idx + 2)
        val terms = readTerms(repo)
        val result =
            when (cmd) {
                "start" -> doStart(repo, ctx, cmdArgs)
                "bad", "new", terms.bad -> doMark(repo, ctx, MarkKind.BAD, cmdArgs, terms)
                "good", "old", terms.good -> doMark(repo, ctx, MarkKind.GOOD, cmdArgs, terms)
                "skip" -> doSkip(repo, ctx, cmdArgs, terms)
                else -> CommandResult(exitCode = 0)
            }
        if (result.exitCode != 0) return result
    }
    return CommandResult(exitCode = 0)
}

// --- run --------------------------------------------------------------------

private suspend fun doRun(
    repo: GitRepo,
    ctx: CommandContext,
    env: GitEnv,
    args: List<String>,
): CommandResult {
    if (args.isEmpty()) {
        ctx.stderr.writeUtf8("git bisect run: no command provided.\n")
        return CommandResult(exitCode = 1)
    }
    if (!isBisecting(repo) || readBad(repo, readTerms(repo)) == null || readGoods(repo, readTerms(repo)).isEmpty()) {
        ctx.stderr.writeUtf8(
            "git bisect run: 'git bisect run' can only be used during an active bisection " +
                "started with good and bad commits.\n",
        )
        return CommandResult(exitCode = 1)
    }
    val runner =
        ctx.shellRunner ?: run {
            ctx.stderr.writeUtf8("git bisect run: command execution requires interpreter context\n")
            return CommandResult(exitCode = 2)
        }
    val script = args.joinToString(" ") { shellQuote(it) }
    val display = args.joinToString(" ")

    while (true) {
        val terms = readTerms(repo)
        val cur =
            currentRev(repo) ?: run {
                ctx.stderr.writeUtf8("git bisect run: no commit checked out\n")
                return CommandResult(exitCode = 1)
            }
        ctx.stdout.writeUtf8("running $display\n")
        val exit =
            runner.run(
                ShellInvocation(
                    script = script,
                    stdout = ctx.stdout,
                    scriptName = "bisect-run",
                    stdin = ctx.stdin,
                    stderr = ctx.stderr,
                    isCLine = true,
                ),
            )

        val kind =
            when {
                exit == 0 -> {
                    MarkKind.GOOD
                }

                exit == 125 -> {
                    MarkKind.SKIP
                }

                exit in 1..127 -> {
                    MarkKind.BAD
                }

                else -> {
                    ctx.stderr.writeUtf8(
                        "bisect run failed: exit code $exit from '$display' is < 0 or >= 128\n",
                    )
                    return CommandResult(exitCode = exit)
                }
            }
        val termWord =
            when (kind) {
                MarkKind.BAD -> terms.bad
                MarkKind.GOOD -> terms.good
                MarkKind.SKIP -> "skip"
            }
        writeMark(repo, kind, cur, terms)
        appendBisectLog(repo, "git bisect $termWord $cur")

        when (val p = advance(repo, ctx, terms)) {
            is Progress.Continue -> {
                continue
            }

            is Progress.FirstFound -> {
                ctx.stdout.writeUtf8("bisect run success\n")
                return CommandResult(exitCode = 0)
            }

            is Progress.OnlySkipped -> {
                return CommandResult(exitCode = 0)
            }

            is Progress.NeedMore -> {
                return CommandResult(exitCode = 0)
            }

            is Progress.Error -> {
                return CommandResult(exitCode = p.code)
            }
        }
    }
}

private fun shellQuote(s: String): String =
    if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "_-./=:@" }) {
        s
    } else {
        "'" + s.replace("'", "'\\''") + "'"
    }

// --- terms ------------------------------------------------------------------

private suspend fun doTerms(
    repo: GitRepo,
    ctx: CommandContext,
    args: List<String>,
    terms: Terms,
): CommandResult {
    when (args.firstOrNull()) {
        "--term-good", "--term-old" -> {
            ctx.stdout.writeUtf8("${terms.good}\n")
            return CommandResult(exitCode = 0)
        }

        "--term-bad", "--term-new" -> {
            ctx.stdout.writeUtf8("${terms.bad}\n")
            return CommandResult(exitCode = 0)
        }

        null -> {
            if (!isBisecting(repo)) {
                // git falls back to the defaults message when not bisecting.
                ctx.stderr.writeUtf8(
                    "no terms defined\n",
                )
                return CommandResult(exitCode = 1)
            }
            ctx.stdout.writeUtf8(
                "Your current terms are ${terms.good} for the old state\n" +
                    "and ${terms.bad} for the new state.\n",
            )
            return CommandResult(exitCode = 0)
        }

        else -> {
            ctx.stderr.writeUtf8("invalid argument ${args.first()} for 'git bisect terms'.\n")
            return CommandResult(exitCode = 1)
        }
    }
}

// --- view / visualize -------------------------------------------------------

private suspend fun doView(
    repo: GitRepo,
    ctx: CommandContext,
): CommandResult {
    if (!isBisecting(repo)) {
        ctx.stderr.writeUtf8("We are not bisecting.\n")
        return CommandResult(exitCode = 1)
    }
    val terms = readTerms(repo)
    val bad = readBad(repo, terms)
    val goods = readGoods(repo, terms)
    if (bad == null) {
        ctx.stdout.writeUtf8("No suspects yet.\n")
        return CommandResult(exitCode = 0)
    }
    val cache = HashMap<String, CommitPayload>()
    val ancestorsOfGoods = mutableSetOf<String>()
    for (g in goods) ancestorsOfGoods += collectAncestors(repo, g, cache)
    val range = collectAncestors(repo, bad, cache).filter { it !in ancestorsOfGoods }
    val whenBy = HashMap<String, Long>(range.size * 2)
    for (c in range) whenBy[c] = commitOf(repo, c, cache).committer.whenSeconds
    val ordered = range.sortedWith(compareByDescending<String> { whenBy[it] ?: 0L }.thenBy { it })
    for (sha in ordered) {
        val subject = commitOf(repo, sha, cache).message.substringBefore('\n')
        ctx.stdout.writeUtf8("${sha.substring(0, 7)} $subject\n")
    }
    return CommandResult(exitCode = 0)
}

// --- shared helpers ---------------------------------------------------------

private suspend fun isBisecting(repo: GitRepo): Boolean = repo.fs.exists("${repo.layout.gitDir}/BISECT_START")

private suspend fun currentRev(repo: GitRepo): String? = repo.refs.resolveHead()

/** Read-through cache: one decode per commit across a single computation. */
private suspend fun commitOf(
    repo: GitRepo,
    sha: String,
    cache: MutableMap<String, CommitPayload>,
): CommitPayload = cache[sha] ?: repo.objects.readCommit(sha).also { cache[sha] = it }

/** All ancestors of [sha], inclusive. */
private suspend fun collectAncestors(
    repo: GitRepo,
    sha: String,
    cache: MutableMap<String, CommitPayload>,
): Set<String> {
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.add(sha)
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (!seen.add(cur)) continue
        commitOf(repo, cur, cache).parents.forEach { queue.add(it) }
    }
    return seen
}

/** Count of commits in [rangeSet] reachable from [c] (inclusive). */
private suspend fun reachesWithin(
    repo: GitRepo,
    c: String,
    rangeSet: Set<String>,
    cache: MutableMap<String, CommitPayload>,
): Int {
    val visited = mutableSetOf<String>()
    val stack = ArrayDeque<String>()
    stack.addLast(c)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        if (cur !in rangeSet) continue
        if (!visited.add(cur)) continue
        commitOf(repo, cur, cache).parents.forEach { stack.addLast(it) }
    }
    return visited.size
}

/**
 * git's `estimate_bisect_steps`: roughly log2(all), rounded to match
 * git's "(roughly N steps)" wording exactly.
 */
private fun estimateBisectSteps(all: Int): Int {
    if (all < 3) return 0
    var n = 0
    var v = all
    while (v > 1) {
        v = v shr 1
        n++
    }
    val e = 1 shl n
    val x = all - e
    return if (e < 3 * x) n else n - 1
}

// --- bisect log file --------------------------------------------------------

private suspend fun writeBisectLog(
    repo: GitRepo,
    contents: String,
) {
    val body = if (contents.endsWith("\n")) contents else "$contents\n"
    repo.fs.writeBytes("${repo.layout.gitDir}/BISECT_LOG", body.encodeToByteArray())
}

private suspend fun appendBisectLog(
    repo: GitRepo,
    line: String,
) {
    val path = "${repo.layout.gitDir}/BISECT_LOG"
    val existing = if (repo.fs.exists(path)) repo.fs.readBytes(path).decodeToString() else ""
    repo.fs.writeBytes(path, (existing + line + "\n").encodeToByteArray())
}

// --- worktree materialization (force checkout) ------------------------------

/**
 * Update the work tree and index to match [sha]'s tree, dropping
 * tracked files that aren't in the new tree. HEAD is NOT moved here —
 * the caller decides symbolic vs detached. Mirrors a forced checkout,
 * which is what bisect always performs.
 */
private suspend fun materializeCommit(
    repo: GitRepo,
    ctx: CommandContext,
    sha: String,
): Int {
    // flattenTree (the shared diff helper) already carries each blob's
    // bytes, so we write the work tree without a second readBlob per file.
    val newFlat = flattenTree(repo, repo.objects.readCommit(sha).tree)
    val oldIndex = repo.readIndex().entries.associateBy { it.path }
    // Remove tracked files no longer present.
    for ((p, _) in oldIndex) {
        if (p !in newFlat) {
            val absPath = absPath(repo.layout.workTree, p)
            if (ctx.fs.exists(absPath)) ctx.fs.remove(absPath)
        }
    }
    // Write the new tree's files and rebuild the index from the same data.
    val indexEntries = ArrayList<IndexEntry>(newFlat.size)
    for ((p, e) in newFlat) {
        val absPath = absPath(repo.layout.workTree, p)
        val parent = absPath.substringBeforeLast('/')
        if (parent.isNotEmpty()) ctx.fs.mkdirs(parent)
        val mode = if (e.mode == FileMode.EXECUTABLE) 0b111_101_101 else 0b110_100_100
        ctx.fs.writeBytes(absPath, e.bytes, mode = mode)
        indexEntries += IndexEntry(mode = e.mode, size = e.bytes.size, sha = e.sha, path = p)
    }
    repo.writeIndex(IndexFile(version = 2, entries = indexEntries))
    return 0
}

private fun absPath(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"
