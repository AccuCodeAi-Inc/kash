package com.accucodeai.kash.tools.gzip

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
import com.accucodeai.kash.fs.FileSystem

internal enum class Mode { COMPRESS, DECOMPRESS, TEST }

private fun resolvePath(
    cwd: String,
    path: String,
): String = if (path.startsWith("/")) path else "$cwd/$path"

/** Strip basename's `.gz`/`.tgz`/`.taz`/`.Z` suffix when decompressing. */
private fun stripGzSuffix(path: String): String? {
    val candidates = listOf(".gz", ".z", ".Z", ".tgz" to ".tar", ".taz" to ".tar")
    if (path.endsWith(".gz")) return path.dropLast(3)
    if (path.endsWith(".z")) return path.dropLast(2)
    if (path.endsWith(".Z")) return path.dropLast(2)
    if (path.endsWith(".tgz")) return path.dropLast(4) + ".tar"
    if (path.endsWith(".taz")) return path.dropLast(4) + ".tar"
    return null
}

private fun basename(path: String): String {
    val i = path.lastIndexOf('/')
    return if (i >= 0) path.substring(i + 1) else path
}

internal class GzipImpl(
    override val name: String,
    private val defaultMode: Mode,
) : Command,
    CommandSpec {
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var mode: Mode = defaultMode
        var stdout = false
        var keep = false
        var force = false
        var noName = false
        var level = 6
        val files = mutableListOf<String>()

        // zcat is forced -dc behavior.
        if (name == "zcat") {
            mode = Mode.DECOMPRESS
            stdout = true
        }

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

                a == "-c" || a == "--stdout" || a == "--to-stdout" -> {
                    stdout = true
                }

                a == "-d" || a == "--decompress" || a == "--uncompress" -> {
                    mode = Mode.DECOMPRESS
                }

                a == "-k" || a == "--keep" -> {
                    keep = true
                }

                a == "-f" || a == "--force" -> {
                    force = true
                }

                a == "-n" || a == "--no-name" -> {
                    noName = true
                }

                a == "-t" || a == "--test" -> {
                    mode = Mode.TEST
                }

                a == "-r" || a == "--recursive" -> {
                    ctx.stderr.writeUtf8("$name: -r/--recursive: not implemented\n")
                    return CommandResult(exitCode = 2)
                }

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8(helpText())
                    return CommandResult(exitCode = 0)
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("$name (kash) 1.0\n")
                    return CommandResult(exitCode = 0)
                }

                a.length == 2 && a[0] == '-' && a[1] in '1'..'9' -> {
                    level = a[1].digitToInt()
                }

                a == "--fast" -> {
                    level = 1
                }

                a == "--best" -> {
                    level = 9
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("$name: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Cluster: parse char by char.
                    var k = 1
                    while (k < a.length) {
                        when (val c = a[k]) {
                            'c' -> {
                                stdout = true
                            }

                            'd' -> {
                                mode = Mode.DECOMPRESS
                            }

                            'k' -> {
                                keep = true
                            }

                            'f' -> {
                                force = true
                            }

                            'n' -> {
                                noName = true
                            }

                            't' -> {
                                mode = Mode.TEST
                            }

                            'h' -> {
                                ctx.stdout.writeUtf8(helpText())
                                return CommandResult(exitCode = 0)
                            }

                            in '1'..'9' -> {
                                level = c.digitToInt()
                            }

                            else -> {
                                ctx.stderr.writeUtf8("$name: unknown option: -$c\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                        k++
                    }
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        // stdin/stdout mode: no files, or any file is "-", or -c.
        val useStdio = files.isEmpty() || (files.size == 1 && files[0] == "-")
        if (useStdio) {
            return runStdio(mode, level, noName, ctx)
        }

        // File mode.
        if (mode == Mode.TEST) {
            return runTestFiles(files, ctx)
        }

        var anyError = false
        for (f in files) {
            if (f == "-") {
                // mixed stdin in among files: process inline
                val rc = runStdio(mode, level, noName, ctx)
                if (rc.exitCode != 0) anyError = true
                continue
            }
            val rc = runOneFile(f, mode, level, stdout, keep, force, noName, ctx)
            if (rc != 0) anyError = true
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private suspend fun runStdio(
        mode: Mode,
        level: Int,
        noName: Boolean,
        ctx: CommandContext,
    ): CommandResult =
        when (mode) {
            Mode.COMPRESS -> {
                try {
                    gzipCompress(ctx.stdin, ctx.stdout, level, storeName = null, storeMtime = 0L)
                    CommandResult(0)
                } catch (e: Throwable) {
                    ctx.stderr.writeUtf8("$name: ${e.message ?: "compress failed"}\n")
                    CommandResult(1)
                }
            }

            Mode.DECOMPRESS -> {
                try {
                    gzipDecompress(ctx.stdin, ctx.stdout)
                    CommandResult(0)
                } catch (e: Throwable) {
                    ctx.stderr.writeUtf8("$name: ${e.message ?: "decompress failed"}\n")
                    CommandResult(1)
                }
            }

            Mode.TEST -> {
                try {
                    gzipTest(ctx.stdin)
                    CommandResult(0)
                } catch (e: Throwable) {
                    ctx.stderr.writeUtf8("$name: stdin: ${e.message ?: "invalid gzip"}\n")
                    CommandResult(1)
                }
            }
        }

    private suspend fun runTestFiles(
        files: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var anyError = false
        for (f in files) {
            if (f == "-") {
                val rc =
                    try {
                        gzipTest(ctx.stdin)
                        0
                    } catch (e: Throwable) {
                        ctx.stderr.writeUtf8("$name: stdin: ${e.message ?: "invalid gzip"}\n")
                        1
                    }
                if (rc != 0) anyError = true
                continue
            }
            val path = resolvePath(ctx.cwd, f)
            val src: SuspendSource =
                try {
                    ctx.fs.source(path)
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("$name: $f: No such file or directory\n")
                    anyError = true
                    continue
                }
            try {
                gzipTest(src)
            } catch (e: Throwable) {
                ctx.stderr.writeUtf8("$name: $f: ${e.message ?: "invalid gzip"}\n")
                anyError = true
            } finally {
                src.close()
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private suspend fun runOneFile(
        operand: String,
        mode: Mode,
        level: Int,
        stdout: Boolean,
        keep: Boolean,
        force: Boolean,
        noName: Boolean,
        ctx: CommandContext,
    ): Int {
        val path = resolvePath(ctx.cwd, operand)
        val fs: FileSystem = ctx.fs

        val src: SuspendSource =
            try {
                fs.source(path)
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("$name: $operand: No such file or directory\n")
                return 1
            }

        when (mode) {
            Mode.COMPRESS -> {
                if (stdout) {
                    return try {
                        gzipCompress(
                            src,
                            ctx.stdout,
                            level,
                            storeName = if (noName) null else basename(path),
                            storeMtime = if (noName) 0L else mtimeOrZero(fs, path),
                        )
                        0
                    } catch (e: Throwable) {
                        ctx.stderr.writeUtf8("$name: ${e.message ?: "compress failed"}\n")
                        1
                    } finally {
                        src.close()
                    }
                }
                // refuse double-compress unless forced
                if (path.endsWith(".gz") && !force) {
                    ctx.stderr.writeUtf8("$name: $operand already has .gz suffix -- unchanged\n")
                    src.close()
                    return 1
                }
                val outPath = "$path.gz"
                if (fs.exists(outPath) && !force) {
                    ctx.stderr.writeUtf8("$name: $outPath already exists; use -f to overwrite\n")
                    src.close()
                    return 1
                }
                val sink: SuspendSink =
                    try {
                        fs.sink(outPath)
                    } catch (e: Throwable) {
                        ctx.stderr.writeUtf8("$name: $outPath: ${e.message ?: "cannot create"}\n")
                        src.close()
                        return 1
                    }
                try {
                    gzipCompress(
                        src,
                        sink,
                        level,
                        storeName = if (noName) null else basename(path),
                        storeMtime = if (noName) 0L else mtimeOrZero(fs, path),
                    )
                } catch (e: Throwable) {
                    ctx.stderr.writeUtf8("$name: ${e.message ?: "compress failed"}\n")
                    src.close()
                    sink.close()
                    return 1
                }
                src.close()
                sink.close()
                if (!keep) {
                    runCatching { fs.remove(path) }
                }
                return 0
            }

            Mode.DECOMPRESS -> {
                if (stdout) {
                    return try {
                        gzipDecompress(src, ctx.stdout)
                        0
                    } catch (e: Throwable) {
                        ctx.stderr.writeUtf8("$name: $operand: ${e.message ?: "decompress failed"}\n")
                        1
                    } finally {
                        src.close()
                    }
                }
                val outPath =
                    stripGzSuffix(path) ?: run {
                        ctx.stderr.writeUtf8("$name: $operand: unknown suffix -- ignored\n")
                        src.close()
                        return 1
                    }
                if (fs.exists(outPath) && !force) {
                    ctx.stderr.writeUtf8("$name: $outPath already exists; use -f to overwrite\n")
                    src.close()
                    return 1
                }
                val sink: SuspendSink =
                    try {
                        fs.sink(outPath)
                    } catch (e: Throwable) {
                        ctx.stderr.writeUtf8("$name: $outPath: ${e.message ?: "cannot create"}\n")
                        src.close()
                        return 1
                    }
                try {
                    gzipDecompress(src, sink)
                } catch (e: Throwable) {
                    ctx.stderr.writeUtf8("$name: $operand: ${e.message ?: "decompress failed"}\n")
                    src.close()
                    sink.close()
                    return 1
                }
                src.close()
                sink.close()
                if (!keep) {
                    runCatching { fs.remove(path) }
                }
                return 0
            }

            Mode.TEST -> {
                // handled separately
                return 0
            }
        }
    }

    private fun mtimeOrZero(
        fs: FileSystem,
        path: String,
    ): Long = runCatching { fs.stat(path).mtimeEpochSeconds }.getOrDefault(0L)

    private fun helpText(): String =
        """
        Usage: $name [OPTION]... [FILE]...
        Compress or decompress FILEs (default action: compress, replacing each file).
          -c, --stdout       write to stdout and leave the input files in place
          -d, --decompress   decompress instead of compress
          -f, --force        overwrite an existing output file
          -k, --keep         do not remove the input files
          -n, --no-name      omit the original name and timestamp
          -t, --test         check a compressed file's integrity
          -1 .. -9           compression level (1 = fastest, 9 = smallest)
          --fast, --best     aliases for -1 and -9
        """.trimIndent() + "\n"
}
