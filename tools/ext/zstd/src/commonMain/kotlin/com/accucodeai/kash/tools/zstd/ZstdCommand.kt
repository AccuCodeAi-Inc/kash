package com.accucodeai.kash.tools.zstd

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
 * `zstd`, `unzstd`, `zstdcat` — compress / decompress with Zstandard.
 *
 * Implemented options (v1):
 *   -c, --stdout          write to stdout, don't change input files
 *   -d, --decompress      decompress (`unzstd` and `zstdcat` default to this)
 *   -k, --keep            keep input files (default for zstd; here for compat)
 *   -f, --force           overwrite existing output files
 *   -t, --test            test integrity of compressed input
 *   -1..-19               compression level (accepted; aircompressor doesn't
 *                         expose levels on the streaming API — value ignored)
 *   -h, --help            print help and exit 0
 *   -V, --version         print version and exit 0
 *   --                    end of options
 *
 * Deferred: `--ultra` / level >19, `--long`, `-o FILE`, `--rm` (unsafe
 * default), `--no-check`, multi-frame concat on decompress, `-r`/`-D`/
 * dictionaries, progress.
 *
 * File semantics: with no operands (or `-`), reads stdin and writes stdout.
 * With file operands and no `-c`/`-t`, compress writes `<file>.zst` next
 * to each input; decompress strips `.zst`/`.tzst` (the latter becomes
 * `.tar`). With `-c`, all output goes to stdout. With `-t`, output is
 * discarded and a one-line OK report is printed per input.
 */
public class ZstdCommand(
    override val name: String,
    private val defaultDecompress: Boolean,
    private val defaultToStdout: Boolean,
) : Command,
    CommandSpec {
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.FS_WRITE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var decompress = defaultDecompress
        var toStdout = defaultToStdout
        var force = false
        var test = false

        @Suppress("UNUSED_VARIABLE")
        var keep = true // zstd default; flag kept for compat
        var level = 3
        var outFile: String? = null
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

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("$name (kash) — zstd via aircompressor\n")
                    return CommandResult()
                }

                a == "-c" || a == "--stdout" || a == "--to-stdout" -> {
                    toStdout = true
                }

                a == "-d" || a == "--decompress" || a == "--uncompress" -> {
                    decompress = true
                }

                a == "-z" || a == "--compress" -> {
                    decompress = false
                }

                a == "-k" || a == "--keep" -> {
                    keep = true
                }

                a == "--rm" -> {
                    ctx.stderr.writeUtf8("$name: --rm not supported\n")
                    return CommandResult(exitCode = 2)
                }

                a == "-f" || a == "--force" -> {
                    force = true
                }

                a == "-t" || a == "--test" -> {
                    test = true
                    decompress = true
                }

                a == "-q" || a == "--quiet" -> {
                    Unit
                }

                // already quiet
                a == "-v" || a == "--verbose" -> {
                    Unit
                }

                // no progress yet
                a.startsWith("-") && a.length > 1 && a.drop(1).all { it.isDigit() } -> {
                    val v = a.drop(1).toIntOrNull()
                    if (v == null || v < 1 || v > 19) {
                        ctx.stderr.writeUtf8("$name: invalid level: '$a' (use -1..-19)\n")
                        return CommandResult(exitCode = 2)
                    }
                    level = v
                }

                a == "--ultra" -> {
                    ctx.stderr.writeUtf8("$name: --ultra not supported\n")
                    return CommandResult(exitCode = 2)
                }

                a.startsWith("--long") -> {
                    ctx.stderr.writeUtf8("$name: --long not supported\n")
                    return CommandResult(exitCode = 2)
                }

                a == "-o" -> {
                    if (++i >= args.size) {
                        ctx.stderr.writeUtf8("$name: -o requires an argument\n")
                        return CommandResult(exitCode = 2)
                    }
                    outFile = args[i]
                }

                // Clustered short flags: -dcf, -cdk, etc.
                a.startsWith("-") && a.length > 1 && !a.startsWith("--") -> {
                    val cluster = a.substring(1)
                    var consumed = true
                    for (ch in cluster) {
                        when (ch) {
                            'c' -> {
                                toStdout = true
                            }

                            'd' -> {
                                decompress = true
                            }

                            'z' -> {
                                decompress = false
                            }

                            'k' -> {
                                keep = true
                            }

                            'f' -> {
                                force = true
                            }

                            't' -> {
                                test = true
                                decompress = true
                            }

                            'q', 'v' -> {
                                Unit
                            }

                            else -> {
                                consumed = false
                                ctx.stderr.writeUtf8("$name: unknown option: -$ch\n")
                            }
                        }
                        if (!consumed) return CommandResult(exitCode = 2)
                    }
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("$name: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        if (!zstdSupported) {
            ctx.stderr.writeUtf8("$name: zstd not available on this platform\n")
            return CommandResult(exitCode = 1)
        }

        if (outFile != null && files.size > 1) {
            ctx.stderr.writeUtf8("$name: -o cannot be used with multiple input files\n")
            return CommandResult(exitCode = 2)
        }

        // stdin → stdout mode (or stdin → -o file)
        if (files.isEmpty() || (files.size == 1 && files[0] == "-")) {
            return if (outFile != null) {
                runStreamToFile(ctx, decompress, level, outFile, force)
            } else {
                runStream(ctx, decompress, test, level)
            }
        }

        // File operand(s)
        var anyError = false
        for (f in files) {
            val rc =
                if (f == "-") {
                    runStream(ctx, decompress, test, level)
                } else if (test) {
                    runTestFile(ctx, f)
                } else if (toStdout) {
                    runFileToStdout(ctx, f, decompress, level)
                } else {
                    runFileToFile(ctx, f, decompress, level, force, outFile)
                }
            if (rc.exitCode != 0) anyError = true
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private suspend fun runStream(
        ctx: CommandContext,
        decompress: Boolean,
        test: Boolean,
        level: Int,
    ): CommandResult {
        val sink: SuspendSink = if (test) NullSuspendSink else ctx.stdout
        return try {
            if (decompress) {
                zstdDecompress(ctx.stdin, sink)
            } else {
                zstdCompress(ctx.stdin, sink, level)
            }
            CommandResult()
        } catch (e: Throwable) {
            ctx.stderr.writeUtf8("$name: ${e.message ?: "error"}\n")
            CommandResult(exitCode = 1)
        }
    }

    private suspend fun runStreamToFile(
        ctx: CommandContext,
        decompress: Boolean,
        level: Int,
        outName: String,
        force: Boolean,
    ): CommandResult {
        val absOut = resolvePath(ctx.cwd, outName)
        if (!force && ctx.fs.exists(absOut)) {
            ctx.stderr.writeUtf8("$name: $outName already exists; use -f to overwrite\n")
            return CommandResult(exitCode = 1)
        }
        val sink =
            try {
                ctx.fs.sink(absOut, append = false)
            } catch (e: Throwable) {
                ctx.stderr.writeUtf8("$name: $outName: ${e.message ?: "cannot open for writing"}\n")
                return CommandResult(exitCode = 1)
            }
        return try {
            if (decompress) {
                zstdDecompress(ctx.stdin, sink)
            } else {
                zstdCompress(ctx.stdin, sink, level)
            }
            CommandResult()
        } catch (e: Throwable) {
            ctx.stderr.writeUtf8("$name: ${e.message ?: "error"}\n")
            CommandResult(exitCode = 1)
        } finally {
            sink.close()
        }
    }

    private suspend fun runTestFile(
        ctx: CommandContext,
        f: String,
    ): CommandResult {
        val abs = resolvePath(ctx.cwd, f)
        return try {
            val src =
                try {
                    ctx.fs.source(abs)
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("$name: $f: No such file or directory\n")
                    return CommandResult(exitCode = 1)
                }
            try {
                zstdDecompress(src, NullSuspendSink)
            } finally {
                src.close()
            }
            ctx.stdout.writeUtf8("$f          : OK\n")
            CommandResult()
        } catch (e: Throwable) {
            ctx.stderr.writeUtf8("$name: $f: ${e.message ?: "corrupt zstd stream"}\n")
            CommandResult(exitCode = 1)
        }
    }

    private suspend fun runFileToStdout(
        ctx: CommandContext,
        f: String,
        decompress: Boolean,
        level: Int,
    ): CommandResult {
        val abs = resolvePath(ctx.cwd, f)
        val src =
            try {
                if (f == "-") ctx.stdin else ctx.fs.source(abs)
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("$name: $f: No such file or directory\n")
                return CommandResult(exitCode = 1)
            }
        return try {
            if (decompress) {
                zstdDecompress(src, ctx.stdout)
            } else {
                zstdCompress(src, ctx.stdout, level)
            }
            CommandResult()
        } catch (e: Throwable) {
            ctx.stderr.writeUtf8("$name: $f: ${e.message ?: "error"}\n")
            CommandResult(exitCode = 1)
        } finally {
            if (f != "-") src.close()
        }
    }

    private suspend fun runFileToFile(
        ctx: CommandContext,
        f: String,
        decompress: Boolean,
        level: Int,
        force: Boolean,
        outOverride: String?,
    ): CommandResult {
        val absIn = resolvePath(ctx.cwd, f)
        val outName =
            outOverride ?: if (decompress) {
                when {
                    f.endsWith(".zst") -> {
                        f.removeSuffix(".zst")
                    }

                    f.endsWith(".tzst") -> {
                        f.removeSuffix(".tzst") + ".tar"
                    }

                    else -> {
                        ctx.stderr.writeUtf8("$name: $f: unknown suffix — ignored\n")
                        return CommandResult(exitCode = 1)
                    }
                }
            } else {
                if (f.endsWith(".zst")) {
                    ctx.stderr.writeUtf8("$name: $f already has .zst suffix — ignored\n")
                    return CommandResult(exitCode = 1)
                }
                "$f.zst"
            }
        val absOut = resolvePath(ctx.cwd, outName)
        if (!force && ctx.fs.exists(absOut)) {
            ctx.stderr.writeUtf8("$name: $outName already exists; use -f to overwrite\n")
            return CommandResult(exitCode = 1)
        }
        val src =
            try {
                ctx.fs.source(absIn)
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("$name: $f: No such file or directory\n")
                return CommandResult(exitCode = 1)
            }
        val sink =
            try {
                ctx.fs.sink(absOut, append = false)
            } catch (e: Throwable) {
                src.close()
                ctx.stderr.writeUtf8("$name: $outName: ${e.message ?: "cannot open for writing"}\n")
                return CommandResult(exitCode = 1)
            }
        return try {
            if (decompress) {
                zstdDecompress(src, sink)
            } else {
                zstdCompress(src, sink, level)
            }
            CommandResult()
        } catch (e: Throwable) {
            ctx.stderr.writeUtf8("$name: $f: ${e.message ?: "error"}\n")
            CommandResult(exitCode = 1)
        } finally {
            try {
                src.close()
            } catch (_: Throwable) {
            }
            try {
                sink.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolvePath(
        cwd: String,
        path: String,
    ): String = if (path.startsWith("/")) path else "$cwd/$path"

    private companion object {
        const val HELP =
            """Usage: zstd [OPTION]... [FILE]...
Compress or decompress FILEs (default: compress, write FILE.zst).

  -c, --stdout       write to stdout
  -d, --decompress   decompress
  -t, --test         test compressed file integrity
  -k, --keep         keep input files (default)
  -f, --force        overwrite output files
  -1..-19            compression level (accepted; level ignored — backend
                     uses its default)
  -h, --help         show this help
  -V, --version      show version
"""
    }
}
