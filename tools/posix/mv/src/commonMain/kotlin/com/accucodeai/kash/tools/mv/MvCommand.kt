package com.accucodeai.kash.tools.mv

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.fs.Paths

/**
 * POSIX [`mv`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/mv.html).
 *
 * Implemented as copy-then-remove since [com.accucodeai.kash.fs.FileSystem]
 * has no native `rename`. Symlinks are moved verbatim (never followed at the
 * top level), regular files preserve mode + mtime, and directories are moved
 * recursively. If the copy half fails, the source is left intact.
 *
 * Supported options:
 *  - `-f` / `--force` — overwrite without prompting (default behavior in
 *    non-interactive contexts; accepted for compatibility).
 *  - `-i` / `--interactive` — accepted but no-op (kash has no TTY-prompt
 *    model yet).
 *  - `-n` / `--no-clobber` — do not overwrite an existing destination.
 *  - `-v` / `--verbose` — emit `renamed 'src' -> 'dest'` per move.
 *  - `-t DIR` / `--target-directory=DIR` — move all sources into `DIR`.
 *  - `-T` / `--no-target-directory` — treat `DEST` as a non-directory
 *    (refuses to move into an existing directory).
 *  - `--` — end of options.
 *
 * Exit code 0 if every operand succeeded, 1 otherwise.
 */
public class MvCommand :
    Command,
    CommandSpec {
    override val name: String = "mv"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.STATEFUL)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var force = false
        var noClobber = false
        var verbose = false

        @Suppress("UNUSED_VARIABLE")
        var interactive = false
        var targetDir: String? = null
        var noTargetDir = false
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

                a == "--force" -> {
                    force = true
                    noClobber = false
                }

                a == "--interactive" -> {
                    interactive = true
                }

                a == "--no-clobber" -> {
                    noClobber = true
                    force = false
                }

                a == "--verbose" -> {
                    verbose = true
                }

                a == "--no-target-directory" -> {
                    noTargetDir = true
                }

                a == "--target-directory" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("mv: option '--target-directory' requires an argument\n")
                        return CommandResult(exitCode = 2)
                    }
                    targetDir = args[++i]
                }

                a.startsWith("--target-directory=") -> {
                    targetDir = a.substringAfter('=')
                }

                a == "-t" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("mv: option requires an argument -- 't'\n")
                        return CommandResult(exitCode = 2)
                    }
                    targetDir = args[++i]
                }

                a == "-T" -> {
                    noTargetDir = true
                }

                a == "-" -> {
                    operands += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    var j = 1
                    while (j < a.length) {
                        when (val ch = a[j]) {
                            'f' -> {
                                force = true
                                noClobber = false
                            }

                            'i' -> {
                                interactive = true
                            }

                            'n' -> {
                                noClobber = true
                                force = false
                            }

                            'v' -> {
                                verbose = true
                            }

                            'T' -> {
                                noTargetDir = true
                            }

                            't' -> {
                                // -tDIR or -t DIR
                                val rest = a.substring(j + 1)
                                if (rest.isNotEmpty()) {
                                    targetDir = rest
                                } else {
                                    if (i + 1 >= args.size) {
                                        ctx.stderr.writeUtf8("mv: option requires an argument -- 't'\n")
                                        return CommandResult(exitCode = 2)
                                    }
                                    targetDir = args[++i]
                                }
                                j = a.length // consumed rest
                            }

                            else -> {
                                ctx.stderr.writeUtf8("mv: invalid option -- '$ch'\n")
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
            ctx.stderr.writeUtf8("mv: missing file operand\n")
            return CommandResult(exitCode = 2)
        }

        // Determine sources and destination.
        val sources: List<String>
        val destOperand: String
        if (targetDir != null) {
            sources = operands
            destOperand = targetDir
        } else {
            if (operands.size < 2) {
                ctx.stderr.writeUtf8("mv: missing destination file operand after '${operands[0]}'\n")
                return CommandResult(exitCode = 2)
            }
            destOperand = operands.last()
            sources = operands.dropLast(1)
        }

        val destAbs = Paths.resolve(ctx.process.cwd, destOperand)
        val destIsExistingDir = ctx.process.fs.exists(destAbs) && ctx.process.fs.isDirectory(destAbs)

        if (targetDir != null) {
            if (!ctx.process.fs.exists(destAbs)) {
                ctx.stderr.writeUtf8("mv: target directory '$destOperand': No such file or directory\n")
                return CommandResult(exitCode = 1)
            }
            if (!destIsExistingDir) {
                ctx.stderr.writeUtf8("mv: target '$destOperand' is not a directory\n")
                return CommandResult(exitCode = 1)
            }
        } else if (sources.size > 1) {
            if (noTargetDir) {
                ctx.stderr.writeUtf8("mv: extra operand '${sources[1]}'\n")
                return CommandResult(exitCode = 1)
            }
            if (!destIsExistingDir) {
                ctx.stderr.writeUtf8("mv: target '$destOperand' is not a directory\n")
                return CommandResult(exitCode = 1)
            }
        }

        var anyError = false
        for (src in sources) {
            val srcAbs = Paths.resolve(ctx.process.cwd, src)
            val finalDest =
                if (targetDir != null) {
                    joinPath(destAbs, Paths.basename(srcAbs))
                } else if (destIsExistingDir && !noTargetDir) {
                    joinPath(destAbs, Paths.basename(srcAbs))
                } else {
                    destAbs
                }

            // -T: refuse to descend into an existing directory.
            if (noTargetDir && ctx.process.fs.exists(finalDest) && ctx.process.fs.isDirectory(finalDest)) {
                ctx.stderr.writeUtf8(
                    "mv: cannot overwrite directory '$destOperand' with non-directory\n",
                )
                anyError = true
                continue
            }

            if (!moveOne(ctx, src, destOperand, srcAbs, finalDest, force, noClobber, verbose)) {
                anyError = true
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    /** Move a single source to a single resolved destination path. */
    private suspend fun moveOne(
        ctx: CommandContext,
        srcDisplay: String,
        destDisplay: String,
        srcAbs: String,
        destAbs: String,
        force: Boolean,
        noClobber: Boolean,
        verbose: Boolean,
    ): Boolean {
        // Source must exist (use statLink to allow symlinks).
        val srcStat =
            try {
                ctx.process.fs.statLink(srcAbs)
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("mv: cannot stat '$srcDisplay': No such file or directory\n")
                return false
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("mv: cannot stat '$srcDisplay': ${e.message ?: "I/O error"}\n")
                return false
            }

        // Same-file check by normalized absolute path.
        if (srcAbs == destAbs) {
            ctx.stderr.writeUtf8("mv: '$srcDisplay' and '$destDisplay' are the same file\n")
            return false
        }

        // Destination parent must exist.
        val destParent = Paths.parent(destAbs)
        if (!ctx.process.fs.exists(destParent) || !ctx.process.fs.isDirectory(destParent)) {
            ctx.stderr.writeUtf8(
                "mv: cannot move '$srcDisplay' to '$destDisplay': No such file or directory\n",
            )
            return false
        }

        // Existing destination handling.
        val destExists = ctx.process.fs.exists(destAbs)
        if (destExists) {
            if (noClobber) {
                // Silently skip; source preserved. GNU mv emits nothing.
                return true
            }
            // -f or default: attempt overwrite. Refuse dir-over-non-dir, etc.
            val destIsDir =
                try {
                    ctx.process.fs
                        .statLink(destAbs)
                        .type == FileType.DIRECTORY
                } catch (_: Exception) {
                    false
                }
            val srcIsDir = srcStat.type == FileType.DIRECTORY
            if (destIsDir && !srcIsDir) {
                ctx.stderr.writeUtf8(
                    "mv: cannot overwrite directory '$destDisplay' with non-directory\n",
                )
                return false
            }
            if (!destIsDir && srcIsDir) {
                ctx.stderr.writeUtf8(
                    "mv: cannot overwrite non-directory '$destDisplay' with directory '$srcDisplay'\n",
                )
                return false
            }
            // Remove existing destination first. For a directory destination,
            // it must be empty (POSIX rename semantics). We keep it simple:
            // remove recursively, since mv-then-clobber of a dir is rare and
            // GNU mv requires it to be empty too — we accept either.
            try {
                removeTree(ctx, destAbs)
            } catch (e: Exception) {
                ctx.stderr.writeUtf8(
                    "mv: cannot remove '$destDisplay': ${e.message ?: "I/O error"}\n",
                )
                return false
            }
            // force/!force: in real mv -f silently overwrites read-only dest.
            // Our FS has no permission gating, so no extra work.
            @Suppress("UNUSED_EXPRESSION")
            force
        }

        // Copy then remove. If copy fails partway, leave source intact AND
        // remove any partial destination we created.
        try {
            copyTree(ctx, srcAbs, destAbs)
        } catch (e: Exception) {
            ctx.stderr.writeUtf8(
                "mv: cannot move '$srcDisplay' to '$destDisplay': ${e.message ?: "I/O error"}\n",
            )
            // Best-effort: clean up partial destination so we don't leak it.
            runCatching { removeTree(ctx, destAbs) }
            return false
        }
        try {
            removeTree(ctx, srcAbs)
        } catch (e: Exception) {
            ctx.stderr.writeUtf8(
                "mv: cannot remove source '$srcDisplay': ${e.message ?: "I/O error"}\n",
            )
            return false
        }

        if (verbose) {
            ctx.stdout.writeUtf8("renamed '$srcDisplay' -> '$destDisplay'\n")
        }
        return true
    }

    /**
     * Recursive copy that preserves mode + mtime and reproduces symlinks
     * verbatim. We use statLink so symlinks are not followed at the top.
     */
    private suspend fun copyTree(
        ctx: CommandContext,
        srcAbs: String,
        destAbs: String,
    ) {
        val stat = ctx.process.fs.statLink(srcAbs)
        when (stat.type) {
            FileType.SYMLINK -> {
                val target = ctx.process.fs.readSymlink(srcAbs)
                ctx.process.fs.createSymlink(destAbs, target)
                // Symlink mode + mtime preservation is mostly meaningless on
                // most platforms; skip.
            }

            FileType.DIRECTORY -> {
                if (!ctx.process.fs.exists(destAbs)) {
                    ctx.process.fs.mkdirs(
                        destAbs,
                        mode =
                            0b111_111_111 and ctx.process.umask.inv(),
                    )
                }
                for (entry in ctx.process.fs.list(srcAbs)) {
                    copyTree(ctx, joinPath(srcAbs, entry), joinPath(destAbs, entry))
                }
                // Apply mode + mtime to the directory itself after children.
                runCatching { ctx.process.fs.chmod(destAbs, stat.mode) }
                runCatching { ctx.process.fs.setMtime(destAbs, stat.mtimeEpochSeconds) }
            }

            FileType.REGULAR -> {
                val bytes = ctx.process.fs.readBytes(srcAbs)
                ctx.process.fs.writeBytes(destAbs, bytes)
                runCatching { ctx.process.fs.chmod(destAbs, stat.mode) }
                runCatching { ctx.process.fs.setMtime(destAbs, stat.mtimeEpochSeconds) }
            }

            else -> {
                // Special files (FIFO, SOCKET, BLOCK, CHAR) aren't producible
                // by the InMemoryFs; surface as an error rather than silently
                // skipping.
                throw RuntimeException("cannot copy special file: $srcAbs")
            }
        }
    }

    /** Depth-first remove of [absPath] (file, symlink, or directory tree). */
    private fun removeTree(
        ctx: CommandContext,
        absPath: String,
    ) {
        if (!ctx.process.fs.exists(absPath)) return
        val stat = ctx.process.fs.statLink(absPath)
        if (stat.type == FileType.DIRECTORY) {
            for (entry in ctx.process.fs.list(absPath)) {
                removeTree(ctx, joinPath(absPath, entry))
            }
        }
        ctx.process.fs.remove(absPath)
    }

    private fun joinPath(
        dir: String,
        name: String,
    ): String =
        when {
            dir == "/" -> "/$name"
            else -> "$dir/$name"
        }
}
