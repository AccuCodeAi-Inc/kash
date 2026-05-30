package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.decodeTag
import com.accucodeai.kash.tools.git.plumbing.parseFramedObject
import kotlin.coroutines.cancellation.CancellationException

// Ref-inspection plumbing: `for-each-ref`, `show-ref`, `symbolic-ref`,
// and `update-ref`. These are clean-room reimplementations matching the
// observable behavior of real git's equivalents (output byte-format,
// exit codes, error text) without referencing git's GPL source.

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private const val DEFAULT_ABBREV = 7

/** A ref the inspection commands operate over: full name + the sha it points at. */
private data class RefRow(
    val name: String,
    val sha: String,
)

/**
 * Enumerate every ref under `refs/` (loose + packed), sorted by refname.
 * Symbolic refs (e.g. a symref under refs/heads) are skipped — they
 * resolve through [RefStore.listRefs] only when they carry a 40-hex sha.
 */
private suspend fun listAllRefs(repo: GitRepo): List<RefRow> =
    repo.refs
        .listRefs("refs")
        .map { RefRow(it.first, it.second) }
        .sortedBy { it.name }

/**
 * The object type stored at [sha] (commit/tree/blob/tag), or null if the
 * object can't be read.
 */
private suspend fun objectTypeOf(
    repo: GitRepo,
    sha: String,
): ObjectType? =
    try {
        parseFramedObject(repo.objects.read(sha)).type
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }

/**
 * Peel an annotated tag through its `object` chain to the underlying
 * non-tag object's sha. For anything that isn't a tag, returns null
 * (there is nothing to peel).
 */
private suspend fun peeledTarget(
    repo: GitRepo,
    sha: String,
): String? {
    var cur = sha
    var peeledAny = false
    repeat(8) {
        val parsed =
            try {
                parseFramedObject(repo.objects.read(cur))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                return if (peeledAny) cur else null
            }
        if (parsed.type != ObjectType.TAG) return if (peeledAny) cur else null
        cur = decodeTag(parsed.payload).targetSha
        peeledAny = true
    }
    return if (peeledAny) cur else null
}

// ---------------------------------------------------------------------------
// for-each-ref
// ---------------------------------------------------------------------------

/**
 * `git for-each-ref [--count=<n>] [--sort=<key>] [--format=<fmt>]
 * [--points-at=<obj>] [<pattern>...]`
 *
 * Iterates refs under `refs/`. Default format is
 * `<objectname> <objecttype>\t<refname>`. Supported `%(...)`
 * placeholders: `refname`, `refname:short`, `objectname`,
 * `objectname:short`, `objecttype`, `HEAD`.
 *
 * Patterns match a ref when the pattern equals the ref or is a
 * `/`-delimited path prefix of it (`refs/heads` matches
 * `refs/heads/main`). Sorting defaults to ascending refname; `-` prefix
 * reverses. `creatordate`/`committerdate` sort by the target commit's
 * committer time.
 */
public fun gitForEachRefSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "for-each-ref"

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

            var count = -1
            var sortKey: String? = null
            var format = "%(objectname) %(objecttype)\t%(refname)"
            var pointsAt: String? = null
            val patterns = mutableListOf<String>()

            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a.startsWith("--count=") -> {
                        count = a.substringAfter('=').toIntOrNull() ?: -1
                        i++
                    }

                    a == "--count" -> {
                        count = args.getOrNull(i + 1)?.toIntOrNull() ?: -1
                        i += 2
                    }

                    a.startsWith("--sort=") -> {
                        sortKey = a.substringAfter('=')
                        i++
                    }

                    a == "--sort" -> {
                        sortKey = args.getOrNull(i + 1)
                        i += 2
                    }

                    a.startsWith("--format=") -> {
                        format = a.substringAfter('=')
                        i++
                    }

                    a == "--format" -> {
                        format = args.getOrNull(i + 1) ?: format
                        i += 2
                    }

                    a.startsWith("--points-at=") -> {
                        pointsAt = a.substringAfter('=')
                        i++
                    }

                    a == "--points-at" -> {
                        pointsAt = args.getOrNull(i + 1)
                        i += 2
                    }

                    a == "--" -> {
                        i++
                        while (i < args.size) {
                            patterns += args[i]
                            i++
                        }
                    }

                    a.startsWith("-") && a != "-" -> {
                        ctx.stderr.writeUtf8("error: unknown option `${a.removePrefix("--")}'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        patterns += a
                        i++
                    }
                }
            }

            var rows = listAllRefs(repo)

            if (patterns.isNotEmpty()) {
                rows = rows.filter { row -> patterns.any { matchRefPattern(it, row.name) } }
            }

            if (pointsAt != null) {
                val target = resolveRev(repo, pointsAt) ?: pointsAt
                rows =
                    rows.filter { row ->
                        row.sha == target || peeledTarget(repo, row.sha) == target
                    }
            }

            // Resolve sort: strip leading '-' for descending.
            if (sortKey != null) {
                val desc = sortKey.startsWith("-")
                val key = sortKey.removePrefix("-")
                rows =
                    when (key) {
                        "refname" -> rows.sortedBy { it.name }
                        "creatordate", "committerdate", "authordate" -> sortByDate(repo, rows)
                        "objectname" -> rows.sortedBy { it.sha }
                        else -> rows.sortedBy { it.name }
                    }
                if (desc) rows = rows.reversed()
            }

            if (count >= 0) rows = rows.take(count)

            val headBranch = currentHeadBranch(repo)

            for (row in rows) {
                ctx.stdout.writeUtf8(renderFormat(repo, format, row, headBranch))
                ctx.stdout.writeUtf8("\n")
            }
            return CommandResult(exitCode = 0)
        }
    }

/** Sort rows by their target commit's committer time (ascending). */
private suspend fun sortByDate(
    repo: GitRepo,
    rows: List<RefRow>,
): List<RefRow> {
    val keyed =
        rows.map { row ->
            val commitSha = peeledTarget(repo, row.sha) ?: row.sha
            val ts =
                try {
                    val commit = repo.objects.readCommit(commitSha)
                    commit.committer.whenSeconds
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    Long.MIN_VALUE
                }
            row to ts
        }
    return keyed.sortedWith(compareBy({ it.second }, { it.first.name })).map { it.first }
}

/** The short branch name HEAD currently points at, or null when detached. */
private suspend fun currentHeadBranch(repo: GitRepo): String? =
    when (val h = repo.refs.readHead()) {
        is RefStore.Head.Symbolic -> h.target
        else -> null
    }

/**
 * Pattern matches a ref name when the pattern equals it or names a
 * leading path-component prefix (`refs/heads` matches `refs/heads/main`
 * but `refs/heads/ma` matches nothing). A trailing slash on the pattern
 * is ignored.
 */
private fun matchRefPattern(
    pattern: String,
    ref: String,
): Boolean {
    val p = pattern.trimEnd('/')
    if (p == ref) return true
    return ref.startsWith("$p/")
}

private suspend fun renderFormat(
    repo: GitRepo,
    format: String,
    row: RefRow,
    headBranch: String?,
): String {
    val sb = StringBuilder()
    var i = 0
    while (i < format.length) {
        val c = format[i]
        if (c == '%' && i + 1 < format.length) {
            when (format[i + 1]) {
                '(' -> {
                    val end = format.indexOf(')', i + 2)
                    if (end < 0) {
                        sb.append(c)
                        i++
                        continue
                    }
                    val token = format.substring(i + 2, end)
                    sb.append(expandToken(repo, token, row, headBranch))
                    i = end + 1
                    continue
                }

                '%' -> {
                    sb.append('%')
                    i += 2
                    continue
                }

                else -> {
                    sb.append(c)
                    i++
                    continue
                }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

private suspend fun expandToken(
    repo: GitRepo,
    token: String,
    row: RefRow,
    headBranch: String?,
): String =
    when (token) {
        "refname" -> row.name
        "refname:short" -> shortRefName(row.name)
        "objectname" -> row.sha
        "objectname:short" -> row.sha.take(DEFAULT_ABBREV)
        "objecttype" -> objectTypeOf(repo, row.sha)?.token ?: ""
        "HEAD" -> if (row.name == headBranch) "*" else " "
        else -> ""
    }

/**
 * Strip the conventional prefix from a full ref for `:short` display:
 * `refs/heads/main` → `main`, `refs/tags/v1` → `v1`,
 * `refs/remotes/origin/main` → `origin/main`. Other refs lose only the
 * `refs/` prefix.
 */
private fun shortRefName(ref: String): String =
    when {
        ref.startsWith("refs/heads/") -> ref.removePrefix("refs/heads/")
        ref.startsWith("refs/tags/") -> ref.removePrefix("refs/tags/")
        ref.startsWith("refs/remotes/") -> ref.removePrefix("refs/remotes/")
        ref.startsWith("refs/") -> ref.removePrefix("refs/")
        else -> ref
    }

// ---------------------------------------------------------------------------
// show-ref
// ---------------------------------------------------------------------------

/**
 * `git show-ref [--head] [--heads] [--tags] [-d|--dereference]
 * [-s|--hash[=<n>]] [--verify] [<pattern>...]`
 *
 * Prints `<sha> <refname>` lines. `-d` appends a `<sha> <ref>^{}`
 * dereference line for annotated tags. `--verify` requires exact full
 * refnames (e.g. `refs/heads/main`) and fails with exit 128 on a
 * missing one. With no `--verify`, exits 1 when nothing matched.
 */
public fun gitShowRefSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "show-ref"

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

            var head = false
            var heads = false
            var tags = false
            var deref = false
            var hashOnly = false
            var hashAbbrev = 40
            var verify = false
            val patterns = mutableListOf<String>()

            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--head" -> {
                        head = true
                        i++
                    }

                    a == "--heads" -> {
                        heads = true
                        i++
                    }

                    a == "--tags" -> {
                        tags = true
                        i++
                    }

                    a == "-d" || a == "--dereference" -> {
                        deref = true
                        i++
                    }

                    a == "-s" || a == "--hash" -> {
                        hashOnly = true
                        i++
                    }

                    a.startsWith("--hash=") -> {
                        hashOnly = true
                        hashAbbrev = a.substringAfter('=').toIntOrNull()?.coerceIn(1, 40) ?: 40
                        i++
                    }

                    a.startsWith("-s") && a.length > 2 -> {
                        hashOnly = true
                        hashAbbrev = a.substring(2).toIntOrNull()?.coerceIn(1, 40) ?: 40
                        i++
                    }

                    a == "--verify" -> {
                        verify = true
                        i++
                    }

                    a == "--" -> {
                        i++
                        while (i < args.size) {
                            patterns += args[i]
                            i++
                        }
                    }

                    a.startsWith("-") && a != "-" -> {
                        ctx.stderr.writeUtf8("error: unknown option `${a.removePrefix("--")}'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        patterns += a
                        i++
                    }
                }
            }

            fun emit(
                sha: String,
                name: String,
            ): String =
                if (hashOnly) {
                    "${sha.take(hashAbbrev)}\n"
                } else {
                    "${sha.take(hashAbbrev)} $name\n"
                }

            // --verify mode: each pattern must be an exact, existing full ref.
            if (verify) {
                var ok = true
                for (p in patterns) {
                    val sha =
                        if (p == "HEAD") {
                            repo.refs.resolveHead()
                        } else {
                            repo.refs.resolve(p)
                        }
                    if (sha == null) {
                        if (!hashOnly) {
                            ctx.stderr.writeUtf8("fatal: '$p' - not a valid ref\n")
                        }
                        ok = false
                        break
                    }
                    ctx.stdout.writeUtf8(emit(sha, p))
                    if (deref) {
                        peeledTarget(repo, sha)?.let { peeled ->
                            ctx.stdout.writeUtf8(emit(peeled, "$p^{}"))
                        }
                    }
                }
                return CommandResult(exitCode = if (ok) 0 else 128)
            }

            val rows = mutableListOf<RefRow>()

            if (head) {
                repo.refs.resolveHead()?.let { rows += RefRow("HEAD", it) }
            }

            val all = listAllRefs(repo)
            val selected =
                if (heads || tags) {
                    all.filter {
                        (heads && it.name.startsWith("refs/heads/")) ||
                            (tags && it.name.startsWith("refs/tags/"))
                    }
                } else {
                    // No --heads/--tags filter (including bare --head) lists everything.
                    all
                }
            rows += selected

            val matched =
                if (patterns.isEmpty()) {
                    rows
                } else {
                    rows.filter { row -> patterns.any { matchShowRefPattern(it, row.name) } }
                }

            var any = false
            for (row in matched) {
                any = true
                ctx.stdout.writeUtf8(emit(row.sha, row.name))
                if (deref && row.name != "HEAD") {
                    peeledTarget(repo, row.sha)?.let { peeled ->
                        ctx.stdout.writeUtf8(emit(peeled, "${row.name}^{}"))
                    }
                }
            }

            return CommandResult(exitCode = if (any) 0 else 1)
        }
    }

/**
 * show-ref pattern: matches when the pattern equals the ref or is a
 * trailing `/`-delimited path suffix (`main` matches `refs/heads/main`,
 * `heads/main` matches too).
 */
private fun matchShowRefPattern(
    pattern: String,
    ref: String,
): Boolean {
    if (pattern == ref) return true
    return ref.endsWith("/$pattern")
}

// ---------------------------------------------------------------------------
// symbolic-ref
// ---------------------------------------------------------------------------

/**
 * `git symbolic-ref [-q] [--short] <name> [<ref>]` and
 * `git symbolic-ref -d [-q] <name>`.
 *
 * Reads (prints the target), sets (writes `ref: <target>`), or deletes
 * a symbolic ref. `--short` strips `refs/heads/` from the printed
 * target. `-q` suppresses the error (and its message) when `<name>` is
 * not a symbolic ref.
 */
public fun gitSymbolicRefSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "symbolic-ref"

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

            var quiet = false
            var short = false
            var delete = false
            val positional = mutableListOf<String>()

            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "-q" || a == "--quiet" -> {
                        quiet = true
                        i++
                    }

                    a == "--short" -> {
                        short = true
                        i++
                    }

                    a == "-d" || a == "--delete" -> {
                        delete = true
                        i++
                    }

                    a == "--" -> {
                        i++
                        while (i < args.size) {
                            positional += args[i]
                            i++
                        }
                    }

                    a.startsWith("-") && a != "-" -> {
                        ctx.stderr.writeUtf8("error: unknown option `${a.removePrefix("--")}'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                        i++
                    }
                }
            }

            val name = positional.getOrNull(0)
            if (name == null) {
                ctx.stderr.writeUtf8("usage: git symbolic-ref [-q] [--short] <name> [<ref>]\n")
                return CommandResult(exitCode = 129)
            }

            if (delete) {
                val current = readSymbolic(repo, name)
                if (current == null) {
                    if (!quiet) ctx.stderr.writeUtf8("fatal: Cannot delete $name, not a symbolic ref\n")
                    return CommandResult(exitCode = 128)
                }
                val path = repo.layout.refFile(name)
                if (ctx.fs.exists(path)) ctx.fs.remove(path)
                return CommandResult(exitCode = 0)
            }

            val newTarget = positional.getOrNull(1)
            if (newTarget != null) {
                val fullTarget =
                    if (newTarget.startsWith("refs/")) newTarget else "refs/heads/$newTarget"
                if (name == "HEAD") {
                    repo.refs.writeHeadSymbolic(fullTarget)
                } else {
                    require(name.startsWith("refs/")) {
                        "symbolic ref name must start with 'refs/': '$name'"
                    }
                    val path = repo.layout.refFile(name)
                    ctx.fs.mkdirs(path.substringBeforeLast('/'))
                    ctx.fs.writeBytes(path, "ref: $fullTarget\n".encodeToByteArray())
                }
                return CommandResult(exitCode = 0)
            }

            // Read mode.
            val target = readSymbolic(repo, name)
            if (target == null) {
                if (!quiet) {
                    ctx.stderr.writeUtf8("fatal: ref $name is not a symbolic ref\n")
                }
                return CommandResult(exitCode = 128)
            }
            val display = if (short) shortRefName(target) else target
            ctx.stdout.writeUtf8("$display\n")
            return CommandResult(exitCode = 0)
        }
    }

/**
 * Return the target of symbolic ref [name] (`HEAD` or any `refs/...`
 * symref written as `ref: <target>`), or null when [name] is absent or
 * holds a direct sha.
 */
private suspend fun readSymbolic(
    repo: GitRepo,
    name: String,
): String? {
    if (name == "HEAD") {
        return when (val h = repo.refs.readHead()) {
            is RefStore.Head.Symbolic -> h.target
            else -> null
        }
    }
    if (!name.startsWith("refs/")) return null
    val path = repo.layout.refFile(name)
    if (!repo.fs.exists(path)) return null
    val raw = repo.fs.readBytes(path).decodeToString()
    val s = raw.trimEnd('\n', '\r')
    return if (s.startsWith("ref: ")) s.substring(5) else null
}

// ---------------------------------------------------------------------------
// update-ref
// ---------------------------------------------------------------------------

/**
 * `git update-ref [-d] [-m <msg>] <ref> [<newvalue> [<oldvalue>]]`.
 *
 * Creates/updates a ref (optionally checking `<oldvalue>` matches the
 * current value — an all-zero oldvalue asserts the ref must not yet
 * exist) or deletes it with `-d`. A reflog entry is written on update.
 * An oldvalue mismatch fails with exit 128 and a git-shaped message.
 */
public fun gitUpdateRefSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "update-ref"

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
            var message: String? = null
            val positional = mutableListOf<String>()

            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "-d" || a == "--delete" -> {
                        delete = true
                        i++
                    }

                    a == "-m" -> {
                        message = args.getOrNull(i + 1)
                        i += 2
                    }

                    a == "--no-deref" -> {
                        // We always operate on the named ref directly.
                        i++
                    }

                    a == "--" -> {
                        i++
                        while (i < args.size) {
                            positional += args[i]
                            i++
                        }
                    }

                    a.startsWith("-") && a != "-" -> {
                        ctx.stderr.writeUtf8("error: unknown option `${a.removePrefix("--")}'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                        i++
                    }
                }
            }

            val zero = "0".repeat(40)
            val ref = positional.getOrNull(0)
            if (ref == null) {
                ctx.stderr.writeUtf8("usage: git update-ref [-m <reason>] [-d] <refname> [<new-oid> [<old-oid>]]\n")
                return CommandResult(exitCode = 129)
            }
            if (!ref.startsWith("refs/") && ref != "HEAD") {
                ctx.stderr.writeUtf8("error: refusing to update ref with bad name '$ref'\n")
                return CommandResult(exitCode = 128)
            }

            if (delete) {
                val oldArg = positional.getOrNull(1)
                val current =
                    if (ref == "HEAD") repo.refs.resolveHead() else repo.refs.resolve(ref)
                if (oldArg != null && oldArg != zero) {
                    val expected = resolveRev(repo, oldArg) ?: oldArg
                    if (current != expected) {
                        ctx.stderr.writeUtf8(
                            "fatal: update_ref failed for ref '$ref': " +
                                "cannot lock ref '$ref': is at ${current ?: "(null)"} but expected $expected\n",
                        )
                        return CommandResult(exitCode = 128)
                    }
                }
                val path = repo.layout.refFile(ref)
                if (ctx.fs.exists(path)) ctx.fs.remove(path)
                val logPath = "${repo.layout.logsDir}/$ref"
                if (ctx.fs.exists(logPath)) ctx.fs.remove(logPath)
                return CommandResult(exitCode = 0)
            }

            val newArg = positional.getOrNull(1)
            if (newArg == null) {
                ctx.stderr.writeUtf8("usage: git update-ref [-m <reason>] [-d] <refname> [<new-oid> [<old-oid>]]\n")
                return CommandResult(exitCode = 129)
            }
            val newSha =
                resolveRev(repo, newArg) ?: run {
                    ctx.stderr.writeUtf8("fatal: $newArg: not a valid SHA1\n")
                    return CommandResult(exitCode = 128)
                }

            val current = if (ref == "HEAD") repo.refs.resolveHead() else repo.refs.resolve(ref)

            val oldArg = positional.getOrNull(2)
            if (oldArg != null) {
                val expected = if (oldArg == zero) null else (resolveRev(repo, oldArg) ?: oldArg)
                if (expected == null) {
                    // Asserting the ref must not yet exist.
                    if (current != null) {
                        ctx.stderr.writeUtf8(
                            "fatal: update_ref failed for ref '$ref': " +
                                "cannot lock ref '$ref': reference already exists\n",
                        )
                        return CommandResult(exitCode = 128)
                    }
                } else if (current != expected) {
                    ctx.stderr.writeUtf8(
                        "fatal: update_ref failed for ref '$ref': " +
                            "cannot lock ref '$ref': is at ${current ?: zero} but expected $expected\n",
                    )
                    return CommandResult(exitCode = 128)
                }
            }

            if (ref == "HEAD") {
                repo.refs.writeHeadDetached(newSha)
            } else {
                repo.refs.writeRef(ref, newSha)
            }

            val now = nowReflogTime(ctx)
            recordReflog(
                repo = repo,
                refName = ref,
                oldSha = current,
                newSha = newSha,
                identity = null,
                whenSeconds = now.first,
                tz = now.second,
                message = message ?: "",
            )
            return CommandResult(exitCode = 0)
        }
    }
