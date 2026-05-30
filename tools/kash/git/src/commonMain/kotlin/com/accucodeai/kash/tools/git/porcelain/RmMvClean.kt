package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.blobSha

/**
 * `git rm` — remove paths from the index and (unless `--cached`) the
 * work tree, with real git's safety semantics.
 *
 * Without `-f`, git refuses to remove a path whose content has diverged
 * — the exact refusal depends on how HEAD, the index, and the work tree
 * differ:
 *  - index differs from HEAD *and* the work tree differs from the index
 *    → "staged content different from both the file and the HEAD"
 *  - index differs from HEAD (but matches the work tree)
 *    → "changes staged in the index"
 *  - work tree differs from the index (index matches HEAD)
 *    → "local modifications"  (only checked when not `--cached`)
 *
 * The check is atomic: if any pathspec'd file is "dirty", nothing is
 * removed. Flags: `--cached`, `-r`, `-f`/`--force`, `-n`/`--dry-run`,
 * `-q`/`--quiet`, `--ignore-unmatch`.
 */
public fun gitRmSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "rm"

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
            var cached = false
            var recursive = false
            var force = false
            var dryRun = false
            var quiet = false
            var ignoreUnmatch = false
            val pathArgs = mutableListOf<String>()
            var endOpts = false
            for (a in args) {
                if (endOpts) {
                    pathArgs += a
                    continue
                }
                when (a) {
                    "--cached" -> {
                        cached = true
                    }

                    "-r" -> {
                        recursive = true
                    }

                    "-f", "--force" -> {
                        force = true
                    }

                    "-n", "--dry-run" -> {
                        dryRun = true
                    }

                    "-q", "--quiet" -> {
                        quiet = true
                    }

                    "--ignore-unmatch" -> {
                        ignoreUnmatch = true
                    }

                    "--" -> {
                        endOpts = true
                    }

                    else -> {
                        if (a.length > 1 && a[0] == '-' && a[1] != '-') {
                            var ok = true
                            for (i in 1 until a.length) {
                                when (a[i]) {
                                    'r' -> recursive = true
                                    'f' -> force = true
                                    'n' -> dryRun = true
                                    'q' -> quiet = true
                                    else -> ok = false
                                }
                            }
                            if (!ok) {
                                ctx.stderr.writeUtf8("git rm: unsupported option '$a'\n")
                                return CommandResult(exitCode = 129)
                            }
                        } else if (a.startsWith("-") && a != "-") {
                            ctx.stderr.writeUtf8("git rm: unsupported option '$a'\n")
                            return CommandResult(exitCode = 129)
                        } else {
                            pathArgs += a
                        }
                    }
                }
            }
            if (pathArgs.isEmpty()) {
                ctx.stderr.writeUtf8("fatal: No pathspec was given. Which files should I remove?\n")
                return CommandResult(exitCode = 128)
            }
            val index = repo.readIndex()
            val byPath = index.entries.associateBy { it.path }.toMutableMap()
            val headTree = readHeadTree(repo)

            // Resolve each pathspec to matching index entries.
            val toRemove = linkedSetOf<String>()
            for (p in pathArgs) {
                val rel = relativizeRm(repo.layout.workTree, env.cwd, p) ?: p
                val matched =
                    byPath.keys.filter {
                        it == rel || rel.isEmpty() || it.startsWith("$rel/")
                    }
                if (matched.isEmpty()) {
                    if (ignoreUnmatch) continue
                    ctx.stderr.writeUtf8("fatal: pathspec '$p' did not match any files\n")
                    return CommandResult(exitCode = 128)
                }
                // Require -r to recurse into a tracked directory.
                val onlyDirMatch = matched.none { it == rel } && matched.any { it.startsWith("$rel/") }
                if (onlyDirMatch && !recursive && rel.isNotEmpty()) {
                    ctx.stderr.writeUtf8("fatal: not removing '$p' recursively without -r\n")
                    return CommandResult(exitCode = 128)
                }
                toRemove += matched
            }

            // Safety scan (skipped under -f). Atomic: collect all dirty files
            // first, then refuse if any.
            if (!force) {
                val localMods = mutableListOf<String>()
                val staged = mutableListOf<String>()
                val both = mutableListOf<String>()
                for (r in toRemove.sorted()) {
                    val idx = byPath[r] ?: continue
                    val headSha = headTree[r]
                    val abs = absOfRm(repo.layout.workTree, r)
                    val wtSha =
                        if (ctx.fs.exists(abs) && !ctx.fs.isDirectory(abs)) {
                            blobSha(ctx.fs.readBytes(abs))
                        } else {
                            null
                        }
                    val indexDiffersHead = headSha == null || headSha != idx.sha
                    val wtDiffersIndex = wtSha != null && wtSha != idx.sha
                    when {
                        indexDiffersHead && wtDiffersIndex -> both += r
                        indexDiffersHead -> staged += r
                        !cached && wtDiffersIndex -> localMods += r
                    }
                }
                if (both.isNotEmpty() || staged.isNotEmpty() || localMods.isNotEmpty()) {
                    emitRmRefusal(ctx, both, staged, localMods)
                    return CommandResult(exitCode = 1)
                }
            }

            for (r in toRemove.sorted()) {
                if (!dryRun) {
                    byPath.remove(r)
                    if (!cached) {
                        val abs = absOfRm(repo.layout.workTree, r)
                        if (ctx.fs.exists(abs)) ctx.fs.remove(abs)
                    }
                }
                if (!quiet) ctx.stdout.writeUtf8("rm '$r'\n")
            }
            if (!dryRun) {
                repo.writeIndex(IndexFile(version = 2, entries = byPath.values.toList()))
            }
            return CommandResult(exitCode = 0)
        }
    }

private suspend fun emitRmRefusal(
    ctx: CommandContext,
    both: List<String>,
    staged: List<String>,
    localMods: List<String>,
) {
    // Refusals are grouped by reason. Each non-empty group prints its header,
    // then its files indented by four spaces, then a trailing hint. The
    // wording here is kash's own (not git's verbatim diagnostic prose); only
    // the structure — `error:` header, indented paths, exit code — and which
    // files land in which group track git's observable behavior.
    suspend fun group(
        files: List<String>,
        singular: String,
        plural: String,
        hint: String,
    ) {
        if (files.isEmpty()) return
        ctx.stderr.writeUtf8("error: ${if (files.size == 1) singular else plural}\n")
        for (f in files) ctx.stderr.writeUtf8("    $f\n")
        ctx.stderr.writeUtf8("$hint\n")
    }
    group(
        both,
        "refusing to remove a file whose staged content differs from both the work tree and HEAD:",
        "refusing to remove files whose staged content differs from both the work tree and HEAD:",
        "(pass -f to remove it anyway)",
    )
    group(
        staged,
        "refusing to remove a file with changes already staged:",
        "refusing to remove files with changes already staged:",
        "(pass --cached to drop only the index entry, or -f to remove it anyway)",
    )
    group(
        localMods,
        "refusing to remove a file with uncommitted work-tree edits:",
        "refusing to remove files with uncommitted work-tree edits:",
        "(pass --cached to drop only the index entry, or -f to remove it anyway)",
    )
}

/**
 * `git mv` — rename/move in the index and on disk.
 *
 *  - multiple sources into a destination directory
 *  - `-f`/`--force` overwrite an existing destination
 *  - `-k` skip move/rename errors (best effort)
 *  - `-n`/`--dry-run`, `-v`/`--verbose`
 *
 * Errors mirror git: `fatal: bad source`, `fatal: not under version
 * control`, `fatal: destination '<x>' already exists`.
 */
public fun gitMvSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "mv"

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
            var force = false
            var dryRun = false
            var verbose = false
            var skipErrors = false
            val positional = mutableListOf<String>()
            var endOpts = false
            for (a in args) {
                if (endOpts) {
                    positional += a
                    continue
                }
                when (a) {
                    "-f", "--force" -> {
                        force = true
                    }

                    "-n", "--dry-run" -> {
                        dryRun = true
                    }

                    "-v", "--verbose" -> {
                        verbose = true
                    }

                    "-k" -> {
                        skipErrors = true
                    }

                    "--" -> {
                        endOpts = true
                    }

                    else -> {
                        if (a.length > 1 && a[0] == '-' && a[1] != '-') {
                            var ok = true
                            for (i in 1 until a.length) {
                                when (a[i]) {
                                    'f' -> force = true
                                    'n' -> dryRun = true
                                    'v' -> verbose = true
                                    'k' -> skipErrors = true
                                    else -> ok = false
                                }
                            }
                            if (!ok) {
                                ctx.stderr.writeUtf8("git mv: unsupported option '$a'\n")
                                return CommandResult(exitCode = 129)
                            }
                        } else if (a.startsWith("-") && a != "-") {
                            ctx.stderr.writeUtf8("git mv: unsupported option '$a'\n")
                            return CommandResult(exitCode = 129)
                        } else {
                            positional += a
                        }
                    }
                }
            }
            if (positional.size < 2) {
                ctx.stderr.writeUtf8("usage: git mv [<options>] <source>... <destination>\n")
                return CommandResult(exitCode = 129)
            }

            val index = repo.readIndex()
            val byPath = index.entries.associateBy { it.path }.toMutableMap()

            val wt = repo.layout.workTree
            val sources = positional.dropLast(1)
            val destArg = positional.last()
            val destRel = relativizeRm(wt, env.cwd, destArg) ?: destArg
            val destAbs = absOfRm(wt, destRel)
            val destIsDir = ctx.fs.exists(destAbs) && ctx.fs.isDirectory(destAbs)

            // With multiple sources the destination must be a directory.
            if (sources.size > 1 && !destIsDir) {
                ctx.stderr.writeUtf8("fatal: destination '$destArg' is not a directory\n")
                return CommandResult(exitCode = 128)
            }

            // Plan each move; bail before mutating anything (atomic, like git).
            data class Move(
                val srcRel: String,
                val dstRel: String,
            )

            val moves = mutableListOf<Move>()
            for (src in sources) {
                val srcRel = relativizeRm(wt, env.cwd, src) ?: src
                val srcAbs = absOfRm(wt, srcRel)
                val tracked = srcRel in byPath
                if (!ctx.fs.exists(srcAbs)) {
                    if (skipErrors) continue
                    ctx.stderr.writeUtf8("fatal: bad source, source=$srcRel, destination=$destRel\n")
                    return CommandResult(exitCode = 128)
                }
                if (!tracked) {
                    if (skipErrors) continue
                    ctx.stderr.writeUtf8("fatal: not under version control, source=$srcRel, destination=$destRel\n")
                    return CommandResult(exitCode = 128)
                }
                val dstRel =
                    if (destIsDir) {
                        val base = srcRel.substringAfterLast('/')
                        if (destRel.isEmpty()) base else "$destRel/$base"
                    } else {
                        destRel
                    }
                val dstAbs = absOfRm(wt, dstRel)
                if (ctx.fs.exists(dstAbs) && !force && dstRel != srcRel) {
                    if (skipErrors) continue
                    ctx.stderr.writeUtf8("fatal: destination exists, source=$srcRel, destination=$dstRel\n")
                    return CommandResult(exitCode = 128)
                }
                moves += Move(srcRel, dstRel)
            }

            for (m in moves) {
                if (dryRun) {
                    ctx.stdout.writeUtf8("Checking rename of '${m.srcRel}' to '${m.dstRel}'\n")
                }
                if (verbose || dryRun) {
                    ctx.stdout.writeUtf8("Renaming ${m.srcRel} to ${m.dstRel}\n")
                }
                if (dryRun) continue
                val entry = byPath.remove(m.srcRel) ?: continue
                byPath.remove(m.dstRel)
                byPath[m.dstRel] = entry.copy(path = m.dstRel)
                val srcAbs = absOfRm(wt, m.srcRel)
                val dstAbs = absOfRm(wt, m.dstRel)
                if (ctx.fs.exists(srcAbs)) {
                    val bytes = ctx.fs.readBytes(srcAbs)
                    val parent = dstAbs.substringBeforeLast('/')
                    if (parent.isNotEmpty() && parent != dstAbs) ctx.fs.mkdirs(parent)
                    ctx.fs.writeBytes(dstAbs, bytes)
                    if (dstAbs != srcAbs) ctx.fs.remove(srcAbs)
                }
            }
            if (!dryRun) {
                repo.writeIndex(IndexFile(version = 2, entries = byPath.values.toList()))
            }
            return CommandResult(exitCode = 0)
        }
    }

/**
 * `git clean` — remove untracked files from the work tree.
 *
 *  - `-n`/`--dry-run`: report ("Would remove ...") without removing.
 *  - `-f`/`--force`: required for actual removal.
 *  - `-d`: also recurse into / remove untracked directories.
 *  - `-x`: do not honor the standard ignore rules (still honors `-e`).
 *  - `-X`: remove ONLY ignored files.
 *  - `-e <pattern>`: add an extra exclude (ignore) pattern.
 */
public fun gitCleanSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "clean"

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
            var force = false
            var dryRun = false
            var alsoDirs = false
            var ignoreStd = false // -x
            var onlyIgnored = false // -X
            val extraExcludes = mutableListOf<String>()
            var i = 0
            var endOpts = false
            while (i < args.size) {
                val a = args[i]
                if (endOpts || !a.startsWith("-") || a == "-") {
                    // Clean ignores positional pathspecs in our subset.
                    i++
                    continue
                }
                when (a) {
                    "--force" -> {
                        force = true
                    }

                    "--dry-run" -> {
                        dryRun = true
                    }

                    "-e", "--exclude" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: option `exclude' requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        extraExcludes += args[++i]
                    }

                    "--" -> {
                        endOpts = true
                    }

                    else -> {
                        if (a.startsWith("--exclude=")) {
                            extraExcludes += a.removePrefix("--exclude=")
                        } else if (a[1] != '-') {
                            // Possibly bundled short flags, with -e taking the rest as value.
                            var ok = true
                            var j = 1
                            while (j < a.length) {
                                when (a[j]) {
                                    'f' -> {
                                        force = true
                                    }

                                    'n' -> {
                                        dryRun = true
                                    }

                                    'd' -> {
                                        alsoDirs = true
                                    }

                                    'x' -> {
                                        ignoreStd = true
                                    }

                                    'X' -> {
                                        onlyIgnored = true
                                    }

                                    'e' -> {
                                        val rest = a.substring(j + 1)
                                        if (rest.isNotEmpty()) {
                                            extraExcludes += rest
                                        } else if (i + 1 < args.size) {
                                            extraExcludes += args[++i]
                                        } else {
                                            ctx.stderr.writeUtf8("error: option `exclude' requires a value\n")
                                            return CommandResult(exitCode = 129)
                                        }
                                        j = a.length
                                        continue
                                    }

                                    else -> {
                                        ok = false
                                    }
                                }
                                j++
                            }
                            if (!ok) {
                                ctx.stderr.writeUtf8("git clean: unsupported option '$a'\n")
                                return CommandResult(exitCode = 129)
                            }
                        } else {
                            ctx.stderr.writeUtf8("git clean: unsupported option '$a'\n")
                            return CommandResult(exitCode = 129)
                        }
                    }
                }
                i++
            }

            if (!force && !dryRun) {
                ctx.stderr.writeUtf8(
                    "fatal: clean.requireForce is true and -f not given: refusing to clean\n",
                )
                return CommandResult(exitCode = 128)
            }

            val tracked =
                repo
                    .readIndex()
                    .entries
                    .map { it.path }
                    .toSet()
            val scan = scanWorkTree(repo, tracked)

            // scan.files = untracked, non-ignored files.
            // scan.ignoredFiles = ignored files (and top-level ignored dirs as "dir/").
            val extraMatcher = buildExtraExcludeMatcher(extraExcludes)

            // A plain mutable set; we sort at emission below to match git's
            // alphabetical removal order (sortedSetOf is JVM-only / not in the
            // multiplatform stdlib).
            val toRemove = mutableSetOf<String>()
            // Files git would consider for removal. scanWorkTree keeps tracked
            // files visible (ignore rules don't apply to them), so filter
            // those out — clean only ever touches untracked paths.
            for (f in scan.files) {
                if (f in tracked) continue
                val stdIgnored = false // scan.files already excludes std-ignored
                val eIgnored = extraMatcher(f, false)
                val remove =
                    when {
                        onlyIgnored -> stdIgnored || eIgnored
                        ignoreStd -> !eIgnored
                        else -> !stdIgnored && !eIgnored
                    }
                if (remove) toRemove += f
            }
            // Ignored files (only relevant under -x or -X).
            if (ignoreStd || onlyIgnored) {
                for (ig in scan.ignoredFiles) {
                    val isDir = ig.endsWith("/")
                    val rel = ig.trimEnd('/')
                    // -X removes ignored unless an -e pattern excludes it; but
                    // -e patterns *add* to the ignore set, so they don't rescue.
                    val eIgnored = extraMatcher(rel, isDir)
                    val remove =
                        when {
                            onlyIgnored -> true

                            // already ignored by standard rules
                            ignoreStd -> !eIgnored

                            else -> false
                        }
                    if (remove) {
                        if (isDir) {
                            if (alsoDirs) toRemove += "$rel/"
                        } else {
                            toRemove += rel
                        }
                    }
                }
            }

            // Untracked directories (only with -d). Under default/-x mode a
            // wholly-untracked dir is removed as a unit; scan.files already
            // surfaced its non-ignored contents individually, so we add the
            // directory entries discovered by a dedicated walk.
            if (alsoDirs) {
                collectUntrackedDirs(
                    ctx.fs,
                    repo.layout.workTree,
                    "",
                    tracked,
                    scan,
                    ignoreStd,
                    onlyIgnored,
                    extraMatcher,
                    toRemove,
                )
            }

            for (p in toRemove.sorted()) {
                ctx.stdout.writeUtf8("${if (dryRun) "Would remove" else "Removing"} $p\n")
                if (!dryRun) ctx.fs.remove(absOfRm(repo.layout.workTree, p.trimEnd('/')))
            }
            return CommandResult(exitCode = 0)
        }
    }

/**
 * Identify untracked directories for `-d`. A directory is removable as a
 * unit when it contains no tracked files. We emit it as `dir/` and prune
 * its individually-listed file children from [toRemove] so the directory
 * subsumes them.
 */
private suspend fun collectUntrackedDirs(
    fs: FileSystem,
    workTree: String,
    rel: String,
    tracked: Set<String>,
    scan: WorkTreeScan,
    ignoreStd: Boolean,
    onlyIgnored: Boolean,
    extraMatcher: (String, Boolean) -> Boolean,
    toRemove: MutableSet<String>,
) {
    val abs = if (rel.isEmpty()) workTree else absOfRm(workTree, rel)
    if (!fs.isDirectory(abs)) return
    for (name in fs.list(abs).sorted()) {
        if (name == ".git") continue
        val sub = if (rel.isEmpty()) name else "$rel/$name"
        val subAbs = absOfRm(workTree, sub)
        if (!fs.isDirectory(subAbs)) continue
        if (fs.exists("$subAbs/.git")) continue // nested repo: leave alone
        val hasTracked = tracked.any { it == sub || it.startsWith("$sub/") }
        if (!hasTracked) {
            // Wholly untracked directory.
            val stdIgnored = scan.ignoredFiles.contains("$sub/")
            val eIgnored = extraMatcher(sub, true)
            val removeDir =
                when {
                    onlyIgnored -> stdIgnored || eIgnored
                    ignoreStd -> !eIgnored
                    else -> !stdIgnored && !eIgnored
                }
            if (removeDir) {
                // Remove any file children already queued; the dir subsumes them.
                toRemove.removeAll { it == sub || it.startsWith("$sub/") }
                toRemove += "$sub/"
            } else if (onlyIgnored && !stdIgnored) {
                // Dir not ignored, but may contain ignored files worth removing
                // individually — recurse (already handled via scan.ignoredFiles).
                collectUntrackedDirs(fs, workTree, sub, tracked, scan, ignoreStd, onlyIgnored, extraMatcher, toRemove)
            }
        } else {
            collectUntrackedDirs(fs, workTree, sub, tracked, scan, ignoreStd, onlyIgnored, extraMatcher, toRemove)
        }
    }
}

/** Build a matcher for `-e` patterns using the gitignore grammar. */
private fun buildExtraExcludeMatcher(patterns: List<String>): (String, Boolean) -> Boolean {
    if (patterns.isEmpty()) return { _, _ -> false }
    val matcher = GitignoreMatcher()
    matcher.push("", patterns.mapNotNull { GitignorePattern.parse(it) })
    return { relPath, isDir -> matcher.isIgnored(relPath, isDir) }
}

private fun absOfRm(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"

private fun relativizeRm(
    workTree: String,
    cwd: String,
    p: String,
): String? {
    val raw =
        if (p.startsWith("/")) {
            p
        } else if (cwd == "/") {
            "/$p"
        } else {
            "$cwd/$p"
        }
    val abs = normalizeAbs(raw)
    val base = if (workTree == "/") "/" else "$workTree/"
    return when {
        workTree == "/" && abs.startsWith("/") -> abs.removePrefix("/")
        abs == workTree -> ""
        abs.startsWith(base) -> abs.removePrefix(base)
        else -> null
    }
}

/** HEAD tree as a flat path → blob-sha map, or empty if no commit yet. */
private suspend fun readHeadTree(repo: GitRepo): Map<String, String> {
    val headSha = repo.refs.resolveHead() ?: return emptyMap()
    val commit = repo.objects.readCommit(headSha)
    return flattenTreeRm(repo, commit.tree, "")
}

private suspend fun flattenTreeRm(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for (entry in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
        when (entry.mode) {
            FileMode.REGULAR, FileMode.EXECUTABLE, FileMode.SYMLINK, FileMode.GITLINK -> out[p] = entry.sha
            FileMode.TREE -> out.putAll(flattenTreeRm(repo, entry.sha, p))
        }
    }
    return out
}
