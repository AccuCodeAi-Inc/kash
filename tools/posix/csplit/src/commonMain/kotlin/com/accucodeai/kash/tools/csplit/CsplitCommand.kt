package com.accucodeai.kash.tools.csplit

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.useSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths

/**
 * POSIX `csplit` — split a file into pieces at lines matching patterns.
 *
 * Operands: FILE PATTERN... (FILE = `-` for stdin).
 *
 * On error, output pieces are removed unless `-k` was specified. Successful
 * runs print the byte size of each written piece on stdout (suppressed with
 * `-s`). All other diagnostics go to stderr.
 */
public class CsplitCommand :
    Command,
    CommandSpec {
    override val name: String = "csplit"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE)
    override val command: Command get() = this

    private data class Options(
        var prefix: String = "xx",
        var digits: Int = 2,
        var format: String? = null,
        var keep: Boolean = false,
        var silent: Boolean = false,
        var elideEmpty: Boolean = false,
        var suppressMatched: Boolean = false,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = Options()
        val operands = mutableListOf<String>()
        var endOfOpts = false
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

                a == "--help" || a == "-h" -> {
                    ctx.stdout.writeUtf8(usage())
                    return CommandResult()
                }

                a == "--version" || a == "-V" -> {
                    ctx.stdout.writeUtf8("csplit (kash) 1.0\n")
                    return CommandResult()
                }

                a == "-k" || a == "--keep-files" -> {
                    opts.keep = true
                }

                a == "-s" || a == "--silent" || a == "--quiet" -> {
                    opts.silent = true
                }

                a == "-z" || a == "--elide-empty-files" -> {
                    opts.elideEmpty = true
                }

                a == "--suppress-matched" -> {
                    opts.suppressMatched = true
                }

                a == "-f" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("csplit: option requires an argument -- 'f'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.prefix = args[i]
                }

                a.startsWith("-f") -> {
                    opts.prefix = a.substring(2)
                }

                a.startsWith("--prefix=") -> {
                    opts.prefix = a.substring("--prefix=".length)
                }

                a == "-n" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("csplit: option requires an argument -- 'n'\n")
                        return CommandResult(exitCode = 2)
                    }
                    val n = args[i].toIntOrNull()
                    if (n == null || n < 1) {
                        ctx.stderr.writeUtf8("csplit: invalid digit count: ${args[i]}\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.digits = n
                }

                a.startsWith("-n") -> {
                    val n = a.substring(2).toIntOrNull()
                    if (n == null || n < 1) {
                        ctx.stderr.writeUtf8("csplit: invalid digit count: ${a.substring(2)}\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.digits = n
                }

                a.startsWith("--digits=") -> {
                    val n = a.substring("--digits=".length).toIntOrNull()
                    if (n == null || n < 1) {
                        ctx.stderr.writeUtf8("csplit: invalid digit count\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.digits = n
                }

                a == "-b" -> {
                    i++
                    if (i >= args.size) {
                        ctx.stderr.writeUtf8("csplit: option requires an argument -- 'b'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.format = args[i]
                }

                a.startsWith("-b") -> {
                    opts.format = a.substring(2)
                }

                a.startsWith("--suffix-format=") -> {
                    opts.format = a.substring("--suffix-format=".length)
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("csplit: unrecognized option '$a'\n")
                    return CommandResult(exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 && !isOperandLike(a) -> {
                    ctx.stderr.writeUtf8("csplit: invalid option -- '${a.substring(1)}'\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        if (operands.size < 2) {
            ctx.stderr.writeUtf8("csplit: usage: csplit [OPTION]... FILE PATTERN...\n")
            return CommandResult(exitCode = 2)
        }

        val file = operands[0]
        val patternStrs = operands.drop(1)

        val patterns: List<Pattern> =
            try {
                parsePatterns(patternStrs)
            } catch (e: IllegalArgumentException) {
                ctx.stderr.writeUtf8("csplit: ${e.message}\n")
                return CommandResult(exitCode = 2)
            }

        val src = openOperand(ctx, file) ?: return CommandResult(exitCode = 1)
        val created = mutableListOf<String>()
        try {
            val sizes = runEngine(src, ctx, patterns, opts, created)
            if (!opts.silent) {
                for (s in sizes) ctx.stdout.writeUtf8("$s\n")
            }
        } catch (e: CsplitError) {
            ctx.stderr.writeUtf8("csplit: ${e.message}\n")
            if (!opts.keep) deleteAll(ctx, created)
            return CommandResult(exitCode = 1)
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("csplit: ${e.message ?: "error"}\n")
            if (!opts.keep) deleteAll(ctx, created)
            return CommandResult(exitCode = 1)
        } finally {
            if (file != "-") {
                try {
                    src.close()
                } catch (_: Exception) {
                }
            }
        }
        return CommandResult()
    }

    private fun isOperandLike(a: String): Boolean {
        // patterns starting with `-` are negative line numbers (invalid, but
        // shouldn't be eaten as options). We accept anything that parses as
        // a pattern; the parser will reject it cleanly.
        if (a.length < 2) return false
        val c = a[1]
        return c.isDigit() || c == '/' || c == '%'
    }

    private suspend fun openOperand(
        ctx: CommandContext,
        op: String,
    ): SuspendSource? {
        if (op == "-") return ctx.stdin
        val abs = Paths.resolve(ctx.process.cwd, op)
        return try {
            ctx.process.fs.source(abs)
        } catch (_: FileNotFound) {
            ctx.stderr.writeUtf8("csplit: $op: No such file or directory\n")
            null
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("csplit: $op: ${e.message ?: "read error"}\n")
            null
        }
    }

    private fun deleteAll(
        ctx: CommandContext,
        paths: List<String>,
    ) {
        for (p in paths) {
            try {
                ctx.process.fs.remove(Paths.resolve(ctx.process.cwd, p))
            } catch (_: Exception) {
            }
        }
    }

    /** Engine: streams [src] line-by-line, applies [patterns] sequentially
     *  to direct line boundaries to pieces. Returns list of piece byte sizes. */
    private suspend fun runEngine(
        src: SuspendSource,
        ctx: CommandContext,
        patterns: List<Pattern>,
        opts: Options,
        created: MutableList<String>,
    ): List<Int> {
        // Buffer all lines up front. csplit is allowed to do this — it must
        // handle negative offsets and look-back on `/RE/-N`. Streaming would
        // require a sliding window; the simpler model is fine for typical use.
        val lines = mutableListOf<String>()
        while (true) {
            val ln = src.readUtf8LineOrNull() ?: break
            lines += ln
        }

        // We interleave split-finding with piece writing so that `-k` (keep
        // pieces on error) preserves the prefix of pieces that were already
        // successfully cut before the first unmatched pattern.
        val sizes = mutableListOf<Int>()
        var pieceStart = 0
        var counter = 0
        var cursor = 0 // next unconsumed line

        suspend fun writePiece(
            start: Int,
            end: Int,
            suppressed: Boolean,
        ) {
            if (suppressed) return
            val from = start.coerceAtLeast(0)
            val to = end.coerceAtMost(lines.size).coerceAtLeast(from)
            val sb = StringBuilder()
            for (idx in from until to) {
                sb.append(lines[idx]).append('\n')
            }
            val content = sb.toString()
            if (opts.elideEmpty && content.isEmpty()) return
            val name = formatName(opts, counter)
            counter++
            created += name
            val abs = Paths.resolve(ctx.process.cwd, name)
            ctx.process.fs.sink(abs, append = false).useSink { sink ->
                sink.writeUtf8(content)
            }
            sizes += content.encodeToByteArray().size
        }

        fun applyOnce(p: Pattern): Int {
            // returns the line index at which the next piece begins, or -1 on no-match.
            return when (p) {
                is Pattern.LineNum -> {
                    val target = p.line - 1 // convert to 0-based
                    if (target < cursor) {
                        // POSIX: line numbers must be increasing; otherwise no-match.
                        -2
                    } else if (target > lines.size) {
                        -1
                    } else {
                        target
                    }
                }

                is Pattern.Regex -> {
                    val re =
                        try {
                            Regex(p.pattern)
                        } catch (e: Exception) {
                            throw CsplitError("invalid regex: ${p.pattern}")
                        }
                    // search forward from cursor for first match
                    var hit = -1
                    for (idx in cursor until lines.size) {
                        if (re.containsMatchIn(lines[idx])) {
                            hit = idx
                            break
                        }
                    }
                    if (hit < 0) return -1
                    val pos = hit + p.offset
                    if (pos < 0 || pos > lines.size) {
                        throw CsplitError("offset out of range")
                    }
                    pos
                }
            }
        }

        for ((pIdx, pat) in patterns.withIndex()) {
            val reps =
                when (pat.repeat) {
                    REPEAT_INFINITE -> Int.MAX_VALUE
                    else -> 1 + pat.repeat
                }
            var done = 0
            while (done < reps) {
                val sp = applyOnce(pat)
                if (sp == -1 || sp == -2) {
                    if (pat.repeat == REPEAT_INFINITE) {
                        // {*}: stop silently when no more matches.
                        break
                    }
                    // Explicit repeat or unrepeated: this is an error unless
                    // the final unmatched is just past EOF.
                    if (done == 0 && reps == 1 && pIdx == patterns.size - 1 && sp == -1) {
                        throw CsplitError("'${describe(pat)}': pattern not found")
                    }
                    throw CsplitError("'${describe(pat)}': pattern not found")
                }
                val skip = pat is Pattern.Regex && pat.skip
                // Write the piece preceding this split.
                writePiece(pieceStart, sp, suppressed = skip)
                pieceStart = sp
                if (opts.suppressMatched && !skip && pat is Pattern.Regex) {
                    // drop the matched line from the next piece
                    pieceStart++
                }
                if (opts.suppressMatched && pat is Pattern.LineNum) {
                    // suppress-matched on a line-num pattern drops that line
                    pieceStart++
                }
                // Advance cursor past the matched line so repeated patterns
                // make forward progress.
                cursor =
                    when (pat) {
                        is Pattern.Regex -> (sp - pat.offset) + 1
                        is Pattern.LineNum -> sp + 1
                    }
                done++
            }
        }

        // Final piece: everything from pieceStart to EOF.
        writePiece(pieceStart, lines.size, suppressed = false)

        return sizes
    }

    private fun describe(p: Pattern): String =
        when (p) {
            is Pattern.Regex -> if (p.skip) "%${p.pattern}%" else "/${p.pattern}/"
            is Pattern.LineNum -> p.line.toString()
        }

    private fun formatName(
        opts: Options,
        counter: Int,
    ): String {
        val suffix =
            if (opts.format != null) {
                formatPrintf(opts.format!!, counter)
            } else {
                counter.toString().padStart(opts.digits, '0')
            }
        return opts.prefix + suffix
    }

    /** Minimal printf int-format support for the -b SUFFIX-FORMAT flag.
     *  Accepts `%[FLAGS][WIDTH][.PRECISION](d|i|o|u|x|X)` plus literal `%%`
     *  and ordinary text. */
    private fun formatPrintf(
        fmt: String,
        value: Int,
    ): String {
        val out = StringBuilder()
        var i = 0
        while (i < fmt.length) {
            val c = fmt[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 < fmt.length && fmt[i + 1] == '%') {
                out.append('%')
                i += 2
                continue
            }
            // Parse spec.
            var j = i + 1
            val flags = StringBuilder()
            while (j < fmt.length && fmt[j] in "-+ 0#") {
                flags.append(fmt[j])
                j++
            }
            val widthSb = StringBuilder()
            while (j < fmt.length && fmt[j].isDigit()) {
                widthSb.append(fmt[j])
                j++
            }
            val precSb = StringBuilder()
            if (j < fmt.length && fmt[j] == '.') {
                j++
                while (j < fmt.length && fmt[j].isDigit()) {
                    precSb.append(fmt[j])
                    j++
                }
            }
            if (j >= fmt.length) {
                out.append(fmt.substring(i))
                break
            }
            val conv = fmt[j]
            j++
            val width = widthSb.toString().toIntOrNull() ?: 0
            val padZero = flags.contains('0') && !flags.contains('-')
            val leftAlign = flags.contains('-')
            val showPlus = flags.contains('+')
            val text: String =
                when (conv) {
                    'd', 'i' -> {
                        val s = value.toString()
                        if (showPlus && value >= 0) "+$s" else s
                    }

                    'u' -> {
                        value.toString()
                    }

                    'o' -> {
                        value.toString(8)
                    }

                    'x' -> {
                        value.toString(16)
                    }

                    'X' -> {
                        value.toString(16).uppercase()
                    }

                    else -> {
                        // unsupported: emit literal
                        out.append(fmt.substring(i, j))
                        i = j
                        continue
                    }
                }
            val padded =
                if (text.length >= width) {
                    text
                } else if (leftAlign) {
                    text + " ".repeat(width - text.length)
                } else if (padZero) {
                    "0".repeat(width - text.length) + text
                } else {
                    " ".repeat(width - text.length) + text
                }
            out.append(padded)
            i = j
        }
        return out.toString()
    }

    private fun usage(): String =
        """
        Usage: csplit [OPTION]... FILE PATTERN...
        Split FILE into sections at PATTERN boundaries.

          -f, --prefix=PREFIX        use PREFIX for output piece names (default: xx)
          -n, --digits=N             use N digits in the suffix (default: 2)
          -b, --suffix-format=FORMAT printf-style suffix format
          -k, --keep-files           do not remove output files on error
          -s, --silent, --quiet      do not print piece byte counts
          -z, --elide-empty-files    omit empty pieces
              --suppress-matched     suppress the line matching PATTERN
        """.trimIndent() + "\n"
}

internal class CsplitError(
    msg: String,
) : RuntimeException(msg)
