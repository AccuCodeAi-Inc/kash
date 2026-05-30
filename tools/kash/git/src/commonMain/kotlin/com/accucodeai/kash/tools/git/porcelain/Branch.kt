package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.isAncestor
import com.accucodeai.kash.tools.git.plumbing.mergeBase
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git branch` — list/create/delete/rename/copy branches under
 * `refs/heads/`, plus upstream-tracking management.
 *
 * Listing:
 *  - no args / `-l` / `--list`: list local branches, marking the
 *    current one with `*`.
 *  - `-r` / `--remotes`: list remote-tracking branches only.
 *  - `-a` / `--all`: list local + remote-tracking branches.
 *  - `-v` / `--verbose`: append `<short-sha> <subject>` (and tracking
 *    `[ahead N, behind M]`); `-vv` also names the upstream:
 *    `[<upstream>: ahead N, behind M]`.
 *  - `--merged[=<commit>]` / `--no-merged[=<commit>]`: keep only
 *    branches (not) reachable from the given commit (default HEAD).
 *  - `--contains <commit>` / `--no-contains <commit>`: keep only
 *    branches whose tip (does not) contain the commit.
 *  - `--sort=<key>` (`refname`, `-refname`, `committerdate`,
 *    `creatordate`, `authordate`, with optional leading `-`).
 *  - `--color[=...]`: accepted as a no-op.
 *
 * Creation:
 *  - `<name> [<start>]`: create branch at `<start>` (default HEAD).
 *  - `-t` / `--track`: also set up upstream tracking when `<start>` is
 *    a remote-tracking branch.
 *  - `-f` / `--force`: move an existing branch instead of erroring.
 *
 * Delete: `-d` / `--delete` (refuses unmerged branches), `-D` (force).
 *
 * Rename: `-m` / `--move` (one or two args), `-M` (force overwrite).
 * Copy: `-c` / `--copy`, `-C` (force overwrite).
 *
 * Upstream: `-u` / `--set-upstream-to=<upstream>` and
 * `--unset-upstream` write/clear `branch.<name>.remote` +
 * `branch.<name>.merge` in `.git/config`.
 *
 * `--show-current` prints the active branch (or nothing if detached).
 */
public fun gitBranchSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "branch"

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

            var showAll = false
            var showRemotes = false
            var showCurrent = false
            var verbose = 0
            var delete = false
            var forceDelete = false
            var move = false
            var copy = false
            var force = false
            var track = false
            var setUpstreamTo: String? = null
            var unsetUpstream = false
            var listExplicit = false
            var sortKey: String? = null
            val mergedFilters = mutableListOf<Pair<Boolean, String>>() // (merged?, commit)
            val containsFilters = mutableListOf<Pair<Boolean, String>>() // (contains?, commit)
            val positional = mutableListOf<String>()

            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--" -> {
                        i++
                        while (i < args.size) {
                            positional += args[i]
                            i++
                        }
                    }

                    a == "-a" || a == "--all" -> {
                        showAll = true
                    }

                    a == "-r" || a == "--remotes" -> {
                        showRemotes = true
                    }

                    a == "-l" || a == "--list" -> {
                        listExplicit = true
                    }

                    a == "-v" || a == "--verbose" -> {
                        verbose++
                    }

                    a == "-vv" -> {
                        verbose += 2
                    }

                    a == "-vvv" -> {
                        verbose += 3
                    }

                    a == "--show-current" -> {
                        showCurrent = true
                    }

                    a == "-d" || a == "--delete" -> {
                        delete = true
                    }

                    a == "-D" -> {
                        delete = true
                        forceDelete = true
                    }

                    a == "-m" || a == "--move" -> {
                        move = true
                    }

                    a == "-M" -> {
                        move = true
                        force = true
                    }

                    a == "-c" || a == "--copy" -> {
                        copy = true
                    }

                    a == "-C" -> {
                        copy = true
                        force = true
                    }

                    a == "-f" || a == "--force" -> {
                        force = true
                    }

                    a == "-t" || a == "--track" -> {
                        track = true
                    }

                    a == "-u" || a == "--set-upstream-to" -> {
                        i++
                        if (i >= args.size) {
                            ctx.stderr.writeUtf8("error: option `set-upstream-to' requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        setUpstreamTo = args[i]
                    }

                    a.startsWith("--set-upstream-to=") -> {
                        setUpstreamTo = a.substringAfter('=')
                    }

                    a == "--unset-upstream" -> {
                        unsetUpstream = true
                    }

                    a == "--merged" -> {
                        mergedFilters += true to "HEAD"
                    }

                    a.startsWith("--merged=") -> {
                        mergedFilters += true to a.substringAfter('=')
                    }

                    a == "--no-merged" -> {
                        mergedFilters += false to "HEAD"
                    }

                    a.startsWith("--no-merged=") -> {
                        mergedFilters += false to a.substringAfter('=')
                    }

                    a == "--contains" -> {
                        i++
                        containsFilters += true to (args.getOrNull(i) ?: "HEAD")
                    }

                    a.startsWith("--contains=") -> {
                        containsFilters += true to a.substringAfter('=')
                    }

                    a == "--no-contains" -> {
                        i++
                        containsFilters += false to (args.getOrNull(i) ?: "HEAD")
                    }

                    a.startsWith("--no-contains=") -> {
                        containsFilters += false to a.substringAfter('=')
                    }

                    a.startsWith("--sort=") -> {
                        sortKey = a.substringAfter('=')
                    }

                    a == "--color" || a.startsWith("--color=") -> {}

                    // no-op
                    a == "--no-color" -> {}

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("error: unknown switch `${a.removePrefix("-").removePrefix("-")}'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                    }
                }
                i++
            }

            val head = repo.refs.readHead()
            val current =
                when (head) {
                    is RefStore.Head.Symbolic -> head.target.removePrefix("refs/heads/")
                    else -> null
                }

            if (showCurrent) {
                if (current != null) ctx.stdout.writeUtf8("$current\n")
                return CommandResult(exitCode = 0)
            }

            // --- upstream management ------------------------------------
            if (setUpstreamTo != null) {
                return setUpstream(repo, ctx, current, positional, setUpstreamTo)
            }
            if (unsetUpstream) {
                return unsetUpstreamOf(repo, ctx, current, positional)
            }

            // --- delete --------------------------------------------------
            if (delete) {
                return deleteBranches(repo, ctx, current, positional, forceDelete, showRemotes)
            }

            // --- move / copy --------------------------------------------
            if (move || copy) {
                return moveOrCopy(repo, ctx, current, positional, copy, force)
            }

            // --- create --------------------------------------------------
            if (positional.isNotEmpty()) {
                return createBranch(repo, ctx, positional, force, track)
            }

            // --- list ----------------------------------------------------
            return listBranches(
                repo,
                ctx,
                current,
                showAll,
                showRemotes,
                verbose,
                mergedFilters,
                containsFilters,
                sortKey,
            )
        }
    }

private fun abbrev(sha: String): String = sha.substring(0, 7)

/** Subject = first line of the commit message (trimmed). */
private suspend fun subjectOf(
    repo: GitRepo,
    sha: String,
): String =
    try {
        repo.objects
            .readCommit(sha)
            .message
            .substringBefore('\n')
            .trim()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        ""
    }

private suspend fun createBranch(
    repo: GitRepo,
    ctx: CommandContext,
    positional: List<String>,
    force: Boolean,
    track: Boolean,
): CommandResult {
    val name = positional[0]
    val startSpec = positional.getOrNull(1) ?: "HEAD"
    val target =
        resolveRev(repo, startSpec) ?: run {
            ctx.stderr.writeUtf8("fatal: not a valid object name: '$startSpec'\n")
            return CommandResult(exitCode = 128)
        }
    val ref = "refs/heads/$name"
    val existing = repo.refs.resolve(ref)
    if (existing != null && !force) {
        ctx.stderr.writeUtf8("fatal: a branch named '$name' already exists\n")
        return CommandResult(exitCode = 128)
    }
    repo.refs.writeRef(ref, target)
    val now = nowReflogTime(ctx)
    val msg = if (existing != null) "branch: Reset to $startSpec" else "branch: Created from $startSpec"
    recordReflog(repo, ref, existing, target, null, now.first, now.second, msg)

    // -t / --track: set up tracking when the start point is a remote ref.
    if (track) {
        val (remote, mergeRef) = trackingTargetFor(repo, startSpec)
        if (remote != null && mergeRef != null) {
            writeUpstreamConfig(repo, ctx, name, remote, mergeRef)
            val short = "$remote/${mergeRef.removePrefix("refs/heads/")}"
            ctx.stdout.writeUtf8("branch '$name' set up to track '$short'.\n")
        }
    }
    return CommandResult(exitCode = 0)
}

/**
 * Given a start spec, work out the (remote, merge-ref) tracking pair if
 * the spec names a remote-tracking branch (`origin/foo` or
 * `refs/remotes/origin/foo`). Returns nulls otherwise.
 */
private suspend fun trackingTargetFor(
    repo: GitRepo,
    startSpec: String,
): Pair<String?, String?> {
    val rel =
        when {
            startSpec.startsWith("refs/remotes/") -> startSpec.removePrefix("refs/remotes/")
            repo.refs.resolve("refs/remotes/$startSpec") != null -> startSpec
            else -> return null to null
        }
    val slash = rel.indexOf('/')
    if (slash <= 0) return null to null
    val remote = rel.substring(0, slash)
    val branch = rel.substring(slash + 1)
    return remote to "refs/heads/$branch"
}

private suspend fun deleteBranches(
    repo: GitRepo,
    ctx: CommandContext,
    current: String?,
    positional: List<String>,
    force: Boolean,
    remotes: Boolean,
): CommandResult {
    if (positional.isEmpty()) {
        ctx.stderr.writeUtf8("fatal: branch name required\n")
        return CommandResult(exitCode = 129)
    }
    val headSha = repo.refs.resolveHead()
    var exit = 0
    for (rawName in positional) {
        val name = rawName
        if (remotes) {
            val ref = "refs/remotes/$name"
            val sha = repo.refs.resolve(ref)
            if (sha == null) {
                ctx.stderr.writeUtf8("error: remote-tracking branch '$name' not found\n")
                exit = 1
                continue
            }
            removeRefAndLog(repo, ctx, ref)
            ctx.stdout.writeUtf8("Deleted remote-tracking branch $name (was ${abbrev(sha)}).\n")
            continue
        }
        if (name == current) {
            ctx.stderr.writeUtf8(
                "error: cannot delete branch '$name' used by worktree at '${repo.layout.workTree}'\n",
            )
            exit = 1
            continue
        }
        val ref = "refs/heads/$name"
        val sha = repo.refs.resolve(ref)
        if (sha == null) {
            ctx.stderr.writeUtf8("error: branch '$name' not found\n")
            exit = 1
            continue
        }
        if (!force) {
            // Refuse unless fully merged into HEAD (or its configured upstream).
            val mergedInto =
                headSha != null && isAncestor(repo, sha, headSha)
            val mergedUpstream =
                !mergedInto &&
                    run {
                        val up = upstreamShaFor(repo, name)
                        up != null && isAncestor(repo, sha, up)
                    }
            if (!mergedInto && !mergedUpstream) {
                ctx.stderr.writeUtf8("error: the branch '$name' is not fully merged.\n")
                ctx.stderr.writeUtf8("hint: If you are sure you want to delete it, run 'git branch -D $name'\n")
                exit = 1
                continue
            }
        }
        removeRefAndLog(repo, ctx, ref)
        ctx.stdout.writeUtf8("Deleted branch $name (was ${abbrev(sha)}).\n")
    }
    return CommandResult(exitCode = exit)
}

private suspend fun removeRefAndLog(
    repo: GitRepo,
    ctx: CommandContext,
    ref: String,
) {
    val path = repo.layout.refFile(ref)
    if (ctx.fs.exists(path)) ctx.fs.remove(path)
    val logPath = "${repo.layout.logsDir}/$ref"
    if (ctx.fs.exists(logPath)) ctx.fs.remove(logPath)
}

private suspend fun moveOrCopy(
    repo: GitRepo,
    ctx: CommandContext,
    current: String?,
    positional: List<String>,
    copy: Boolean,
    force: Boolean,
): CommandResult {
    val (oldName, newName) =
        when (positional.size) {
            1 -> {
                if (current == null) {
                    ctx.stderr.writeUtf8("fatal: no branch is currently checked out\n")
                    return CommandResult(exitCode = 128)
                }
                current to positional[0]
            }

            2 -> {
                positional[0] to positional[1]
            }

            else -> {
                ctx.stderr.writeUtf8("fatal: too many arguments for a rename operation\n")
                return CommandResult(exitCode = 128)
            }
        }

    val oldRef = "refs/heads/$oldName"
    val newRef = "refs/heads/$newName"
    val sha = repo.refs.resolve(oldRef)
    if (sha == null) {
        ctx.stderr.writeUtf8("fatal: Invalid branch name: '$oldName'\n")
        return CommandResult(exitCode = 128)
    }
    if (repo.refs.resolve(newRef) != null && !force) {
        ctx.stderr.writeUtf8("fatal: a branch named '$newName' already exists\n")
        return CommandResult(exitCode = 128)
    }

    // Write the new ref.
    repo.refs.writeRef(newRef, sha)

    // Move the reflog file.
    val oldLog = "${repo.layout.logsDir}/$oldRef"
    val newLog = "${repo.layout.logsDir}/$newRef"
    if (!copy) {
        if (ctx.fs.exists(oldLog)) {
            val data = ctx.fs.readBytes(oldLog)
            val parent = newLog.substringBeforeLast('/').ifEmpty { "/" }
            if (!ctx.fs.exists(parent)) ctx.fs.mkdirs(parent)
            ctx.fs.writeBytes(newLog, data)
            ctx.fs.remove(oldLog)
        }
        // Remove the old ref.
        val oldPath = repo.layout.refFile(oldRef)
        if (ctx.fs.exists(oldPath)) ctx.fs.remove(oldPath)
        // Migrate config section branch.<old> → branch.<new>.
        migrateBranchConfig(repo, ctx, oldName, newName)
    } else {
        if (ctx.fs.exists(oldLog)) {
            val data = ctx.fs.readBytes(oldLog)
            val parent = newLog.substringBeforeLast('/').ifEmpty { "/" }
            if (!ctx.fs.exists(parent)) ctx.fs.mkdirs(parent)
            ctx.fs.writeBytes(newLog, data)
        }
        copyBranchConfig(repo, ctx, oldName, newName)
    }

    // A rename of the current branch needs HEAD repointed at the new ref.
    if (!copy && oldName == current) {
        repo.refs.writeHeadSymbolic(newRef)
    }

    // Append the rename event to the new branch's reflog (matches real git).
    val verb = if (copy) "copied" else "renamed"
    val now = nowReflogTime(ctx)
    recordReflog(
        repo,
        newRef,
        sha,
        sha,
        null,
        now.first,
        now.second,
        "Branch: $verb $oldRef to $newRef",
    )

    return CommandResult(exitCode = 0)
}

private suspend fun setUpstream(
    repo: GitRepo,
    ctx: CommandContext,
    current: String?,
    positional: List<String>,
    upstream: String,
): CommandResult {
    val branch =
        positional.firstOrNull() ?: current
            ?: run {
                ctx.stderr.writeUtf8("fatal: HEAD not found below refs/heads!\n")
                return CommandResult(exitCode = 128)
            }
    if (repo.refs.resolve("refs/heads/$branch") == null) {
        ctx.stderr.writeUtf8("error: branch '$branch' does not exist\n")
        return CommandResult(exitCode = 1)
    }
    val (remote, mergeRef, display) =
        resolveUpstreamSpec(repo, upstream) ?: run {
            ctx.stderr.writeUtf8("error: the requested upstream branch '$upstream' does not exist\n")
            return CommandResult(exitCode = 1)
        }
    writeUpstreamConfig(repo, ctx, branch, remote, mergeRef)
    ctx.stdout.writeUtf8("branch '$branch' set up to track '$display'.\n")
    return CommandResult(exitCode = 0)
}

/**
 * Resolve a `--set-upstream-to` spec to (remote, mergeRef, display).
 * Accepts `<remote>/<branch>`, `refs/remotes/<remote>/<branch>`, or a
 * local branch (remote `"."`).
 */
private suspend fun resolveUpstreamSpec(
    repo: GitRepo,
    upstream: String,
): Triple<String, String, String>? {
    val rel =
        when {
            upstream.startsWith("refs/remotes/") -> upstream.removePrefix("refs/remotes/")
            repo.refs.resolve("refs/remotes/$upstream") != null -> upstream
            else -> null
        }
    if (rel != null) {
        val slash = rel.indexOf('/')
        if (slash > 0) {
            val remote = rel.substring(0, slash)
            val branch = rel.substring(slash + 1)
            return Triple(remote, "refs/heads/$branch", "$remote/$branch")
        }
    }
    // Local branch upstream → remote "."
    val local = upstream.removePrefix("refs/heads/")
    if (repo.refs.resolve("refs/heads/$local") != null) {
        return Triple(".", "refs/heads/$local", local)
    }
    return null
}

private suspend fun unsetUpstreamOf(
    repo: GitRepo,
    ctx: CommandContext,
    current: String?,
    positional: List<String>,
): CommandResult {
    val branch =
        positional.firstOrNull() ?: current
            ?: run {
                ctx.stderr.writeUtf8("fatal: HEAD not found below refs/heads!\n")
                return CommandResult(exitCode = 128)
            }
    val cfg = readGitConfig(repo)
    val section = "branch \"$branch\""
    if (cfg[section]?.get("remote") == null && cfg[section]?.get("merge") == null) {
        ctx.stderr.writeUtf8("fatal: branch '$branch' has no upstream information\n")
        return CommandResult(exitCode = 128)
    }
    val store = ConfigStore.load(ctx.fs, repo.layout.configFile)
    store.unset(section, "remote")
    store.unset(section, "merge")
    store.save(ctx.fs, repo.layout.configFile)
    return CommandResult(exitCode = 0)
}

private suspend fun writeUpstreamConfig(
    repo: GitRepo,
    ctx: CommandContext,
    branch: String,
    remote: String,
    mergeRef: String,
) {
    val store = ConfigStore.load(ctx.fs, repo.layout.configFile)
    val section = "branch \"$branch\""
    store.set(section, "remote", remote)
    store.set(section, "merge", mergeRef)
    store.save(ctx.fs, repo.layout.configFile)
}

private suspend fun migrateBranchConfig(
    repo: GitRepo,
    ctx: CommandContext,
    oldName: String,
    newName: String,
) {
    val cfg = readGitConfig(repo)
    val oldSection = "branch \"$oldName\""
    val entries = cfg[oldSection] ?: return
    val store = ConfigStore.load(ctx.fs, repo.layout.configFile)
    val newSection = "branch \"$newName\""
    for ((k, v) in entries) store.set(newSection, k, v)
    for (k in entries.keys) store.unset(oldSection, k)
    store.save(ctx.fs, repo.layout.configFile)
}

private suspend fun copyBranchConfig(
    repo: GitRepo,
    ctx: CommandContext,
    oldName: String,
    newName: String,
) {
    val cfg = readGitConfig(repo)
    val oldSection = "branch \"$oldName\""
    val entries = cfg[oldSection] ?: return
    val store = ConfigStore.load(ctx.fs, repo.layout.configFile)
    val newSection = "branch \"$newName\""
    for ((k, v) in entries) store.set(newSection, k, v)
    store.save(ctx.fs, repo.layout.configFile)
}

/** Resolve the upstream commit sha for a local branch, or null. */
private suspend fun upstreamShaFor(
    repo: GitRepo,
    branch: String,
): String? {
    val (remote, mergeRef) = upstreamConfigOf(repo, branch) ?: return null
    val short = mergeRef.removePrefix("refs/heads/")
    return if (remote == ".") {
        repo.refs.resolve(mergeRef)
    } else {
        repo.refs.resolve("refs/remotes/$remote/$short")
    }
}

private suspend fun upstreamConfigOf(
    repo: GitRepo,
    branch: String,
): Pair<String, String>? {
    val cfg = readGitConfig(repo)
    val section = "branch \"$branch\""
    val remote = cfg[section]?.get("remote")
    val merge = cfg[section]?.get("merge")
    if (remote == null || merge == null) return null
    return remote to merge
}

/** Short display name for an upstream, e.g. `origin/main` or local `main`. */
private suspend fun upstreamDisplayOf(
    repo: GitRepo,
    branch: String,
): String? {
    val (remote, mergeRef) = upstreamConfigOf(repo, branch) ?: return null
    val short = mergeRef.removePrefix("refs/heads/")
    return if (remote == ".") short else "$remote/$short"
}

/**
 * Count commits ahead (in [branchSha] but not [upstreamSha]) and behind
 * (in [upstreamSha] but not [branchSha]) using the merge base.
 */
private suspend fun aheadBehind(
    repo: GitRepo,
    branchSha: String,
    upstreamSha: String,
): Pair<Int, Int> {
    if (branchSha == upstreamSha) return 0 to 0
    val base = mergeBase(repo, branchSha, upstreamSha)
    val ahead = countToBase(repo, branchSha, base)
    val behind = countToBase(repo, upstreamSha, base)
    return ahead to behind
}

/** Count commits reachable from [from] but stopping at [base] (exclusive). */
private suspend fun countToBase(
    repo: GitRepo,
    from: String,
    base: String?,
): Int {
    if (from == base) return 0
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.add(from)
    var count = 0
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (cur == base) continue
        if (!visited.add(cur)) continue
        count++
        val commit =
            try {
                repo.objects.readCommit(cur)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                continue
            }
        for (p in commit.parents) {
            // Don't walk past `base` — it bounds the count.
            if (p != base && p !in visited) queue.add(p)
        }
    }
    return count
}

private suspend fun listBranches(
    repo: GitRepo,
    ctx: CommandContext,
    current: String?,
    showAll: Boolean,
    showRemotes: Boolean,
    verbose: Int,
    mergedFilters: List<Pair<Boolean, String>>,
    containsFilters: List<Pair<Boolean, String>>,
    sortKey: String?,
): CommandResult {
    data class Row(
        val display: String,
        val refName: String,
        val sha: String,
        val isCurrent: Boolean,
        val isRemote: Boolean,
    )

    val rows = mutableListOf<Row>()

    if (!showRemotes || showAll) {
        val heads =
            repo.refs
                .listRefs("refs/heads")
                .map { it.first.removePrefix("refs/heads/") to it.second }
        for ((b, sha) in heads) {
            rows += Row(b, "refs/heads/$b", sha, b == current, false)
        }
    }
    if (showRemotes || showAll) {
        // `-r` lists `origin/main`; `-a` prefixes `remotes/origin/main`.
        val prefix = if (showAll) "remotes/" else ""
        val remotes =
            repo.refs
                .listRefs("refs/remotes")
                .map { it.first.removePrefix("refs/remotes/") to it.second }
        for ((r, sha) in remotes) {
            rows += Row("$prefix$r", "refs/remotes/$r", sha, false, true)
        }
    }

    // Apply --merged / --no-merged filters.
    var filtered = rows.toList()
    for ((wantMerged, spec) in mergedFilters) {
        val tip = resolveRev(repo, spec) ?: continue
        filtered =
            filtered.filter { row ->
                val merged = isAncestor(repo, row.sha, tip)
                merged == wantMerged
            }
    }
    // Apply --contains / --no-contains filters.
    for ((wantContains, spec) in containsFilters) {
        val commit = resolveRev(repo, spec) ?: continue
        filtered =
            filtered.filter { row ->
                val contains = isAncestor(repo, commit, row.sha)
                contains == wantContains
            }
    }

    // Sort.
    val sorted = sortRows(repo, filtered, sortKey) { it.display to it.sha }

    // Column width for verbose alignment.
    val maxWidth = sorted.maxOfOrNull { it.display.length } ?: 0

    for (row in sorted) {
        val mark = if (row.isCurrent) "* " else "  "
        if (verbose == 0) {
            ctx.stdout.writeUtf8("$mark${row.display}\n")
        } else {
            val padded = row.display.padEnd(maxWidth)
            val sub = subjectOf(repo, row.sha)
            val track =
                if (!row.isRemote) trackingSuffix(repo, row.display, row.sha, verbose) else ""
            val line =
                buildString {
                    append(mark)
                    append(padded)
                    append(' ')
                    append(abbrev(row.sha))
                    append(' ')
                    if (track.isNotEmpty()) {
                        append(track)
                        append(' ')
                    }
                    append(sub)
                }
            ctx.stdout.writeUtf8(line.trimEnd() + "\n")
        }
    }
    return CommandResult(exitCode = 0)
}

/**
 * Build the `[...]` tracking annotation for verbose listing. For `-v`
 * (verbose==1) git shows only `[ahead N, behind M]`; for `-vv` it
 * prefixes the upstream name (`[origin/main: ahead N]`) and emits
 * `[origin/main: gone]` when the upstream ref is missing, or
 * `[origin/main]` when in sync.
 */
private suspend fun trackingSuffix(
    repo: GitRepo,
    branch: String,
    branchSha: String,
    verbose: Int,
): String {
    val display = upstreamDisplayOf(repo, branch) ?: return ""
    val upstreamSha = upstreamShaFor(repo, branch)
    if (upstreamSha == null) {
        return if (verbose >= 2) "[$display: gone]" else ""
    }
    val (ahead, behind) = aheadBehind(repo, branchSha, upstreamSha)
    val counts =
        when {
            ahead > 0 && behind > 0 -> "ahead $ahead, behind $behind"
            ahead > 0 -> "ahead $ahead"
            behind > 0 -> "behind $behind"
            else -> ""
        }
    return if (verbose >= 2) {
        if (counts.isEmpty()) "[$display]" else "[$display: $counts]"
    } else {
        if (counts.isEmpty()) "" else "[$counts]"
    }
}

/**
 * Sort rows by [sortKey]. Default (null) is ascending refname with
 * remote-tracking branches after local ones — but real git interleaves
 * by full refname; we sort by display name within each group which
 * matches the common observed output. Date keys read the tip commit's
 * committer/author time.
 */
private suspend fun <T> sortRows(
    repo: GitRepo,
    rows: List<T>,
    sortKey: String?,
    keyOf: (T) -> Pair<String, String>,
): List<T> {
    val descending = sortKey?.startsWith("-") == true
    val key = sortKey?.removePrefix("-")
    val base =
        when (key) {
            "committerdate", "creatordate", "authordate" -> {
                // Precompute the date key per row (suspend reads outside the comparator).
                val withDate =
                    rows.map { row ->
                        val sha = keyOf(row).second
                        val ts =
                            try {
                                val c = repo.objects.readCommit(sha)
                                if (key == "authordate") c.author.whenSeconds else c.committer.whenSeconds
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                Long.MIN_VALUE
                            }
                        row to ts
                    }
                withDate.sortedBy { it.second }.map { it.first }
            }

            else -> {
                rows.sortedBy { keyOf(it).first }
            } // null / "refname" / unknown
        }
    return if (descending) base.reversed() else base
}
