package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.IndexFile

/**
 * `git rm` — remove from the index and (unless `--cached`) the work tree.
 *
 * Flags:
 *  - `--cached` — keep the file on disk; only unstage.
 *  - `-r` — recurse into directories.
 *  - `-f` — force (we never refuse in v1; flag is accepted for parity).
 *
 * Paths must already be tracked; `git rm` of an untracked path errors
 * matching real git.
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
            val pathArgs = mutableListOf<String>()
            for (a in args) {
                when {
                    a == "--cached" -> {
                        cached = true
                    }

                    a == "-r" -> {
                        recursive = true
                    }

                    a == "-f" || a == "--force" -> {}

                    a == "--" -> {}

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git rm: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        pathArgs += a
                    }
                }
            }
            if (pathArgs.isEmpty()) {
                ctx.stderr.writeUtf8("git rm: pathspec required\n")
                return CommandResult(exitCode = 129)
            }
            val index = repo.readIndex()
            val byPath = index.entries.associateBy { it.path }.toMutableMap()

            val toRemove = mutableSetOf<String>()
            for (p in pathArgs) {
                val rel = relativizeRm(repo.layout.workTree, env.cwd, p) ?: p
                val matched = byPath.keys.filter { it == rel || (recursive && it.startsWith("$rel/")) }
                if (matched.isEmpty()) {
                    ctx.stderr.writeUtf8("fatal: pathspec '$p' did not match any files\n")
                    return CommandResult(exitCode = 128)
                }
                toRemove += matched
            }
            for (r in toRemove.sorted()) {
                byPath.remove(r)
                if (!cached) {
                    val abs = absOfRm(repo.layout.workTree, r)
                    if (ctx.fs.exists(abs)) ctx.fs.remove(abs)
                }
                ctx.stdout.writeUtf8("rm '$r'\n")
            }
            repo.writeIndex(IndexFile(version = 2, entries = byPath.values.toList()))
            return CommandResult(exitCode = 0)
        }
    }

/**
 * `git mv <from> <to>` — rename in the index and on disk. v1: single
 * source/destination only (the common case). Real git allows multiple
 * sources with a destination directory; deferred.
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
            val positional = args.filterNot { it.startsWith("-") }
            if (positional.size != 2) {
                ctx.stderr.writeUtf8("git mv: expected <from> <to>\n")
                return CommandResult(exitCode = 129)
            }
            val (fromRel0, toRel0) = positional[0] to positional[1]
            val fromRel = relativizeRm(repo.layout.workTree, env.cwd, fromRel0) ?: fromRel0
            val toRel = relativizeRm(repo.layout.workTree, env.cwd, toRel0) ?: toRel0
            val index = repo.readIndex()
            val byPath = index.entries.associateBy { it.path }.toMutableMap()
            val entry = byPath.remove(fromRel)
            if (entry == null) {
                ctx.stderr.writeUtf8("fatal: not under version control: $fromRel0\n")
                return CommandResult(exitCode = 128)
            }
            byPath[toRel] = entry.copy(path = toRel)
            repo.writeIndex(IndexFile(version = 2, entries = byPath.values.toList()))

            val fromAbs = absOfRm(repo.layout.workTree, fromRel)
            val toAbs = absOfRm(repo.layout.workTree, toRel)
            if (ctx.fs.exists(fromAbs)) {
                val bytes = ctx.fs.readBytes(fromAbs)
                val parent = toAbs.substringBeforeLast('/')
                if (parent.isNotEmpty()) ctx.fs.mkdirs(parent)
                ctx.fs.writeBytes(toAbs, bytes)
                ctx.fs.remove(fromAbs)
            }
            return CommandResult(exitCode = 0)
        }
    }

/**
 * `git clean` — remove untracked files from the work tree.
 *
 *  - `-n` / `--dry-run`: report what would be removed without doing it.
 *  - `-f`: required for actual removal (real git refuses without it).
 *  - `-d`: also remove untracked directories.
 *  - `-x`: ignore .gitignore (we don't honor .gitignore in v1 anyway,
 *    so this is effectively a no-op).
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
            for (a in args) {
                when (a) {
                    "-f", "--force" -> {
                        force = true
                    }

                    "-n", "--dry-run" -> {
                        dryRun = true
                    }

                    "-d" -> {
                        alsoDirs = true
                    }

                    "-x" -> {}

                    // accept-and-ignore in v1
                    "-fd", "-df" -> {
                        force = true
                        alsoDirs = true
                    }

                    "-fn", "-nf" -> {
                        force = true
                        dryRun = true
                    }

                    "--" -> {}

                    else -> {
                        if (a.startsWith("-")) {
                            ctx.stderr.writeUtf8("git clean: unsupported option '$a'\n")
                            return CommandResult(exitCode = 129)
                        }
                    }
                }
            }
            if (!force && !dryRun) {
                ctx.stderr.writeUtf8(
                    "fatal: clean.requireForce defaults to true and neither -n nor -f given; refusing to clean\n",
                )
                return CommandResult(exitCode = 1)
            }

            val tracked =
                repo
                    .readIndex()
                    .entries
                    .map { it.path }
                    .toSet()
            val untracked = mutableListOf<String>()
            val untrackedDirs = mutableListOf<String>()
            walkClean(ctx.fs, repo.layout.workTree, "", tracked, untracked, untrackedDirs, alsoDirs)

            for (p in untracked.sorted()) {
                ctx.stdout.writeUtf8("${if (dryRun) "Would remove" else "Removing"} $p\n")
                if (!dryRun) ctx.fs.remove(absOfRm(repo.layout.workTree, p))
            }
            if (alsoDirs) {
                for (d in untrackedDirs.sortedDescending()) {
                    ctx.stdout.writeUtf8("${if (dryRun) "Would remove" else "Removing"} $d/\n")
                    if (!dryRun) ctx.fs.remove(absOfRm(repo.layout.workTree, d))
                }
            }
            return CommandResult(exitCode = 0)
        }
    }

private fun walkClean(
    fs: FileSystem,
    workTree: String,
    rel: String,
    tracked: Set<String>,
    files: MutableList<String>,
    dirs: MutableList<String>,
    collectDirs: Boolean,
) {
    val abs = if (rel.isEmpty()) workTree else absOfRm(workTree, rel)
    if (!fs.isDirectory(abs)) return
    for (name in fs.list(abs)) {
        if (name == ".git") continue
        val sub = if (rel.isEmpty()) name else "$rel/$name"
        val subAbs = absOfRm(workTree, sub)
        if (fs.isDirectory(subAbs)) {
            val before = files.size
            walkClean(fs, workTree, sub, tracked, files, dirs, collectDirs)
            // If we collected nothing AND no tracked file lives below us,
            // the directory itself is untracked.
            val hasTracked = tracked.any { it == sub || it.startsWith("$sub/") }
            if (collectDirs && !hasTracked && files.size == before) {
                dirs += sub
            }
        } else if (sub !in tracked) {
            files += sub
        }
    }
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
    val abs =
        if (p.startsWith("/")) {
            p
        } else if (cwd == "/") {
            "/$p"
        } else {
            "$cwd/$p"
        }
    val base = if (workTree == "/") "/" else "$workTree/"
    return when {
        workTree == "/" && abs.startsWith("/") -> abs.removePrefix("/")
        abs == workTree -> ""
        abs.startsWith(base) -> abs.removePrefix(base)
        else -> null
    }
}
