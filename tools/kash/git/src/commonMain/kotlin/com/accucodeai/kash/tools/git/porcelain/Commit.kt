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
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.IndexEntry
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.encodeCommit
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git commit` — record the current index as a new commit.
 *
 * Surface:
 *  - `-m <msg>` / `--message=<msg>`: message. Repeated `-m` join as
 *    separate paragraphs (blank line between).
 *  - `-F <file>` / `--file=<file>`: read the message from a file; `-F -`
 *    reads stdin. Mutually exclusive with `-m`, `-C`, `-c`.
 *  - `-a` / `--all`: stage modifications and deletions of already-tracked
 *    paths before committing (does NOT add untracked files). `-am <msg>`
 *    is supported as a combined short flag.
 *  - `--author=<Name <email>>`: override the author identity.
 *  - `--date=<date>`: set the author date (committer date still comes
 *    from env / wall clock).
 *  - `-C <commit>` / `--reuse-message=<commit>`: reuse another commit's
 *    message *and* author. `-c` / `--reedit-message=<commit>` behaves the
 *    same here (no editor to re-open).
 *  - `--reset-author`: reset the author to the current identity + date
 *    (only valid with `-C`/`-c`/`--amend`).
 *  - `--no-edit`: with `--amend`, keep the existing message.
 *  - `--allow-empty`: produce a commit even when nothing is staged.
 *  - `--allow-empty-message`: permit an empty commit message.
 *  - `--no-verify`: skip `.git/hooks/pre-commit` (still runs the host
 *    [com.accucodeai.kash.api.git.PreCommitValidator] — that one is
 *    unbypassable by design).
 *  - `-- <paths>`: partial commit. Only the named paths (taken from the
 *    work tree) are committed on top of HEAD; the rest of the index is
 *    left untouched in the working index.
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

            val messages = mutableListOf<String>()
            var allowEmpty = false
            var allowEmptyMessage = false
            var noVerify = false
            var quiet = false
            var amend = false
            var all = false
            var noEdit = false
            var resetAuthor = false
            var fixupOf: String? = null
            var squashOf: String? = null
            var author: String? = null
            var dateStr: String? = null
            var fileMsg: String? = null // sentinel "" handled via flag below
            var fileMsgSet = false
            var reuseFrom: String? = null // -C / -c source commit rev
            val pathspecs = mutableListOf<String>()
            var endOpts = false
            var i = 0

            fun needsValue(flag: String): String? {
                if (i + 1 >= args.size) {
                    return null
                }
                return args[i + 1]
            }

            while (i < args.size) {
                val a = args[i]
                when {
                    endOpts -> {
                        pathspecs += a
                        i++
                    }

                    a == "-m" || a == "--message" -> {
                        val v =
                            needsValue(a) ?: run {
                                ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                                return CommandResult(exitCode = 129)
                            }
                        messages += v
                        i += 2
                    }

                    a.startsWith("--message=") -> {
                        messages += a.substringAfter("=")
                        i++
                    }

                    a.startsWith("-m") && a.length > 2 -> {
                        // -m<msg> attached form.
                        messages += a.substring(2)
                        i++
                    }

                    a == "-F" || a == "--file" -> {
                        val v =
                            needsValue(a) ?: run {
                                ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                                return CommandResult(exitCode = 129)
                            }
                        fileMsg = v
                        fileMsgSet = true
                        i += 2
                    }

                    a.startsWith("--file=") -> {
                        fileMsg = a.substringAfter("=")
                        fileMsgSet = true
                        i++
                    }

                    a == "-C" || a == "--reuse-message" -> {
                        val v =
                            needsValue(a) ?: run {
                                ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                                return CommandResult(exitCode = 129)
                            }
                        reuseFrom = v
                        i += 2
                    }

                    a.startsWith("--reuse-message=") -> {
                        reuseFrom = a.substringAfter("=")
                        i++
                    }

                    // -c / --reedit-message: with no editor available this
                    // is indistinguishable from -C (reuse).
                    a == "-c" || a == "--reedit-message" -> {
                        val v =
                            needsValue(a) ?: run {
                                ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                                return CommandResult(exitCode = 129)
                            }
                        reuseFrom = v
                        i += 2
                    }

                    a.startsWith("--reedit-message=") -> {
                        reuseFrom = a.substringAfter("=")
                        i++
                    }

                    a.startsWith("--author=") -> {
                        author = a.substringAfter("=")
                        i++
                    }

                    a == "--author" -> {
                        val v =
                            needsValue(a) ?: run {
                                ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                                return CommandResult(exitCode = 129)
                            }
                        author = v
                        i += 2
                    }

                    a.startsWith("--date=") -> {
                        dateStr = a.substringAfter("=")
                        i++
                    }

                    a == "--date" -> {
                        val v =
                            needsValue(a) ?: run {
                                ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                                return CommandResult(exitCode = 129)
                            }
                        dateStr = v
                        i += 2
                    }

                    a == "-a" || a == "--all" -> {
                        all = true
                        i++
                    }

                    // Combined short-flag cluster: -am <msg>, -aq, -qam …
                    // Composed only of the value-less flags a/q and the
                    // value-taking flags m/F (which, if present, must be
                    // last in the cluster). Anything else falls through.
                    a.length > 2 && a[0] == '-' && a[1] != '-' && isShortCluster(a.substring(1)) -> {
                        val cluster = a.substring(1)
                        var consumed = false
                        var k = 0
                        loop@ while (k < cluster.length) {
                            when (cluster[k]) {
                                'a' -> {
                                    all = true
                                }

                                'q' -> {
                                    quiet = true
                                }

                                'm', 'F' -> {
                                    val isFile = cluster[k] == 'F'
                                    val rest = cluster.substring(k + 1)
                                    val value =
                                        if (rest.isNotEmpty()) {
                                            rest
                                        } else {
                                            consumed = true
                                            needsValue("-${cluster[k]}") ?: run {
                                                ctx.stderr.writeUtf8(
                                                    "error: switch \"${cluster[k]}\" requires a value\n",
                                                )
                                                return CommandResult(exitCode = 129)
                                            }
                                        }
                                    if (isFile) {
                                        fileMsg = value
                                        fileMsgSet = true
                                    } else {
                                        messages += value
                                    }
                                    break@loop
                                }
                            }
                            k++
                        }
                        i += if (consumed) 2 else 1
                    }

                    a == "--allow-empty" -> {
                        allowEmpty = true
                        i++
                    }

                    a == "--allow-empty-message" -> {
                        allowEmptyMessage = true
                        i++
                    }

                    a == "--reset-author" -> {
                        resetAuthor = true
                        i++
                    }

                    a == "--no-edit" -> {
                        noEdit = true
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
                        val v =
                            needsValue(a) ?: run {
                                ctx.stderr.writeUtf8("error: switch \"--fixup\" requires a value\n")
                                return CommandResult(exitCode = 129)
                            }
                        fixupOf = v
                        i += 2
                    }

                    a.startsWith("--squash=") -> {
                        squashOf = a.substringAfter("=")
                        i++
                    }

                    a == "--squash" -> {
                        val v =
                            needsValue(a) ?: run {
                                ctx.stderr.writeUtf8("error: switch \"--squash\" requires a value\n")
                                return CommandResult(exitCode = 129)
                            }
                        squashOf = v
                        i += 2
                    }

                    a == "--" -> {
                        endOpts = true
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git commit: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        pathspecs += a
                        i++
                    }
                }
            }

            // ---- mutual-exclusion checks (match real git wording) ----
            val msgSourceCount =
                listOf(
                    messages.isNotEmpty(),
                    fileMsgSet,
                    reuseFrom != null,
                ).count { it }
            if (msgSourceCount > 1) {
                // git reports the first conflicting pair; replicate the
                // common ones we can hit.
                val pairMsg =
                    when {
                        messages.isNotEmpty() && fileMsgSet -> "options '-m' and '-F' cannot be used together"
                        messages.isNotEmpty() && reuseFrom != null -> "options '-m' and '-C' cannot be used together"
                        fileMsgSet && reuseFrom != null -> "options '-F' and '-C' cannot be used together"
                        else -> "conflicting message options"
                    }
                ctx.stderr.writeUtf8("fatal: $pairMsg\n")
                return CommandResult(exitCode = 128)
            }
            if (all && pathspecs.isNotEmpty()) {
                val shown = pathspecs.joinToString(" ") + " ..."
                ctx.stderr.writeUtf8("fatal: paths '$shown' with -a does not make sense\n")
                return CommandResult(exitCode = 128)
            }
            if (resetAuthor && !amend && reuseFrom == null) {
                ctx.stderr.writeUtf8("fatal: --reset-author can be used only with -C, -c or --amend.\n")
                return CommandResult(exitCode = 128)
            }

            // Resolve an explicit author identity (overrides config/env for
            // the author line only).
            var authorOverride: GitIdentity? = null
            if (author != null) {
                val parsed = parseAuthor(author)
                if (parsed == null) {
                    ctx.stderr.writeUtf8("fatal: malformed --author parameter\n")
                    return CommandResult(exitCode = 128)
                }
                authorOverride = parsed
            }

            var index = repo.readIndex()
            val headSha = repo.refs.resolveHead()

            // --amend: the new commit replaces HEAD, so its parents are
            // HEAD's parents (not HEAD), and a missing message reuses
            // HEAD's. Refuses if there is no HEAD to amend.
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

            // -C / -c: pull message + author from another commit.
            val reuseCommit =
                if (reuseFrom != null) {
                    val sha = resolveRev(repo, reuseFrom)
                    if (sha == null) {
                        ctx.stderr.writeUtf8("fatal: could not lookup commit '$reuseFrom'\n")
                        return CommandResult(exitCode = 128)
                    }
                    repo.objects.readCommit(sha)
                } else {
                    null
                }

            // ---- determine the message, by source precedence ----
            var message: String? =
                when {
                    messages.isNotEmpty() -> {
                        messages.joinToString("\n\n")
                    }

                    fileMsgSet -> {
                        if (fileMsg == "-") {
                            ctx.stdin.readUtf8Text()
                        } else {
                            val path = resolveCwdPath(env.cwd, fileMsg!!)
                            if (!ctx.fs.exists(path)) {
                                ctx.stderr.writeUtf8(
                                    "fatal: could not read log file '$fileMsg': No such file or directory\n",
                                )
                                return CommandResult(exitCode = 128)
                            }
                            ctx.fs.readBytes(path).decodeToString()
                        }
                    }

                    reuseCommit != null -> {
                        reuseCommit.message
                    }

                    amend -> {
                        priorCommit!!.message
                    }

                    else -> {
                        null
                    }
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
            // noEdit with amend is a no-op here (we already reuse the prior
            // message when none is given); referencing it keeps the flag
            // meaningful and avoids "unused" surprises in future edits.
            if (noEdit && amend && message == null) {
                message = priorCommit!!.message
            }

            // Strip a single trailing newline so the LF-append below is
            // idempotent; an entirely-empty message stays empty.
            val normalizedMsg = message?.trimEnd('\n')
            if (normalizedMsg.isNullOrEmpty() && !allowEmptyMessage) {
                ctx.stderr.writeUtf8("Aborting commit due to empty commit message.\n")
                return CommandResult(exitCode = 1)
            }
            message = normalizedMsg

            val parentSha = if (amend) priorCommit!!.parents.firstOrNull() else headSha
            val parentTreeSha =
                if (parentSha != null) repo.objects.readCommit(parentSha).tree else null

            // ---- -a / --all: stage tracked modifications + deletions ----
            if (all) {
                index = stageAllTracked(repo, index)
            }

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

            // Partial commit (`commit -- <paths>`): the committed tree is
            // HEAD's tree with the named paths overlaid from the work tree.
            // The rest of the index is left untouched, but the named paths
            // are written through to the index too (so they become part of
            // HEAD and stop showing as staged afterwards).
            //
            // `commitIndex` is the entry set used to build the committed
            // tree; `persistIndex` is what gets written back to disk.
            var persistIndex: IndexFile? = if (all) index else null
            val commitIndex: IndexFile
            if (pathspecs.isNotEmpty()) {
                val resolved =
                    resolvePartialIndex(repo, env.cwd, index, parentSha, pathspecs)
                        ?: run {
                            // resolvePartialIndex prints its own diagnostic.
                            return CommandResult(exitCode = 128)
                        }
                if (resolved.unmatched != null) {
                    ctx.stderr.writeUtf8(
                        "error: pathspec '${resolved.unmatched}' did not match any file(s) known to git\n",
                    )
                    return CommandResult(exitCode = 128)
                }
                commitIndex = resolved.committed
                persistIndex = resolved.updatedWorking
            } else {
                commitIndex = index
            }

            val newTreeSha = writeTreeFromIndex(repo, commitIndex)
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
            // The committer is always "now" / current identity.
            val committerSec =
                (
                    ctx.env["GIT_COMMITTER_DATE"]
                        ?.split(' ')
                        ?.firstOrNull()
                        ?.toLongOrNull()
                )
                    ?: whenSec
            val committerTz =
                ctx.env["GIT_COMMITTER_DATE"]
                    ?.split(' ')
                    ?.getOrNull(1)
                    ?.takeIf { it.length == 5 }
                    ?: tz
            val committer = PersonStamp(identity.name, identity.email, committerSec, committerTz)

            // ---- author identity resolution ----
            // Priority:
            //  1. --reset-author → current identity + committer date
            //  2. --author=<...> for name/email (+ --date or now for date)
            //  3. -C/-c source author (name/email/date)
            //  4. --amend → preserve prior author
            //  5. default identity + author date (env/now)
            //
            // --date always overrides the *date* component when given.
            val parsedDate: Pair<Long, String>? =
                if (dateStr != null) {
                    val pd = parseGitDate(dateStr, tz)
                    if (pd == null) {
                        ctx.stderr.writeUtf8("fatal: invalid date format: $dateStr\n")
                        return CommandResult(exitCode = 128)
                    }
                    pd
                } else {
                    null
                }

            val authorPerson: PersonStamp =
                when {
                    resetAuthor -> {
                        val s = parsedDate?.first ?: committerSec
                        val z = parsedDate?.second ?: committerTz
                        PersonStamp(identity.name, identity.email, s, z)
                    }

                    authorOverride != null -> {
                        val s = parsedDate?.first ?: whenSec
                        val z = parsedDate?.second ?: tz
                        PersonStamp(authorOverride.name, authorOverride.email, s, z)
                    }

                    reuseCommit != null -> {
                        val base = reuseCommit.author
                        PersonStamp(
                            base.name,
                            base.email,
                            parsedDate?.first ?: base.whenSeconds,
                            parsedDate?.second ?: base.tz,
                        )
                    }

                    amend -> {
                        val base = priorCommit!!.author
                        PersonStamp(
                            base.name,
                            base.email,
                            parsedDate?.first ?: base.whenSeconds,
                            parsedDate?.second ?: base.tz,
                        )
                    }

                    else -> {
                        val s = parsedDate?.first ?: whenSec
                        val z = parsedDate?.second ?: tz
                        PersonStamp(identity.name, identity.email, s, z)
                    }
                }
            val person = committer
            val msg = message ?: ""
            val finalMsg =
                if (msg.isEmpty()) {
                    ""
                } else if (msg.endsWith("\n")) {
                    msg
                } else {
                    "$msg\n"
                }

            // Pre-commit gate. Snapshot the entries that actually form the
            // committed tree (`commitIndex`) — for a partial `commit -- <paths>`
            // that differs from the working index, so the validator sees what
            // is really being committed.
            val staged =
                commitIndex.entries.map { e ->
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
                    author = authorPerson,
                    committer = person,
                    message = finalMsg,
                )
            val newSha = repo.objects.write(ObjectType.COMMIT, encodeCommit(commit))
            val priorTip = if (amend) headSha else parentSha
            repo.refs.writeRef(branchRef, newSha)

            // Persist any index mutations (`-a` staging, partial-commit
            // pathspec write-through) only after the commit succeeds.
            persistIndex?.let { repo.writeIndex(it) }
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

        private suspend fun resolveIdentity(
            env: GitEnv,
            shellEnv: Map<String, String>,
            repo: GitRepo,
            fs: com.accucodeai.kash.fs.FileSystem,
        ): GitIdentity {
            // Real git's observed identity-fallback order:
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
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        false
    }

/**
 * A combined short-flag cluster (`-am`, `-qa`, `-aF`) is accepted iff it
 * is made only of the value-less flags `a`/`q` plus an optional single
 * value-taking flag (`m`/`F`) that, if present, terminates the cluster.
 * The value-taking flag may carry its value glued on (`-ammsg`).
 */
private fun isShortCluster(cluster: String): Boolean {
    var k = 0
    while (k < cluster.length) {
        when (cluster[k]) {
            'a', 'q' -> k++

            'm', 'F' -> return true

            // value flag terminates the cluster
            else -> return false
        }
    }
    return true
}

/** Parse `Name <email>` into a [GitIdentity]; null if malformed. */
private fun parseAuthor(s: String): GitIdentity? {
    val lt = s.indexOf('<')
    val gt = s.indexOf('>', lt + 1)
    if (lt < 0 || gt < 0) return null
    val name = s.substring(0, lt).trim()
    val email = s.substring(lt + 1, gt).trim()
    if (name.isEmpty() && email.isEmpty()) return null
    return GitIdentity(name, email)
}

/** Resolve a possibly-relative path against [cwd]. */
private fun resolveCwdPath(
    cwd: String,
    p: String,
): String =
    when {
        p.startsWith("/") -> p
        cwd == "/" -> "/$p"
        else -> "$cwd/$p"
    }

/**
 * `-a` / `--all`: refresh every already-tracked index entry from the work
 * tree — restage modifications, drop entries whose file was deleted.
 * Untracked files are not added.
 */
private suspend fun stageAllTracked(
    repo: GitRepo,
    index: IndexFile,
): IndexFile {
    val workTree = repo.layout.workTree
    val out = mutableListOf<IndexEntry>()
    for (e in index.entries) {
        if (e.stage != 0) {
            // Preserve conflict stages untouched — the unmerged check
            // upstream will reject the commit anyway.
            out += e
            continue
        }
        val abs = if (workTree == "/") "/${e.path}" else "$workTree/${e.path}"
        if (!repo.fs.exists(abs) || repo.fs.isDirectory(abs)) {
            // Deleted in the work tree → drop from the index.
            continue
        }
        val bytes = repo.fs.readBytes(abs)
        val sha = repo.objects.write(ObjectType.BLOB, bytes)
        out +=
            IndexEntry(
                mode = inferWorkTreeMode(repo.fs, abs),
                size = bytes.size,
                sha = sha,
                path = e.path,
            )
    }
    return IndexFile(version = 2, entries = out)
}

private fun inferWorkTreeMode(
    fs: com.accucodeai.kash.fs.FileSystem,
    abs: String,
): FileMode {
    val s =
        try {
            fs.stat(abs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return FileMode.REGULAR
        }
    return if ((s.mode and 0b001_001_001) != 0) FileMode.EXECUTABLE else FileMode.REGULAR
}

/** Outcome of resolving a partial (`commit -- <paths>`) request. */
private class PartialIndexResult(
    /** Entry set used to build the committed tree (HEAD + overlaid paths). */
    val committed: IndexFile,
    /** Entry set to persist back to `.git/index` after the commit. */
    val updatedWorking: IndexFile,
    /** First pathspec that matched nothing known to git, or null. */
    val unmatched: String?,
)

/**
 * Build the index views for a partial commit.
 *
 * The committed tree is HEAD's tree with the named paths overlaid from the
 * work tree (added, modified, or deleted). The working index keeps all of
 * its current entries, except the named paths which are written through to
 * their committed (work-tree) value — so they stop showing as staged.
 *
 * A pathspec that matches neither the index nor HEAD nor an existing
 * work-tree file is reported via [PartialIndexResult.unmatched].
 */
private suspend fun resolvePartialIndex(
    repo: GitRepo,
    cwd: String,
    index: IndexFile,
    parentSha: String?,
    pathspecs: List<String>,
): PartialIndexResult? {
    val workTree = repo.layout.workTree

    // HEAD-tree paths → sha+mode, the base the committed tree starts from.
    val headEntries = mutableMapOf<String, IndexEntry>()
    if (parentSha != null) {
        val tree = repo.objects.readCommit(parentSha).tree
        for ((path, te) in flattenTree(repo, tree, "")) {
            headEntries[path] =
                IndexEntry(mode = te.first, size = 0, sha = te.second, path = path)
        }
    }
    val indexByPath = index.entries.filter { it.stage == 0 }.associateBy { it.path }

    // Normalize each pathspec to a repo-relative prefix. A pathspec may
    // name a file or a directory; we match by exact path or path-prefix.
    val rels =
        pathspecs.map { spec ->
            val abs = resolveCwdPath(cwd, spec)
            val base = if (workTree == "/") "/" else "$workTree/"
            when {
                workTree == "/" && abs.startsWith("/") -> abs.removePrefix("/")
                abs == workTree -> ""
                abs.startsWith(base) -> abs.removePrefix(base)
                else -> spec
            }
        }

    // Candidate path universe: everything git knows about (index + HEAD).
    val known = (indexByPath.keys + headEntries.keys)

    val committed = headEntries.toMutableMap()
    val working = indexByPath.toMutableMap()

    for (rel in rels) {
        val matched =
            known.filter { it == rel || it.startsWith("$rel/") }.toMutableSet()
        // Also include a work-tree file that exists but isn't tracked yet?
        // Real git rejects untracked pathspecs, so only known paths count.
        if (matched.isEmpty()) {
            // Maybe the path exists in the work tree but is untracked, or
            // matches nothing at all → git's "did not match" error.
            return PartialIndexResult(index, index, unmatched = rel)
        }
        for (path in matched) {
            val abs = if (workTree == "/") "/$path" else "$workTree/$path"
            if (repo.fs.exists(abs) && !repo.fs.isDirectory(abs)) {
                val bytes = repo.fs.readBytes(abs)
                val sha = repo.objects.write(ObjectType.BLOB, bytes)
                val entry =
                    IndexEntry(
                        mode = inferWorkTreeMode(repo.fs, abs),
                        size = bytes.size,
                        sha = sha,
                        path = path,
                    )
                committed[path] = entry
                working[path] = entry
            } else {
                // Deleted in the work tree → remove from both views.
                committed.remove(path)
                working.remove(path)
            }
        }
    }

    return PartialIndexResult(
        committed = IndexFile(version = 2, entries = committed.values.toList()),
        updatedWorking = IndexFile(version = 2, entries = working.values.toList()),
        unmatched = null,
    )
}

private suspend fun flattenTree(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
): Map<String, Pair<FileMode, String>> {
    val out = mutableMapOf<String, Pair<FileMode, String>>()
    for (e in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        if (e.mode == FileMode.TREE) {
            out.putAll(flattenTree(repo, e.sha, p))
        } else {
            out[p] = e.mode to e.sha
        }
    }
    return out
}

/**
 * Parse a `--date` value into `(epochSeconds, ±HHMM)`. Returns null if the
 * format is not understood. Supports:
 *  - raw git form: `<seconds> <±HHMM>` (e.g. `1112937193 +0200`)
 *  - epoch form: `@<seconds>` (optionally `@<seconds> <±HHMM>`)
 *  - ISO-8601: `YYYY-MM-DD[ T]HH:MM:SS[ ±HH:MM | ±HHMM | Z]`
 *  - ISO date only: `YYYY-MM-DD` (time defaults to 00:00:00)
 *
 * No-offset ISO inputs use [defaultTz] (the prevailing author tz), matching
 * real git interpreting bare local times in the ambient timezone.
 */
internal fun parseGitDate(
    raw: String,
    defaultTz: String,
): Pair<Long, String>? {
    val s = raw.trim()
    if (s.isEmpty()) return null

    // Raw `<seconds> <tz>`.
    val rawParts = s.split(' ')
    if (rawParts.size == 2 && rawParts[0].toLongOrNull() != null && isTz(rawParts[1])) {
        return rawParts[0].toLong() to rawParts[1]
    }
    // `@<seconds>` (git internal form), optional tz.
    if (s.startsWith("@")) {
        val rest = s.substring(1).split(' ')
        val sec = rest[0].toLongOrNull() ?: return null
        val tz = rest.getOrNull(1)?.takeIf { isTz(it) } ?: defaultTz
        return sec to tz
    }
    // Bare seconds.
    if (rawParts.size == 1) {
        s.toLongOrNull()?.let { return it to defaultTz }
    }

    return parseIso(s, defaultTz)
}

private fun isTz(s: String): Boolean = s.length == 5 && (s[0] == '+' || s[0] == '-') && s.drop(1).all { it.isDigit() }

private fun parseIso(
    raw: String,
    defaultTz: String,
): Pair<Long, String>? {
    // Split off a trailing timezone token: Z, ±HH:MM, ±HHMM, or ±HH.
    var body = raw.trim()
    var tz = defaultTz
    var tzMinutes: Int? = null

    fun applyTz(token: String): Boolean {
        when {
            token == "Z" || token == "z" -> {
                tz = "+0000"
                tzMinutes = 0
            }

            token.length >= 2 && (token[0] == '+' || token[0] == '-') -> {
                val sign = if (token[0] == '-') -1 else 1
                val digits = token.substring(1).replace(":", "")
                if (digits.length != 4 && digits.length != 2) return false
                if (!digits.all { it.isDigit() }) return false
                val hh = digits.substring(0, 2).toInt()
                val mm = if (digits.length == 4) digits.substring(2, 4).toInt() else 0
                tzMinutes = sign * (hh * 60 + mm)
                tz = formatTz(tzMinutes)
            }

            else -> {
                return false
            }
        }
        return true
    }

    // Trailing Z?
    if (body.endsWith("Z") || body.endsWith("z")) {
        applyTz("Z")
        body = body.dropLast(1).trim()
    } else {
        // A trailing timezone is recognized in two shapes:
        //  - separated by a space:  "...:13 +0200"
        //  - glued onto an ISO time: "...22:13:13+02:00"
        // We must NOT mistake the date's own hyphens (e.g. "1970-01-02")
        // for an offset, so a glued offset is only accepted when the body
        // contains a time component (a ':').
        val spaceTz = Regex("\\s([+-]\\d{2}:?\\d{2})\\s*$").find(body)
        if (spaceTz != null) {
            if (!applyTz(spaceTz.groupValues[1])) return null
            body = body.substring(0, spaceTz.range.first).trim()
        } else if (body.contains(':')) {
            val gluedTz = Regex("([+-]\\d{2}:?\\d{2})\\s*$").find(body)
            if (gluedTz != null) {
                if (!applyTz(gluedTz.groupValues[1])) return null
                body = body.substring(0, gluedTz.range.first).trim()
            }
        }
    }

    // Now body is `YYYY-MM-DD[ T]HH:MM[:SS]` or `YYYY-MM-DD`.
    val dt = body.replace('T', ' ').trim()
    val parts = dt.split(Regex("\\s+"))
    val datePart = parts[0]
    val timePart = parts.getOrNull(1)

    val d = datePart.split("-")
    if (d.size != 3) return null
    val year = d[0].toIntOrNull() ?: return null
    val month = d[1].toIntOrNull() ?: return null
    val day = d[2].toIntOrNull() ?: return null

    var hh = 0
    var mm = 0
    var ss = 0
    if (timePart != null) {
        val t = timePart.split(":")
        if (t.isEmpty()) return null
        hh = t[0].toIntOrNull() ?: return null
        mm = t.getOrNull(1)?.toIntOrNull() ?: 0
        ss = t.getOrNull(2)?.toIntOrNull() ?: 0
    }

    val offsetMin = tzMinutes ?: parseTzMinutes(defaultTz) ?: 0
    val epoch = civilToEpochSeconds(year, month, day, hh, mm, ss) - offsetMin * 60L
    return epoch to tz
}

private fun parseTzMinutes(tz: String): Int? {
    if (!isTz(tz)) return null
    val sign = if (tz[0] == '-') -1 else 1
    val hh = tz.substring(1, 3).toInt()
    val mm = tz.substring(3, 5).toInt()
    return sign * (hh * 60 + mm)
}

private fun formatTz(minutes: Int): String {
    val sign = if (minutes < 0) '-' else '+'
    val abs = if (minutes < 0) -minutes else minutes
    val hh = abs / 60
    val mm = abs % 60

    fun two(n: Int): String = if (n < 10) "0$n" else "$n"
    return "$sign${two(hh)}${two(mm)}"
}

/**
 * Days-from-civil algorithm (Howard Hinnant) → seconds since the Unix
 * epoch, treating the input as a UTC wall-clock time. Caller subtracts the
 * timezone offset to recover the instant.
 */
private fun civilToEpochSeconds(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = (y - era * 400).toLong()
    val doy = ((153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1).toLong()
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era.toLong() * 146097 + doe - 719468
    return days * 86400 + hour * 3600L + minute * 60L + second
}
