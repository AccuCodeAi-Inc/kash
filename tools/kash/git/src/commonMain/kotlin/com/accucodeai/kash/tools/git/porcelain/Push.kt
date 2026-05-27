package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.git.GitObjectBundle
import com.accucodeai.kash.api.git.GitPushOutcome
import com.accucodeai.kash.api.git.GitPushRequest
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.encodeCommit
import com.accucodeai.kash.tools.git.plumbing.encodeTree
import com.accucodeai.kash.tools.git.plumbing.framedObject

/**
 * `git push` — hand the LLM's new commits to the host upstream.
 *
 * v1 surface:
 *  - `git push` (no args): push the current branch to `origin`, using
 *    the same name upstream.
 *  - `git push <remote> <branch>`: explicit form. Only `origin` is
 *    recognized in v1 since the adapter only models one upstream.
 *  - `git push -u …` / `--set-upstream`: accepted (we already maintain
 *    `refs/remotes/origin/<branch>` for every local branch, so this
 *    is implicit).
 *
 * Cutoff: walks from the local branch tip back through the parent
 * chain, stopping when it reaches the sha currently held by
 * `refs/remotes/origin/<branch>` (the snapshot of upstream at session
 * start). Every commit visited in that walk is bundled — commit
 * object, the trees it references that aren't already on the upstream
 * side, and the blobs those trees reference.
 *
 * On `GitPushOutcome.Accepted(newTip)` we advance the local tracking
 * ref to `newTip`. On `Rejected(reason)` we print the standard
 * `! [rejected]` line and exit non-zero.
 */
public fun gitPushSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "push"

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

            var remote: String? = null
            var refspec: String? = null
            // We accept and ignore `-u`/`--set-upstream`/`--force-with-lease`
            // /`--tags` so scripts don't error out — semantics for them are
            // either default-on (tracking refs) or out-of-scope in v1.
            for (a in args) {
                when {
                    a == "-u" || a == "--set-upstream" || a == "--force-with-lease" || a == "--tags" -> {}

                    a == "--" -> {}

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git push: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    remote == null -> {
                        remote = a
                    }

                    refspec == null -> {
                        refspec = a
                    }

                    else -> {
                        ctx.stderr.writeUtf8("git push: too many arguments\n")
                        return CommandResult(exitCode = 129)
                    }
                }
            }
            val effectiveRemote = remote ?: "origin"
            if (effectiveRemote != "origin") {
                ctx.stderr.writeUtf8("fatal: '$effectiveRemote' does not appear to be a git repository\n")
                return CommandResult(exitCode = 128)
            }

            val head = repo.refs.readHead()
            val branchName =
                refspec
                    ?: when (head) {
                        is RefStore.Head.Symbolic -> {
                            head.target.removePrefix("refs/heads/")
                        }

                        else -> {
                            ctx.stderr.writeUtf8("fatal: cannot push from detached HEAD without explicit refspec\n")
                            return CommandResult(exitCode = 128)
                        }
                    }
            // Tag refspec: `git push origin <tagname>` pushes refs/tags/<n>
            // to upstream. Annotated tags ship the tag object; lightweight
            // tags just advance the ref. Mutually exclusive with the
            // branch push path below.
            if (refspec != null) {
                val tagRef = "refs/tags/$refspec"
                val tagSha = repo.refs.resolve(tagRef)
                if (tagSha != null) {
                    return pushTag(repo, env, ctx, refspec, tagRef, tagSha)
                }
            }
            val localRef = "refs/heads/$branchName"
            val trackingRef = "refs/remotes/origin/$branchName"
            val localTip =
                repo.refs.resolve(localRef) ?: run {
                    ctx.stderr.writeUtf8("error: src refspec $branchName does not match any\n")
                    return CommandResult(exitCode = 1)
                }
            val upstreamTip = repo.refs.resolve(trackingRef)

            if (localTip == upstreamTip) {
                ctx.stdout.writeUtf8("Everything up-to-date\n")
                return CommandResult(exitCode = 0)
            }

            val applier = env.pushApplier
            if (applier == null) {
                ctx.stderr.writeUtf8(
                    "fatal: no remote configured. (host did not supply a GitPushApplier.)\n",
                )
                return CommandResult(exitCode = 128)
            }

            // Walk: local tip → ... → upstreamTip (exclusive). v1 uses
            // first-parent only — when merge commits land we'll teach
            // this to fan out.
            val bundles = mutableListOf<GitObjectBundle>()
            val visitedTrees = mutableSetOf<String>()
            val visitedBlobs = mutableSetOf<String>()
            var cur: String? = localTip
            while (cur != null && cur != upstreamTip) {
                val commit = repo.objects.readCommit(cur)
                val (trees, blobs) = collectTreeAndBlobs(repo, commit.tree, visitedTrees, visitedBlobs)
                bundles +=
                    GitObjectBundle(
                        commitSha = cur,
                        commit =
                            framedObject(
                                com.accucodeai.kash.tools.git.plumbing.ObjectType.COMMIT,
                                encodeCommit(commit),
                            ),
                        trees = trees,
                        blobs = blobs,
                    )
                cur = commit.parents.firstOrNull()
            }
            // Oldest-first ordering is what most appliers want.
            bundles.reverse()

            val request =
                GitPushRequest(
                    branch = branchName,
                    expectedOldSha = upstreamTip,
                    newCommits = bundles,
                    tipSha = localTip,
                )
            // Prefer the network path when an upstream URL is configured.
            // The local in-memory applier is the fallback for adapters
            // that have no transport.
            val originUrl = readGitConfig(repo)["remote \"$effectiveRemote\""]?.get("url")
            val effectivePolicy =
                env.adapter?.networkPolicy
                    ?: ctx.process.machine.networkPolicy
            val outcome =
                if (originUrl != null) {
                    applier.applyToUrl(originUrl, request, effectivePolicy)
                } else {
                    applier.apply(request)
                }
            return when (outcome) {
                is GitPushOutcome.Accepted -> {
                    repo.refs.writeRef(trackingRef, outcome.newTipSha)
                    val now = nowReflogTime(ctx)
                    recordReflog(
                        repo,
                        trackingRef,
                        upstreamTip,
                        outcome.newTipSha,
                        null,
                        now.first,
                        now.second,
                        "push to $branchName",
                    )
                    val shortOld = upstreamTip?.substring(0, 7) ?: "0000000"
                    val shortNew = outcome.newTipSha.substring(0, 7)
                    ctx.stdout.writeUtf8("To origin\n")
                    ctx.stdout.writeUtf8("   $shortOld..$shortNew  $branchName -> $branchName\n")
                    CommandResult(exitCode = 0)
                }

                is GitPushOutcome.Rejected -> {
                    ctx.stderr.writeUtf8("To origin\n")
                    ctx.stderr.writeUtf8(
                        " ! [rejected]        $branchName -> $branchName (${outcome.reason})\n",
                    )
                    ctx.stderr.writeUtf8("error: failed to push some refs to 'origin'\n")
                    CommandResult(exitCode = 1)
                }
            }
        }

        /**
         * Push a single tag ref (`refs/tags/<name>`). For an annotated
         * tag, the tag object itself is bundled; the underlying commit
         * is assumed to already be on upstream (typical workflow: push
         * the branch, then push the tag). For a lightweight tag, just
         * advance the ref.
         */
        private suspend fun pushTag(
            repo: GitRepo,
            env: GitEnv,
            ctx: CommandContext,
            tagName: String,
            tagRef: String,
            tagSha: String,
        ): CommandResult {
            val applier =
                env.pushApplier ?: run {
                    ctx.stderr.writeUtf8(
                        "fatal: no remote configured. (host did not supply a GitPushApplier.)\n",
                    )
                    return CommandResult(exitCode = 128)
                }
            // Read whatever the tag points at. If it's a tag object,
            // bundle it; if it's a commit, this is lightweight and we
            // just send a ref update.
            val obj = repo.objects.readParsed(tagSha)
            val tagObjects =
                if (obj.type == com.accucodeai.kash.tools.git.plumbing.ObjectType.TAG) {
                    mapOf(
                        tagSha to
                            com.accucodeai.kash.tools.git.plumbing
                                .framedObject(obj.type, obj.payload),
                    )
                } else {
                    emptyMap()
                }
            val request =
                GitPushRequest(
                    branch = tagRef,
                    expectedOldSha = null,
                    newCommits = emptyList(),
                    tipSha = tagSha,
                    tagObjects = tagObjects,
                )
            val originUrl = readGitConfig(repo)["remote \"origin\""]?.get("url")
            val effectivePolicy =
                env.adapter?.networkPolicy
                    ?: ctx.process.machine.networkPolicy
            val outcome =
                if (originUrl != null) {
                    applier.applyToUrl(originUrl, request, effectivePolicy)
                } else {
                    applier.apply(request)
                }
            return when (outcome) {
                is com.accucodeai.kash.api.git.GitPushOutcome.Accepted -> {
                    ctx.stdout.writeUtf8("To origin\n")
                    ctx.stdout.writeUtf8(" * [new tag]         $tagName -> $tagName\n")
                    CommandResult(exitCode = 0)
                }

                is com.accucodeai.kash.api.git.GitPushOutcome.Rejected -> {
                    ctx.stderr.writeUtf8("To origin\n")
                    ctx.stderr.writeUtf8(" ! [rejected]        $tagName -> $tagName (${outcome.reason})\n")
                    CommandResult(exitCode = 1)
                }
            }
        }

        private suspend fun collectTreeAndBlobs(
            repo: GitRepo,
            rootTreeSha: String,
            visitedTrees: MutableSet<String>,
            visitedBlobs: MutableSet<String>,
        ): Pair<Map<String, ByteArray>, Map<String, ByteArray>> {
            val trees = mutableMapOf<String, ByteArray>()
            val blobs = mutableMapOf<String, ByteArray>()
            walk(repo, rootTreeSha, trees, blobs, visitedTrees, visitedBlobs)
            return trees to blobs
        }

        private suspend fun walk(
            repo: GitRepo,
            treeSha: String,
            trees: MutableMap<String, ByteArray>,
            blobs: MutableMap<String, ByteArray>,
            visitedTrees: MutableSet<String>,
            visitedBlobs: MutableSet<String>,
        ) {
            if (!visitedTrees.add(treeSha)) return
            val entries = repo.objects.readTree(treeSha)
            trees[treeSha] =
                framedObject(
                    com.accucodeai.kash.tools.git.plumbing.ObjectType.TREE,
                    encodeTree(entries),
                )
            for (e in entries) {
                when (e.mode) {
                    FileMode.TREE -> {
                        walk(repo, e.sha, trees, blobs, visitedTrees, visitedBlobs)
                    }

                    else -> {
                        if (visitedBlobs.add(e.sha)) {
                            val bytes = repo.objects.readBlob(e.sha)
                            blobs[e.sha] =
                                framedObject(
                                    com.accucodeai.kash.tools.git.plumbing.ObjectType.BLOB,
                                    bytes,
                                )
                        }
                    }
                }
            }
        }
    }
