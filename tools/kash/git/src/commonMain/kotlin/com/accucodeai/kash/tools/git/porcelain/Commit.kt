package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.git.ChangedFile
import com.accucodeai.kash.api.git.GitCommitAck
import com.accucodeai.kash.api.git.GitCommitEvent
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.PreCommitRequest
import com.accucodeai.kash.api.git.PreCommitResult
import com.accucodeai.kash.api.git.StagedFile
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.encodeCommit

/**
 * `git commit` — record the current index as a new commit.
 *
 * v1 surface:
 *  - `-m <msg>`: required (no editor in v1).
 *  - `--allow-empty`: produce a commit even when nothing is staged.
 *  - `--no-verify`: skip `.git/hooks/pre-commit` (still runs the host
 *    [com.accucodeai.kash.api.git.PreCommitValidator] — that one is
 *    unbypassable by design).
 *
 * Adapter integration:
 *  - If [GitEnv.adapter] is set, [com.accucodeai.kash.api.git.GitHostAdapter.preCommitValidator]
 *    runs after the index is materialized but before the commit object
 *    is written. A `Reject` aborts with exit 1, no ref movement, the
 *    validator's message on stderr.
 *  - On success, [com.accucodeai.kash.api.git.GitHostAdapter.onCommit]
 *    fires with the new sha. `GitCommitAck.Done` returns exit 0 and an
 *    info line telling the host the commit was accepted.
 */
public fun gitCommitSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "commit"

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

            var message: String? = null
            var allowEmpty = false
            var noVerify = false
            var quiet = false
            var amend = false
            var fixupOf: String? = null
            var squashOf: String? = null
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "-m" || a == "--message" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        message = appendOrSet(message, args[i + 1])
                        i += 2
                    }

                    a.startsWith("--message=") -> {
                        message = appendOrSet(message, a.substringAfter("="))
                        i++
                    }

                    a == "--allow-empty" -> {
                        allowEmpty = true
                        i++
                    }

                    a == "--no-verify" -> {
                        noVerify = true
                        i++
                    }

                    a == "-q" || a == "--quiet" -> {
                        quiet = true
                        i++
                    }

                    a == "--amend" -> {
                        amend = true
                        i++
                    }

                    a.startsWith("--fixup=") -> {
                        fixupOf = a.substringAfter("=")
                        i++
                    }

                    a == "--fixup" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"--fixup\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        fixupOf = args[i + 1]
                        i += 2
                    }

                    a.startsWith("--squash=") -> {
                        squashOf = a.substringAfter("=")
                        i++
                    }

                    a == "--squash" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"--squash\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        squashOf = args[i + 1]
                        i += 2
                    }

                    a == "--" -> {
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git commit: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        ctx.stderr.writeUtf8("git commit: positional pathspec not supported in v1\n")
                        return CommandResult(exitCode = 129)
                    }
                }
            }
            val index = repo.readIndex()
            val headSha = repo.refs.resolveHead()

            // --amend: the new commit replaces HEAD, so its parents are
            // HEAD's parents (not HEAD), and a missing -m reuses HEAD's
            // message. Refuses if there is no HEAD to amend.
            val priorCommit =
                if (amend) {
                    if (headSha == null) {
                        ctx.stderr.writeUtf8("fatal: You have nothing to amend.\n")
                        return CommandResult(exitCode = 128)
                    }
                    repo.objects.readCommit(headSha)
                } else {
                    null
                }
            if (amend && message == null) {
                message = priorCommit!!.message.trimEnd('\n')
            }
            // --fixup / --squash: build the magic message prefix from the
            // target commit's subject. `rebase --autosquash` looks for
            // these prefixes when reordering.
            if (fixupOf != null || squashOf != null) {
                val target = fixupOf ?: squashOf!!
                val sha = resolveRev(repo, target)
                if (sha == null) {
                    ctx.stderr.writeUtf8("fatal: could not resolve commit '$target'\n")
                    return CommandResult(exitCode = 128)
                }
                val subj =
                    repo.objects
                        .readCommit(sha)
                        .message
                        .substringBefore('\n')
                message =
                    if (fixupOf != null) {
                        "fixup! $subj"
                    } else {
                        val body = message?.let { "\n\n$it" } ?: ""
                        "squash! $subj$body"
                    }
            }
            if (message == null) {
                ctx.stderr.writeUtf8("error: commit message required (-m). v1 has no editor.\n")
                return CommandResult(exitCode = 1)
            }

            val parentSha = if (amend) priorCommit!!.parents.firstOrNull() else headSha
            val parentTreeSha =
                if (parentSha != null) repo.objects.readCommit(parentSha).tree else null

            // Mid-merge?  If `.git/MERGE_HEAD` exists, we're finalizing
            // a paused merge — the new commit has [parent, mergeHead]
            // as parents, and we refuse if any index entry is still in
            // stage 1/2/3 (unresolved conflicts).
            val mergeHeadPath = "${repo.layout.gitDir}/MERGE_HEAD"
            val mergeHeadSha: String? =
                if (ctx.fs.exists(mergeHeadPath)) {
                    ctx.fs
                        .readBytes(mergeHeadPath)
                        .decodeToString()
                        .trim()
                        .ifEmpty { null }
                } else {
                    null
                }
            if (mergeHeadSha != null) {
                val unresolved = index.entries.any { it.stage != 0 }
                if (unresolved) {
                    ctx.stderr.writeUtf8(
                        "error: Committing is not possible because you have unmerged files.\n",
                    )
                    ctx.stderr.writeUtf8(
                        "hint: Fix them up in the work tree, and then use 'git add/rm <file>'\n",
                    )
                    return CommandResult(exitCode = 1)
                }
            }

            val newTreeSha = writeTreeFromIndex(repo, index)
            if (newTreeSha == parentTreeSha && !allowEmpty && mergeHeadSha == null && !amend) {
                ctx.stderr.writeUtf8(
                    "nothing to commit, working tree clean (use --allow-empty to override)\n",
                )
                return CommandResult(exitCode = 1)
            }

            val head = repo.refs.readHead()
            val branchRef =
                when (head) {
                    is RefStore.Head.Symbolic -> head.target
                    else -> "HEAD"
                }
            val branchName =
                if (branchRef.startsWith("refs/heads/")) {
                    branchRef.removePrefix("refs/heads/")
                } else {
                    branchRef
                }

            val identity = resolveIdentity(env, ctx.env, repo, ctx.fs)
            val whenSec =
                (
                    ctx.env["GIT_AUTHOR_DATE"]
                        ?.split(' ')
                        ?.firstOrNull()
                        ?.toLongOrNull()
                )
                    ?: (
                        ctx.env["GIT_COMMITTER_DATE"]
                            ?.split(' ')
                            ?.firstOrNull()
                            ?.toLongOrNull()
                    )
                    ?: ctx.process.machine.clock
                        .now()
                        .epochSeconds
            val tz =
                ctx.env["GIT_AUTHOR_DATE"]
                    ?.split(' ')
                    ?.getOrNull(1)
                    ?.takeIf { it.length == 5 }
                    ?: ctx.process.machine.clock
                        .localTz()
            val person = PersonStamp(identity.name, identity.email, whenSec, tz)
            val msg = message
            val finalMsg = if (msg.endsWith("\n")) msg else "$msg\n"

            // Pre-commit gate.
            val staged =
                index.entries.map { e ->
                    StagedFile(
                        path = e.path,
                        mode = stageModeInt(e.mode),
                        sha = e.sha,
                        newBytes = null,
                    )
                }
            val req =
                PreCommitRequest(
                    branch = branchName,
                    message = finalMsg,
                    author = identity,
                    parentSha = parentSha,
                    staged = staged,
                )
            val adapter = env.adapter
            if (adapter != null) {
                val result = adapter.preCommitValidator?.validate(req)
                if (result is PreCommitResult.Reject) {
                    ctx.stderr.writeUtf8("pre-commit: ${result.message}\n")
                    for (d in result.details) ctx.stderr.writeUtf8("  $d\n")
                    return CommandResult(exitCode = 1)
                }
            }
            if (!noVerify) {
                val hookPath = "${repo.layout.hooksDir}/pre-commit"
                if (ctx.fs.exists(hookPath) && isExecutable(ctx.fs, hookPath)) {
                    val runner = ctx.shellRunner
                    if (runner == null) {
                        ctx.stderr.writeUtf8(
                            "warning: .git/hooks/pre-commit present but no shellRunner available; skipping\n",
                        )
                    } else {
                        val hookScript = ctx.fs.readBytes(hookPath).decodeToString()
                        val hookExit =
                            runner.run(
                                com.accucodeai.kash.api.ShellInvocation(
                                    script = hookScript,
                                    scriptName = ".git/hooks/pre-commit",
                                    stdout = ctx.stdout,
                                    stderr = ctx.stderr,
                                ),
                            )
                        if (hookExit != 0) {
                            ctx.stderr.writeUtf8(
                                "error: pre-commit hook failed (exit $hookExit). Use --no-verify to bypass.\n",
                            )
                            return CommandResult(exitCode = 1)
                        }
                    }
                }
            }

            // Parents:
            //  - amend: preserve HEAD's full parent list (matters for
            //    merge commits — amending must keep both sides).
            //  - mid-merge: HEAD + MERGE_HEAD.
            //  - normal: HEAD only.
            val parents =
                when {
                    amend -> priorCommit!!.parents
                    else -> listOfNotNull(parentSha, mergeHeadSha)
                }
            val commit =
                CommitPayload(
                    tree = newTreeSha,
                    parents = parents,
                    author = if (amend) priorCommit!!.author else person,
                    committer = person,
                    message = finalMsg,
                )
            val newSha = repo.objects.write(ObjectType.COMMIT, encodeCommit(commit))
            val priorTip = if (amend) headSha else parentSha
            repo.refs.writeRef(branchRef, newSha)
            recordReflog(
                repo = repo,
                refName = branchRef,
                oldSha = priorTip,
                newSha = newSha,
                identity = identity,
                whenSeconds = whenSec,
                tz = tz,
                message =
                    when {
                        amend -> "commit (amend): ${finalMsg.substringBefore('\n')}"
                        mergeHeadSha != null -> "commit (merge): ${finalMsg.substringBefore('\n')}"
                        priorTip == null -> "commit (initial): ${finalMsg.substringBefore('\n')}"
                        else -> "commit: ${finalMsg.substringBefore('\n')}"
                    },
            )

            // If we just finalized a merge, clear the on-disk state
            // (matches real-git's behavior after a successful merge commit).
            if (mergeHeadSha != null) {
                for (name in listOf("MERGE_HEAD", "MERGE_MSG", "MERGE_MODE")) {
                    val p = "${repo.layout.gitDir}/$name"
                    if (ctx.fs.exists(p)) ctx.fs.remove(p)
                }
            }

            if (!quiet) {
                val firstLine = finalMsg.substringBefore('\n')
                ctx.stdout.writeUtf8("[$branchName ${newSha.substring(0, 7)}] $firstLine\n")
            }

            if (adapter != null) {
                val ack =
                    adapter.onCommit.onCommit(
                        GitCommitEvent(
                            sha = newSha,
                            branch = branchName,
                            parentSha = parentSha,
                            message = finalMsg,
                            author = identity,
                            isSyncBranch = branchName == adapter.syncBranch,
                            changedFiles = computeChangedFiles(repo, parentTreeSha, newTreeSha),
                        ),
                    )
                when (ack) {
                    is GitCommitAck.Accepted -> {}

                    is GitCommitAck.Warn -> {
                        ctx.stderr.writeUtf8("warning: ${ack.message}\n")
                    }

                    is GitCommitAck.Done -> {
                        if (branchName == adapter.syncBranch) {
                            ctx.stdout.writeUtf8(
                                "commit $newSha accepted by host (sync branch '$branchName')\n",
                            )
                        }
                    }
                }
            }
            return CommandResult(exitCode = 0)
        }

        private fun appendOrSet(
            existing: String?,
            additional: String,
        ): String = if (existing == null) additional else "$existing\n\n$additional"

        private suspend fun resolveIdentity(
            env: GitEnv,
            shellEnv: Map<String, String>,
            repo: GitRepo,
            fs: com.accucodeai.kash.fs.FileSystem,
        ): GitIdentity {
            // Real git's identity-fallback chain (commit.c → ident.c):
            //   1. GIT_AUTHOR_* / GIT_COMMITTER_* env
            //   2. [user] name/email in .git/config (repo-local)
            //   3. [user] name/email in $HOME/.gitconfig (user-global)
            //   4. host adapter's default identity
            //   5. last-resort placeholder
            // We previously skipped (2) and (3) and went straight to (4) —
            // so `git config user.email foo` had no effect on commits.
            val repoCfg = runCatching { readGitConfig(repo) }.getOrDefault(emptyMap())
            val userCfg =
                shellEnv["HOME"]?.let { home ->
                    val path = "${home.trimEnd('/')}/.gitconfig"
                    if (fs.exists(path)) parseGitConfigText(fs.readBytes(path).decodeToString()) else null
                } ?: emptyMap()

            fun cfg(key: String): String? = repoCfg["user"]?.get(key) ?: userCfg["user"]?.get(key)

            val name =
                shellEnv["GIT_AUTHOR_NAME"]
                    ?: shellEnv["GIT_COMMITTER_NAME"]
                    ?: cfg("name")
                    ?: env.adapter?.identity?.name
                    ?: "kash"
            val email =
                shellEnv["GIT_AUTHOR_EMAIL"]
                    ?: shellEnv["GIT_COMMITTER_EMAIL"]
                    ?: cfg("email")
                    ?: env.adapter?.identity?.email
                    ?: "kash@localhost"
            return GitIdentity(name, email)
        }

        private fun stageModeInt(m: com.accucodeai.kash.tools.git.plumbing.FileMode): Int =
            // Octal mode as an integer — Kotlin has no octal literal so we
            // hex-encode the same bit pattern. 0x81A4 = 0o100644, etc.
            when (m) {
                com.accucodeai.kash.tools.git.plumbing.FileMode.REGULAR -> 0x81A4
                com.accucodeai.kash.tools.git.plumbing.FileMode.EXECUTABLE -> 0x81ED
                com.accucodeai.kash.tools.git.plumbing.FileMode.SYMLINK -> 0xA000
                com.accucodeai.kash.tools.git.plumbing.FileMode.GITLINK -> 0xE000
                com.accucodeai.kash.tools.git.plumbing.FileMode.TREE -> 0x4000
            }

        private suspend fun computeChangedFiles(
            repo: GitRepo,
            oldTree: String?,
            newTree: String,
        ): List<ChangedFile> {
            val oldFlat = if (oldTree == null) emptyMap() else flattenTreeShas(repo, oldTree, "")
            val newFlat = flattenTreeShas(repo, newTree, "")
            val out = mutableListOf<ChangedFile>()
            for ((p, sha) in newFlat) {
                val prev = oldFlat[p]
                if (prev == null) {
                    out += ChangedFile(p, ChangedFile.Change.ADDED)
                } else if (prev != sha) {
                    out += ChangedFile(p, ChangedFile.Change.MODIFIED)
                }
            }
            for ((p, _) in oldFlat) {
                if (p !in newFlat) out += ChangedFile(p, ChangedFile.Change.DELETED)
            }
            return out
        }

        private suspend fun flattenTreeShas(
            repo: GitRepo,
            treeSha: String,
            prefix: String,
        ): Map<String, String> {
            val out = mutableMapOf<String, String>()
            for (e in repo.objects.readTree(treeSha)) {
                val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                when (e.mode) {
                    com.accucodeai.kash.tools.git.plumbing.FileMode.TREE -> {
                        out.putAll(flattenTreeShas(repo, e.sha, p))
                    }

                    else -> {
                        out[p] = e.sha
                    }
                }
            }
            return out
        }
    }

private fun isExecutable(
    fs: com.accucodeai.kash.fs.FileSystem,
    path: String,
): Boolean =
    try {
        (fs.stat(path).mode and 0b001_001_001) != 0
    } catch (_: Throwable) {
        false
    }
