package com.accucodeai.kash.tools.ln

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.NotASymlink
import com.accucodeai.kash.fs.Paths

/**
 * POSIX [`ln`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/ln.html).
 *
 * Forms accepted:
 *  - `ln [OPTIONS] TARGET LINK_NAME`
 *  - `ln [OPTIONS] TARGET... DIRECTORY`
 *  - `ln [OPTIONS] -t DIRECTORY TARGET...`
 *
 * Options:
 *  - `-s` / `--symbolic` — create symbolic links (target stored verbatim;
 *    POSIX permits dangling/relative symlinks, so target is NOT validated
 *    and is NOT resolved against `ctx.process.cwd`).
 *  - `-f` / `--force` — remove existing destination before creating link.
 *  - `-n` / `--no-dereference` — with `-s`, treat an existing LINK that is
 *    itself a symlink-to-directory as a regular file (i.e. don't descend).
 *  - `-v` / `--verbose` — print `'link' -> 'target'` per link.
 *  - `-t DIR` / `--target-directory=DIR` — remaining operands are sources
 *    linked into DIR.
 *  - `-T` / `--no-target-directory` — treat LINK as a non-directory; refuse
 *    even if it's a directory.
 *  - `-i` / `--interactive` — accepted, no-op (kash has no TTY model yet).
 *  - `--` — end of options.
 *
 * Exit 0 on full success, 1 if any per-operand link failed.
 */
public class LnCommand :
    Command,
    CommandSpec {
    override val name: String = "ln"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.STATEFUL)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var symbolic = false
        var force = false
        var noDeref = false
        var verbose = false
        var noTargetDir = false
        var targetDir: String? = null

        @Suppress("UNUSED_VARIABLE")
        var interactive = false

        val operands = mutableListOf<String>()
        var endOfOptions = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                endOfOptions -> {
                    operands += a
                }

                a == "--" -> {
                    endOfOptions = true
                }

                a == "--symbolic" -> {
                    symbolic = true
                }

                a == "--force" -> {
                    force = true
                }

                a == "--no-dereference" -> {
                    noDeref = true
                }

                a == "--verbose" -> {
                    verbose = true
                }

                a == "--interactive" -> {
                    interactive = true
                }

                a == "--no-target-directory" -> {
                    noTargetDir = true
                }

                a == "--target-directory" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("ln: option requires an argument -- 'target-directory'\n")
                        return CommandResult(exitCode = 2)
                    }
                    targetDir = args[++i]
                }

                a.startsWith("--target-directory=") -> {
                    targetDir = a.removePrefix("--target-directory=")
                }

                a == "-" -> {
                    operands += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    var j = 1
                    while (j < a.length) {
                        when (val ch = a[j]) {
                            's' -> {
                                symbolic = true
                            }

                            'f' -> {
                                force = true
                            }

                            'n' -> {
                                noDeref = true
                            }

                            'v' -> {
                                verbose = true
                            }

                            'i' -> {
                                interactive = true
                            }

                            'T' -> {
                                noTargetDir = true
                            }

                            't' -> {
                                // -t may take rest of arg or next arg
                                val rest = a.substring(j + 1)
                                if (rest.isNotEmpty()) {
                                    targetDir = rest
                                    j = a.length
                                } else {
                                    if (i + 1 >= args.size) {
                                        ctx.stderr.writeUtf8("ln: option requires an argument -- 't'\n")
                                        return CommandResult(exitCode = 2)
                                    }
                                    targetDir = args[++i]
                                }
                            }

                            else -> {
                                ctx.stderr.writeUtf8("ln: invalid option -- '$ch'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                        j++
                    }
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        if (operands.isEmpty()) {
            ctx.stderr.writeUtf8("ln: missing file operand\n")
            return CommandResult(exitCode = 2)
        }

        if (targetDir != null && noTargetDir) {
            ctx.stderr.writeUtf8("ln: cannot combine --target-directory and --no-target-directory\n")
            return CommandResult(exitCode = 2)
        }

        // Determine operating mode.
        // Modes:
        //   1. -t DIR: every operand is a source; link into DIR.
        //   2. -T: must have exactly 2 operands; second is LINK (never a dir to descend into).
        //   3. Final operand is an existing directory (and not -T): multi-source into dir.
        //   4. Exactly 2 operands: TARGET LINK_NAME.
        //   5. >=3 operands but final not a dir: error.

        return when {
            targetDir != null -> {
                runMultiIntoDir(ctx, operands, targetDir, symbolic, force, noDeref, verbose)
            }

            noTargetDir -> {
                if (operands.size != 2) {
                    ctx.stderr.writeUtf8("ln: missing destination file operand after '${operands.first()}'\n")
                    return CommandResult(exitCode = 2)
                }
                val target = operands[0]
                val link = operands[1]
                // -T: refuse if link is a (real) directory.
                val linkAbs = Paths.resolve(ctx.process.cwd, link)
                if (ctx.process.fs.exists(linkAbs) && ctx.process.fs.isDirectory(linkAbs) && !isSymlink(ctx, linkAbs)) {
                    ctx.stderr.writeUtf8("ln: target '$link' is a directory\n")
                    return CommandResult(exitCode = 1)
                }
                val ok = doLink(ctx, target, link, symbolic, force, noDeref, verbose)
                CommandResult(exitCode = if (ok) 0 else 1)
            }

            operands.size == 1 -> {
                // `ln TARGET` -> link into CWD with basename of TARGET.
                val target = operands[0]
                val link = basenameOf(target)
                val ok = doLink(ctx, target, link, symbolic, force, noDeref, verbose)
                CommandResult(exitCode = if (ok) 0 else 1)
            }

            operands.size == 2 -> {
                val target = operands[0]
                val link = operands[1]
                val linkAbs = Paths.resolve(ctx.process.cwd, link)
                // If link is an existing directory (and -n with symlink-to-dir doesn't override
                // — that's the "treat as file" case), drop basename of TARGET into it.
                val linkIsDir =
                    ctx.process.fs.exists(linkAbs) && ctx.process.fs.isDirectory(linkAbs) &&
                        !(noDeref && symbolic && isSymlink(ctx, linkAbs))
                if (linkIsDir) {
                    runMultiIntoDir(ctx, listOf(target), link, symbolic, force, noDeref, verbose)
                } else {
                    val ok = doLink(ctx, target, link, symbolic, force, noDeref, verbose)
                    CommandResult(exitCode = if (ok) 0 else 1)
                }
            }

            else -> {
                // 3+ operands: final must be a directory.
                val last = operands.last()
                val sources = operands.dropLast(1)
                val lastAbs = Paths.resolve(ctx.process.cwd, last)
                if (!ctx.process.fs.exists(lastAbs) || !ctx.process.fs.isDirectory(lastAbs)) {
                    ctx.stderr.writeUtf8("ln: target '$last' is not a directory\n")
                    return CommandResult(exitCode = 1)
                }
                runMultiIntoDir(ctx, sources, last, symbolic, force, noDeref, verbose)
            }
        }
    }

    private suspend fun runMultiIntoDir(
        ctx: CommandContext,
        sources: List<String>,
        dir: String,
        symbolic: Boolean,
        force: Boolean,
        noDeref: Boolean,
        verbose: Boolean,
    ): CommandResult {
        val dirAbs = Paths.resolve(ctx.process.cwd, dir)
        if (!ctx.process.fs.exists(dirAbs) || !ctx.process.fs.isDirectory(dirAbs)) {
            ctx.stderr.writeUtf8("ln: target directory '$dir' is not a directory\n")
            return CommandResult(exitCode = 1)
        }
        var anyError = false
        for (src in sources) {
            val linkDisplay = joinDisplay(dir, basenameOf(src))
            if (!doLink(ctx, src, linkDisplay, symbolic, force, noDeref, verbose)) {
                anyError = true
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    /**
     * Create a single link. Returns true on success. Errors are written to
     * stderr; caller decides exit code aggregation.
     *
     * IMPORTANT: For symlinks the [targetSpec] is stored verbatim. For hard
     * links the target must be resolved against `ctx.process.cwd` so the FS sees the
     * actual file.
     */
    private suspend fun doLink(
        ctx: CommandContext,
        targetSpec: String,
        linkSpec: String,
        symbolic: Boolean,
        force: Boolean,
        @Suppress("UNUSED_PARAMETER") noDeref: Boolean,
        verbose: Boolean,
    ): Boolean {
        val linkAbs = Paths.resolve(ctx.process.cwd, linkSpec)

        // Pre-flight: if link exists, either remove (force) or fail.
        if (linkExists(ctx, linkAbs)) {
            if (!force) {
                ctx.stderr.writeUtf8(
                    "ln: failed to create ${if (symbolic) "symbolic " else ""}link '$linkSpec': File exists\n",
                )
                return false
            }
            try {
                ctx.process.fs.remove(linkAbs)
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("ln: cannot remove '$linkSpec': ${e.message ?: "I/O error"}\n")
                return false
            }
        }

        return if (symbolic) {
            // POSIX: target stored verbatim, not validated, not resolved.
            try {
                ctx.process.fs.createSymlink(linkAbs, targetSpec)
                if (verbose) ctx.stderr.writeUtf8("'$linkSpec' -> '$targetSpec'\n")
                true
            } catch (e: UnsupportedOperationException) {
                ctx.stderr.writeUtf8(
                    "ln: failed to create symbolic link '$linkSpec': ${e.message ?: "not supported"}\n",
                )
                false
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("ln: failed to create symbolic link '$linkSpec': ${e.message ?: "I/O error"}\n")
                false
            }
        } else {
            // Hard link: target must exist as regular file and is resolved.
            val targetAbs = Paths.resolve(ctx.process.cwd, targetSpec)
            if (!ctx.process.fs.exists(targetAbs)) {
                ctx.stderr.writeUtf8("ln: failed to access '$targetSpec': No such file or directory\n")
                return false
            }
            if (ctx.process.fs.isDirectory(targetAbs)) {
                ctx.stderr.writeUtf8("ln: '$targetSpec': hard link not allowed for directory\n")
                return false
            }
            try {
                ctx.process.fs.createHardLink(linkAbs, targetAbs)
                if (verbose) ctx.stderr.writeUtf8("'$linkSpec' => '$targetSpec'\n")
                true
            } catch (e: UnsupportedOperationException) {
                ctx.stderr.writeUtf8(
                    "ln: failed to create hard link '$linkSpec' => '$targetSpec': ${e.message ?: "not supported"}\n",
                )
                false
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("ln: failed to access '$targetSpec': No such file or directory\n")
                false
            } catch (e: Exception) {
                ctx.stderr.writeUtf8(
                    "ln: failed to create hard link '$linkSpec' => '$targetSpec': ${e.message ?: "I/O error"}\n",
                )
                false
            }
        }
    }

    /** Does the path exist as anything — regular, dir, or symlink (including dangling)? */
    private fun linkExists(
        ctx: CommandContext,
        path: String,
    ): Boolean {
        if (ctx.process.fs.exists(path)) return true
        // Dangling symlink: statLink succeeds where exists() (which follows) doesn't.
        return try {
            ctx.process.fs.statLink(path)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isSymlink(
        ctx: CommandContext,
        path: String,
    ): Boolean =
        try {
            ctx.process.fs.readSymlink(path)
            true
        } catch (_: NotASymlink) {
            false
        } catch (_: Exception) {
            false
        }

    private fun basenameOf(p: String): String {
        if (p.isEmpty()) return p
        val trimmed = p.trimEnd('/')
        if (trimmed.isEmpty()) return "/"
        val idx = trimmed.lastIndexOf('/')
        return if (idx < 0) trimmed else trimmed.substring(idx + 1)
    }

    private fun joinDisplay(
        dir: String,
        name: String,
    ): String {
        val d = dir.trimEnd('/')
        return if (d.isEmpty()) "/$name" else "$d/$name"
    }
}
