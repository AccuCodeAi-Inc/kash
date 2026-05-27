package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.RefStore

/**
 * `git branch` — list/create/delete branches under `refs/heads/`.
 *
 *  - no args / `-l` / `--list`: list local branches, marking the
 *    current one with `*`.
 *  - `-a`: also list remote-tracking branches.
 *  - `<name>`: create branch `name` at HEAD.
 *  - `<name> <start>`: create branch `name` at the given revspec.
 *  - `-d <name>` / `--delete`: delete a branch (rejects current branch).
 *  - `-D <name>`: force delete (same as `-d` in v1; no ahead-of-upstream
 *    check yet).
 *  - `--show-current`: print the active branch (or nothing if detached).
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
            var showCurrent = false
            var delete = false
            val positional = mutableListOf<String>()
            for (a in args) {
                when {
                    a == "-a" || a == "--all" -> {
                        showAll = true
                    }

                    a == "-l" || a == "--list" -> {}

                    // default action
                    a == "-d" || a == "--delete" || a == "-D" -> {
                        delete = true
                    }

                    a == "--show-current" -> {
                        showCurrent = true
                    }

                    a == "--" -> {}

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git branch: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                    }
                }
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

            if (delete) {
                if (positional.isEmpty()) {
                    ctx.stderr.writeUtf8("git branch: branch name required\n")
                    return CommandResult(exitCode = 129)
                }
                for (name in positional) {
                    if (name == current) {
                        ctx.stderr.writeUtf8("error: cannot delete branch '$name' checked out\n")
                        return CommandResult(exitCode = 1)
                    }
                    val ref = "refs/heads/$name"
                    if (repo.refs.resolve(ref) == null) {
                        ctx.stderr.writeUtf8("error: branch '$name' not found.\n")
                        return CommandResult(exitCode = 1)
                    }
                    val path = repo.layout.refFile(ref)
                    if (ctx.fs.exists(path)) ctx.fs.remove(path)
                    // Real git also deletes the branch's reflog.
                    val logPath = "${repo.layout.logsDir}/$ref"
                    if (ctx.fs.exists(logPath)) ctx.fs.remove(logPath)
                    ctx.stdout.writeUtf8("Deleted branch $name.\n")
                }
                return CommandResult(exitCode = 0)
            }

            if (positional.isNotEmpty()) {
                val name = positional[0]
                val startSpec = positional.getOrNull(1) ?: "HEAD"
                val target =
                    resolveRev(repo, startSpec) ?: run {
                        ctx.stderr.writeUtf8("fatal: not a valid object name: '$startSpec'\n")
                        return CommandResult(exitCode = 128)
                    }
                val ref = "refs/heads/$name"
                if (repo.refs.resolve(ref) != null) {
                    ctx.stderr.writeUtf8("fatal: a branch named '$name' already exists\n")
                    return CommandResult(exitCode = 128)
                }
                repo.refs.writeRef(ref, target)
                val now = nowReflogTime(ctx)
                recordReflog(
                    repo,
                    ref,
                    null,
                    target,
                    null,
                    now.first,
                    now.second,
                    "branch: Created from $startSpec",
                )
                return CommandResult(exitCode = 0)
            }

            // List branches.
            val heads =
                repo.refs
                    .listRefs("refs/heads")
                    .map { it.first.removePrefix("refs/heads/") }
                    .sorted()
            for (b in heads) {
                val mark = if (b == current) "* " else "  "
                ctx.stdout.writeUtf8("$mark$b\n")
            }
            if (showAll) {
                val remotes =
                    repo.refs
                        .listRefs("refs/remotes")
                        .map { it.first.removePrefix("refs/remotes/") }
                        .sorted()
                for (r in remotes) ctx.stdout.writeUtf8("  remotes/$r\n")
            }
            return CommandResult(exitCode = 0)
        }
    }
