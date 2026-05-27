package com.accucodeai.kash.tools.lz4

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

public enum class Lz4Mode {
    COMPRESS,
    DECOMPRESS,
    DECOMPRESS_TO_STDOUT,
}

/**
 * `lz4` / `unlz4` / `lz4cat` — LZ4 frame-format (de)compression backed by
 * Apache Commons Compress (`FramedLZ4Compressor*`).
 *
 * Flags (v1, subset of upstream `lz4`):
 *  -c / --stdout     write to stdout, keep input files
 *  -d / --decompress force-decompress (overrides invocation default)
 *  -z / --compress   force-compress
 *  -k / --keep       keep input files after operation
 *  -f / --force      overwrite existing outputs / allow .lz4-suffixed input
 *  -t / --test       test integrity (decode, discard output)
 *  -1..-12           compression level accepted (codec ignores — commons-compress
 *                    framed-LZ4 doesn't expose level knobs in this artifact)
 *  -q / -v           quiet / verbose, accepted but treated identically
 *  --                end of options
 *  --help / --version
 *
 * File operands: empty (or `-`) → stdin → stdout (-c implied).
 *   Compress: write `<file>.lz4`, remove original unless -k.
 *   Decompress: strip `.lz4` suffix, remove input unless -k.
 *   -c: emit to stdout, leave originals.
 *   -t: validate each file, write nothing.
 */
public class Lz4Command(
    override val name: String,
    private val defaultMode: Lz4Mode,
) : Command,
    CommandSpec {
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.FS_WRITE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var mode: Lz4Mode = defaultMode
        var toStdout = defaultMode == Lz4Mode.DECOMPRESS_TO_STDOUT
        var keep = false
        var force = false
        var test = false
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
                    mode = Lz4Mode.DECOMPRESS
                }

                a == "--compress" -> {
                    mode = Lz4Mode.COMPRESS
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

                // Numeric level flags: -1..-9, -10, -11, -12 — accepted, ignored.
                a.length >= 2 && a[0] == '-' && a.substring(1).all { it.isDigit() } &&
                    a.substring(1).toIntOrNull() in 1..12 -> {
                    Unit
                }

                a.startsWith("-") && a.length > 1 -> {
                    for (idx in 1 until a.length) {
                        when (val c = a[idx]) {
                            'c' -> {
                                toStdout = true
                            }

                            'd' -> {
                                mode = Lz4Mode.DECOMPRESS
                            }

                            'z' -> {
                                mode = Lz4Mode.COMPRESS
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

                            'h' -> {
                                ctx.stdout.writeUtf8(helpText(name))
                                return CommandResult()
                            }

                            'V' -> {
                                ctx.stdout.writeUtf8("kash $name 1.0 (commons-compress backend)\n")
                                return CommandResult()
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

        if (test) mode = Lz4Mode.DECOMPRESS

        if (files.isEmpty() || (files.size == 1 && files[0] == "-")) {
            return runStream(mode, test, ctx)
        }

        var hadError = false
        for (path in files) {
            val ok = runFile(path, mode, toStdout, keep, force, test, ctx)
            if (!ok) hadError = true
        }
        return CommandResult(exitCode = if (hadError) 1 else 0)
    }

    private suspend fun runStream(
        mode: Lz4Mode,
        test: Boolean,
        ctx: CommandContext,
    ): CommandResult =
        try {
            when (mode) {
                Lz4Mode.COMPRESS -> {
                    lz4Compress(ctx.stdin, ctx.stdout)
                    CommandResult()
                }

                Lz4Mode.DECOMPRESS, Lz4Mode.DECOMPRESS_TO_STDOUT -> {
                    if (test) {
                        lz4Test(ctx.stdin)
                    } else {
                        lz4Decompress(ctx.stdin, ctx.stdout)
                    }
                    CommandResult()
                }
            }
        } catch (e: Lz4FormatException) {
            ctx.stderr.writeUtf8("$name: ${e.message}\n")
            CommandResult(exitCode = 2)
        }

    private suspend fun runFile(
        rawPath: String,
        mode: Lz4Mode,
        toStdout: Boolean,
        keep: Boolean,
        force: Boolean,
        test: Boolean,
        ctx: CommandContext,
    ): Boolean {
        val abs = Paths.resolve(ctx.cwd, rawPath)
        if (!ctx.fs.exists(abs)) {
            ctx.stderr.writeUtf8("$name: $rawPath: No such file or directory\n")
            return false
        }
        if (ctx.fs.isDirectory(abs)) {
            ctx.stderr.writeUtf8("$name: $rawPath: Is a directory -- ignored\n")
            return false
        }

        val outPath: String?
        val outSink: SuspendSink
        var closeOutSink = false
        when {
            test -> {
                outPath = null
                outSink = ctx.stdout
            }

            toStdout -> {
                outPath = null
                outSink = ctx.stdout
            }

            mode == Lz4Mode.COMPRESS -> {
                if (rawPath.endsWith(".lz4") && !force) {
                    ctx.stderr.writeUtf8("$name: $rawPath: Input file already has .lz4 suffix.\n")
                    return false
                }
                val target = "$abs.lz4"
                if (ctx.fs.exists(target) && !force) {
                    ctx.stderr.writeUtf8("$name: Output file $target already exists.\n")
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
                val target =
                    if (abs.endsWith(".lz4")) {
                        abs.removeSuffix(".lz4")
                    } else {
                        ctx.stderr.writeUtf8("$name: $rawPath: unknown suffix -- using $rawPath.out\n")
                        "$abs.out"
                    }
                if (ctx.fs.exists(target) && !force) {
                    ctx.stderr.writeUtf8("$name: Output file $target already exists.\n")
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
                Lz4Mode.COMPRESS -> {
                    lz4Compress(src, outSink)
                }

                Lz4Mode.DECOMPRESS, Lz4Mode.DECOMPRESS_TO_STDOUT -> {
                    if (test) {
                        lz4Test(src)
                    } else {
                        lz4Decompress(src, outSink)
                    }
                }
            }
        } catch (e: Lz4FormatException) {
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

        if (ok && !keep && !test && !toStdout) {
            try {
                ctx.fs.remove(abs)
            } catch (_: Exception) {
            }
        }
        if (!ok && outPath != null) {
            try {
                ctx.fs.remove(outPath)
            } catch (_: Throwable) {
            }
        }
        return ok
    }

    private fun helpText(toolName: String): String =
        """
        Usage: $toolName [OPTION]... [FILE]...
        Compress or decompress FILEs using the lz4 frame format.

          -c, --stdout       write to stdout and leave the input files in place
          -d, --decompress   decompress instead of compress
          -z, --compress     force compression
          -k, --keep         do not remove the input files
          -f, --force        overwrite existing output files
          -t, --test         check a compressed file's integrity
          -q, --quiet        suppress non-critical messages
          -v, --verbose      verbose output (no-op)
          -1 .. -12          accept a compression level (codec uses its default)
          -h, --help         show this help and exit
              --version      show version information and exit
        """.trimIndent() + "\n"
}
