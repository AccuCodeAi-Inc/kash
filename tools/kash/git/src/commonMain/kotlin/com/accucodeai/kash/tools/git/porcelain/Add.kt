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
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git add` — copy paths from the working tree into the index.
 *
 * Surface (matched against `/usr/bin/git`):
 *  - bare paths and directories (`git add path1 dir`). A directory adds
 *    every non-ignored regular file under it recursively.
 *  - `-A` / `--all`: stage every changed/new path in the work tree;
 *    also clear entries for deleted paths.
 *  - `-u` / `--update`: stage modifications/deletions of already-
 *    tracked paths; do not add new files.
 *  - `--ignore-removal` / `--no-all`: stage new + modified paths but
 *    NOT deletions (this is also the implicit default for bare paths).
 *  - `-n` / `--dry-run`: print `add '<path>'` lines for what *would* be
 *    added/updated, change nothing. Exits non-zero if an explicit path
 *    is ignored (same as a real add).
 *  - `-v` / `--verbose`: print `add '<path>'` for each path whose index
 *    entry actually changes.
 *  - `-f` / `--force`: add paths even if a `.gitignore` rule excludes
 *    them.
 *  - `--refresh`: refresh stat info only — no-op here since our index
 *    carries zeroed stat fields; accepted for parity.
 *  - `--ignore-errors`: continue past unreadable files (best effort).
 *
 * Out of scope: `-p` / `-i` (interactive), `--intent-to-add`, pathspec
 * magic. Interactive modes are rejected with a clean error.
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

            // `-A`/`--all`/`--no-ignore-removal` and `--ignore-removal`/
            // `--no-all` are last-one-wins toggles of the same "stage every
            // work-tree change including deletions" mode. null = unset.
            var all = false
            var update = false
            var dryRun = false
            var verbose = false
            var force = false
            var ignoreErrors = false
            val paths = mutableListOf<String>()
            var endOpts = false
            for (a in args) {
                if (endOpts) {
                    paths += a
                    continue
                }
                when (a) {
                    "-A", "--all", "--no-ignore-removal" -> {
                        all = true
                    }

                    "-u", "--update" -> {
                        update = true
                    }

                    "--ignore-removal", "--no-all" -> {
                        // Cancels -A's whole-tree-with-deletions mode entirely;
                        // reverts to plain pathspec add (no removal staging).
                        all = false
                    }

                    "-n", "--dry-run" -> {
                        dryRun = true
                    }

                    "-v", "--verbose" -> {
                        verbose = true
                    }

                    "-f", "--force" -> {
                        force = true
                    }

                    "--refresh" -> {}

                    // stat-only refresh; no-op for our index
                    "--ignore-errors" -> {
                        ignoreErrors = true
                    }

                    "--" -> {
                        endOpts = true
                    }

                    "-i", "--interactive", "-p", "--patch" -> {
                        ctx.stderr.writeUtf8("git add: interactive mode ($a) is not supported\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        if (a.length > 1 && a[0] == '-' && a[1] != '-') {
                            // Bundled short flags, e.g. -An, -nv, -Af.
                            val parsed = parseBundledAddFlags(a)
                            if (parsed == null) {
                                ctx.stderr.writeUtf8("git add: unsupported option '$a'\n")
                                return CommandResult(exitCode = 129)
                            }
                            all = all || parsed.all
                            update = update || parsed.update
                            dryRun = dryRun || parsed.dryRun
                            verbose = verbose || parsed.verbose
                            force = force || parsed.force
                        } else if (a.startsWith("-") && a != "-") {
                            ctx.stderr.writeUtf8("git add: unsupported option '$a'\n")
                            return CommandResult(exitCode = 129)
                        } else {
                            paths += a
                        }
                    }
                }
            }

            if (!all && !update && paths.isEmpty()) {
                // git prints a hint and exits 0 when nothing is specified.
                ctx.stderr.writeUtf8("Nothing specified, nothing added.\n")
                ctx.stderr.writeUtf8("hint: Maybe you wanted to say 'git add .'?\n")
                ctx.stderr.writeUtf8(
                    "hint: Disable this message with \"git config set advice.addEmptyPathspec false\"\n",
                )
                return CommandResult(exitCode = 0)
            }

            val index = repo.readIndex()
            val byPath = index.entries.associateBy { it.path }.toMutableMap()
            val tracked = byPath.keys.toSet()

            // .gitignore-aware scan of the work tree (tracked files never hidden).
            val scan = scanWorkTree(repo, tracked)
            val workTreeRel = scan.files
            // Build a fast "is ignored" lookup for explicit-path force checks.
            val ignoredSet = scan.ignoredFiles

            // -A also stages deletions; -u stages deletions of tracked; bare
            // paths and --ignore-removal do NOT stage deletions.
            val stageDeletions = all || update

            // Determine candidate set. For explicit pathspecs we also track
            // which paths were *named directly* (vs. discovered by walking a
            // directory) — only directly-named ignored files trigger an error;
            // ignored files found inside a walked directory are skipped.
            val candidates: Set<String>
            val explicitPaths: Boolean
            val namedFiles = mutableSetOf<String>()
            if (all) {
                candidates = workTreeRel + byPath.keys
                explicitPaths = false
            } else if (update) {
                candidates = byPath.keys.toSet()
                explicitPaths = false
            } else {
                explicitPaths = true
                val expanded = mutableSetOf<String>()
                for (p in paths) {
                    val rel = relativizeAdd(repo.layout.workTree, env.cwd, p) ?: p
                    val abs = absOfAdd(repo.layout.workTree, rel)
                    when {
                        ctx.fs.exists(abs) && ctx.fs.isDirectory(abs) -> {
                            // Directory pathspec: take only non-ignored work-tree
                            // files beneath it (plus tracked-but-deleted ones).
                            val prefix = if (rel.isEmpty()) "" else "$rel/"
                            val beneath =
                                workTreeRel.filter { rel.isEmpty() || it.startsWith(prefix) } +
                                    byPath.keys.filter { rel.isEmpty() || it.startsWith(prefix) }
                            expanded += beneath
                        }

                        ctx.fs.exists(abs) -> {
                            // A directly-named regular file (or symlink).
                            expanded += rel
                            namedFiles += rel
                        }

                        rel in byPath -> {
                            // Tracked but deleted from the work tree — valid pathspec.
                            expanded += rel
                            namedFiles += rel
                        }

                        else -> {
                            ctx.stderr.writeUtf8("fatal: pathspec '$p' did not match any files\n")
                            return CommandResult(exitCode = 128)
                        }
                    }
                }
                candidates = expanded
            }

            // Reject directly-named ignored files unless -f.
            if (explicitPaths && !force) {
                val ignoredHits =
                    namedFiles
                        .filter { it !in tracked && it in ignoredSet }
                        .sorted()
                if (ignoredHits.isNotEmpty()) {
                    ctx.stderr.writeUtf8(
                        "The following paths are ignored by one of your .gitignore files:\n",
                    )
                    for (h in ignoredHits) ctx.stderr.writeUtf8("$h\n")
                    ctx.stderr.writeUtf8("hint: Use -f if you really want to add them.\n")
                    ctx.stderr.writeUtf8(
                        "hint: Disable this message with \"git config set advice.addIgnoredFile false\"\n",
                    )
                    return CommandResult(exitCode = 1)
                }
            }

            // Compute the new index, tracking which paths actually change so
            // -v / -n can report them. We don't mutate byPath until we know
            // dry-run vs. real.
            data class Change(
                val path: String,
                val deleted: Boolean,
                val entry: IndexEntry?,
            )

            val changes = mutableListOf<Change>()
            for (relPath in candidates.sorted()) {
                val abs = absOfAdd(repo.layout.workTree, relPath)
                if (!ctx.fs.exists(abs)) {
                    // Removed from the work tree.
                    if (stageDeletions) {
                        if (relPath in byPath) changes += Change(relPath, deleted = true, entry = null)
                    }
                    // --ignore-removal / bare paths: silently skip deletions.
                    continue
                }
                if (ctx.fs.isDirectory(abs)) continue
                val bytes =
                    try {
                        ctx.fs.readBytes(abs)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        if (ignoreErrors) {
                            continue
                        } else {
                            ctx.stderr.writeUtf8("error: open(\"$relPath\"): failed to read\n")
                            ctx.stderr.writeUtf8("error: unable to index file '$relPath'\n")
                            ctx.stderr.writeUtf8("fatal: adding files failed\n")
                            return CommandResult(exitCode = 128)
                        }
                    }
                val sha =
                    if (dryRun) {
                        // Don't write objects on a dry run; we only need to
                        // know whether the entry would change.
                        com.accucodeai.kash.tools.git.plumbing
                            .blobSha(bytes)
                    } else {
                        repo.objects.write(ObjectType.BLOB, bytes)
                    }
                val mode = inferAddMode(ctx.fs, abs)
                val existing = byPath[relPath]
                val unchanged = existing != null && existing.sha == sha && existing.mode == mode
                if (unchanged) continue
                changes +=
                    Change(
                        relPath,
                        deleted = false,
                        entry =
                            IndexEntry(
                                mode = mode,
                                size = bytes.size,
                                sha = sha,
                                path = relPath,
                            ),
                    )
            }

            // Report (dry-run or verbose). git prints "add '<path>'" for both
            // additions and modifications; deletions are silent.
            if (dryRun || verbose) {
                for (c in changes) {
                    if (!c.deleted) ctx.stdout.writeUtf8("add '${c.path}'\n")
                }
            }

            if (dryRun) {
                return CommandResult(exitCode = 0)
            }

            for (c in changes) {
                if (c.deleted) {
                    byPath.remove(c.path)
                } else {
                    byPath[c.path] = c.entry!!
                }
            }
            repo.writeIndex(IndexFile(version = 2, entries = byPath.values.toList()))
            return CommandResult(exitCode = 0)
        }

        private fun inferAddMode(
            fs: FileSystem,
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
    }

private class BundledAddFlags(
    val all: Boolean,
    val update: Boolean,
    val dryRun: Boolean,
    val verbose: Boolean,
    val force: Boolean,
)

/** Parse a bundled short-flag cluster like `-An`. Returns null on any unknown letter. */
private fun parseBundledAddFlags(arg: String): BundledAddFlags? {
    var all = false
    var update = false
    var dryRun = false
    var verbose = false
    var force = false
    for (i in 1 until arg.length) {
        when (arg[i]) {
            'A' -> all = true
            'u' -> update = true
            'n' -> dryRun = true
            'v' -> verbose = true
            'f' -> force = true
            else -> return null
        }
    }
    return BundledAddFlags(all, update, dryRun, verbose, force)
}

private fun absOfAdd(
    workTree: String,
    rel: String,
): String = if (workTree == "/") "/$rel" else "$workTree/$rel"

private fun relativizeAdd(
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

/** Collapse `.`/`..` segments and redundant slashes in an absolute path. */
internal fun normalizeAbs(abs: String): String {
    val parts = abs.split('/').filter { it.isNotEmpty() }
    val stack = ArrayDeque<String>()
    for (part in parts) {
        when (part) {
            "." -> {}

            ".." -> {
                if (stack.isNotEmpty()) stack.removeLast()
            }

            else -> {
                stack.addLast(part)
            }
        }
    }
    return "/" + stack.joinToString("/")
}
