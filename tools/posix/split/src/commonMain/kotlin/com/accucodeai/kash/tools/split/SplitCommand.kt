package com.accucodeai.kash.tools.split

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * `split` — break a file into pieces.
 *
 * Flags:
 *  - `-l N` / `--lines=N` — N lines per piece (default 1000).
 *  - `-b SIZE` / `--bytes=SIZE` — bytes per piece. SIZE accepts `k`/`K`/`m`/`M`/`g`/`G` suffixes.
 *  - `-C SIZE` / `--line-bytes=SIZE` — at most SIZE bytes per piece, breaking on line boundaries.
 *  - `-n N`, `-n l/N` — split into N chunks (`l/N` = line-boundary chunks).
 *  - `-a N` / `--suffix-length=N` — suffix length (default 2).
 *  - `-d` / `--numeric-suffixes[=FROM]` — numeric suffix (default start 0).
 *  - `-x` / `--hex-suffixes[=FROM]` — hex suffix.
 *  - `--additional-suffix=SUF` — append SUF to each piece name.
 *  - `-e` / `--elide-empty-files` — don't create empty pieces under `-n`.
 *  - `--verbose` — print piece name to stderr as each is created.
 *
 * Operands: `[INPUT [PREFIX]]`. INPUT defaults to `-` (stdin). PREFIX defaults to `x`.
 *
 * Deferred: `-n r/N` round-robin; `-n N/CHUNK` extract-only mode; `--filter=CMD`.
 * Suffix exhaustion is reported as an error (GNU's auto-grow not implemented).
 */
public class SplitCommand :
    Command,
    CommandSpec {
    override val name: String = "split"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.IMPURE)
    override val command: Command get() = this

    private sealed class Mode {
        data class Lines(
            val n: Long,
        ) : Mode()

        data class Bytes(
            val n: Long,
        ) : Mode()

        data class LineBytes(
            val n: Long,
        ) : Mode()

        data class Chunks(
            val n: Int,
            val onLines: Boolean,
        ) : Mode()
    }

    private enum class SuffixKind { ALPHA, NUM, HEX }

    private data class Opts(
        val mode: Mode = Mode.Lines(1000),
        val suffixLen: Int = 2,
        val suffixKind: SuffixKind = SuffixKind.ALPHA,
        val suffixStart: Long = 0,
        val additionalSuffix: String = "",
        val elideEmpty: Boolean = false,
        val verbose: Boolean = false,
        val input: String = "-",
        val prefix: String = "x",
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val parsed = parseArgs(args, ctx)
        if (parsed.handled) return CommandResult(exitCode = parsed.exitCode ?: 0)
        val opts = parsed.opts ?: return CommandResult(exitCode = parsed.exitCode ?: 2)
        if (opts.suffixLen <= 0) {
            ctx.stderr.writeUtf8("split: invalid suffix length: ${opts.suffixLen}\n")
            return CommandResult(exitCode = 1)
        }
        return runWith(opts, ctx)
    }

    private suspend fun runWith(
        opts: Opts,
        ctx: CommandContext,
    ): CommandResult {
        // Read input — sizes for -n need full content; for -l/-b/-C we still buffer
        // since split semantics aren't streaming-friendly with growing suffixes anyway.
        val bytes: ByteArray =
            if (opts.input == "-") {
                ctx.stdin.readAllBytes()
            } else {
                val abs = Paths.resolve(ctx.process.cwd, opts.input)
                if (!ctx.process.fs.exists(abs)) {
                    ctx.stderr.writeUtf8("split: cannot open '${opts.input}' for reading: No such file or directory\n")
                    return CommandResult(exitCode = 1)
                }
                ctx.process.fs.readBytes(abs)
            }

        val gen = SuffixGen(opts.suffixKind, opts.suffixLen, opts.suffixStart)

        suspend fun writePiece(
            data: ByteArray,
            allowEmpty: Boolean,
        ): Int {
            if (data.isEmpty() && !allowEmpty) return 0
            val sfx =
                gen.next() ?: run {
                    ctx.stderr.writeUtf8("split: output file suffixes exhausted\n")
                    return 1
                }
            val name = opts.prefix + sfx + opts.additionalSuffix
            val abs = Paths.resolve(ctx.process.cwd, name)
            ctx.process.fs.writeBytes(abs, data)
            if (opts.verbose) ctx.stderr.writeUtf8("creating file '$name'\n")
            return 0
        }

        return when (val m = opts.mode) {
            is Mode.Lines -> {
                if (m.n <= 0) {
                    ctx.stderr.writeUtf8("split: invalid number of lines: ${m.n}\n")
                    return CommandResult(exitCode = 1)
                }
                for (piece in splitByLines(bytes, m.n)) {
                    val rc = writePiece(piece, allowEmpty = false)
                    if (rc != 0) return CommandResult(exitCode = rc)
                }
                CommandResult(exitCode = 0)
            }

            is Mode.Bytes -> {
                if (m.n <= 0) {
                    ctx.stderr.writeUtf8("split: invalid number of bytes: ${m.n}\n")
                    return CommandResult(exitCode = 1)
                }
                var i = 0
                while (i < bytes.size) {
                    val end = minOf(i + m.n, bytes.size.toLong()).toInt()
                    val rc = writePiece(bytes.copyOfRange(i, end), allowEmpty = false)
                    if (rc != 0) return CommandResult(exitCode = rc)
                    i = end
                }
                CommandResult(exitCode = 0)
            }

            is Mode.LineBytes -> {
                if (m.n <= 0) {
                    ctx.stderr.writeUtf8("split: invalid number of bytes: ${m.n}\n")
                    return CommandResult(exitCode = 1)
                }
                for (piece in splitByLineBytes(bytes, m.n)) {
                    val rc = writePiece(piece, allowEmpty = false)
                    if (rc != 0) return CommandResult(exitCode = rc)
                }
                CommandResult(exitCode = 0)
            }

            is Mode.Chunks -> {
                if (m.n <= 0) {
                    ctx.stderr.writeUtf8("split: invalid number of chunks: ${m.n}\n")
                    return CommandResult(exitCode = 1)
                }
                val pieces = if (m.onLines) splitChunksByLines(bytes, m.n) else splitChunks(bytes, m.n)
                for (p in pieces) {
                    val rc = writePiece(p, allowEmpty = !opts.elideEmpty)
                    if (rc != 0) return CommandResult(exitCode = rc)
                }
                CommandResult(exitCode = 0)
            }
        }
    }

    // ---------- splitting algorithms ----------

    /** Yield pieces of [linesPerPiece] lines. A line ends at '\n' or at EOF (final piece may be partial). */
    private fun splitByLines(
        bytes: ByteArray,
        linesPerPiece: Long,
    ): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val out = mutableListOf<ByteArray>()
        var start = 0
        var i = 0
        var lineCount = 0L
        while (i < bytes.size) {
            if (bytes[i] == NL) {
                lineCount++
                if (lineCount == linesPerPiece) {
                    out += bytes.copyOfRange(start, i + 1)
                    start = i + 1
                    lineCount = 0
                }
            }
            i++
        }
        if (start < bytes.size) out += bytes.copyOfRange(start, bytes.size)
        return out
    }

    /** -C: pieces ≤ [max] bytes, breaking at last preceding newline if the piece would exceed [max] mid-line. */
    private fun splitByLineBytes(
        bytes: ByteArray,
        max: Long,
    ): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val maxI = max.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val out = mutableListOf<ByteArray>()
        var start = 0
        while (start < bytes.size) {
            val remaining = bytes.size - start
            if (remaining <= maxI) {
                out += bytes.copyOfRange(start, bytes.size)
                break
            }
            // Find last newline within [start, start+maxI)
            var brk = -1
            var k = start + maxI - 1
            while (k >= start) {
                if (bytes[k] == NL) {
                    brk = k
                    break
                }
                k--
            }
            val end =
                if (brk >= 0) {
                    brk + 1
                } else {
                    // No newline in the window — emit maxI bytes anyway (line longer than chunk).
                    start + maxI
                }
            out += bytes.copyOfRange(start, end)
            start = end
        }
        return out
    }

    /** -n N: split into exactly N byte chunks (first chunks get the extra byte if not divisible). */
    private fun splitChunks(
        bytes: ByteArray,
        n: Int,
    ): List<ByteArray> {
        val total = bytes.size
        val base = total / n
        val extra = total % n
        val out = ArrayList<ByteArray>(n)
        var off = 0
        for (i in 0 until n) {
            val size = base + if (i < extra) 1 else 0
            out += bytes.copyOfRange(off, off + size)
            off += size
        }
        return out
    }

    /** -n l/N: split into N chunks on line boundaries. */
    private fun splitChunksByLines(
        bytes: ByteArray,
        n: Int,
    ): List<ByteArray> {
        val total = bytes.size
        val out = ArrayList<ByteArray>(n)
        val ideal = total.toDouble() / n
        var off = 0
        for (i in 0 until n) {
            if (i == n - 1) {
                out += bytes.copyOfRange(off, total)
                break
            }
            val target = ((i + 1) * ideal).toInt().coerceAtMost(total)
            // Advance to next newline at or after target (so the chunk ends after a newline).
            var k = target
            while (k < total && bytes[k - 1] != NL) k++
            // If target landed at a position whose previous byte is '\n' we're done.
            // Otherwise scan forward.
            if (k < total && bytes[k - 1] != NL) {
                while (k < total && bytes[k] != NL) k++
                if (k < total) k++ // include the newline
            }
            if (k < off) k = off
            if (k > total) k = total
            out += bytes.copyOfRange(off, k)
            off = k
        }
        return out
    }

    // ---------- suffix generator ----------

    private class SuffixGen(
        val kind: SuffixKind,
        val len: Int,
        start: Long,
    ) {
        private var counter: Long = start
        private val max: Long =
            when (kind) {
                SuffixKind.ALPHA -> pow(26L, len)
                SuffixKind.NUM -> pow(10L, len)
                SuffixKind.HEX -> pow(16L, len)
            }

        fun next(): String? {
            if (counter >= max) return null
            val s =
                when (kind) {
                    SuffixKind.ALPHA -> alpha(counter, len)
                    SuffixKind.NUM -> pad(counter.toString(), len)
                    SuffixKind.HEX -> pad(counter.toString(16), len)
                }
            counter++
            return s
        }

        private fun alpha(
            v: Long,
            n: Int,
        ): String {
            val chars = CharArray(n)
            var x = v
            for (i in n - 1 downTo 0) {
                chars[i] = ('a' + (x % 26).toInt())
                x /= 26
            }
            return chars.concatToString()
        }

        private fun pad(
            s: String,
            n: Int,
        ): String = if (s.length >= n) s else "0".repeat(n - s.length) + s

        private fun pow(
            base: Long,
            exp: Int,
        ): Long {
            var r = 1L
            repeat(exp) { r *= base }
            return r
        }
    }

    // ---------- argv ----------

    private data class ParseResult(
        val opts: Opts? = null,
        val handled: Boolean = false,
        val exitCode: Int? = null,
    )

    private suspend fun parseArgs(
        args: List<String>,
        ctx: CommandContext,
    ): ParseResult {
        var mode: Mode = Mode.Lines(1000)
        var suffixLen = 2
        var suffixKind = SuffixKind.ALPHA
        var suffixStart = 0L
        var additionalSuffix = ""
        var elideEmpty = false
        var verbose = false
        val operands = mutableListOf<String>()
        var endOfOpts = false
        var explicitSuffixLen = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                operands += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-" -> {
                    operands += a
                }

                a == "-l" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("split: option requires an argument -- 'l'\n")
                        return ParseResult(exitCode = 2)
                    }
                    val v = parseSize(args[++i]) ?: return badNum(ctx, args[i], "lines")
                    mode = Mode.Lines(v)
                }

                a.startsWith("--lines=") -> {
                    val v =
                        parseSize(a.substring("--lines=".length))
                            ?: return badNum(ctx, a.substring("--lines=".length), "lines")
                    mode = Mode.Lines(v)
                }

                a.startsWith("-l") && a.length > 2 -> {
                    val v = parseSize(a.substring(2)) ?: return badNum(ctx, a.substring(2), "lines")
                    mode = Mode.Lines(v)
                }

                a == "-b" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("split: option requires an argument -- 'b'\n")
                        return ParseResult(exitCode = 2)
                    }
                    val v = parseSize(args[++i]) ?: return badNum(ctx, args[i], "bytes")
                    mode = Mode.Bytes(v)
                }

                a.startsWith("--bytes=") -> {
                    val raw = a.substring("--bytes=".length)
                    val v = parseSize(raw) ?: return badNum(ctx, raw, "bytes")
                    mode = Mode.Bytes(v)
                }

                a.startsWith("-b") && a.length > 2 -> {
                    val v = parseSize(a.substring(2)) ?: return badNum(ctx, a.substring(2), "bytes")
                    mode = Mode.Bytes(v)
                }

                a == "-C" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("split: option requires an argument -- 'C'\n")
                        return ParseResult(exitCode = 2)
                    }
                    val v = parseSize(args[++i]) ?: return badNum(ctx, args[i], "bytes")
                    mode = Mode.LineBytes(v)
                }

                a.startsWith("--line-bytes=") -> {
                    val raw = a.substring("--line-bytes=".length)
                    val v = parseSize(raw) ?: return badNum(ctx, raw, "bytes")
                    mode = Mode.LineBytes(v)
                }

                a.startsWith("-C") && a.length > 2 -> {
                    val v = parseSize(a.substring(2)) ?: return badNum(ctx, a.substring(2), "bytes")
                    mode = Mode.LineBytes(v)
                }

                a == "-n" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("split: option requires an argument -- 'n'\n")
                        return ParseResult(exitCode = 2)
                    }
                    mode = parseChunkSpec(args[++i], ctx) ?: return ParseResult(exitCode = 2)
                }

                a.startsWith("--number=") -> {
                    mode = parseChunkSpec(a.substring("--number=".length), ctx) ?: return ParseResult(exitCode = 2)
                }

                a.startsWith("-n") && a.length > 2 -> {
                    mode = parseChunkSpec(a.substring(2), ctx) ?: return ParseResult(exitCode = 2)
                }

                a == "-a" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("split: option requires an argument -- 'a'\n")
                        return ParseResult(exitCode = 2)
                    }
                    suffixLen = args[++i].toIntOrNull() ?: return badNum(ctx, args[i], "suffix length")
                    explicitSuffixLen = true
                }

                a.startsWith("--suffix-length=") -> {
                    val raw = a.substring("--suffix-length=".length)
                    suffixLen = raw.toIntOrNull() ?: return badNum(ctx, raw, "suffix length")
                    explicitSuffixLen = true
                }

                a.startsWith("-a") && a.length > 2 -> {
                    val raw = a.substring(2)
                    suffixLen = raw.toIntOrNull() ?: return badNum(ctx, raw, "suffix length")
                    explicitSuffixLen = true
                }

                a == "-d" -> {
                    suffixKind = SuffixKind.NUM
                    suffixStart = 0
                }

                a == "--numeric-suffixes" -> {
                    suffixKind = SuffixKind.NUM
                    suffixStart = 0
                }

                a.startsWith("--numeric-suffixes=") -> {
                    suffixKind = SuffixKind.NUM
                    val raw = a.substring("--numeric-suffixes=".length)
                    suffixStart = raw.toLongOrNull() ?: return badNum(ctx, raw, "start value")
                }

                a == "-x" -> {
                    suffixKind = SuffixKind.HEX
                    suffixStart = 0
                }

                a == "--hex-suffixes" -> {
                    suffixKind = SuffixKind.HEX
                    suffixStart = 0
                }

                a.startsWith("--hex-suffixes=") -> {
                    suffixKind = SuffixKind.HEX
                    val raw = a.substring("--hex-suffixes=".length)
                    suffixStart = raw.toLongOrNull(16) ?: return badNum(ctx, raw, "start value")
                }

                a.startsWith("--additional-suffix=") -> {
                    additionalSuffix = a.substring("--additional-suffix=".length)
                }

                a == "-e" || a == "--elide-empty-files" -> {
                    elideEmpty = true
                }

                a == "--verbose" -> {
                    verbose = true
                }

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8(helpText())
                    return ParseResult(handled = true, exitCode = 0)
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("split (kash) 1.0\n")
                    return ParseResult(handled = true, exitCode = 0)
                }

                a.startsWith("--filter=") -> {
                    ctx.stderr.writeUtf8("split: --filter is not supported\n")
                    return ParseResult(exitCode = 2)
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("split: unrecognized option: $a\n")
                    return ParseResult(exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("split: invalid option -- '${a.substring(1)}'\n")
                    return ParseResult(exitCode = 2)
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        if (operands.size > 2) {
            ctx.stderr.writeUtf8("split: extra operand: '${operands[2]}'\n")
            return ParseResult(exitCode = 2)
        }
        val input = operands.getOrNull(0) ?: "-"
        val prefix = operands.getOrNull(1) ?: "x"

        // GNU `-n` defaults suffix-length wide enough for the count if unset.
        if (mode is Mode.Chunks && !explicitSuffixLen) {
            val n = mode.n
            val needed = digitsNeeded(n, suffixKind)
            if (needed > suffixLen) suffixLen = needed
        }

        return ParseResult(
            opts =
                Opts(
                    mode = mode,
                    suffixLen = suffixLen,
                    suffixKind = suffixKind,
                    suffixStart = suffixStart,
                    additionalSuffix = additionalSuffix,
                    elideEmpty = elideEmpty,
                    verbose = verbose,
                    input = input,
                    prefix = prefix,
                ),
        )
    }

    private fun digitsNeeded(
        n: Int,
        kind: SuffixKind,
    ): Int {
        if (n <= 0) return 1
        val base =
            when (kind) {
                SuffixKind.ALPHA -> 26
                SuffixKind.NUM -> 10
                SuffixKind.HEX -> 16
            }
        var d = 0
        var x = (n - 1).toLong()
        if (x == 0L) return 1
        while (x > 0) {
            x /= base
            d++
        }
        return d
    }

    private suspend fun parseChunkSpec(
        spec: String,
        ctx: CommandContext,
    ): Mode? {
        // Accept: "N", "l/N". Reject "r/N", "N/CHUNK", "l/K/N" as unsupported.
        val parts = spec.split('/')
        return when (parts.size) {
            1 -> {
                val n = parts[0].toIntOrNull()
                if (n == null || n <= 0) {
                    ctx.stderr.writeUtf8("split: invalid number of chunks: '$spec'\n")
                    null
                } else {
                    Mode.Chunks(n, onLines = false)
                }
            }

            2 -> {
                if (parts[0] == "l") {
                    val n = parts[1].toIntOrNull()
                    if (n == null || n <= 0) {
                        ctx.stderr.writeUtf8("split: invalid number of chunks: '$spec'\n")
                        null
                    } else {
                        Mode.Chunks(n, onLines = true)
                    }
                } else if (parts[0] == "r") {
                    ctx.stderr.writeUtf8("split: '-n r/N' (round-robin) is not supported\n")
                    null
                } else {
                    ctx.stderr.writeUtf8("split: '-n K/N' (extract chunk K) is not supported\n")
                    null
                }
            }

            else -> {
                ctx.stderr.writeUtf8("split: invalid -n spec: '$spec'\n")
                null
            }
        }
    }

    private suspend fun badNum(
        ctx: CommandContext,
        raw: String,
        kind: String,
    ): ParseResult {
        ctx.stderr.writeUtf8("split: invalid number of $kind: '$raw'\n")
        return ParseResult(exitCode = 2)
    }

    /** Parse SIZE: optional K/M/G suffix (lowercase k/m/g equivalent). */
    private fun parseSize(s: String): Long? {
        if (s.isEmpty()) return null
        val last = s.last()
        val (body, mult) =
            when (last) {
                'k', 'K' -> s.dropLast(1) to 1024L
                'm', 'M' -> s.dropLast(1) to 1024L * 1024L
                'g', 'G' -> s.dropLast(1) to 1024L * 1024L * 1024L
                'b' -> s.dropLast(1) to 512L
                else -> s to 1L
            }
        if (body.isEmpty() || !body.all { it in '0'..'9' }) return null
        val v = body.toLongOrNull() ?: return null
        return v * mult
    }

    private fun helpText(): String =
        "Usage: split [OPTIONS] [INPUT [PREFIX]]\n" +
            "Split INPUT (default: standard input) into pieces named PREFIX<suffix> (default prefix: x).\n" +
            "Options: -l N, -b SIZE, -C SIZE, -n CHUNKS, -a N, -d, -x, -e, --verbose, --additional-suffix=SUF\n"

    private companion object {
        const val NL: Byte = '\n'.code.toByte()
    }
}
