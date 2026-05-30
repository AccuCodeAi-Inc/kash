package com.accucodeai.kash.tools.nl

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
import com.accucodeai.kash.shared.regex.LinearRegex
import com.accucodeai.kash.shared.regex.breToEre

/**
 * `nl` — POSIX line numbering filter.
 *
 * Numbers lines of one or more files (or stdin) according to per-section
 * numbering types. A logical page is divided into header, body and footer
 * sections by delimiter lines whose content is built from the two delimiter
 * characters `-d` (default `\:`):
 *
 *  - header delimiter: `\:\:\:`
 *  - body  delimiter: `\:\:`
 *  - footer delimiter: `\:`
 *
 * Without any delimiter lines the entire input is one body section.
 *
 * Flags (all POSIX):
 *  - `-b TYPE` body numbering (default `t` — non-empty)
 *  - `-h TYPE` header numbering (default `n`)
 *  - `-f TYPE` footer numbering (default `n`)
 *
 *    TYPE is one of `a` (all lines), `t` (non-empty), `n` (none),
 *    `pREGEX` (lines matching the BRE REGEX).
 *
 *  - `-d CC` two-character delimiter (default `\:`). Single char ⇒ second
 *    char defaults to `:`.
 *  - `-i N` line-number increment (default 1).
 *  - `-l N` group of N adjacent blank lines counts as one (only relevant
 *    when body type is `a`). Default 1.
 *  - `-n FORMAT` number format: `ln` (left, no leading zero),
 *    `rn` (right, no leading zero — default), `rz` (right, zero-padded).
 *  - `-p` do not reset numbering at the start of each logical page.
 *  - `-s STRING` separator between number and text (default tab).
 *  - `-v N` initial line number for each logical page (default 1).
 *  - `-w N` field width for line numbers (default 6).
 *  - `--` end of options.
 *
 * Operand `-` means stdin. With multiple files they are processed in order;
 * numbering state continues across them (i.e. they are one logical document).
 * Missing files emit an error to stderr and contribute
 * exit 1, other files still process.
 */
public class NlCommand :
    Command,
    CommandSpec {
    override val name: String = "nl"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private enum class NumType { ALL, NONEMPTY, NONE, REGEX }

    private enum class Format { LN, RN, RZ }

    private data class TypeSpec(
        val type: NumType,
        val regex: LinearRegex? = null,
    )

    private data class Opts(
        val body: TypeSpec = TypeSpec(NumType.NONEMPTY),
        val header: TypeSpec = TypeSpec(NumType.NONE),
        val footer: TypeSpec = TypeSpec(NumType.NONE),
        val d1: Char = '\\',
        val d2: Char = ':',
        val increment: Int = 1,
        val blankJoin: Int = 1,
        val format: Format = Format.RN,
        val noRenumber: Boolean = false,
        val separator: String = "\t",
        val startNumber: Int = 1,
        val width: Int = 6,
        val files: List<String> = emptyList(),
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts =
            try {
                parseArgs(args)
            } catch (e: UsageError) {
                ctx.stderr.writeUtf8("nl: ${e.message}\n")
                return CommandResult(exitCode = 2)
            }

        val files = if (opts.files.isEmpty()) listOf("-") else opts.files

        var exit = 0
        val state = State(opts)
        for (path in files) {
            if (path == "-") {
                process(ctx.stdin, opts, state, ctx, closeAfter = false)
            } else {
                val abs = Paths.resolve(ctx.process.cwd, path)
                if (!ctx.process.fs.exists(abs)) {
                    ctx.stderr.writeUtf8("nl: $path: No such file or directory\n")
                    exit = 1
                    continue
                }
                val src =
                    try {
                        ctx.process.fs.source(abs)
                    } catch (e: Exception) {
                        ctx.stderr.writeUtf8("nl: $path: ${e.message ?: "read error"}\n")
                        exit = 1
                        continue
                    }
                process(src, opts, state, ctx, closeAfter = true)
            }
        }
        return CommandResult(exitCode = exit)
    }

    private class State(
        opts: Opts,
    ) {
        var section: Section = Section.BODY
        var lineNum: Int = opts.startNumber
        var blankRun: Int = 0
    }

    private enum class Section { HEADER, BODY, FOOTER }

    private suspend fun process(
        source: SuspendSource,
        opts: Opts,
        state: State,
        ctx: CommandContext,
        closeAfter: Boolean,
    ) {
        try {
            while (true) {
                val line = source.readUtf8LineOrNull() ?: return
                val transition = matchDelimiter(line, opts)
                if (transition != null) {
                    state.section = transition
                    state.blankRun = 0
                    if (!opts.noRenumber && transition == Section.HEADER) {
                        state.lineNum = opts.startNumber
                    }
                    // Delimiter lines produce an empty output line per POSIX.
                    ctx.stdout.writeLine("")
                    continue
                }
                val spec =
                    when (state.section) {
                        Section.HEADER -> opts.header
                        Section.BODY -> opts.body
                        Section.FOOTER -> opts.footer
                    }
                val shouldNumber = decideNumber(line, spec, state, opts)
                if (shouldNumber) {
                    ctx.stdout.writeLine(formatNumber(state.lineNum, opts) + opts.separator + line)
                    state.lineNum += opts.increment
                } else {
                    // Unnumbered line: per POSIX, output a blank field
                    // of width (separator is omitted). We emit `width` spaces
                    // followed by the line content with no separator.
                    ctx.stdout.writeLine(blankField(opts) + line)
                }
            }
        } finally {
            if (closeAfter) {
                try {
                    source.close()
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }
    }

    private fun decideNumber(
        line: String,
        spec: TypeSpec,
        state: State,
        opts: Opts,
    ): Boolean {
        val isBlank = line.isEmpty()
        return when (spec.type) {
            NumType.NONE -> {
                state.blankRun = if (isBlank) state.blankRun + 1 else 0
                false
            }

            NumType.NONEMPTY -> {
                state.blankRun = if (isBlank) state.blankRun + 1 else 0
                !isBlank
            }

            NumType.ALL -> {
                if (isBlank) {
                    state.blankRun += 1
                    if (state.blankRun >= opts.blankJoin) {
                        state.blankRun = 0
                        true
                    } else {
                        false
                    }
                } else {
                    state.blankRun = 0
                    true
                }
            }

            NumType.REGEX -> {
                state.blankRun = if (isBlank) state.blankRun + 1 else 0
                spec.regex!!.containsMatch(line)
            }
        }
    }

    private fun matchDelimiter(
        line: String,
        opts: Opts,
    ): Section? {
        // Page delimiters are *exactly* the delimiter pattern, nothing more.
        val a = "${opts.d1}${opts.d2}"
        return when (line) {
            a + a + a -> Section.HEADER
            a + a -> Section.BODY
            a -> Section.FOOTER
            else -> null
        }
    }

    private fun formatNumber(
        n: Int,
        opts: Opts,
    ): String {
        val s = n.toString()
        return when (opts.format) {
            Format.LN -> s.padEnd(opts.width, ' ')
            Format.RN -> s.padStart(opts.width, ' ')
            Format.RZ -> s.padStart(opts.width, '0')
        }
    }

    private fun blankField(opts: Opts): String {
        // Unnumbered lines get a blank gutter the same width as the number
        // field plus the separator: width spaces + separator-width spaces,
        // to keep columns aligned.
        return " ".repeat(opts.width) + " ".repeat(opts.separator.length)
    }

    private class UsageError(
        msg: String,
    ) : RuntimeException(msg)

    private fun parseTypeSpec(
        arg: String,
        optName: String,
    ): TypeSpec {
        if (arg.isEmpty()) throw UsageError("invalid $optName type: empty")
        return when (arg[0]) {
            'a' -> {
                if (arg.length == 1) TypeSpec(NumType.ALL) else throw UsageError("invalid $optName type: $arg")
            }

            't' -> {
                if (arg.length == 1) TypeSpec(NumType.NONEMPTY) else throw UsageError("invalid $optName type: $arg")
            }

            'n' -> {
                if (arg.length == 1) TypeSpec(NumType.NONE) else throw UsageError("invalid $optName type: $arg")
            }

            'p' -> {
                val pat = arg.substring(1)
                if (pat.isEmpty()) throw UsageError("invalid $optName type: empty regex")
                val ere = breToEre(pat)
                val rx =
                    try {
                        LinearRegex(ere, "")
                    } catch (e: Exception) {
                        throw UsageError("invalid regex for $optName: $pat")
                    }
                TypeSpec(NumType.REGEX, rx)
            }

            else -> {
                throw UsageError("invalid $optName type: $arg")
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun parseArgs(args: List<String>): Opts {
        var body = TypeSpec(NumType.NONEMPTY)
        var header = TypeSpec(NumType.NONE)
        var footer = TypeSpec(NumType.NONE)
        var d1 = '\\'
        var d2 = ':'
        var increment = 1
        var blankJoin = 1
        var format = Format.RN
        var noRenumber = false
        var separator = "\t"
        var startNumber = 1
        var width = 6
        val files = mutableListOf<String>()

        var i = 0
        var endOfOpts = false

        fun requireArg(flag: String): String {
            if (i + 1 >= args.size) throw UsageError("option requires an argument -- '$flag'")
            return args[++i]
        }

        fun parseIntArg(
            s: String,
            name: String,
        ): Int = s.toIntOrNull() ?: throw UsageError("invalid $name: '$s'")

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

                a == "-p" -> {
                    noRenumber = true
                }

                a == "-b" -> {
                    body = parseTypeSpec(requireArg("b"), "body")
                }

                a.startsWith("-b") && a.length > 2 -> {
                    body = parseTypeSpec(a.substring(2), "body")
                }

                a == "-h" -> {
                    header = parseTypeSpec(requireArg("h"), "header")
                }

                a.startsWith("-h") && a.length > 2 -> {
                    header = parseTypeSpec(a.substring(2), "header")
                }

                a == "-f" -> {
                    footer = parseTypeSpec(requireArg("f"), "footer")
                }

                a.startsWith("-f") && a.length > 2 -> {
                    footer = parseTypeSpec(a.substring(2), "footer")
                }

                a == "-d" -> {
                    val v = requireArg("d")
                    if (v.isEmpty()) throw UsageError("invalid delimiter: empty")
                    d1 = v[0]
                    d2 = if (v.length >= 2) v[1] else ':'
                }

                a.startsWith("-d") && a.length > 2 -> {
                    val v = a.substring(2)
                    d1 = v[0]
                    d2 = if (v.length >= 2) v[1] else ':'
                }

                a == "-i" -> {
                    increment = parseIntArg(requireArg("i"), "increment")
                }

                a.startsWith("-i") && a.length > 2 -> {
                    increment = parseIntArg(a.substring(2), "increment")
                }

                a == "-l" -> {
                    blankJoin = parseIntArg(requireArg("l"), "blank-join").coerceAtLeast(1)
                }

                a.startsWith("-l") && a.length > 2 -> {
                    blankJoin =
                        parseIntArg(a.substring(2), "blank-join").coerceAtLeast(1)
                }

                a == "-n" -> {
                    format = parseFormat(requireArg("n"))
                }

                a.startsWith("-n") && a.length > 2 -> {
                    format = parseFormat(a.substring(2))
                }

                a == "-s" -> {
                    separator = requireArg("s")
                }

                a.startsWith("-s") && a.length > 2 -> {
                    separator = a.substring(2)
                }

                a == "-v" -> {
                    startNumber = parseIntArg(requireArg("v"), "starting line number")
                }

                a.startsWith("-v") && a.length > 2 -> {
                    startNumber = parseIntArg(a.substring(2), "starting line number")
                }

                a == "-w" -> {
                    width = parseIntArg(requireArg("w"), "width").coerceAtLeast(1)
                }

                a.startsWith("-w") && a.length > 2 -> {
                    width = parseIntArg(a.substring(2), "width").coerceAtLeast(1)
                }

                a.startsWith("-") && a.length > 1 -> {
                    throw UsageError("invalid option -- '${a.substring(1)}'")
                }

                else -> {
                    files += a
                }
            }
            i++
        }
        return Opts(
            body = body,
            header = header,
            footer = footer,
            d1 = d1,
            d2 = d2,
            increment = increment,
            blankJoin = blankJoin,
            format = format,
            noRenumber = noRenumber,
            separator = separator,
            startNumber = startNumber,
            width = width,
            files = files,
        )
    }

    private fun parseFormat(s: String): Format =
        when (s) {
            "ln" -> Format.LN
            "rn" -> Format.RN
            "rz" -> Format.RZ
            else -> throw UsageError("invalid number format: '$s' (expected ln, rn, rz)")
        }
}
