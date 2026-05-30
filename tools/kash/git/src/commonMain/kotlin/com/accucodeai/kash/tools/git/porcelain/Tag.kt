package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.TagPayload
import com.accucodeai.kash.tools.git.plumbing.decodeTag
import com.accucodeai.kash.tools.git.plumbing.encodeTag
import com.accucodeai.kash.tools.git.plumbing.isAncestor
import com.accucodeai.kash.tools.git.plumbing.parseFramedObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git tag` — lightweight + annotated tags under `refs/tags/`.
 *
 * Create:
 *  - `<name> [<commit>]`            lightweight tag at HEAD or <commit>.
 *  - `-a <name> [-m <msg>]`         annotated tag (writes a tag object).
 *  - `-m <msg>` / `-F <file>`       message (implies `-a`); multiple `-m`
 *                                   are joined by a blank line; `-F -`
 *                                   reads stdin.
 *  - `-s` / `--sign`                treated as `-a` plus a stderr note
 *                                   that signing is unsupported.
 *  - `-f` / `--force`               overwrite an existing tag ref.
 *
 * List (default, or `-l`/`--list`):
 *  - shell-glob `<pattern>` args filter by tag name.
 *  - `-n[<num>]` shows up to <num> annotation lines beside each tag.
 *  - `--sort=<key>` (`refname`, `-refname`, `creatordate`, …).
 *  - `--contains`/`--no-contains`, `--merged`/`--no-merged`,
 *    `--points-at <object>` filters.
 *
 * Delete:
 *  - `-d`/`--delete <name>...`      prints `Deleted tag '<name>' (was <sha>)`.
 *
 * The tag object's tagger identity & date come from the committer
 * fallback chain (GIT_COMMITTER_* → config → adapter), matching real git.
 */
public fun gitTagSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "tag"

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

            var delete = false
            var annotated = false
            var sign = false
            var force = false
            var list = false
            var numLines: Int? = null
            val messages = mutableListOf<String>()
            var fromFile: String? = null
            var sort: String? = null
            val pointsAt = mutableListOf<String>()
            val contains = mutableListOf<String>()
            val noContains = mutableListOf<String>()
            val merged = mutableListOf<String>()
            val noMerged = mutableListOf<String>()
            val positional = mutableListOf<String>()
            var noMoreFlags = false
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    noMoreFlags -> {
                        positional += a
                        i++
                    }

                    a == "--" -> {
                        noMoreFlags = true
                        i++
                    }

                    a == "-l" || a == "--list" -> {
                        list = true
                        i++
                    }

                    a == "-d" || a == "--delete" -> {
                        delete = true
                        i++
                    }

                    a == "-a" || a == "--annotate" -> {
                        annotated = true
                        i++
                    }

                    a == "-s" || a == "--sign" -> {
                        sign = true
                        annotated = true
                        i++
                    }

                    a == "-f" || a == "--force" -> {
                        force = true
                        i++
                    }

                    a == "-m" || a == "--message" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch `m' requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        messages += args[i + 1]
                        annotated = true
                        i += 2
                    }

                    a.startsWith("--message=") -> {
                        messages += a.substringAfter("=")
                        annotated = true
                        i++
                    }

                    a.startsWith("-m") && a.length > 2 -> {
                        messages += a.substring(2)
                        annotated = true
                        i++
                    }

                    a == "-F" || a == "--file" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch `F' requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        fromFile = args[i + 1]
                        annotated = true
                        i += 2
                    }

                    a.startsWith("--file=") -> {
                        fromFile = a.substringAfter("=")
                        annotated = true
                        i++
                    }

                    a.startsWith("-F") && a.length > 2 -> {
                        fromFile = a.substring(2)
                        annotated = true
                        i++
                    }

                    a == "-n" -> {
                        numLines = 1
                        list = true
                        i++
                    }

                    a.startsWith("-n") && a.substring(2).toIntOrNull() != null -> {
                        numLines = a.substring(2).toInt()
                        list = true
                        i++
                    }

                    a.startsWith("--sort=") -> {
                        sort = a.substringAfter("=")
                        i++
                    }

                    a == "--sort" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch `sort' requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        sort = args[i + 1]
                        i += 2
                    }

                    a.startsWith("--points-at=") -> {
                        pointsAt += a.substringAfter("=")
                        list = true
                        i++
                    }

                    a == "--points-at" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch `points-at' requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        pointsAt += args[i + 1]
                        list = true
                        i += 2
                    }

                    a.startsWith("--contains=") -> {
                        contains += a.substringAfter("=")
                        list = true
                        i++
                    }

                    a == "--contains" -> {
                        contains += args.getOrNull(i + 1) ?: "HEAD"
                        list = true
                        i += if (i + 1 < args.size) 2 else 1
                    }

                    a.startsWith("--no-contains=") -> {
                        noContains += a.substringAfter("=")
                        list = true
                        i++
                    }

                    a == "--no-contains" -> {
                        noContains += args.getOrNull(i + 1) ?: "HEAD"
                        list = true
                        i += if (i + 1 < args.size) 2 else 1
                    }

                    a.startsWith("--merged=") -> {
                        merged += a.substringAfter("=")
                        list = true
                        i++
                    }

                    a == "--merged" -> {
                        merged += args.getOrNull(i + 1) ?: "HEAD"
                        list = true
                        i += if (i + 1 < args.size) 2 else 1
                    }

                    a.startsWith("--no-merged=") -> {
                        noMerged += a.substringAfter("=")
                        list = true
                        i++
                    }

                    a == "--no-merged" -> {
                        noMerged += args.getOrNull(i + 1) ?: "HEAD"
                        list = true
                        i += if (i + 1 < args.size) 2 else 1
                    }

                    a.startsWith("-") && a != "-" -> {
                        ctx.stderr.writeUtf8("error: unknown switch `${a.trimStart('-')}'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                        i++
                    }
                }
            }

            if (!messages.isEmpty() && fromFile != null) {
                ctx.stderr.writeUtf8("fatal: options '-F' and '-m' cannot be used together\n")
                return CommandResult(exitCode = 128)
            }

            if (delete) {
                return runDelete(repo, ctx, positional)
            }

            // List mode: explicit -l/-n/filters/sort, or no positional name
            // and no creation intent.
            val creating = positional.isNotEmpty() && !list
            if (!creating) {
                return runList(
                    repo = repo,
                    ctx = ctx,
                    patterns = positional,
                    numLines = numLines,
                    sort = sort,
                    pointsAt = pointsAt,
                    contains = contains,
                    noContains = noContains,
                    merged = merged,
                    noMerged = noMerged,
                )
            }

            // Creating a tag.
            val name = positional[0]
            if (positional.size > 2) {
                ctx.stderr.writeUtf8("fatal: too many arguments\n")
                return CommandResult(exitCode = 128)
            }
            val spec = positional.getOrNull(1) ?: "HEAD"
            val target =
                resolveObjectRef(repo, spec) ?: run {
                    ctx.stderr.writeUtf8("fatal: Failed to resolve '$spec' as a valid ref.\n")
                    return CommandResult(exitCode = 128)
                }

            val refName = "refs/tags/$name"
            val existing = repo.refs.resolve(refName)
            if (existing != null && !force) {
                ctx.stderr.writeUtf8("fatal: tag '$name' already exists\n")
                return CommandResult(exitCode = 128)
            }

            if (sign) {
                ctx.stderr.writeUtf8(
                    "warning: git tag: signing is unsupported in kash; writing an unsigned annotated tag\n",
                )
            }

            val newRefValue: String =
                if (annotated) {
                    val rawMessage: String? =
                        when {
                            fromFile != null -> readMessageFile(ctx, env, fromFile)
                            messages.isNotEmpty() -> messages.joinToString("\n\n")
                            else -> null
                        }
                    if (rawMessage == null) {
                        // No editor in kash — refuse rather than open one.
                        ctx.stderr.writeUtf8(
                            "fatal: no tag message given (kash has no editor; use -m or -F)\n",
                        )
                        return CommandResult(exitCode = 128)
                    }
                    val cleaned = cleanupMessage(rawMessage)
                    val targetType =
                        try {
                            parseFramedObject(repo.objects.read(target)).type
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Throwable) {
                            ObjectType.COMMIT
                        }
                    val tagger = resolveTagger(env, ctx, repo)
                    val tagPayload =
                        TagPayload(
                            targetSha = target,
                            targetType = targetType,
                            tagName = name,
                            tagger = tagger,
                            message = cleaned,
                        )
                    repo.objects.write(ObjectType.TAG, encodeTag(tagPayload))
                } else {
                    target
                }

            // "Updated tag" feedback only when force actually changed the ref.
            if (existing != null && force && existing != newRefValue) {
                ctx.stdout.writeUtf8("Updated tag '$name' (was ${existing.substring(0, 7)})\n")
            }
            repo.refs.writeRef(refName, newRefValue)
            return CommandResult(exitCode = 0)
        }

        private suspend fun runDelete(
            repo: GitRepo,
            ctx: CommandContext,
            names: List<String>,
        ): CommandResult {
            if (names.isEmpty()) {
                ctx.stderr.writeUtf8("fatal: tag name required\n")
                return CommandResult(exitCode = 128)
            }
            var anyMissing = false
            for (name in names) {
                val ref = "refs/tags/$name"
                val sha = repo.refs.resolve(ref)
                if (sha == null) {
                    ctx.stderr.writeUtf8("error: tag '$name' not found.\n")
                    anyMissing = true
                    continue
                }
                val path = repo.layout.refFile(ref)
                if (ctx.fs.exists(path)) ctx.fs.remove(path)
                ctx.stdout.writeUtf8("Deleted tag '$name' (was ${sha.substring(0, 7)})\n")
            }
            return CommandResult(exitCode = if (anyMissing) 1 else 0)
        }

        private suspend fun runList(
            repo: GitRepo,
            ctx: CommandContext,
            patterns: List<String>,
            numLines: Int?,
            sort: String?,
            pointsAt: List<String>,
            contains: List<String>,
            noContains: List<String>,
            merged: List<String>,
            noMerged: List<String>,
        ): CommandResult {
            data class TagInfo(
                val name: String,
                val refSha: String,
                // The eventual commit (peeled through annotated tags), or
                // null if the ref doesn't resolve to a commit.
                val peeledCommit: String?,
                val creatorSeconds: Long,
            )

            val resolvedPointsAt = pointsAt.mapNotNull { resolveObjectRef(repo, it) }.toSet()
            val resolvedContains = contains.mapNotNull { resolveRev(repo, it) }
            val resolvedNoContains = noContains.mapNotNull { resolveRev(repo, it) }
            val resolvedMerged = merged.mapNotNull { resolveRev(repo, it) }
            val resolvedNoMerged = noMerged.mapNotNull { resolveRev(repo, it) }

            val infos = mutableListOf<TagInfo>()
            for ((ref, sha) in repo.refs.listRefs("refs/tags")) {
                val name = ref.removePrefix("refs/tags/")
                if (patterns.isNotEmpty() && patterns.none { tagGlobMatch(it, name) }) continue

                // Resolve to the eventual commit for ancestry filters, and
                // collect the creator (tagger/committer) timestamp for sort.
                var peeledCommit: String? = null
                var creatorSeconds = 0L
                val parsed =
                    try {
                        parseFramedObject(repo.objects.read(sha))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        null
                    }
                when (parsed?.type) {
                    ObjectType.TAG -> {
                        val tag = decodeTag(parsed.payload)
                        creatorSeconds = tag.tagger.whenSeconds
                        peeledCommit = peelToCommit(repo, sha)
                    }

                    ObjectType.COMMIT -> {
                        peeledCommit = sha
                        creatorSeconds =
                            try {
                                repo.objects
                                    .readCommit(sha)
                                    .committer.whenSeconds
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                0L
                            }
                    }

                    else -> {
                        peeledCommit = peelToCommit(repo, sha)
                    }
                }
                infos += TagInfo(name, sha, peeledCommit, creatorSeconds)
            }

            // Apply --points-at / --contains / --merged filters.
            val filtered =
                infos.filter { info ->
                    if (resolvedPointsAt.isNotEmpty() && info.refSha !in resolvedPointsAt) return@filter false
                    val commit = info.peeledCommit
                    if (resolvedContains.isNotEmpty()) {
                        if (commit == null) return@filter false
                        if (resolvedContains.none { isAncestor(repo, it, commit) }) return@filter false
                    }
                    if (resolvedNoContains.isNotEmpty()) {
                        if (commit != null && resolvedNoContains.any { isAncestor(repo, it, commit) }) {
                            return@filter false
                        }
                    }
                    if (resolvedMerged.isNotEmpty()) {
                        if (commit == null) return@filter false
                        if (resolvedMerged.none { isAncestor(repo, commit, it) }) return@filter false
                    }
                    if (resolvedNoMerged.isNotEmpty()) {
                        if (commit != null && resolvedNoMerged.any { isAncestor(repo, commit, it) }) {
                            return@filter false
                        }
                    }
                    true
                }

            val sorted =
                when (sort) {
                    null, "refname" -> {
                        filtered.sortedBy { it.name }
                    }

                    "-refname" -> {
                        filtered.sortedByDescending { it.name }
                    }

                    "creatordate", "taggerdate" -> {
                        filtered.sortedWith(compareBy({ it.creatorSeconds }, { it.name }))
                    }

                    "-creatordate", "-taggerdate" -> {
                        filtered.sortedWith(compareByDescending<TagInfo> { it.creatorSeconds }.thenBy { it.name })
                    }

                    else -> {
                        filtered.sortedBy { it.name }
                    }
                }

            for (info in sorted) {
                if (numLines == null) {
                    ctx.stdout.writeUtf8("${info.name}\n")
                    continue
                }
                val annotation = annotationLines(repo, info.refSha, numLines)
                if (numLines <= 0 || annotation.isEmpty()) {
                    ctx.stdout.writeUtf8("${info.name}\n")
                    continue
                }
                // First line: `%-15s ` + first annotation line.
                ctx.stdout.writeUtf8("${padName(info.name)}${annotation[0]}\n")
                for (j in 1 until annotation.size) {
                    ctx.stdout.writeUtf8("    ${annotation[j]}\n")
                }
            }
            return CommandResult(exitCode = 0)
        }

        /**
         * The annotation text git shows for `-n`: for annotated tags it's
         * the tag message; for lightweight tags it's the target commit's
         * message. Returns up to [n] non-trailing lines.
         */
        private suspend fun annotationLines(
            repo: GitRepo,
            refSha: String,
            n: Int,
        ): List<String> {
            val message: String =
                try {
                    val parsed = parseFramedObject(repo.objects.read(refSha))
                    when (parsed.type) {
                        ObjectType.TAG -> decodeTag(parsed.payload).message
                        ObjectType.COMMIT -> repo.objects.readCommit(refSha).message
                        else -> return emptyList()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    return emptyList()
                }
            val lines = message.trimEnd('\n').split('\n')
            return lines.take(n)
        }

        private suspend fun peelToCommit(
            repo: GitRepo,
            sha: String,
        ): String? {
            var cur = sha
            repeat(10) {
                val parsed =
                    try {
                        parseFramedObject(repo.objects.read(cur))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        return null
                    }
                when (parsed.type) {
                    ObjectType.COMMIT -> return cur
                    ObjectType.TAG -> cur = decodeTag(parsed.payload).targetSha
                    else -> return null
                }
            }
            return null
        }

        private suspend fun readMessageFile(
            ctx: CommandContext,
            env: GitEnv,
            file: String,
        ): String {
            if (file == "-") {
                return ctx.stdin.readAllBytes().decodeToString()
            }
            val path =
                when {
                    file.startsWith("/") -> file
                    env.cwd == "/" -> "/$file"
                    else -> "${env.cwd}/$file"
                }
            return ctx.fs.readBytes(path).decodeToString()
        }

        private suspend fun resolveTagger(
            env: GitEnv,
            ctx: CommandContext,
            repo: GitRepo,
        ): PersonStamp {
            val shellEnv = ctx.env
            val repoCfg = runCatching { readGitConfig(repo) }.getOrDefault(emptyMap())
            val userCfg =
                shellEnv["HOME"]?.let { home ->
                    val path = "${home.trimEnd('/')}/.gitconfig"
                    if (ctx.fs.exists(path)) {
                        parseGitConfigText(ctx.fs.readBytes(path).decodeToString())
                    } else {
                        null
                    }
                } ?: emptyMap()

            fun cfg(key: String): String? = repoCfg["user"]?.get(key) ?: userCfg["user"]?.get(key)

            // Tagger uses the committer fallback chain (matches real git).
            val name =
                shellEnv["GIT_COMMITTER_NAME"]
                    ?: cfg("name")
                    ?: env.adapter?.identity?.name
                    ?: "kash"
            val email =
                shellEnv["GIT_COMMITTER_EMAIL"]
                    ?: cfg("email")
                    ?: env.adapter?.identity?.email
                    ?: "kash@localhost"
            val clock = ctx.process.machine.clock
            val committerDate = shellEnv["GIT_COMMITTER_DATE"]?.split(' ')
            val whenSec = committerDate?.firstOrNull()?.toLongOrNull() ?: clock.now().epochSeconds
            val tz = committerDate?.getOrNull(1)?.takeIf { it.length == 5 } ?: clock.localTz()
            return PersonStamp(name, email, whenSec, tz)
        }
    }

/** Left-justify the tag name in a 15-char field, then a single space. */
private fun padName(name: String): String =
    if (name.length < 15) name + " ".repeat(15 - name.length) + " " else "$name "

/**
 * git's `cleanup=strip` for tag messages:
 *  - strip trailing whitespace on each line
 *  - drop lines whose first character is `#` (comments)
 *  - drop leading and trailing blank lines
 *  - collapse runs of internal blank lines to a single blank line
 *  - terminate with exactly one trailing newline (empty stays empty)
 */
internal fun cleanupMessage(raw: String): String {
    val out = StringBuilder()
    var pendingBlank = false
    var emittedAny = false
    for (rawLine in raw.split('\n')) {
        if (rawLine.startsWith("#")) continue
        val line = rawLine.trimEnd(' ', '\t', '\r')
        if (line.isEmpty()) {
            if (emittedAny) pendingBlank = true
            continue
        }
        if (pendingBlank) {
            out.append('\n')
            pendingBlank = false
        }
        out.append(line).append('\n')
        emittedAny = true
    }
    return out.toString()
}

/**
 * Shell-glob match for `git tag -l <pattern>`. Supports `*`, `?`, and
 * `[...]` character classes; matches the whole name. A wildcard-free
 * pattern matches exactly — git additionally treats it as a `<pattern>/`
 * hierarchy prefix, but tags rarely nest so we keep it to exact + glob.
 */
private fun tagGlobMatch(
    pattern: String,
    name: String,
): Boolean {
    if (pattern.none { it == '*' || it == '?' || it == '[' }) return pattern == name
    val sb = StringBuilder("^")
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when (c) {
            '*' -> {
                sb.append(".*")
            }

            '?' -> {
                sb.append('.')
            }

            '[' -> {
                val close = pattern.indexOf(']', i + 1)
                if (close < 0) {
                    sb.append("\\[")
                } else {
                    sb.append(pattern.substring(i, close + 1))
                    i = close
                }
            }

            '.', '(', ')', '+', '{', '}', '^', '$', '\\', '|' -> {
                sb.append('\\').append(c)
            }

            else -> {
                sb.append(c)
            }
        }
        i++
    }
    sb.append('$')
    return Regex(sb.toString()).matches(name)
}
