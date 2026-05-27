package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.IndexEntry
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.ObjectType

/**
 * `git add` — copy paths from the working tree into the index. v1
 * surface:
 *  - bare paths and globs (`git add path1 path2`). A directory adds
 *    every regular file under it recursively.
 *  - `-A` / `--all`: stage every changed/new path in the work tree;
 *    also clear entries for deleted paths.
 *  - `-u` / `--update`: stage modifications/deletions of already-
 *    tracked paths; do not add new files.
 *
 * Out of scope for v1: `-p` (interactive patches), pathspec magic
 * (`:(exclude)`, etc.), `--intent-to-add`. We document the surface so
 * the LLM gets a clean error rather than silent partial behavior.
 */
public fun gitAddSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "add"

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

            var all = false
            var update = false
            val paths = mutableListOf<String>()
            var endOpts = false
            for (a in args) {
                when {
                    !endOpts && (a == "-A" || a == "--all") -> {
                        all = true
                    }

                    !endOpts && (a == "-u" || a == "--update") -> {
                        update = true
                    }

                    !endOpts && a == "--" -> {
                        endOpts = true
                    }

                    !endOpts && a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git add: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        paths += a
                    }
                }
            }
            if (!all && !update && paths.isEmpty()) {
                ctx.stderr.writeUtf8("Nothing specified, nothing added.\n")
                return CommandResult(exitCode = 0)
            }

            val index = repo.readIndex()
            val byPath = index.entries.associateBy { it.path }.toMutableMap()

            // Tracked set = current index paths (HEAD-tracked rows are
            // already in the index after first add+commit). Used to keep
            // .gitignore from hiding files that are already tracked.
            val tracked = byPath.keys
            // .gitignore-aware scan of the work tree.
            val workTreeRel = scanWorkTree(repo, tracked).files

            val candidates: Set<String> =
                when {
                    all -> {
                        workTreeRel + byPath.keys
                    }

                    // include known-tracked that may have been deleted
                    update -> {
                        byPath.keys
                    }

                    else -> {
                        val expanded = mutableSetOf<String>()
                        for (p in paths) expanded += expandPath(ctx.fs, repo.layout.workTree, env.cwd, p)
                        expanded
                    }
                }

            for (relPath in candidates.sorted()) {
                val abs = if (repo.layout.workTree == "/") "/$relPath" else "${repo.layout.workTree}/$relPath"
                if (!ctx.fs.exists(abs)) {
                    // Removed from work tree.
                    if (all || update) {
                        byPath.remove(relPath)
                    } else {
                        // For explicit paths: real git errors here.
                        ctx.stderr.writeUtf8("fatal: pathspec '$relPath' did not match any files\n")
                        return CommandResult(exitCode = 128)
                    }
                    continue
                }
                if (ctx.fs.isDirectory(abs)) continue // skip dirs themselves
                val bytes = ctx.fs.readBytes(abs)
                val sha = repo.objects.write(ObjectType.BLOB, bytes)
                val mode = inferMode(ctx.fs, abs)
                byPath[relPath] =
                    IndexEntry(
                        mode = mode,
                        size = bytes.size,
                        sha = sha,
                        path = relPath,
                    )
            }

            repo.writeIndex(IndexFile(version = 2, entries = byPath.values.toList()))
            return CommandResult(exitCode = 0)
        }

        private fun inferMode(
            fs: FileSystem,
            abs: String,
        ): FileMode {
            // Use stat mode's 0o100 bit to tell executable from regular.
            val s =
                try {
                    fs.stat(abs)
                } catch (_: Throwable) {
                    return FileMode.REGULAR
                }
            // Heuristic: any of u/g/o exec bits → EXECUTABLE.
            return if ((s.mode and 0b001_001_001) != 0) FileMode.EXECUTABLE else FileMode.REGULAR
        }
    }

private fun expandPath(
    fs: FileSystem,
    workTree: String,
    cwd: String,
    p: String,
): Set<String> {
    // Resolve relative to cwd; convert to repo-relative.
    val abs =
        if (p.startsWith("/")) {
            p
        } else if (cwd == "/") {
            "/$p"
        } else {
            "$cwd/$p"
        }
    val rel = relativize(workTree, abs) ?: return emptySet()
    if (!fs.exists(abs)) return setOf(rel)
    if (!fs.isDirectory(abs)) return setOf(rel)
    val out = mutableSetOf<String>()
    walkWorktree(fs, abs, rel, out)
    return out
}

private fun relativize(
    workTree: String,
    abs: String,
): String? {
    val base = if (workTree == "/") "/" else "$workTree/"
    return when {
        workTree == "/" && abs.startsWith("/") -> abs.removePrefix("/")
        abs == workTree -> ""
        abs.startsWith(base) -> abs.removePrefix(base)
        else -> null
    }
}

private fun walkWorktree(
    fs: FileSystem,
    abs: String,
    rel: String,
    out: MutableSet<String>,
) {
    for (name in fs.list(abs)) {
        if (name == ".git") continue // skip the repo's own .git (and nested ones)
        val sub = if (abs.endsWith("/")) "$abs$name" else "$abs/$name"
        val subRel = if (rel.isEmpty()) name else "$rel/$name"
        if (fs.isDirectory(sub)) {
            // Stop at nested-repo boundaries — never descend into a
            // directory that contains its own `.git/`, otherwise
            // `git add -A` from a parent would stage the inner repo's
            // working files and (worse) its `.git/objects/*` binaries.
            if (fs.exists("$sub/.git")) continue
            walkWorktree(fs, sub, subRel, out)
        } else {
            out += subRel
        }
    }
}

private fun collectWorktreePaths(
    fs: FileSystem,
    workTree: String,
): Set<String> {
    val out = mutableSetOf<String>()
    walkWorktree(fs, workTree, "", out)
    return out
}
