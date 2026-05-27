package com.accucodeai.kash.tools.bzip2

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths

/**
 * Default mode for the entrypoint. Mirrors how real bzip2 forks behave when
 * invoked by alternative names:
 *  - `bzip2`   → compress; -d switches to decompress.
 *  - `bunzip2` → decompress (equivalent to `bzip2 -d`); -z switches back.
 *  - `bzcat`   → decompress to stdout (equivalent to `bzip2 -dc`).
 */
public enum class Bzip2Mode {
    COMPRESS,
    DECOMPRESS,
    DECOMPRESS_TO_STDOUT,
}

/**
 * `bzip2` / `bunzip2` / `bzcat` — bzip2 (de)compression backed by Apache
 * Commons Compress.
 *
 * Flags (v1):
 *  -c  write to stdout, do not modify files
 *  -d  decompress (force-decompress; `bunzip2` defaults here)
 *  -z  compress (force-compress; overrides invocation default)
 *  -k  keep input files (do not unlink after compress/decompress)
 *  -f  force: overwrite existing output; allow operating on dirs/links
 *      we keep this as "overwrite output if it exists" — kash FS doesn't
 *      model dir/link permission denial in the bzip2 sense.
 *  -t  test integrity: decode but throw away the output
 *  -1..-9  block size for compression (default 9 = 900 KiB)
 *  -q / -v  quiet / verbose — accepted, output is plain regardless
 *  --   end of options
 *
 * File operands:
 *  - With no operand (or `-`): stdin → stdout (-c implied).
 *  - With file operands: each file is processed independently.
 *    Compress: writes `<file>.bz2`, removes `<file>` unless -k.
 *    Decompress: writes `<base>` (strips `.bz2` / `.bz` / `.tbz2`→`.tar`
 *    / `.tbz`→`.tar`), removes `<file>.bz2` unless -k.
 *    With -c: result is concatenated to stdout, originals untouched.
 *    With -t: decompresses each, validates, writes nothing.
 */
public class Bzip2Command(
    override val name: String,
    private val defaultMode: Bzip2Mode,
) : Command,
    CommandSpec {
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.FS_WRITE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var mode: Bzip2Mode = defaultMode
        var toStdout = defaultMode == Bzip2Mode.DECOMPRESS_TO_STDOUT
        var keep = false
        var force = false
        var test = false
        var blockSize = 9
        val files = mutableListOf<String>()

        var i = 0
        var endOfOpts = false
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                files += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-" -> {
                    files += a
                }

                a == "--stdout" -> {
                    toStdout = true
                }

                a == "--decompress" || a == "--uncompress" -> {
                    mode = Bzip2Mode.DECOMPRESS
                }

                a == "--compress" -> {
                    mode = Bzip2Mode.COMPRESS
                }

                a == "--keep" -> {
                    keep = true
                }

                a == "--force" -> {
                    force = true
                }

                a == "--test" -> {
                    test = true
                }

                a == "--quiet" || a == "--verbose" -> {
                    Unit
                }

                a == "--small" || a == "-s" -> {
                    Unit
                }

                // accepted, not modeled
                a == "--help" -> {
                    ctx.stdout.writeUtf8(helpText(name))
                    return CommandResult()
                }

                a == "--version" -> {
                    ctx.stdout.writeUtf8("kash $name 1.0 (commons-compress backend)\n")
                    return CommandResult()
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("$name: unrecognized option '$a'\n")
                    return CommandResult(exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Bundled short options: -dkf9 etc.
                    for (idx in 1 until a.length) {
                        when (val c = a[idx]) {
                            'c' -> {
                                toStdout = true
                            }

                            'd' -> {
                                mode = Bzip2Mode.DECOMPRESS
                            }

                            'z' -> {
                                mode = Bzip2Mode.COMPRESS
                            }

                            'k' -> {
                                keep = true
                            }

                            'f' -> {
                                force = true
                            }

                            't' -> {
                                test = true
                            }

                            'q', 'v' -> {
                                Unit
                            }

                            's' -> {
                                Unit
                            }

                            'L' -> {
                                ctx.stdout.writeUtf8(licenseText())
                                return CommandResult()
                            }

                            'V' -> {
                                ctx.stdout.writeUtf8("kash $name 1.0 (commons-compress backend)\n")
                                return CommandResult()
                            }

                            'h' -> {
                                ctx.stdout.writeUtf8(helpText(name))
                                return CommandResult()
                            }

                            in '1'..'9' -> {
                                blockSize = c - '0'
                            }

                            else -> {
                                ctx.stderr.writeUtf8("$name: unknown option '-$c'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                    }
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        // -t implies decompress-and-discard. Doesn't change toStdout (no output
        // produced anyway), but downstream code branches on `test`.
        if (test) mode = Bzip2Mode.DECOMPRESS

        // No file operands or only `-` -> stdin/stdout. -c implicit.
        if (files.isEmpty() || (files.size == 1 && files[0] == "-")) {
            return runStream(mode, test, blockSize, ctx)
        }

        // File mode.
        var hadError = false
        for (path in files) {
            val ok = runFile(path, mode, toStdout, keep, force, test, blockSize, ctx)
            if (!ok) hadError = true
        }
        return CommandResult(exitCode = if (hadError) 1 else 0)
    }

    private suspend fun runStream(
        mode: Bzip2Mode,
        test: Boolean,
        blockSize: Int,
        ctx: CommandContext,
    ): CommandResult {
        return try {
            when (mode) {
                Bzip2Mode.COMPRESS -> {
                    if (blockSize !in 1..9) {
                        ctx.stderr.writeUtf8("$name: invalid block size $blockSize (must be 1..9)\n")
                        return CommandResult(exitCode = 2)
                    }
                    bzip2Compress(ctx.stdin, ctx.stdout, blockSize)
                    CommandResult()
                }

                Bzip2Mode.DECOMPRESS, Bzip2Mode.DECOMPRESS_TO_STDOUT -> {
                    if (test) {
                        bzip2Test(ctx.stdin)
                        CommandResult()
                    } else {
                        bzip2Decompress(ctx.stdin, ctx.stdout)
                        CommandResult()
                    }
                }
            }
        } catch (e: Bzip2FormatException) {
            ctx.stderr.writeUtf8("$name: ${e.message}\n")
            CommandResult(exitCode = 2)
        }
    }

    private suspend fun runFile(
        rawPath: String,
        mode: Bzip2Mode,
        toStdout: Boolean,
        keep: Boolean,
        force: Boolean,
        test: Boolean,
        blockSize: Int,
        ctx: CommandContext,
    ): Boolean {
        val abs = Paths.resolve(ctx.cwd, rawPath)

        // Pre-checks.
        if (!ctx.fs.exists(abs)) {
            ctx.stderr.writeUtf8("$name: $rawPath: No such file or directory\n")
            return false
        }
        if (ctx.fs.isDirectory(abs)) {
            ctx.stderr.writeUtf8("$name: $rawPath: Is a directory -- ignored\n")
            return false
        }

        // Determine output path / sink.
        val outPath: String?
        val outSink: SuspendSink
        var closeOutSink = false
        when {
            test -> {
                outPath = null
                outSink = ctx.stdout // unused
            }

            toStdout -> {
                outPath = null
                outSink = ctx.stdout
            }

            mode == Bzip2Mode.COMPRESS -> {
                if (rawPath.endsWith(".bz2") || rawPath.endsWith(".tbz2") ||
                    rawPath.endsWith(".tbz") || rawPath.endsWith(".bz")
                ) {
                    if (!force) {
                        ctx.stderr.writeUtf8(
                            "$name: $rawPath: Input file already has .bz2 suffix.\n",
                        )
                        return false
                    }
                }
                val target = "$abs.bz2"
                if (ctx.fs.exists(target) && !force) {
                    ctx.stderr.writeUtf8(
                        "$name: Output file $target already exists.\n",
                    )
                    return false
                }
                outPath = target
                outSink =
                    try {
                        ctx.fs.sink(target, append = false)
                    } catch (e: Exception) {
                        ctx.stderr.writeUtf8("$name: cannot write $target: ${e.message ?: "I/O error"}\n")
                        return false
                    }
                closeOutSink = true
            }

            else -> {
                // Decompress.
                val target =
                    decompressTarget(abs) ?: run {
                        ctx.stderr.writeUtf8(
                            "$name: $rawPath: Can't guess original name -- using $rawPath.out\n",
                        )
                        "$abs.out"
                    }
                if (ctx.fs.exists(target) && !force) {
                    ctx.stderr.writeUtf8(
                        "$name: Output file $target already exists.\n",
                    )
                    return false
                }
                outPath = target
                outSink =
                    try {
                        ctx.fs.sink(target, append = false)
                    } catch (e: Exception) {
                        ctx.stderr.writeUtf8("$name: cannot write $target: ${e.message ?: "I/O error"}\n")
                        return false
                    }
                closeOutSink = true
            }
        }

        val src: SuspendSource =
            try {
                ctx.fs.source(abs)
            } catch (_: FileNotFound) {
                if (closeOutSink) outSink.close()
                ctx.stderr.writeUtf8("$name: $rawPath: No such file or directory\n")
                return false
            }

        var ok = true
        try {
            when (mode) {
                Bzip2Mode.COMPRESS -> {
                    if (blockSize !in 1..9) {
                        ctx.stderr.writeUtf8("$name: invalid block size $blockSize (must be 1..9)\n")
                        return false
                    }
                    bzip2Compress(src, outSink, blockSize)
                }

                Bzip2Mode.DECOMPRESS, Bzip2Mode.DECOMPRESS_TO_STDOUT -> {
                    if (test) {
                        bzip2Test(src)
                    } else {
                        bzip2Decompress(src, outSink)
                    }
                }
            }
        } catch (e: Bzip2FormatException) {
            ctx.stderr.writeUtf8("$name: $rawPath: ${e.message}\n")
            ok = false
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("$name: $rawPath: ${e.message ?: "I/O error"}\n")
            ok = false
        } finally {
            try {
                src.close()
            } catch (_: Throwable) {
            }
            if (closeOutSink) {
                try {
                    outSink.close()
                } catch (_: Throwable) {
                }
            }
        }

        // Cleanup: remove original unless -k / -t / -c, and only on success.
        if (ok && !keep && !test && !toStdout) {
            try {
                ctx.fs.remove(abs)
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("$name: $rawPath: could not unlink: ${e.message ?: "I/O error"}\n")
                // not strictly fatal; bzip2 prints warning but exit 0
            }
        }
        // If we wrote a partial output file on failure, try to remove it.
        if (!ok && outPath != null) {
            try {
                ctx.fs.remove(outPath)
            } catch (_: Throwable) {
            }
        }
        return ok
    }

    private fun decompressTarget(absPath: String): String? =
        when {
            absPath.endsWith(".bz2") -> absPath.removeSuffix(".bz2")
            absPath.endsWith(".bz") -> absPath.removeSuffix(".bz")
            absPath.endsWith(".tbz2") -> absPath.removeSuffix(".tbz2") + ".tar"
            absPath.endsWith(".tbz") -> absPath.removeSuffix(".tbz") + ".tar"
            else -> null
        }

    private fun helpText(toolName: String): String =
        """
        Usage: $toolName [OPTION]... [FILE]...
        Compress or decompress FILEs in the bzip2 format.

          -c, --stdout       write on standard output, keep original files
          -d, --decompress   decompress
          -z, --compress     force compression
          -k, --keep         keep (don't delete) input files
          -f, --force        overwrite existing output files
          -t, --test         test compressed file integrity
          -q, --quiet        suppress noncritical error messages
          -v, --verbose      be verbose (no-op)
          -1 .. -9           set block size to 100k .. 900k
          -h, --help         display this help and exit
              --version      output version information and exit
        """.trimIndent() + "\n"

    private fun licenseText(): String = "bzip2 in kash uses Apache Commons Compress (Apache-2.0).\n"
}
