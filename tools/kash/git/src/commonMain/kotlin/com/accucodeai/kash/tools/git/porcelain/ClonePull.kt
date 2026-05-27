package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.RepoLayout

/**
 * `git clone <url> [<dir>]` — initialize a new repo at [dir] (default:
 * basename of URL minus `.git`) and use the host's [GitEnv.refResolver]
 * to populate `refs/heads/<default>` from the remote.
 *
 * In kash the realistic shape is: the seed materializer has *already*
 * laid down a `.git/` for us, so most "clone" calls discover that
 * existing repo and become a no-op. When the target is empty, we
 * synthesize an `init`-style layout, then fetch from origin.
 */
public fun gitCloneSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "clone"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            // Pull out `--depth=N` / `--filter=<spec>` first; the
            // remainder gets the usual clone-flag treatment.
            val (scope, postScope) =
                try {
                    parseFetchScopeArgs(args)
                } catch (t: IllegalArgumentException) {
                    ctx.stderr.writeUtf8("fatal: ${t.message}\n")
                    return CommandResult(exitCode = 129)
                }
            val positional = mutableListOf<String>()
            for (a in postScope) {
                if (a.startsWith("-")) {
                    // Common flags we accept-but-ignore. Anything else gets a warning.
                    if (a !in setOf("-q", "--quiet", "--bare", "--mirror", "--single-branch", "--no-tags")) {
                        ctx.stderr.writeUtf8("warning: ignoring unsupported clone option '$a'\n")
                    }
                } else {
                    positional += a
                }
            }
            if (positional.isEmpty()) {
                ctx.stderr.writeUtf8("usage: git clone <url> [<dir>]\n")
                return CommandResult(exitCode = 129)
            }
            val url = positional[0]
            val dir = positional.getOrNull(1) ?: deriveDirFromUrl(url)
            val absDir =
                when {
                    dir.startsWith("/") -> dir
                    env.cwd == "/" -> "/$dir"
                    else -> "${env.cwd}/$dir"
                }

            // If the target already has a `.git/`, treat as no-op (matches
            // the kash seed-already-mounted case).
            val layout = RepoLayout(absDir.trimEnd('/').ifEmpty { "/" })
            if (ctx.fs.exists(layout.gitDir) && ctx.fs.isDirectory(layout.gitDir)) {
                ctx.stdout.writeUtf8("Cloning into '$dir'...\n")
                ctx.stdout.writeUtf8("note: target already has a .git/; reusing existing seed.\n")
                ensureOriginRemote(absDir, url, ctx, env)
                return CommandResult(exitCode = 0)
            }

            // Otherwise, init then fetch.
            ctx.stdout.writeUtf8("Cloning into '$dir'...\n")
            if (!ctx.fs.exists(absDir)) ctx.fs.mkdirs(absDir)
            // Real git's `clone` runs init silently — it doesn't print
            // "Initialized empty Git repository …" because the user
            // asked to *clone*, not to init. Pass `-q` so init suppresses
            // that line; clone's own "Cloning into …" + the fetch lines
            // are the surface output.
            val initResult = gitInitSubcommand().run(listOf("-q", absDir), ctx, env)
            if (initResult.exitCode != 0) return initResult

            ensureOriginRemote(absDir, url, ctx, env)

            val cloneEnv =
                GitEnv(
                    adapter = env.adapter,
                    cwd = absDir,
                    resolver = env.resolver,
                    pushApplier = env.pushApplier,
                    refResolver = env.refResolver,
                )
            if (env.refResolver != null) {
                // Discover upstream's default branch before fetching. Real
                // git does this via the smart-HTTP ref advertisement's
                // symbolic-HEAD line. Without it kash hard-coded
                // `refs/heads/main`, which fails on repos whose default
                // is anything else (e.g. octocat/Hello-World uses master).
                // Null return falls back to `main` — same as before.
                val effectivePolicy =
                    env.adapter?.networkPolicy
                        ?: ctx.process.machine.networkPolicy
                val defaultBranch = env.refResolver.discoverDefaultBranch(url, effectivePolicy) ?: "main"
                // Forward depth/filter into the inner `git fetch` so the
                // adapter receives the same FetchScope the user asked for.
                val fetchArgs =
                    buildList {
                        add("origin")
                        add(defaultBranch)
                        if (scope.depth != null) add("--depth=${scope.depth}")
                        if (scope.filter != null) add("--filter=${scope.filter}")
                    }
                val fetchResult = gitFetchSubcommand().run(fetchArgs, ctx, cloneEnv)
                if (fetchResult.exitCode != 0) {
                    // Fetch already wrote its own `fatal: …` line to
                    // stderr. Surface clone-context too so the user sees
                    // we didn't end up with a usable working tree.
                    ctx.stderr.writeUtf8("fatal: clone of '$url' aborted: fetch failed\n")
                    return CommandResult(exitCode = fetchResult.exitCode)
                }
                // Point HEAD at the just-fetched origin/<defaultBranch>
                // (or fall back to /main, then to the first remote ref).
                val repo = GitRepo.openFromCwd(ctx.fs, absDir, env.resolver)
                if (repo != null) {
                    val originRefs = repo.refs.listRefs("refs/remotes/origin")
                    val target =
                        originRefs.firstOrNull { it.first.endsWith("/$defaultBranch") }
                            ?: originRefs.firstOrNull { it.first.endsWith("/main") }
                            ?: originRefs.firstOrNull()
                    if (target != null) {
                        val branchName = target.first.removePrefix("refs/remotes/origin/")
                        repo.refs.writeRef("refs/heads/$branchName", target.second)
                        repo.refs.writeHeadSymbolic("refs/heads/$branchName")
                        materializeWorkTree(repo, target.second)
                    }
                }
            } else {
                ctx.stderr.writeUtf8(
                    "warning: no GitRefResolver configured; clone initialized an empty repo.\n",
                )
            }
            return CommandResult(exitCode = 0)
        }
    }

private fun deriveDirFromUrl(url: String): String {
    val tail = url.trimEnd('/').substringAfterLast('/').ifEmpty { url }
    return tail.removeSuffix(".git")
}

private suspend fun ensureOriginRemote(
    absDir: String,
    url: String,
    ctx: CommandContext,
    env: GitEnv,
) {
    val layout = RepoLayout(absDir.trimEnd('/').ifEmpty { "/" })
    if (!ctx.fs.exists(layout.configFile)) return
    val store = ConfigStore.load(ctx.fs, layout.configFile)
    if (store.get("remote \"origin\"", "url") == null) {
        store.set("remote \"origin\"", "url", url)
        store.set("remote \"origin\"", "fetch", "+refs/heads/*:refs/remotes/origin/*")
        store.save(ctx.fs, layout.configFile)
    }
}

private suspend fun materializeWorkTree(
    repo: GitRepo,
    commitSha: String,
) {
    val tree = repo.objects.readCommit(commitSha).tree
    val newIndex =
        com.accucodeai.kash.tools.git.plumbing.IndexFile(
            version = 2,
            entries =
                buildList {
                    flattenForIndex(repo, tree, "", this)
                },
        )
    repo.writeIndex(newIndex)
    for (e in newIndex.entries) {
        val abs = repo.layout.workPath(e.path)
        val parent = abs.substringBeforeLast('/').ifEmpty { "/" }
        if (!repo.fs.exists(parent)) repo.fs.mkdirs(parent)
        repo.fs.writeBytes(abs, repo.objects.readBlob(e.sha))
    }
}

private suspend fun flattenForIndex(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
    out: MutableList<com.accucodeai.kash.tools.git.plumbing.IndexEntry>,
) {
    for (e in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        when (e.mode) {
            com.accucodeai.kash.tools.git.plumbing.FileMode.TREE -> {
                flattenForIndex(repo, e.sha, p, out)
            }

            else -> {
                out +=
                    com.accucodeai.kash.tools.git.plumbing.IndexEntry(
                        mode = e.mode,
                        size = repo.objects.readBlob(e.sha).size,
                        sha = e.sha,
                        path = p,
                    )
            }
        }
    }
}

/**
 * `git pull [<remote> [<branch>]]` — sugar for `fetch` + `merge`. We
 * forward to those subcommands directly. v1 does not support `--rebase`.
 */
public fun gitPullSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "pull"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            var rebase = false
            val positional = mutableListOf<String>()
            for (a in args) {
                when {
                    a == "--rebase" || a == "-r" -> {
                        rebase = true
                    }

                    a == "--no-rebase" -> {
                        rebase = false
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git pull: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                    }
                }
            }
            val remote = positional.getOrNull(0) ?: "origin"
            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver) ?: run {
                    ctx.stderr.writeUtf8("fatal: not a git repository\n")
                    return CommandResult(exitCode = 128)
                }
            val branch =
                positional.getOrNull(1)
                    ?: run {
                        val head = repo.refs.readHead()
                        if (head is com.accucodeai.kash.tools.git.plumbing.RefStore.Head.Symbolic) {
                            head.target.removePrefix("refs/heads/")
                        } else {
                            ctx.stderr.writeUtf8("fatal: HEAD is detached; specify a branch to pull\n")
                            return CommandResult(exitCode = 1)
                        }
                    }

            val fetchResult = gitFetchSubcommand().run(listOf(remote, branch), ctx, env)
            if (fetchResult.exitCode != 0) return fetchResult

            val tracking = "refs/remotes/$remote/$branch"
            val theirs =
                repo.refs.resolve(tracking) ?: run {
                    ctx.stderr.writeUtf8("fatal: pull: no tracking ref $tracking\n")
                    return CommandResult(exitCode = 1)
                }

            val mergeArgs = if (rebase) listOf(theirs) else listOf(theirs)
            return if (rebase) {
                gitRebaseSubcommand().run(mergeArgs, ctx, env)
            } else {
                gitMergeSubcommand().run(mergeArgs, ctx, env)
            }
        }
    }
