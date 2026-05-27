package com.accucodeai.kash.tools.cut

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * `cut` — select portions of each line.
 *
 * Modes (mutually exclusive, exactly one required):
 * - `-b LIST` byte ranges. **Simplification:** in this implementation `-b` is
 *   treated identically to `-c` (codepoint ranges). For ASCII input — the
 *   overwhelming common case — the two are equivalent. Multi-byte UTF-8 is
 *   sliced by codepoint rather than by raw byte.
 * - `-c LIST` codepoint ranges.
 * - `-f LIST` field ranges. The default delimiter is TAB (not whitespace).
 *
 * Flags:
 * - `-d DELIM` single-character delimiter for `-f` (default `\t`).
 * - `-s` with `-f`, suppress lines that don't contain the delimiter.
 * - `--output-delimiter=STR` output delimiter (default: input delimiter for
 *   `-f`; otherwise no separator between selected positions).
 * - `--complement` invert the selection.
 * - `-n` accepted but ignored (POSIX legacy companion to `-b`).
 * - `--` ends option processing.
 *
 * Selected positions are output in ascending sorted unique order (matching
 * GNU `cut`), regardless of how they were listed.
 */
public class CutCommand :
    Command,
    CommandSpec {
    override val name: String = "cut"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private enum class Mode { BYTE, CHAR, FIELD }

    private data class Opts(
        val mode: Mode,
        val ranges: List<IntRange>,
        val delim: Char,
        val outputDelim: String,
        val suppress: Boolean,
        val complement: Boolean,
        val files: List<String>,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = parseArgs(args, ctx)
        if (opts.exitCode != null) return CommandResult(exitCode = opts.exitCode)
        val o = opts.opts!!

        var anyErr = false

        suspend fun process(src: SuspendSource) {
            while (true) {
                val line = src.readUtf8LineOrNull() ?: break
                val outLine =
                    when (o.mode) {
                        Mode.BYTE, Mode.CHAR -> cutChars(line, o)
                        Mode.FIELD -> cutFields(line, o) ?: continue
                    }
                ctx.stdout.writeLine(outLine)
            }
        }

        if (o.files.isEmpty()) {
            process(ctx.stdin)
        } else {
            for (f in o.files) {
                if (f == "-") {
                    process(ctx.stdin)
                } else {
                    val abs = Paths.resolve(ctx.process.cwd, f)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("cut: $f: No such file or directory\n")
                        anyErr = true
                        continue
                    }
                    process(ctx.process.fs.source(abs))
                }
            }
        }
        return CommandResult(exitCode = if (anyErr) 1 else 0)
    }

    private fun cutChars(
        line: String,
        o: Opts,
    ): String {
        val cps = line.codePointsList()
        val n = cps.size
        val keep = BooleanArray(n)
        for (r in o.ranges) {
            val lo = (r.first - 1).coerceAtLeast(0)
            val hi = (r.last - 1).coerceAtMost(n - 1)
            if (lo > hi) continue
            for (i in lo..hi) keep[i] = true
        }
        if (o.complement) {
            for (i in 0 until n) keep[i] = !keep[i]
        }
        val sb = StringBuilder()
        var first = true
        for (i in 0 until n) {
            if (!keep[i]) continue
            if (!first && o.outputDelim.isNotEmpty()) sb.append(o.outputDelim)
            sb.appendCodePoint(cps[i])
            first = false
        }
        return sb.toString()
    }

    /** Returns null when the line should be suppressed. */
    private fun cutFields(
        line: String,
        o: Opts,
    ): String? {
        val idx = line.indexOf(o.delim)
        if (idx < 0) {
            return if (o.suppress) null else line
        }
        val fields = splitOn(line, o.delim)
        val n = fields.size
        val keep = BooleanArray(n)
        for (r in o.ranges) {
            val lo = (r.first - 1).coerceAtLeast(0)
            val hi = (r.last - 1).coerceAtMost(n - 1)
            if (lo > hi) continue
            for (i in lo..hi) keep[i] = true
        }
        if (o.complement) {
            for (i in 0 until n) keep[i] = !keep[i]
        }
        val sb = StringBuilder()
        var first = true
        for (i in 0 until n) {
            if (!keep[i]) continue
            if (!first) sb.append(o.outputDelim)
            sb.append(fields[i])
            first = false
        }
        return sb.toString()
    }

    private fun splitOn(
        s: String,
        delim: Char,
    ): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        for (i in s.indices) {
            if (s[i] == delim) {
                out += s.substring(start, i)
                start = i + 1
            }
        }
        out += s.substring(start)
        return out
    }

    private fun String.codePointsList(): IntArray {
        val list = IntArray(this.length)
        var n = 0
        var i = 0
        while (i < this.length) {
            val c1 = this[i]
            if (c1.isHighSurrogate() && i + 1 < this.length && this[i + 1].isLowSurrogate()) {
                val cp = 0x10000 + ((c1.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
                list[n++] = cp
                i += 2
            } else {
                list[n++] = c1.code
                i++
            }
        }
        return list.copyOf(n)
    }

    private fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
        if (cp < 0x10000) {
            append(cp.toChar())
        } else {
            val v = cp - 0x10000
            append((0xD800 + (v shr 10)).toChar())
            append((0xDC00 + (v and 0x3FF)).toChar())
        }
        return this
    }

    private data class ParseResult(
        val opts: Opts? = null,
        val exitCode: Int? = null,
    )

    private suspend fun parseArgs(
        args: List<String>,
        ctx: CommandContext,
    ): ParseResult {
        var mode: Mode? = null
        var listSpec: String? = null
        var delim = '\t'
        var outputDelim: String? = null
        var suppress = false
        var complement = false
        val files = mutableListOf<String>()
        var endOfOpts = false

        suspend fun usage(msg: String): ParseResult {
            ctx.stderr.writeUtf8("cut: $msg\n")
            return ParseResult(exitCode = 2)
        }

        suspend fun setMode(
            m: Mode,
            spec: String,
        ): ParseResult? {
            if (mode != null && mode != m) {
                return usage("only one type of list may be specified")
            }
            mode = m
            listSpec = spec
            return null
        }

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

                a == "--complement" -> {
                    complement = true
                }

                a.startsWith("--output-delimiter=") -> {
                    outputDelim = a.substringAfter("=")
                }

                a == "--output-delimiter" -> {
                    if (i + 1 >= args.size) return usage("option requires an argument --output-delimiter")
                    outputDelim = args[++i]
                }

                a == "-n" -> {}

                // ignored
                a == "-s" -> {
                    suppress = true
                }

                a == "-d" -> {
                    if (i + 1 >= args.size) return usage("option requires an argument -- 'd'")
                    val d = args[++i]
                    if (d.length != 1) return usage("the delimiter must be a single character")
                    delim = d[0]
                }

                a.startsWith("-d") && a.length > 2 -> {
                    val d = a.substring(2)
                    if (d.length != 1) return usage("the delimiter must be a single character")
                    delim = d[0]
                }

                a == "-b" || a == "-c" || a == "-f" -> {
                    if (i + 1 >= args.size) return usage("option requires an argument -- '${a[1]}'")
                    val m =
                        when (a) {
                            "-b" -> Mode.BYTE
                            "-c" -> Mode.CHAR
                            else -> Mode.FIELD
                        }
                    setMode(m, args[++i])?.let { return it }
                }

                a.startsWith("-b") && a.length > 2 -> {
                    setMode(Mode.BYTE, a.substring(2))?.let { return it }
                }

                a.startsWith("-c") && a.length > 2 -> {
                    setMode(Mode.CHAR, a.substring(2))?.let { return it }
                }

                a.startsWith("-f") && a.length > 2 -> {
                    setMode(Mode.FIELD, a.substring(2))?.let { return it }
                }

                a.startsWith("--") -> {
                    return usage("unrecognized option: $a")
                }

                a.startsWith("-") && a.length > 1 -> {
                    return usage("invalid option -- '${a.substring(1)}'")
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        val theMode =
            mode ?: run {
                ctx.stderr.writeUtf8("cut: you must specify a list of bytes, characters, or fields\n")
                return ParseResult(exitCode = 2)
            }
        val ranges =
            parseList(listSpec!!) ?: run {
                ctx.stderr.writeUtf8("cut: invalid range\n")
                return ParseResult(exitCode = 1)
            }

        val effectiveOutDelim =
            outputDelim ?: if (theMode == Mode.FIELD) delim.toString() else ""

        return ParseResult(
            opts =
                Opts(
                    mode = theMode,
                    ranges = ranges,
                    delim = delim,
                    outputDelim = effectiveOutDelim,
                    suppress = suppress,
                    complement = complement,
                    files = files,
                ),
        )
    }

    private fun parseList(spec: String): List<IntRange>? {
        if (spec.isEmpty()) return null
        val out = mutableListOf<IntRange>()
        for (item in spec.split(',')) {
            if (item.isEmpty()) return null
            val dash = item.indexOf('-')
            if (dash < 0) {
                val n = item.toIntOrNull() ?: return null
                if (n <= 0) return null
                out += n..n
            } else {
                val a = item.substring(0, dash)
                val b = item.substring(dash + 1)
                val lo: Int
                val hi: Int
                if (a.isEmpty() && b.isEmpty()) {
                    return null
                } else if (a.isEmpty()) {
                    val bi = b.toIntOrNull() ?: return null
                    if (bi <= 0) return null
                    lo = 1
                    hi = bi
                } else if (b.isEmpty()) {
                    val ai = a.toIntOrNull() ?: return null
                    if (ai <= 0) return null
                    lo = ai
                    hi = Int.MAX_VALUE
                } else {
                    val ai = a.toIntOrNull() ?: return null
                    val bi = b.toIntOrNull() ?: return null
                    if (ai <= 0 || bi <= 0 || ai > bi) return null
                    lo = ai
                    hi = bi
                }
                out += lo..hi
            }
        }
        return out
    }
}
