package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.IndexEntry
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.RefStore

/**
 * `git switch` / `git checkout` for branch switching, and `git
 * restore` for file restoration. v1 scope:
 *
 *  - `git switch <branch>` / `git checkout <branch>`: switch HEAD to a
 *    branch, refresh the work tree + index from its tree. Refuses if
 *    the work tree has unstaged changes to tracked files (real git's
 *    "would overwrite local changes" behavior).
 *  - `git switch -c <name>` / `git checkout -b <name>`: create then
 *    switch.
 *  - `git restore <path...>`: restore the path's content from the
 *    index back into the work tree.
 *  - `git restore --staged <path...>`: unstage — copy from HEAD into
 *    the index.
 *  - `git restore --source=<rev> <path...>`: restore from an arbitrary
 *    revision.
 *
 * Out of scope (v1): detached-HEAD checkout (works for refs but
 * doesn't warn the way real git does), `--orphan`, `-m`/merge during
 * checkout.
 */
public fun gitSwitchSubcommand(): GitSubcommand = namedAlias("switch", switchCheckoutImpl(createSwitch = true))

public fun gitCheckoutSubcommand(): GitSubcommand = namedAlias("checkout", switchCheckoutImpl(createSwitch = false))

private fun namedAlias(
    n: String,
    impl: GitSubcommand,
): GitSubcommand =
    object : GitSubcommand {
        override val name: String = n

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult = impl.run(args, ctx, env)
    }

private fun switchCheckoutImpl(createSwitch: Boolean): GitSubcommand =
    object : GitSubcommand {
        override val name: String = if (createSwitch) "switch" else "checkout"

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

            var create = false
            var force = false
            val positional = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "-c" || a == "-b" -> {
                        create = true
                        i++
                    }

                    a == "-C" || a == "-B" -> {
                        create = true
                        force = true
                        i++
                    }

                    a == "-f" || a == "--force" -> {
                        force = true
                        i++
                    }

                    a == "--" -> {
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git $name: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                        i++
                    }
                }
            }
            if (positional.isEmpty()) {
                ctx.stderr.writeUtf8("git $name: target required\n")
                return CommandResult(exitCode = 129)
            }
            val target = positional[0]

            val priorHead = repo.refs.resolveHead()
            val priorBranch =
                (repo.refs.readHead() as? com.accucodeai.kash.tools.git.plumbing.RefStore.Head.Symbolic)
                    ?.target
                    ?.removePrefix("refs/heads/")
            val nowSec = nowReflogTime(ctx)
            if (create) {
                val branchRef = "refs/heads/$target"
                if (repo.refs.resolve(branchRef) != null && !force) {
                    ctx.stderr.writeUtf8("fatal: a branch named '$target' already exists\n")
                    return CommandResult(exitCode = 128)
                }
                val startSpec = positional.getOrNull(1) ?: "HEAD"
                val startSha =
                    resolveRev(repo, startSpec) ?: run {
                        ctx.stderr.writeUtf8("fatal: not a valid object name: '$startSpec'\n")
                        return CommandResult(exitCode = 128)
                    }
                repo.refs.writeRef(branchRef, startSha)
                recordReflog(
                    repo,
                    branchRef,
                    null,
                    startSha,
                    null,
                    nowSec.first,
                    nowSec.second,
                    "branch: Created from $startSpec",
                )
                switchToRef(repo, ctx, "refs/heads/$target", force)
                recordReflog(
                    repo,
                    "HEAD",
                    priorHead,
                    startSha,
                    null,
                    nowSec.first,
                    nowSec.second,
                    "checkout: moving from ${priorBranch ?: priorHead?.substring(0, 7) ?: ""} to $target",
                )
                ctx.stdout.writeUtf8("Switched to a new branch '$target'\n")
                return CommandResult(exitCode = 0)
            }

            // Plain switch.
            val ref = "refs/heads/$target"
            if (repo.refs.resolve(ref) != null) {
                val result = switchToRef(repo, ctx, ref, force)
                if (result != 0) return CommandResult(exitCode = result)
                val newHead = repo.refs.resolveHead()
                if (newHead != null) {
                    recordReflog(
                        repo,
                        "HEAD",
                        priorHead,
                        newHead,
                        null,
                        nowSec.first,
                        nowSec.second,
                        "checkout: moving from ${priorBranch ?: priorHead?.substring(0, 7) ?: ""} to $target",
                    )
                }
                ctx.stdout.writeUtf8("Switched to branch '$target'\n")
                return CommandResult(exitCode = 0)
            }
            // Detached: treat target as a commit.
            val sha =
                resolveRev(repo, target) ?: run {
                    ctx.stderr.writeUtf8("error: pathspec '$target' did not match any file(s) known to git\n")
                    return CommandResult(exitCode = 1)
                }
            val result = checkoutCommit(repo, ctx, sha, force)
            if (result != 0) return CommandResult(exitCode = result)
            repo.refs.writeHeadDetached(sha)
            recordReflog(
                repo,
                "HEAD",
                priorHead,
                sha,
                null,
                nowSec.first,
                nowSec.second,
                "checkout: moving from ${priorBranch ?: priorHead?.substring(0, 7) ?: ""} to ${sha.substring(0, 7)}",
            )
            ctx.stdout.writeUtf8("HEAD is now at ${sha.substring(0, 7)}\n")
            return CommandResult(exitCode = 0)
        }
    }

internal fun nowReflogTime(ctx: com.accucodeai.kash.api.CommandContext): Pair<Long, String> {
    val authorDate = ctx.env["GIT_AUTHOR_DATE"]?.split(' ')
    val clock = ctx.process.machine.clock
    val ts = authorDate?.firstOrNull()?.toLongOrNull() ?: clock.now().epochSeconds
    val tz = authorDate?.getOrNull(1)?.takeIf { it.length == 5 } ?: clock.localTz()
    return ts to tz
}

/** `git restore` — work tree / index restoration. */
public fun gitRestoreSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "restore"

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

            var staged = false
            var source: String? = null
            val paths = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--staged" || a == "-S" -> {
                        staged = true
                        i++
                    }

                    a == "--source" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"--source\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        source = args[i + 1]
                        i += 2
                    }

                    a.startsWith("--source=") -> {
                        source = a.substringAfter("=")
                        i++
                    }

                    a == "--" -> {
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git restore: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        paths += a
                        i++
                    }
                }
            }
            if (paths.isEmpty()) {
                ctx.stderr.writeUtf8("error: pathspec required\n")
                return CommandResult(exitCode = 129)
            }

            if (staged) {
                // Copy from HEAD (or --source) into the index.
                val srcSha =
                    when {
                        source != null -> resolveRev(repo, source)
                        else -> repo.refs.resolveHead()
                    } ?: run {
                        ctx.stderr.writeUtf8("fatal: could not resolve source\n")
                        return CommandResult(exitCode = 128)
                    }
                val srcTree = repo.objects.readCommit(srcSha).tree
                val srcFlat = flatTreeIndexEntries(repo, srcTree)
                val index =
                    repo
                        .readIndex()
                        .entries
                        .associateBy { it.path }
                        .toMutableMap()
                for (p in paths) {
                    val target = srcFlat[p]
                    if (target == null) index.remove(p) else index[p] = target
                }
                repo.writeIndex(IndexFile(version = 2, entries = index.values.toList()))
                return CommandResult(exitCode = 0)
            }

            // Worktree restoration from index (or --source).
            val srcEntries: Map<String, IndexEntry> =
                if (source != null) {
                    val srcSha =
                        resolveRev(repo, source) ?: run {
                            ctx.stderr.writeUtf8("fatal: could not resolve source\n")
                            return CommandResult(exitCode = 128)
                        }
                    flatTreeIndexEntries(repo, repo.objects.readCommit(srcSha).tree)
                } else {
                    repo.readIndex().entries.associateBy { it.path }
                }
            for (p in paths) {
                val e =
                    srcEntries[p] ?: run {
                        ctx.stderr.writeUtf8("error: pathspec '$p' did not match any file\n")
                        return CommandResult(exitCode = 1)
                    }
                val bytes = repo.objects.readBlob(e.sha)
                val abs = absOf2(repo.layout.workTree, p)
                val parent = abs.substringBeforeLast('/')
                if (parent.isNotEmpty()) ctx.fs.mkdirs(parent)
                val mode = if (e.mode == FileMode.EXECUTABLE) 0b111_101_101 else 0b110_100_100
                ctx.fs.writeBytes(abs, bytes, mode = mode)
            }
            return CommandResult(exitCode = 0)
        }
    }

/** `git reset` — move HEAD and (per mode) refresh index / work tree. */
public fun gitResetSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "reset"

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

            var mode = ResetMode.MIXED
            val positional = mutableListOf<String>()
            for (a in args) {
                when (a) {
                    "--soft" -> {
                        mode = ResetMode.SOFT
                    }

                    "--mixed" -> {
                        mode = ResetMode.MIXED
                    }

                    "--hard" -> {
                        mode = ResetMode.HARD
                    }

                    "--" -> {}

                    else -> {
                        if (a.startsWith("-")) {
                            ctx.stderr.writeUtf8("git reset: unsupported option '$a'\n")
                            return CommandResult(exitCode = 129)
                        } else {
                            positional += a
                        }
                    }
                }
            }
            val spec = positional.firstOrNull() ?: "HEAD"
            val target =
                resolveRev(repo, spec) ?: run {
                    ctx.stderr.writeUtf8("fatal: ambiguous argument '$spec'\n")
                    return CommandResult(exitCode = 128)
                }

            val priorHead = repo.refs.resolveHead()
            val now = nowReflogTime(ctx)
            val modeWord =
                when (mode) {
                    ResetMode.SOFT -> "(soft)"
                    ResetMode.MIXED -> ""
                    ResetMode.HARD -> "(hard)"
                }
            val reflogMsg = "reset: moving to $spec".let { if (modeWord.isEmpty()) it else "$it $modeWord" }
            // Move HEAD's branch (or detached HEAD) to target.
            when (val h = repo.refs.readHead()) {
                is RefStore.Head.Symbolic -> {
                    repo.refs.writeRef(h.target, target)
                    recordReflog(repo, h.target, priorHead, target, null, now.first, now.second, reflogMsg)
                }

                is RefStore.Head.Detached -> {
                    repo.refs.writeHeadDetached(target)
                    recordReflog(repo, "HEAD", priorHead, target, null, now.first, now.second, reflogMsg)
                }

                null -> {
                    repo.refs.writeHeadDetached(target)
                    recordReflog(repo, "HEAD", priorHead, target, null, now.first, now.second, reflogMsg)
                }
            }

            if (mode == ResetMode.SOFT) return CommandResult(exitCode = 0)

            // MIXED: refresh index from target.
            val targetTree = repo.objects.readCommit(target).tree
            val flat = flatTreeIndexEntries(repo, targetTree)
            repo.writeIndex(IndexFile(version = 2, entries = flat.values.toList()))

            if (mode == ResetMode.HARD) {
                // Refresh work tree to match index. Drop tracked files
                // that aren't in the target tree.
                val keepPaths = flat.keys
                for (p in collectWork2(ctx.fs, repo.layout.workTree)) {
                    if (p !in keepPaths) {
                        // Only drop tracked-but-removed paths. We can't
                        // distinguish untracked here without HEAD-before
                        // snapshot, so we leave untracked alone.
                    }
                }
                for ((p, e) in flat) {
                    val bytes = repo.objects.readBlob(e.sha)
                    val abs = absOf2(repo.layout.workTree, p)
                    val parent = abs.substringBeforeLast('/')
                    if (parent.isNotEmpty()) ctx.fs.mkdirs(parent)
                    val mode2 = if (e.mode == FileMode.EXECUTABLE) 0b111_101_101 else 0b110_100_100
                    ctx.fs.writeBytes(abs, bytes, mode = mode2)
                }
            }
            return CommandResult(exitCode = 0)
        }
    }

private enum class ResetMode { SOFT, MIXED, HARD }

private suspend fun switchToRef(
    repo: GitRepo,
    ctx: CommandContext,
    ref: String,
    force: Boolean,
): Int {
    val sha = repo.refs.resolve(ref) ?: return 1
    val code = checkoutCommit(repo, ctx, sha, force)
    if (code != 0) return code
    repo.refs.writeHeadSymbolic(ref)
    return 0
}

private suspend fun checkoutCommit(
    repo: GitRepo,
    ctx: CommandContext,
    sha: String,
    force: Boolean,
): Int {
    val newTree = repo.objects.readCommit(sha).tree
    val newFlat = flatTreeIndexEntries(repo, newTree)

    // Safety check: any tracked file that differs in the work tree from
    // its current index sha would be overwritten — refuse unless --force.
    val oldIndex = repo.readIndex().entries.associateBy { it.path }
    if (!force) {
        for ((p, ie) in oldIndex) {
            val abs = absOf2(repo.layout.workTree, p)
            if (!ctx.fs.exists(abs)) continue
            val bytes = ctx.fs.readBytes(abs)
            val sha2 =
                com.accucodeai.kash.tools.git.plumbing
                    .blobSha(bytes)
            if (sha2 != ie.sha && p in newFlat && newFlat[p]?.sha != ie.sha) {
                ctx.stderr.writeUtf8(
                    "error: your local changes to '$p' would be overwritten by checkout.\n",
                )
                return 1
            }
        }
    }

    // Drop work-tree files that are tracked-by-old-index but not in the
    // new tree.
    for ((p, _) in oldIndex) {
        if (p !in newFlat) {
            val abs = absOf2(repo.layout.workTree, p)
            if (ctx.fs.exists(abs)) ctx.fs.remove(abs)
        }
    }

    // Write each file from the new tree into the work tree.
    for ((p, e) in newFlat) {
        val bytes = repo.objects.readBlob(e.sha)
        val abs = absOf2(repo.layout.workTree, p)
        val parent = abs.substringBeforeLast('/')
        if (parent.isNotEmpty()) ctx.fs.mkdirs(parent)
        val mode = if (e.mode == FileMode.EXECUTABLE) 0b111_101_101 else 0b110_100_100
        ctx.fs.writeBytes(abs, bytes, mode = mode)
    }

    // Replace the index.
    repo.writeIndex(IndexFile(version = 2, entries = newFlat.values.toList()))
    return 0
}

private suspend fun flatTreeIndexEntries(
    repo: GitRepo,
    treeSha: String,
): Map<String, IndexEntry> {
    val out = mutableMapOf<String, IndexEntry>()
    walkAsEntries(repo, treeSha, "", out)
    return out
}

private suspend fun walkAsEntries(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
    out: MutableMap<String, IndexEntry>,
) {
    for (e in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        when (e.mode) {
            FileMode.TREE -> {
                walkAsEntries(repo, e.sha, p, out)
            }

            else -> {
                val bytes = repo.objects.readBlob(e.sha)
                out[p] = IndexEntry(mode = e.mode, size = bytes.size, sha = e.sha, path = p)
            }
        }
    }
}

private fun absOf2(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"

private fun collectWork2(
    fs: FileSystem,
    workTree: String,
): Set<String> {
    val out = mutableSetOf<String>()
    walkWork2(fs, workTree, "", out)
    return out
}

private fun walkWork2(
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
            walkWork2(fs, sub, subRel, out)
        } else {
            out += subRel
        }
    }
}
