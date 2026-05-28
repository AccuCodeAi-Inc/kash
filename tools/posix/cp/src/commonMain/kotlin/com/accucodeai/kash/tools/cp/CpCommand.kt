package com.accucodeai.kash.tools.cp

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.DirWalkGuard
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.fs.NotASymlink
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.fs.SymlinkLoop

/**
 * POSIX [`cp`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/cp.html).
 *
 * Supported forms:
 *  - `cp [opts] SRC DEST`              — single-file or single-tree copy.
 *  - `cp [opts] SRC... DIR/`           — copy each SRC into the existing DIR.
 *  - `cp -t DIR [opts] SRC...`         — same as above with target-first.
 *
 * Flags:
 *  - `-r` / `-R` — recurse into directories.
 *  - `-i` — interactive (accepted; no TTY model — acts as default).
 *  - `-f` — force: silently overwrite read-only destination via remove+rewrite.
 *  - `-n` — no-clobber: skip if dest exists.
 *  - `-p` — preserve mode + mtime (best-effort; ignored if FS doesn't support).
 *  - `-v` — verbose: emit `'src' -> 'dest'` per file.
 *  - `-t DIR` — explicit target directory.
 *  - `-T` — never treat DEST as a directory.
 *  - `-L` — follow symlinks (default).
 *  - `-P` — never follow symlinks: recreate the link itself.
 *  - `--` — end-of-options.
 */
public class CpCommand :
    Command,
    CommandSpec {
    override val name: String = "cp"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.STATEFUL)
    override val command: Command get() = this

    private data class Opts(
        var recursive: Boolean = false,
        var force: Boolean = false,
        var noClobber: Boolean = false,
        var preserve: Boolean = false,
        var verbose: Boolean = false,
        var targetDir: String? = null,
        var noTargetDir: Boolean = false,
        var followSymlinks: Boolean = true,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = Opts()
        val operands = mutableListOf<String>()
        var endOfOptions = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (endOfOptions) {
                operands += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOptions = true
                    i++
                }

                a == "-" -> {
                    operands += a
                    i++
                }

                a == "--recursive" -> {
                    opts.recursive = true
                    i++
                }

                a == "--force" -> {
                    opts.force = true
                    i++
                }

                a == "--interactive" -> {
                    i++ // no-op
                }

                a == "--no-clobber" -> {
                    opts.noClobber = true
                    i++
                }

                a == "--preserve" || a.startsWith("--preserve=") -> {
                    opts.preserve = true
                    i++
                }

                a == "--verbose" -> {
                    opts.verbose = true
                    i++
                }

                a == "--no-target-directory" -> {
                    opts.noTargetDir = true
                    i++
                }

                a == "--dereference" -> {
                    opts.followSymlinks = true
                    i++
                }

                a == "--no-dereference" -> {
                    opts.followSymlinks = false
                    i++
                }

                a == "--target-directory" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("cp: option requires an argument -- 'target-directory'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.targetDir = args[i + 1]
                    i += 2
                }

                a.startsWith("--target-directory=") -> {
                    opts.targetDir = a.substringAfter("=")
                    i++
                }

                a.startsWith("-") && a.length > 1 -> {
                    val chars = a.drop(1)
                    var j = 0
                    while (j < chars.length) {
                        val ch = chars[j]
                        when (ch) {
                            'r', 'R' -> {
                                opts.recursive = true
                            }

                            'f' -> {
                                opts.force = true
                            }

                            'i' -> {
                                Unit
                            }

                            // no-op
                            'n' -> {
                                opts.noClobber = true
                            }

                            'p' -> {
                                opts.preserve = true
                            }

                            'v' -> {
                                opts.verbose = true
                            }

                            'T' -> {
                                opts.noTargetDir = true
                            }

                            'L' -> {
                                opts.followSymlinks = true
                            }

                            'P' -> {
                                opts.followSymlinks = false
                            }

                            'd' -> {
                                opts.followSymlinks = false
                                opts.preserve = true
                            }

                            'a' -> {
                                opts.recursive = true
                                opts.preserve = true
                                opts.followSymlinks = false
                            }

                            't' -> {
                                // -t TARGET — TARGET is next operand or rest of this arg.
                                val rest = chars.substring(j + 1)
                                if (rest.isNotEmpty()) {
                                    opts.targetDir = rest
                                    j = chars.length
                                } else if (i + 1 < args.size) {
                                    opts.targetDir = args[i + 1]
                                    i++
                                } else {
                                    ctx.stderr.writeUtf8("cp: option requires an argument -- 't'\n")
                                    return CommandResult(exitCode = 2)
                                }
                            }

                            else -> {
                                ctx.stderr.writeUtf8("cp: invalid option -- '$ch'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                        j++
                    }
                    i++
                }

                else -> {
                    operands += a
                    i++
                }
            }
        }

        // Determine sources and dest.
        val sources: List<String>
        val destRaw: String
        val destIsExplicitDir: Boolean
        if (opts.targetDir != null) {
            if (operands.isEmpty()) {
                ctx.stderr.writeUtf8("cp: missing file operand\n")
                return CommandResult(exitCode = 1)
            }
            sources = operands.toList()
            destRaw = opts.targetDir!!
            destIsExplicitDir = true
        } else {
            if (operands.size < 2) {
                if (operands.isEmpty()) {
                    ctx.stderr.writeUtf8("cp: missing file operand\n")
                } else {
                    ctx.stderr.writeUtf8("cp: missing destination file operand after '${operands[0]}'\n")
                }
                return CommandResult(exitCode = 1)
            }
            sources = operands.subList(0, operands.size - 1).toList()
            destRaw = operands.last()
            destIsExplicitDir = false
        }

        val destAbs = Paths.resolve(ctx.process.cwd, destRaw)
        val destExists = ctx.process.fs.exists(destAbs)
        val destIsDir = destExists && ctx.process.fs.isDirectory(destAbs)

        if (opts.noTargetDir && destIsDir) {
            ctx.stderr.writeUtf8("cp: cannot overwrite directory '$destRaw' with non-directory\n")
            return CommandResult(exitCode = 1)
        }

        if (destIsExplicitDir) {
            if (!destExists) {
                ctx.stderr.writeUtf8("cp: target directory '$destRaw' does not exist\n")
                return CommandResult(exitCode = 1)
            }
            if (!destIsDir) {
                ctx.stderr.writeUtf8("cp: target '$destRaw' is not a directory\n")
                return CommandResult(exitCode = 1)
            }
        }

        if (sources.size > 1 && !destIsDir) {
            ctx.stderr.writeUtf8("cp: target '$destRaw' is not a directory\n")
            return CommandResult(exitCode = 1)
        }

        var anyError = false
        for (src in sources) {
            val srcAbs = Paths.resolve(ctx.process.cwd, src)
            // Determine effective target absolute path.
            val effectiveDest =
                if (destIsDir && !opts.noTargetDir) {
                    val base = Paths.basename(srcAbs)
                    if (destAbs == "/") "/$base" else "$destAbs/$base"
                } else {
                    destAbs
                }
            try {
                val guard = DirWalkGuard(ctx.process.fs, followSymlinks = opts.followSymlinks)
                if (!copyOne(ctx, opts, src, srcAbs, destRaw, effectiveDest, depth = 0, guard = guard)) {
                    anyError = true
                }
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("cp: cannot copy '$src': ${e.message ?: "I/O error"}\n")
                anyError = true
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    /**
     * Copy a single operand. Returns true on success. Reports per-file
     * errors to stderr.
     */
    private suspend fun copyOne(
        ctx: CommandContext,
        opts: Opts,
        srcDisplay: String,
        srcAbs: String,
        destDisplay: String,
        destAbs: String,
        depth: Int,
        guard: DirWalkGuard,
    ): Boolean {
        // Existence and stat of source.
        val srcStat: FileStat =
            try {
                if (opts.followSymlinks) ctx.process.fs.stat(srcAbs) else ctx.process.fs.statLink(srcAbs)
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("cp: cannot stat '$srcDisplay': No such file or directory\n")
                return false
            } catch (e: SymlinkLoop) {
                ctx.stderr.writeUtf8("cp: cannot stat '$srcDisplay': Too many levels of symbolic links\n")
                return false
            }

        if (srcAbs == destAbs) {
            ctx.stderr.writeUtf8("cp: '$srcDisplay' and '$destDisplay' are the same file\n")
            return false
        }

        when (srcStat.type) {
            FileType.DIRECTORY -> {
                if (!opts.recursive) {
                    ctx.stderr.writeUtf8("cp: -r not specified; omitting directory '$srcDisplay'\n")
                    return false
                }
                // Don't recurse into a followed symlinked dir that would form a
                // cycle (or past the depth backstop). Under -L this is the loop
                // vector; the command-line operand (depth 0) is always copied.
                if (depth > 0 && !guard.shouldDescend(srcAbs, depth)) {
                    return true
                }
                return copyDir(ctx, opts, srcDisplay, srcAbs, destDisplay, destAbs, depth, guard)
            }

            FileType.SYMLINK -> {
                // We only hit this when !followSymlinks (statLink returned SYMLINK).
                return copySymlink(ctx, opts, srcDisplay, srcAbs, destDisplay, destAbs)
            }

            else -> {
                return copyFile(ctx, opts, srcDisplay, srcStat, srcAbs, destDisplay, destAbs)
            }
        }
    }

    private suspend fun copyFile(
        ctx: CommandContext,
        opts: Opts,
        srcDisplay: String,
        srcStat: FileStat,
        srcAbs: String,
        destDisplay: String,
        destAbs: String,
    ): Boolean {
        val destExists = ctx.process.fs.exists(destAbs)
        if (destExists) {
            if (opts.noClobber) {
                return true // skip silently
            }
            if (ctx.process.fs.isDirectory(destAbs)) {
                ctx.stderr.writeUtf8("cp: cannot overwrite directory '$destDisplay' with non-directory\n")
                return false
            }
            if (opts.force) {
                try {
                    ctx.process.fs.remove(destAbs)
                } catch (_: Exception) {
                    // best-effort; fall through to sink which may still succeed
                }
            }
        }
        try {
            val bytes = ctx.process.fs.readBytes(srcAbs)
            // Without `-p`, new files honor umask against the source mode —
            // POSIX cp default. `-p` then overwrites via preserveAttrs.
            val createMode = srcStat.mode and ctx.process.umask.inv()
            ctx.process.fs.writeBytes(destAbs, bytes, mode = createMode)
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("cp: cannot create regular file '$destDisplay': ${e.message ?: "I/O error"}\n")
            return false
        }
        if (opts.preserve) {
            preserveAttrs(ctx, srcStat, destAbs)
        }
        if (opts.verbose) {
            ctx.stdout.writeUtf8("'$srcDisplay' -> '$destDisplay'\n")
        }
        return true
    }

    private suspend fun copySymlink(
        ctx: CommandContext,
        opts: Opts,
        srcDisplay: String,
        srcAbs: String,
        destDisplay: String,
        destAbs: String,
    ): Boolean {
        val target =
            try {
                ctx.process.fs.readSymlink(srcAbs)
            } catch (_: NotASymlink) {
                // Shouldn't happen — statLink said SYMLINK — but degrade safely.
                ctx.stderr.writeUtf8("cp: cannot read link '$srcDisplay'\n")
                return false
            }
        if (ctx.process.fs.exists(destAbs)) {
            if (opts.noClobber) return true
            if (ctx.process.fs.isDirectory(destAbs)) {
                ctx.stderr.writeUtf8("cp: cannot overwrite directory '$destDisplay' with non-directory\n")
                return false
            }
            if (opts.force) {
                try {
                    ctx.process.fs.remove(destAbs)
                } catch (_: Exception) {
                    // ignore
                }
            } else {
                try {
                    ctx.process.fs.remove(destAbs)
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
        try {
            ctx.process.fs.createSymlink(destAbs, target)
        } catch (e: UnsupportedOperationException) {
            ctx.stderr.writeUtf8("cp: cannot create symlink '$destDisplay': ${e.message}\n")
            return false
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("cp: cannot create symlink '$destDisplay': ${e.message ?: "I/O error"}\n")
            return false
        }
        if (opts.verbose) {
            ctx.stdout.writeUtf8("'$srcDisplay' -> '$destDisplay'\n")
        }
        return true
    }

    private suspend fun copyDir(
        ctx: CommandContext,
        opts: Opts,
        srcDisplay: String,
        srcAbs: String,
        destDisplay: String,
        destAbs: String,
        depth: Int,
        guard: DirWalkGuard,
    ): Boolean {
        // Create destination directory if it doesn't exist; if it exists as
        // a file we error.
        if (ctx.process.fs.exists(destAbs)) {
            if (!ctx.process.fs.isDirectory(destAbs)) {
                ctx.stderr.writeUtf8("cp: cannot overwrite non-directory '$destDisplay' with directory '$srcDisplay'\n")
                return false
            }
        } else {
            try {
                ctx.process.fs.mkdirs(destAbs, mode = 0b111_111_111 and ctx.process.umask.inv())
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("cp: cannot create directory '$destDisplay': ${e.message ?: "I/O error"}\n")
                return false
            }
        }
        if (opts.verbose) {
            ctx.stdout.writeUtf8("'$srcDisplay' -> '$destDisplay'\n")
        }

        var ok = true
        val entries: List<FileStat> =
            try {
                ctx.process.fs.listStat(srcAbs)
            } catch (e: SymlinkLoop) {
                ctx.stderr.writeUtf8("cp: cannot read directory '$srcDisplay': Too many levels of symbolic links\n")
                return false
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("cp: cannot read directory '$srcDisplay': ${e.message ?: "I/O error"}\n")
                return false
            }
        val srcPrefix = if (srcAbs == "/") "/" else "$srcAbs/"
        val destPrefix = if (destAbs == "/") "/" else "$destAbs/"
        val displayPrefix = if (srcDisplay.endsWith("/")) srcDisplay else "$srcDisplay/"
        val destDisplayPrefix = if (destDisplay.endsWith("/")) destDisplay else "$destDisplay/"
        for (entry in entries) {
            val name = Paths.basename(entry.path)
            val childSrcAbs = "$srcPrefix$name"
            val childDestAbs = "$destPrefix$name"
            val childSrcDisplay = "$displayPrefix$name"
            val childDestDisplay = "$destDisplayPrefix$name"
            try {
                if (!copyOne(
                        ctx,
                        opts,
                        childSrcDisplay,
                        childSrcAbs,
                        childDestDisplay,
                        childDestAbs,
                        depth + 1,
                        guard,
                    )
                ) {
                    ok = false
                }
            } catch (e: SymlinkLoop) {
                ctx.stderr.writeUtf8("cp: cannot copy '$childSrcDisplay': Too many levels of symbolic links\n")
                ok = false
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("cp: cannot copy '$childSrcDisplay': ${e.message ?: "I/O error"}\n")
                ok = false
            }
        }
        if (opts.preserve) {
            // Preserve mode + mtime on the directory after children land.
            val dirStat =
                try {
                    ctx.process.fs.statLink(srcAbs)
                } catch (_: Exception) {
                    null
                }
            if (dirStat != null) preserveAttrs(ctx, dirStat, destAbs)
        }
        return ok
    }

    private fun preserveAttrs(
        ctx: CommandContext,
        srcStat: FileStat,
        destAbs: String,
    ) {
        try {
            ctx.process.fs.chmod(destAbs, srcStat.mode)
        } catch (_: UnsupportedOperationException) {
            // Backend doesn't model mode bits — silently skip.
        } catch (_: Exception) {
            // Skip — preserve is best-effort.
        }
        try {
            ctx.process.fs.setMtime(destAbs, srcStat.mtimeEpochSeconds)
        } catch (_: UnsupportedOperationException) {
            // Skip.
        } catch (_: Exception) {
            // Skip.
        }
    }
}
