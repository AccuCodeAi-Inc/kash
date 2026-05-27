package com.accucodeai.kash.tools.column

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths

/**
 * `column` — BSD/util-linux column formatter.
 *
 * Modes:
 *  - Default (no `-t`): grid mode. Whitespace-separated tokens from input are
 *    arranged into a grid sized to fit `$COLUMNS` (or 80 if unset). Columns are
 *    filled first (top-to-bottom, then left-to-right) unless `-x` is given,
 *    in which case rows are filled first.
 *  - `-t` / `--table`: each input line is split into fields and printed with
 *    each column padded to that column's max width across all rows.
 *
 * Flags:
 *  - `-t`, `--table`         table mode
 *  - `-s SEP`, `--separator=SEP`        input separator (set of single-char
 *      delimiters when in `-t`; with default whitespace, runs collapse)
 *  - `-o SEP`, `--output-separator=SEP` output column separator
 *  - `-N NAMES`, `--table-columns=NAMES` comma-separated header row prepended
 *  - `-x`, `--fillrows`      fill rows before columns (grid mode)
 *
 * Operands are file paths (`-` = stdin). No operand reads stdin.
 */
public class ColumnCommand :
    Command,
    CommandSpec {
    override val name: String = "column"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    private data class Options(
        val tableMode: Boolean,
        val inputSep: String?, // null = whitespace
        val outputSep: String?, // null = default for mode
        val headerNames: List<String>?,
        val fillRows: Boolean,
        val termWidth: Int,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var tableMode = false
        var inputSep: String? = null
        var outputSep: String? = null
        var headerNames: List<String>? = null
        var fillRows = false
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

                a == "-t" || a == "--table" -> {
                    tableMode = true
                }

                a == "-x" || a == "--fillrows" -> {
                    fillRows = true
                }

                a == "-s" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("column: option requires an argument -- 's'\n")
                        return CommandResult(exitCode = 2)
                    }
                    inputSep = args[++i]
                }

                a.startsWith("--separator=") -> {
                    inputSep = a.removePrefix("--separator=")
                }

                a == "-o" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("column: option requires an argument -- 'o'\n")
                        return CommandResult(exitCode = 2)
                    }
                    outputSep = args[++i]
                }

                a.startsWith("--output-separator=") -> {
                    outputSep = a.removePrefix("--output-separator=")
                }

                a == "-N" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("column: option requires an argument -- 'N'\n")
                        return CommandResult(exitCode = 2)
                    }
                    headerNames = args[++i].split(',')
                }

                a.startsWith("--table-columns=") -> {
                    headerNames = a.removePrefix("--table-columns=").split(',')
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("column: unrecognized option '$a'\n")
                    return CommandResult(exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    // bundle short flags (only those without arguments)
                    var k = 1
                    while (k < a.length) {
                        when (val c = a[k]) {
                            't' -> {
                                tableMode = true
                            }

                            'x' -> {
                                fillRows = true
                            }

                            's', 'o', 'N' -> {
                                // value may be glued to the flag, or in the next arg
                                val value = if (k + 1 < a.length) a.substring(k + 1) else null
                                val resolved =
                                    when {
                                        value != null -> {
                                            value
                                        }

                                        i + 1 < args.size -> {
                                            args[++i]
                                        }

                                        else -> {
                                            ctx.stderr.writeUtf8("column: option requires an argument -- '$c'\n")
                                            return CommandResult(exitCode = 2)
                                        }
                                    }
                                when (c) {
                                    's' -> inputSep = resolved
                                    'o' -> outputSep = resolved
                                    'N' -> headerNames = resolved.split(',')
                                }
                                k = a.length // consume rest of bundle
                            }

                            else -> {
                                ctx.stderr.writeUtf8("column: invalid option -- '$c'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                        k++
                    }
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        val termWidth =
            ctx.process.env["COLUMNS"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 } ?: 80

        val options =
            Options(
                tableMode = tableMode,
                inputSep = inputSep,
                outputSep = outputSep,
                headerNames = headerNames,
                fillRows = fillRows,
                termWidth = termWidth,
            )

        // Read all input lines.
        val lines = ArrayList<String>()
        var exit = 0
        if (operands.isEmpty()) {
            readAllLines(ctx.stdin, lines)
        } else {
            for (op in operands) {
                if (op == "-") {
                    readAllLines(ctx.stdin, lines)
                    continue
                }
                val abs = Paths.resolve(ctx.process.cwd, op)
                if (!ctx.process.fs.exists(abs)) {
                    ctx.stderr.writeUtf8("column: $op: No such file or directory\n")
                    exit = 1
                    continue
                }
                val src =
                    try {
                        ctx.process.fs.source(abs)
                    } catch (_: FileNotFound) {
                        ctx.stderr.writeUtf8("column: $op: No such file or directory\n")
                        exit = 1
                        continue
                    }
                try {
                    readAllLines(src, lines)
                } finally {
                    src.close()
                }
            }
        }

        if (options.tableMode) {
            emitTable(lines, options, ctx)
        } else {
            emitGrid(lines, options, ctx)
        }
        return CommandResult(exitCode = exit)
    }

    private suspend fun readAllLines(
        src: SuspendSource,
        into: MutableList<String>,
    ) {
        while (true) {
            val line = src.readUtf8LineOrNull() ?: break
            into += line
        }
    }

    /** Split a line for table mode based on separator option. */
    private fun splitTableRow(
        line: String,
        sep: String?,
    ): List<String> {
        if (sep == null) {
            // whitespace, collapse runs, drop leading/trailing empties
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return emptyList()
            return trimmed.split(Regex("\\s+"))
        }
        if (sep.isEmpty()) return listOf(line)
        // Single-char delimiter set. Split on any occurrence.
        val delims = sep.toSet()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in line) {
            if (ch in delims) {
                out += cur.toString()
                cur.clear()
            } else {
                cur.append(ch)
            }
        }
        out += cur.toString()
        return out
    }

    private suspend fun emitTable(
        lines: List<String>,
        opts: Options,
        ctx: CommandContext,
    ) {
        // Header is prepended as a logical row.
        val rows = ArrayList<List<String>>()
        if (opts.headerNames != null) rows += opts.headerNames
        for (line in lines) {
            rows += splitTableRow(line, opts.inputSep)
        }
        if (rows.isEmpty()) return

        val ncols = rows.maxOf { it.size }
        if (ncols == 0) return
        val widths = IntArray(ncols)
        for (row in rows) {
            for ((idx, field) in row.withIndex()) {
                if (field.length > widths[idx]) widths[idx] = field.length
            }
        }

        val outSep = opts.outputSep ?: "  "
        val sb = StringBuilder()
        for (row in rows) {
            sb.clear()
            for (idx in 0 until ncols) {
                val field = row.getOrElse(idx) { "" }
                val isLast = idx == ncols - 1 || row.size - 1 == idx
                if (isLast) {
                    // Don't pad the last field in a row — match BSD/util-linux behavior.
                    sb.append(field)
                } else {
                    sb.append(field)
                    val pad = widths[idx] - field.length
                    if (pad > 0) sb.append(" ".repeat(pad))
                    sb.append(outSep)
                }
            }
            sb.append('\n')
            ctx.stdout.writeUtf8(sb.toString())
        }
    }

    private suspend fun emitGrid(
        lines: List<String>,
        opts: Options,
        ctx: CommandContext,
    ) {
        // Tokenize: lines are split on whitespace. Empty lines drop. Each non-empty
        // line yields one item per token (mirrors util-linux default-mode behavior
        // when input is whitespace-delimited).
        val items = ArrayList<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            items += trimmed.split(Regex("\\s+"))
        }
        if (items.isEmpty()) return

        val outSep = opts.outputSep ?: "  "
        val sepLen = outSep.length
        val maxItemLen = items.maxOf { it.length }
        val width = opts.termWidth.coerceAtLeast(1)

        // Determine number of columns: largest N such that the layout fits.
        // For column-first (default), with n items and c cols → r = ceil(n/c) rows.
        // Each column's width is the max item length in that column; we approximate
        // by using maxItemLen across all columns (matches util-linux's simple model).
        // Width consumed = c * maxItemLen + (c - 1) * sepLen <= width.
        var cols =
            if (maxItemLen == 0) {
                1
            } else {
                ((width + sepLen) / (maxItemLen + sepLen)).coerceAtLeast(1)
            }
        if (cols > items.size) cols = items.size

        val n = items.size
        val rows = (n + cols - 1) / cols

        // Per-column widths computed from actual item lengths.
        val colWidths = IntArray(cols)
        if (opts.fillRows) {
            // row-first: item at (r, c) is items[r * cols + c]
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    if (idx < n) {
                        val len = items[idx].length
                        if (len > colWidths[c]) colWidths[c] = len
                    }
                }
            }
            val sb = StringBuilder()
            for (r in 0 until rows) {
                sb.clear()
                var lastCol = -1
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    if (idx < n) lastCol = c
                }
                for (c in 0..lastCol) {
                    val idx = r * cols + c
                    val field = if (idx < n) items[idx] else ""
                    if (c == lastCol) {
                        sb.append(field)
                    } else {
                        sb.append(field)
                        val pad = colWidths[c] - field.length
                        if (pad > 0) sb.append(" ".repeat(pad))
                        sb.append(outSep)
                    }
                }
                sb.append('\n')
                ctx.stdout.writeUtf8(sb.toString())
            }
        } else {
            // column-first: item at (r, c) is items[c * rows + r]
            for (c in 0 until cols) {
                for (r in 0 until rows) {
                    val idx = c * rows + r
                    if (idx < n) {
                        val len = items[idx].length
                        if (len > colWidths[c]) colWidths[c] = len
                    }
                }
            }
            val sb = StringBuilder()
            for (r in 0 until rows) {
                sb.clear()
                // Identify the last column that has an item in this row.
                var lastCol = -1
                for (c in 0 until cols) {
                    val idx = c * rows + r
                    if (idx < n) lastCol = c
                }
                for (c in 0..lastCol) {
                    val idx = c * rows + r
                    val field = if (idx < n) items[idx] else ""
                    if (c == lastCol) {
                        sb.append(field)
                    } else {
                        sb.append(field)
                        val pad = colWidths[c] - field.length
                        if (pad > 0) sb.append(" ".repeat(pad))
                        sb.append(outSep)
                    }
                }
                sb.append('\n')
                ctx.stdout.writeUtf8(sb.toString())
            }
        }
    }
}
