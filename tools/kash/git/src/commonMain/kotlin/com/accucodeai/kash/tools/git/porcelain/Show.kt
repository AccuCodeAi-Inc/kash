package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.decodeTag
import com.accucodeai.kash.tools.git.plumbing.renderStat
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git show` — print one or more objects. For commits (and annotated
 * tags peeling to commits) this prints the log-style header + message
 * followed by the commit's diff against its first parent — matching
 * `git show`'s default `--cc -p` behavior for non-merge commits.
 *
 * Supported flags:
 *  - `--stat` — diffstat instead of the full patch
 *  - `-s` / `--no-patch` — suppress the diff (header + message only)
 *  - `--name-only` — list changed paths instead of the patch
 *  - `--name-status` — `<status>\t<path>` per changed file
 *  - `--oneline` — `<short-sha> <subject>` header (still followed by the diff)
 *  - `--pretty=<fmt>` / `--format=<fmt>` — custom commit formatting
 *  - `--abbrev-commit` — abbreviate the `commit <sha>` line
 *  - `<rev>:<path>` — print the blob at `<path>` as of `<rev>`
 *
 * Multiple object arguments are concatenated. Rename detection is not
 * performed (a rename shows as a delete + add).
 */
public fun gitShowSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "show"

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

            var mode = ShowMode.PATCH
            var oneline = false
            var pretty: String? = null
            var abbrevCommit = false
            val specs = mutableListOf<String>()
            var afterDoubleDash = false

            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (afterDoubleDash) {
                    specs += a
                    i++
                    continue
                }
                when {
                    a == "--" -> {
                        afterDoubleDash = true
                    }

                    a == "--stat" -> {
                        mode = ShowMode.STAT
                    }

                    a == "-s" || a == "--no-patch" -> {
                        mode = ShowMode.NO_PATCH
                    }

                    a == "--name-only" -> {
                        mode = ShowMode.NAME_ONLY
                    }

                    a == "--name-status" -> {
                        mode = ShowMode.NAME_STATUS
                    }

                    a == "-p" || a == "-u" || a == "--patch" -> {
                        mode = ShowMode.PATCH
                    }

                    a == "--oneline" -> {
                        oneline = true
                        abbrevCommit = true
                    }

                    a == "--abbrev-commit" -> {
                        abbrevCommit = true
                    }

                    a == "--no-abbrev-commit" -> {
                        abbrevCommit = false
                    }

                    a.startsWith("--pretty=") -> {
                        pretty = a.substringAfter("=")
                    }

                    a.startsWith("--format=") -> {
                        // `--format=<x>` aliases `--pretty=<x>`, but a bare
                        // format string means `format:<x>` (as in `git log`).
                        val v = a.substringAfter("=")
                        pretty = if (prettyPreset(v) != null || v.startsWith("format:")) v else "format:$v"
                    }

                    a == "--pretty" || a == "--format" -> {
                        pretty = "medium"
                    }

                    a == "--no-color" || a == "--no-renames" || a == "--renames" -> {}

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git show: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        specs += a
                    }
                }
                i++
            }

            if (specs.isEmpty()) specs += "HEAD"

            for (spec in specs) {
                if (':' in spec && resolveRev(repo, spec.substringBefore(':')) != null) {
                    val rc = showBlobAtPath(repo, spec, ctx)
                    if (rc != 0) return CommandResult(exitCode = rc)
                    continue
                }
                val rc = showObject(repo, spec, mode, oneline, pretty, abbrevCommit, ctx)
                if (rc != 0) return CommandResult(exitCode = rc)
            }
            return CommandResult(exitCode = 0)
        }

        private suspend fun showBlobAtPath(
            repo: GitRepo,
            spec: String,
            ctx: CommandContext,
        ): Int {
            val rev = spec.substringBefore(':')
            val path = spec.substringAfter(':')
            val commitSha =
                resolveRev(repo, rev) ?: run {
                    ctx.stderr.writeUtf8("fatal: bad revision '$rev'\n")
                    return 128
                }
            val commit = repo.objects.readCommit(commitSha)
            val blobSha =
                lookupPath(repo, commit.tree, path) ?: run {
                    ctx.stderr.writeUtf8("fatal: path '$path' does not exist in '$rev'\n")
                    return 128
                }
            val bytes = repo.objects.readBlob(blobSha)
            ctx.stdout.writeUtf8(bytes.decodeToString())
            return 0
        }

        private suspend fun showObject(
            repo: GitRepo,
            spec: String,
            mode: ShowMode,
            oneline: Boolean,
            pretty: String?,
            abbrevCommit: Boolean,
            ctx: CommandContext,
        ): Int {
            // `resolveRev` peels annotated tags straight through to the
            // commit. For `git show <tag>` we want the tag object itself
            // (so we can print the tag header), so probe the raw ref target
            // first and fall back to the peeling resolver.
            val rawTagSha = rawRefTarget(repo, spec)
            val sha =
                rawTagSha
                    ?: resolveRev(repo, spec)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: bad revision '$spec'\n")
                        return 128
                    }
            val parsed = repo.objects.readParsed(sha)
            when (parsed.type) {
                ObjectType.COMMIT -> {
                    val commit = repo.objects.readCommit(sha)
                    renderCommitHeader(sha, commit, oneline, pretty, abbrevCommit, ctx)
                    renderCommitBody(repo, sha, mode, ctx)
                }

                ObjectType.TAG -> {
                    val tag = decodeTag(parsed.payload)
                    renderTagHeader(tag.tagName, tag.tagger, tag.message, ctx)
                    // Peel through (possibly chained) tag objects to the
                    // underlying commit, then show it.
                    val target = peelToCommit(repo, tag.targetSha)
                    if (target != null) {
                        val commit = repo.objects.readCommit(target)
                        renderCommitHeader(target, commit, oneline, pretty, abbrevCommit, ctx)
                        renderCommitBody(repo, target, mode, ctx)
                    }
                }

                ObjectType.BLOB -> {
                    ctx.stdout.writeUtf8(parsed.payload.decodeToString())
                }

                ObjectType.TREE -> {
                    for (e in repo.objects.readTree(sha)) {
                        ctx.stdout.writeUtf8("${e.mode.wire} ${e.sha}\t${e.name}\n")
                    }
                }
            }
            return 0
        }

        private suspend fun renderCommitHeader(
            sha: String,
            c: CommitPayload,
            oneline: Boolean,
            pretty: String?,
            abbrevCommit: Boolean,
            ctx: CommandContext,
        ) {
            val format =
                when {
                    pretty == null -> null
                    pretty.startsWith("format:") -> pretty.removePrefix("format:")
                    prettyPreset(pretty) != null -> prettyPreset(pretty)
                    pretty.startsWith("%") -> pretty
                    else -> null
                }
            when {
                format != null -> {
                    ctx.stdout.writeUtf8(renderPrettyFormat(format, sha, c))
                    if (!format.endsWith("\n")) ctx.stdout.writeUtf8("\n")
                }

                oneline -> {
                    ctx.stdout.writeUtf8("${sha.substring(0, 7)} ${c.message.substringBefore('\n')}\n")
                }

                else -> {
                    val shaOut = if (abbrevCommit) sha.substring(0, 7) else sha
                    ctx.stdout.writeUtf8("commit $shaOut\n")
                    ctx.stdout.writeUtf8("Author: ${c.author.name} <${c.author.email}>\n")
                    ctx.stdout.writeUtf8("Date:   ${gitDateFormat(c.author.whenSeconds, c.author.tz)}\n")
                    ctx.stdout.writeUtf8("\n")
                    for (line in c.message.trimEnd('\n').split('\n')) {
                        ctx.stdout.writeUtf8("    $line\n")
                    }
                }
            }
        }

        private suspend fun renderTagHeader(
            tagName: String,
            tagger: PersonStamp,
            message: String,
            ctx: CommandContext,
        ) {
            ctx.stdout.writeUtf8("tag $tagName\n")
            ctx.stdout.writeUtf8("Tagger: ${tagger.name} <${tagger.email}>\n")
            ctx.stdout.writeUtf8("Date:   ${gitDateFormat(tagger.whenSeconds, tagger.tz)}\n")
            ctx.stdout.writeUtf8("\n")
            ctx.stdout.writeUtf8(message.trimEnd('\n'))
            ctx.stdout.writeUtf8("\n\n")
        }

        private suspend fun renderCommitBody(
            repo: GitRepo,
            sha: String,
            mode: ShowMode,
            ctx: CommandContext,
        ) {
            when (mode) {
                ShowMode.NO_PATCH -> {}

                ShowMode.STAT -> {
                    val stat = commitDiffStat(repo, sha)
                    if (stat.isNotEmpty()) {
                        ctx.stdout.writeUtf8("\n")
                        ctx.stdout.writeUtf8(renderStat(stat))
                    }
                }

                ShowMode.NAME_ONLY -> {
                    val names = changedPaths(repo, sha)
                    if (names.isNotEmpty()) {
                        ctx.stdout.writeUtf8("\n")
                        for (n in names) ctx.stdout.writeUtf8("${n.second}\n")
                    }
                }

                ShowMode.NAME_STATUS -> {
                    val names = changedPaths(repo, sha)
                    if (names.isNotEmpty()) {
                        ctx.stdout.writeUtf8("\n")
                        for ((status, path) in names) ctx.stdout.writeUtf8("$status\t$path\n")
                    }
                }

                ShowMode.PATCH -> {
                    val patch = commitDiffPatch(repo, sha)
                    if (patch.isNotEmpty()) {
                        ctx.stdout.writeUtf8("\n")
                        ctx.stdout.writeUtf8(patch)
                    }
                }
            }
        }

        /** Changed paths for [sha] vs its first parent, with M/A/D status. */
        private suspend fun changedPaths(
            repo: GitRepo,
            sha: String,
        ): List<Pair<Char, String>> {
            val commit = repo.objects.readCommit(sha)
            val newSide = flattenTree(repo, commit.tree)
            val oldSide =
                commit.parents.firstOrNull()?.let { p ->
                    flattenTree(repo, repo.objects.readCommit(p).tree)
                } ?: emptyMap()
            val out = mutableListOf<Pair<Char, String>>()
            for (path in (oldSide.keys + newSide.keys).sorted()) {
                val l = oldSide[path]
                val r = newSide[path]
                when {
                    l == null && r != null -> {
                        out += 'A' to path
                    }

                    r == null && l != null -> {
                        out += 'D' to path
                    }

                    l != null && r != null && (!l.bytes.contentEquals(r.bytes) || l.mode != r.mode) -> {
                        out += 'M' to path
                    }
                }
            }
            return out
        }

        /**
         * If [spec] names a ref whose raw target is an annotated-tag
         * object, return that tag object's sha (un-peeled). Returns null
         * otherwise so the caller falls back to [resolveRev].
         */
        private suspend fun rawRefTarget(
            repo: GitRepo,
            spec: String,
        ): String? {
            val candidates =
                when {
                    spec.startsWith("refs/") -> listOf(spec)
                    else -> listOf("refs/tags/$spec")
                }
            for (ref in candidates) {
                val target = repo.refs.resolve(ref) ?: continue
                val type =
                    try {
                        repo.objects.readParsed(target).type
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        continue
                    }
                if (type == ObjectType.TAG) return target
            }
            return null
        }

        /** Follow (chained) annotated-tag objects down to a commit sha. */
        private suspend fun peelToCommit(
            repo: GitRepo,
            startSha: String,
        ): String? {
            var cur = startSha
            repeat(8) {
                val p =
                    try {
                        repo.objects.readParsed(cur)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        return null
                    }
                when (p.type) {
                    ObjectType.COMMIT -> return cur
                    ObjectType.TAG -> cur = decodeTag(p.payload).targetSha
                    else -> return null
                }
            }
            return null
        }

        private suspend fun lookupPath(
            repo: GitRepo,
            rootTree: String,
            path: String,
        ): String? {
            var curTree = rootTree
            val parts = path.split('/')
            for ((i, part) in parts.withIndex()) {
                val entries = repo.objects.readTree(curTree)
                val match = entries.firstOrNull { it.name == part } ?: return null
                if (i == parts.lastIndex) {
                    return if (match.mode == FileMode.TREE) null else match.sha
                }
                if (match.mode != FileMode.TREE) return null
                curTree = match.sha
            }
            return null
        }
    }

private enum class ShowMode { PATCH, STAT, NO_PATCH, NAME_ONLY, NAME_STATUS }
