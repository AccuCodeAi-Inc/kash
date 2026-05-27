package com.accucodeai.kash.tools.head

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths
import kotlinx.io.readByteArray

/**
 * `head` — print the first part of files.
 *
 * Flags:
 * - `-n N` / `-nN` / `--lines=N` — first N lines (default 10).
 * - `-n -N` — all lines except the last N.
 * - `-c N` / `-cN` / `--bytes=N` — first N bytes (mutually exclusive with `-n`,
 *   last wins).
 * - `-c -N` — all bytes except the last N.
 * - `-q` / `--quiet` / `--silent` — never print `==> NAME <==` headers.
 * - `-v` / `--verbose` — always print headers, even with a single file.
 * - Obsolete-form `-N` (e.g. `head -5`) is accepted as a line count.
 * - `--` ends options.
 *
 * Operands: zero or `-` ⇒ stdin (no header). With ≥2 file operands a header
 * `==> NAME <==` precedes each block, separated by a blank line. Missing files
 * produce an error message and contribute exit 1 at the end, but other files
 * are still processed.
 *
 * Streaming: when only `-n N` (positive) is in effect, the line form stops
 * reading once N lines have been emitted — important for `producer | head -n 5`
 * to terminate the producer via SIGPIPE.
 */
public class HeadCommand :
    Command,
    CommandSpec {
    override val name: String = "head"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private sealed class Mode {
        data class Lines(
            val n: Long,
            val fromEnd: Boolean,
        ) : Mode()

        data class Bytes(
            val n: Long,
            val fromEnd: Boolean,
        ) : Mode()
    }

    private data class Parsed(
        val mode: Mode,
        val quiet: Boolean,
        val verbose: Boolean,
        val files: List<String>,
        val exitCode: Int? = null,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val p = parseArgs(args, ctx) ?: return CommandResult(exitCode = 2)
        if (p.exitCode != null) return CommandResult(exitCode = p.exitCode)

        val files = if (p.files.isEmpty()) listOf("-") else p.files
        val showHeaders =
            when {
                p.quiet -> false
                p.verbose -> true
                else -> files.size > 1
            }

        var anyError = false
        var firstBlock = true
        for (path in files) {
            val abs = if (path == "-") path else Paths.resolve(ctx.process.cwd, path)
            if (path != "-" && !ctx.process.fs.exists(abs)) {
                ctx.stderr.writeUtf8("head: cannot open '$path' for reading: No such file or directory\n")
                anyError = true
                continue
            }
            if (showHeaders) {
                if (!firstBlock) ctx.stdout.writeUtf8("\n")
                val label = if (path == "-") "standard input" else path
                ctx.stdout.writeUtf8("==> $label <==\n")
            }
            firstBlock = false
            val source: SuspendSource = if (path == "-") ctx.stdin else ctx.process.fs.source(abs)
            try {
                emit(source, p.mode, ctx)
            } finally {
                if (path != "-") {
                    try {
                        source.close()
                    } catch (_: Throwable) {
                        // ignore
                    }
                }
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private suspend fun emit(
        source: SuspendSource,
        mode: Mode,
        ctx: CommandContext,
    ) {
        when (mode) {
            is Mode.Lines -> emitLines(source, mode, ctx)
            is Mode.Bytes -> emitBytes(source, mode, ctx)
        }
    }

    private suspend fun emitLines(
        source: SuspendSource,
        mode: Mode.Lines,
        ctx: CommandContext,
    ) {
        if (!mode.fromEnd) {
            // First N lines — streaming, stop early.
            if (mode.n <= 0) return
            var remaining = mode.n
            while (remaining > 0) {
                val line = source.readUtf8LineOrNull() ?: return
                ctx.stdout.writeLine(line)
                remaining--
            }
        } else {
            // All but last N — buffer a sliding window of size N.
            val n = mode.n
            if (n <= 0) {
                // Drop nothing — print everything.
                while (true) {
                    val line = source.readUtf8LineOrNull() ?: return
                    ctx.stdout.writeLine(line)
                }
            }
            val window = ArrayDeque<String>()
            while (true) {
                val line = source.readUtf8LineOrNull() ?: return
                window.addLast(line)
                if (window.size > n) {
                    ctx.stdout.writeLine(window.removeFirst())
                }
            }
        }
    }

    private suspend fun emitBytes(
        source: SuspendSource,
        mode: Mode.Bytes,
        ctx: CommandContext,
    ) {
        if (!mode.fromEnd) {
            // First N bytes — stream and stop. The previous readAllBytes()
            // drained the entire source before slicing, which made
            // `head -c 16 /dev/urandom` allocate until OOM.
            var remaining = mode.n
            val buf = kotlinx.io.Buffer()
            while (remaining > 0L) {
                val n = source.readAtMostTo(buf, remaining)
                if (n == -1L) break
                if (n == 0L) continue
                val bytes = buf.readByteArray()
                ctx.stdout.writeBytes(bytes)
                remaining -= bytes.size.toLong()
            }
        } else {
            // All but last N — inherently needs to see EOF. On unbounded
            // sources (`head -c -16 /dev/urandom`) this is unsatisfiable
            // and will not return.
            val all = source.readAllBytes()
            val drop = minOf(mode.n, all.size.toLong()).toInt()
            val end = all.size - drop
            if (end > 0) ctx.stdout.writeBytes(all.copyOfRange(0, end))
        }
    }

    private suspend fun parseArgs(
        args: List<String>,
        ctx: CommandContext,
    ): Parsed? {
        var mode: Mode = Mode.Lines(n = 10, fromEnd = false)
        var quiet = false
        var verbose = false
        val files = mutableListOf<String>()
        var endOfOpts = false
        var i = 0
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

                a == "-q" || a == "--quiet" || a == "--silent" -> {
                    quiet = true
                }

                a == "-v" || a == "--verbose" -> {
                    verbose = true
                }

                a == "-n" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("head: option requires an argument -- 'n'\n")
                        return Parsed(mode, quiet, verbose, files, exitCode = 2)
                    }
                    val v =
                        parseSignedCount(args[++i]) ?: run {
                            ctx.stderr.writeUtf8("head: invalid number of lines: '${args[i]}'\n")
                            return Parsed(mode, quiet, verbose, files, exitCode = 1)
                        }
                    mode = Mode.Lines(n = kotlin.math.abs(v), fromEnd = v < 0)
                }

                a.startsWith("--lines=") -> {
                    val raw = a.substring("--lines=".length)
                    val v =
                        parseSignedCount(raw) ?: run {
                            ctx.stderr.writeUtf8("head: invalid number of lines: '$raw'\n")
                            return Parsed(mode, quiet, verbose, files, exitCode = 1)
                        }
                    mode = Mode.Lines(n = kotlin.math.abs(v), fromEnd = v < 0)
                }

                a.startsWith("-n") && a.length > 2 -> {
                    val raw = a.substring(2)
                    val v =
                        parseSignedCount(raw) ?: run {
                            ctx.stderr.writeUtf8("head: invalid number of lines: '$raw'\n")
                            return Parsed(mode, quiet, verbose, files, exitCode = 1)
                        }
                    mode = Mode.Lines(n = kotlin.math.abs(v), fromEnd = v < 0)
                }

                a == "-c" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("head: option requires an argument -- 'c'\n")
                        return Parsed(mode, quiet, verbose, files, exitCode = 2)
                    }
                    val v =
                        parseSignedCount(args[++i]) ?: run {
                            ctx.stderr.writeUtf8("head: invalid number of bytes: '${args[i]}'\n")
                            return Parsed(mode, quiet, verbose, files, exitCode = 1)
                        }
                    mode = Mode.Bytes(n = kotlin.math.abs(v), fromEnd = v < 0)
                }

                a.startsWith("--bytes=") -> {
                    val raw = a.substring("--bytes=".length)
                    val v =
                        parseSignedCount(raw) ?: run {
                            ctx.stderr.writeUtf8("head: invalid number of bytes: '$raw'\n")
                            return Parsed(mode, quiet, verbose, files, exitCode = 1)
                        }
                    mode = Mode.Bytes(n = kotlin.math.abs(v), fromEnd = v < 0)
                }

                a.startsWith("-c") && a.length > 2 -> {
                    val raw = a.substring(2)
                    val v =
                        parseSignedCount(raw) ?: run {
                            ctx.stderr.writeUtf8("head: invalid number of bytes: '$raw'\n")
                            return Parsed(mode, quiet, verbose, files, exitCode = 1)
                        }
                    mode = Mode.Bytes(n = kotlin.math.abs(v), fromEnd = v < 0)
                }

                // Obsolete-form: -<digits> e.g. `head -5`
                a.length > 1 && a[0] == '-' && a.substring(1).all { it in '0'..'9' } -> {
                    val v =
                        a.substring(1).toLongOrNull() ?: run {
                            ctx.stderr.writeUtf8("head: invalid number of lines: '${a.substring(1)}'\n")
                            return Parsed(mode, quiet, verbose, files, exitCode = 1)
                        }
                    mode = Mode.Lines(n = v, fromEnd = false)
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("head: unrecognized option: $a\n")
                    return Parsed(mode, quiet, verbose, files, exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("head: invalid option -- '${a.substring(1)}'\n")
                    return Parsed(mode, quiet, verbose, files, exitCode = 2)
                }

                else -> {
                    files += a
                }
            }
            i++
        }
        return Parsed(mode, quiet, verbose, files)
    }

    /** Parse an optionally-signed integer count. Returns null on malformed input. */
    private fun parseSignedCount(s: String): Long? {
        if (s.isEmpty()) return null
        val negative = s.startsWith("-")
        val body = if (negative || s.startsWith("+")) s.substring(1) else s
        if (body.isEmpty() || !body.all { it in '0'..'9' }) return null
        val mag = body.toLongOrNull() ?: return null
        return if (negative) -mag else mag
    }
}
