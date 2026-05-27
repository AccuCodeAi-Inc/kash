package com.accucodeai.kash.tools.xz

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.NullSuspendSink
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound

/**
 * Shared back-end for the six tools `xz`, `unxz`, `xzcat`, `lzma`, `unlzma`,
 * `lzcat`. Each spec sets defaults (decode flag, format, default-to-stdout)
 * and shares the same flag parser and file-handling pipeline.
 *
 * v1 flag set: `-c` stdout, `-d` decompress, `-z` compress, `-k` keep,
 * `-0..-9` preset, `-f` force, `-t` test, `-q` quiet, `-v` verbose (no-op),
 * `-e` extreme (no-op marker), `--` end-of-options.
 *
 * File handling: when given file operands the tool writes `<name>.xz`
 * (or `.lzma`) next to each input on compress, or strips that suffix on
 * decompress. The original is removed unless `-k` was given. With no file
 * operands, or with `-`, the tool reads stdin → writes stdout.
 */
public class XzCommand internal constructor(
    override val name: String,
    private val defaultDecode: Boolean,
    private val defaultStdout: Boolean,
    private val format: XzFormat,
) : Command,
    CommandSpec {
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE, CommandTag.FS_WRITE)
    override val command: Command get() = this

    private val suffix: String = if (format == XzFormat.XZ) ".xz" else ".lzma"

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var decode = defaultDecode
        var toStdout = defaultStdout
        var keep = defaultStdout // -c implies keeping originals
        var force = false
        var test = false
        var preset = 6
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

                a == "--help" -> {
                    ctx.stdout.writeUtf8("$name: compress / decompress files (kash built-in)\n")
                    return CommandResult()
                }

                a == "--decompress" || a == "--uncompress" -> {
                    decode = true
                }

                a == "--compress" -> {
                    decode = false
                }

                a == "--stdout" || a == "--to-stdout" -> {
                    toStdout = true
                    keep = true
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

                a == "--quiet" || a == "--verbose" || a == "--extreme" -> {
                    Unit
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("$name: unrecognized option '$a'\n")
                    return CommandResult(exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Short option cluster: -dckf, -9e, etc.
                    var j = 1
                    while (j < a.length) {
                        when (val c = a[j]) {
                            'd' -> {
                                decode = true
                            }

                            'z' -> {
                                decode = false
                            }

                            'c' -> {
                                toStdout = true
                                keep = true
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

                            'q', 'v', 'e' -> {
                                Unit
                            }

                            in '0'..'9' -> {
                                preset = c - '0'
                            }

                            else -> {
                                ctx.stderr.writeUtf8("$name: unrecognized option '-$c'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                        j++
                    }
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        // --- Pipeline mode (stdin → stdout) ---
        if (files.isEmpty() || (files.size == 1 && files[0] == "-")) {
            return try {
                if (test) {
                    runCodec(ctx.stdin, NullSuspendSink, decode = true)
                } else if (decode) {
                    runCodec(ctx.stdin, ctx.stdout, decode = true)
                } else {
                    runCodec(ctx.stdin, ctx.stdout, decode = false, preset = preset)
                }
                CommandResult()
            } catch (e: IllegalArgumentException) {
                ctx.stderr.writeUtf8("$name: (stdin): ${e.message ?: "corrupt input"}\n")
                CommandResult(exitCode = 1)
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("$name: (stdin): ${e.message ?: e::class.simpleName}\n")
                CommandResult(exitCode = 1)
            }
        }

        // --- File mode ---
        var anyError = false
        for (file in files) {
            if (file == "-") {
                // mixed stdin within a file list — handle as pipe-to-stdout segment
                try {
                    if (test) {
                        runCodec(ctx.stdin, NullSuspendSink, decode = true)
                    } else if (decode) {
                        runCodec(ctx.stdin, ctx.stdout, decode = true)
                    } else {
                        runCodec(ctx.stdin, ctx.stdout, decode = false, preset = preset)
                    }
                } catch (e: Exception) {
                    ctx.stderr.writeUtf8("$name: (stdin): ${e.message ?: e::class.simpleName}\n")
                    anyError = true
                }
                continue
            }
            val inputPath = resolvePath(ctx.cwd, file)
            if (!ctx.fs.exists(inputPath)) {
                ctx.stderr.writeUtf8("$name: $file: No such file or directory\n")
                anyError = true
                continue
            }
            if (ctx.fs.isDirectory(inputPath)) {
                ctx.stderr.writeUtf8("$name: $file: Is a directory -- ignored\n")
                anyError = true
                continue
            }

            // Determine output path
            val outputPath: String? =
                when {
                    test -> {
                        null
                    }

                    toStdout -> {
                        null
                    }

                    decode -> {
                        if (!file.endsWith(suffix)) {
                            ctx.stderr.writeUtf8("$name: $file: Unknown suffix -- ignored\n")
                            anyError = true
                            continue
                        }
                        resolvePath(ctx.cwd, file.removeSuffix(suffix))
                    }

                    else -> {
                        resolvePath(ctx.cwd, "$file$suffix")
                    }
                }

            if (outputPath != null && !force && ctx.fs.exists(outputPath)) {
                ctx.stderr.writeUtf8("$name: ${stripCwd(outputPath, ctx.cwd)}: File exists\n")
                anyError = true
                continue
            }

            try {
                val src: SuspendSource =
                    try {
                        ctx.fs.source(inputPath)
                    } catch (_: FileNotFound) {
                        ctx.stderr.writeUtf8("$name: $file: No such file or directory\n")
                        anyError = true
                        continue
                    }
                val dst: SuspendSink =
                    when {
                        test -> NullSuspendSink
                        toStdout -> ctx.stdout
                        else -> ctx.fs.sink(outputPath!!, append = false, mode = 0b110_100_100)
                    }
                try {
                    if (decode || test) {
                        runCodec(src, dst, decode = true)
                    } else {
                        runCodec(src, dst, decode = false, preset = preset)
                    }
                } finally {
                    src.close()
                    if (dst !== ctx.stdout && dst !== NullSuspendSink) {
                        try {
                            dst.close()
                        } catch (_: Throwable) {
                        }
                    }
                }

                // Remove original unless we're in stdout/test/keep mode
                if (!test && !toStdout && !keep) {
                    try {
                        ctx.fs.remove(inputPath)
                    } catch (_: Throwable) {
                        // best-effort; not fatal
                    }
                }
            } catch (e: IllegalArgumentException) {
                ctx.stderr.writeUtf8("$name: $file: ${e.message ?: "corrupt input"}\n")
                anyError = true
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("$name: $file: ${e.message ?: e::class.simpleName}\n")
                anyError = true
            }
        }

        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private suspend fun runCodec(
        src: SuspendSource,
        dst: SuspendSink,
        decode: Boolean,
        preset: Int = 6,
    ) {
        if (decode) {
            xzDecompress(src, dst, format)
        } else {
            xzCompress(src, dst, format, preset)
        }
    }

    private fun resolvePath(
        cwd: String,
        path: String,
    ): String = if (path.startsWith("/")) path else "$cwd/$path"

    private fun stripCwd(
        path: String,
        cwd: String,
    ): String =
        if (path.startsWith("$cwd/")) {
            path.substring(cwd.length + 1)
        } else {
            path
        }
}
