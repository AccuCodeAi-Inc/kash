package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo

/**
 * `git fetch [<remote>] [<branch>]` — ask the host's ref resolver for
 * the current upstream tips and advance our `refs/remotes/origin/<b>`
 * to match. Object content is materialized lazily: the resolver chain
 * is already wired into `ObjectStore.read`, so anything the LLM
 * subsequently inspects (`git log origin/main`, `git diff
 * origin/main`) will resolve through on first touch.
 *
 * v1 only knows `origin`. Without a `GitRefResolver` on the adapter
 * the command emits "no remote configured" and exits 1.
 */
public fun gitFetchSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "fetch"

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
            val (scope, scrubbed) =
                try {
                    parseFetchScopeArgs(args)
                } catch (t: IllegalArgumentException) {
                    ctx.stderr.writeUtf8("fatal: ${t.message}\n")
                    return CommandResult(exitCode = 129)
                }
            val positional = scrubbed.filterNot { it.startsWith("-") }
            val remote = positional.getOrNull(0) ?: "origin"
            val explicitBranch = positional.getOrNull(1)
            if (remote != "origin") {
                ctx.stderr.writeUtf8("fatal: '$remote' does not appear to be a git repository\n")
                return CommandResult(exitCode = 128)
            }
            val refResolver = env.refResolver
            if (refResolver == null) {
                ctx.stderr.writeUtf8("fatal: no remote configured (host did not supply a GitRefResolver)\n")
                return CommandResult(exitCode = 1)
            }

            // Decide which refs to refresh.
            val refs =
                if (explicitBranch != null) {
                    listOf("refs/heads/$explicitBranch")
                } else {
                    repo.refs
                        .listRefs("refs/remotes/origin")
                        .map {
                            "refs/heads/" + it.first.removePrefix("refs/remotes/origin/")
                        }.ifEmpty { listOf("refs/heads/main") }
                }

            // If the user configured a real upstream URL, route through
            // the adapter's network-aware path. The host (e.g. JGit's
            // Transport) does ls-remote + fetch in one shot and lands
            // objects in its own cache; objectResolver picks them up
            // from there.
            val originUrl = readGitConfig(repo)["remote \"$remote\""]?.get("url")
            val effectivePolicy =
                env.adapter?.networkPolicy
                    ?: ctx.process.machine.networkPolicy
            val refToSha: Map<String, String> =
                if (originUrl != null) {
                    // Surface transport failures on stderr. The kash
                    // machine catches uncaught throwables from forked
                    // commands and silently sets exit=1 — without this
                    // try/catch, browser CORS errors, DNS failures, and
                    // smart-HTTP protocol mismatches all show up as a
                    // mysteriously empty `git fetch` with no diagnostic
                    // ("Already up to date." against an empty repo).
                    val fetched =
                        try {
                            refResolver.fetchFromUrl(originUrl, refs, effectivePolicy, scope)
                        } catch (t: Throwable) {
                            ctx.stderr.writeUtf8(
                                "fatal: unable to fetch from '$originUrl'\n" +
                                    "       ${t.message ?: t::class.simpleName ?: "unknown error"}\n",
                            )
                            return CommandResult(exitCode = 1)
                        }
                    if (fetched.isEmpty()) {
                        // Host doesn't support URL transport — fall back
                        // to the local-only resolver.
                        refs.mapNotNull { r -> refResolver.resolveRef(r)?.let { r to it } }.toMap()
                    } else {
                        fetched
                    }
                } else {
                    refs.mapNotNull { r -> refResolver.resolveRef(r)?.let { r to it } }.toMap()
                }

            var anyChanged = false
            for (ref in refs) {
                val branchName = ref.removePrefix("refs/heads/")
                val trackingRef = "refs/remotes/origin/$branchName"
                val before = repo.refs.resolve(trackingRef)
                val upstreamSha = refToSha[ref] ?: continue
                if (upstreamSha == before) continue
                // Trigger a resolve so the new tip's commit object is
                // pulled into the local store (and its tree/blobs on
                // first access — fully lazy past that).
                runCatching { repo.objects.readCommit(upstreamSha) }
                repo.refs.writeRef(trackingRef, upstreamSha)
                val now = nowReflogTime(ctx)
                recordReflog(
                    repo,
                    trackingRef,
                    before,
                    upstreamSha,
                    null,
                    now.first,
                    now.second,
                    "fetch $remote $branchName: forced-update",
                )
                ctx.stdout.writeUtf8(
                    "From origin\n   ${before?.substring(0, 7) ?: "0000000"}..${upstreamSha.substring(0, 7)}  " +
                        "$branchName -> origin/$branchName\n",
                )
                anyChanged = true
            }
            if (!anyChanged) ctx.stdout.writeUtf8("Already up to date.\n")
            return CommandResult(exitCode = 0)
        }
    }
