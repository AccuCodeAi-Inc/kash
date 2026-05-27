package com.accucodeai.kash.tools.df

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.fs.MountedFileSystem
import com.accucodeai.kash.fs.Paths

/**
 * `df` — report filesystem disk usage.
 *
 * With no operands, lists every mounted filesystem the session knows about.
 * With one or more PATH operands, lists the filesystem that owns each path.
 *
 * Sizes in kash are computed from the backing FS's recursive file-size sum
 * — there's no underlying block device to ask. For backings without a
 * sensible capacity (`InMemoryFs`, `ToolsFs`), "Available" is reported as
 * `0` (honest "no headroom signal") so awk/grep pipelines computing free-
 * space thresholds get a number, not a literal dash. "Use%" still renders
 * as `-` when total is zero (avoids divide-by-zero nonsense).
 *
 * Flags:
 *   -k         1024-byte blocks (POSIX default)
 *   -m         1M blocks
 *   -B SIZE    custom block size
 *   -h         human-readable
 *   -P         POSIX single-line-per-row format
 *   -i         inode info instead of block info
 *   -t TYPE    filter by backing FS class name (case-insensitive substring)
 *   -T         add "Type" column
 *   -a         include all (no filtering of zero-size mounts) — accepted
 *   --total    add a grand-total row
 *   --help     help
 */
public class DfCommand :
    Command,
    CommandSpec {
    override val name: String = "df"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts =
            parseOpts(args, ctx)
                ?: return CommandResult(exitCode = 2)
        if (opts.showHelp) {
            ctx.stdout.writeUtf8(HELP)
            return CommandResult(exitCode = 0)
        }

        val mfs = ctx.process.machine.fs as? MountedFileSystem
        val mountInfos: List<MountInfo> =
            if (mfs != null) {
                mfs.mounts().map { m ->
                    MountInfo(
                        mountPoint = m.mountPoint,
                        filesystem = m.label.name.lowercase(),
                        type = m.fs::class.simpleName ?: "fs",
                        fs = m.fs,
                        readOnly = m.readOnly,
                    )
                }
            } else {
                listOf(
                    MountInfo(
                        mountPoint = "/",
                        filesystem = "rootfs",
                        type = ctx.process.machine.fs::class.simpleName ?: "fs",
                        fs = ctx.process.machine.fs,
                        readOnly = false,
                    ),
                )
            }

        // Path operands map to the mount that owns each. POSIX df with an
        // operand prints exactly the row for that operand's filesystem.
        val rowsForOperands: List<MountInfo> =
            if (opts.operands.isEmpty()) {
                mountInfos
            } else {
                val sel = mutableListOf<MountInfo>()
                var exit = 0
                for (p in opts.operands) {
                    val abs = Paths.resolve(ctx.cwd, p)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("df: $p: No such file or directory\n")
                        exit = 1
                        continue
                    }
                    val owner =
                        mountInfos.maxByOrNull { mi ->
                            if (abs == mi.mountPoint ||
                                abs.startsWith(if (mi.mountPoint == "/") "/" else "${mi.mountPoint}/") ||
                                mi.mountPoint == "/"
                            ) {
                                mi.mountPoint.length
                            } else {
                                -1
                            }
                        }
                    if (owner != null && owner !in sel) sel += owner
                }
                if (sel.isEmpty()) return CommandResult(exitCode = exit)
                sel
            }

        // Type filter (-t): substring match (case-insensitive) against the
        // backing FS class name. POSIX df's -t is more nuanced (mount-type
        // labels) but we don't have those; class-name match is the closest
        // honest mapping.
        val filtered =
            if (opts.typeFilter != null) {
                rowsForOperands.filter { it.type.contains(opts.typeFilter, ignoreCase = true) }
            } else {
                rowsForOperands
            }

        // Walk each mount and tally size. For inode mode (-i), tally file
        // and directory counts instead.
        val rows = filtered.map { mi -> tally(mi, ctx) }

        val unit = opts.blockSize
        renderTable(rows, opts, unit, ctx)
        if (opts.totalRow && rows.isNotEmpty()) {
            val sumSize = rows.sumOf { it.totalBytes }
            val sumUsed = rows.sumOf { it.usedBytes }
            val sumAvail = rows.sumOf { it.availBytes }
            val sumInodes = rows.sumOf { it.totalInodes }
            val sumIused = rows.sumOf { it.usedInodes }
            renderTotal(
                Row(
                    filesystem = "total",
                    type = "-",
                    totalBytes = sumSize,
                    usedBytes = sumUsed,
                    availBytes = sumAvail,
                    totalInodes = sumInodes,
                    usedInodes = sumIused,
                    mountPoint = "-",
                    knownCapacity = rows.any { it.knownCapacity },
                ),
                opts,
                unit,
                ctx,
            )
        }
        return CommandResult(exitCode = 0)
    }

    /**
     * Walk [mi]'s backing filesystem and report consumed bytes, file count,
     * and directory count. `availBytes` is left at 0 — kash's in-memory
     * backings have no fixed capacity, so we conservatively render "-" in
     * the available column.
     */
    private fun tally(
        mi: MountInfo,
        ctx: CommandContext,
    ): Row {
        var bytes = 0L
        var files = 0L
        var dirs = 0L
        try {
            walk(mi.fs, "/") { stat ->
                when (stat.type) {
                    FileType.DIRECTORY -> {
                        dirs++
                    }

                    else -> {
                        files++
                        bytes += if (stat.size > 0) stat.size else 0L
                    }
                }
            }
        } catch (_: Exception) {
            // Read failures (no permissions, transient FS) — leave the
            // tallies at whatever we accumulated.
        }
        return Row(
            filesystem = mi.filesystem,
            type = mi.type,
            totalBytes = bytes,
            usedBytes = bytes,
            availBytes = 0L,
            totalInodes = dirs + files,
            usedInodes = dirs + files,
            mountPoint = mi.mountPoint,
            knownCapacity = false,
        )
    }

    private fun walk(
        fs: FileSystem,
        path: String,
        cb: (com.accucodeai.kash.fs.FileStat) -> Unit,
    ) {
        val entries =
            try {
                fs.listStat(path)
            } catch (_: Exception) {
                return
            }
        for (e in entries) {
            cb(e)
            if (e.type == FileType.DIRECTORY) walk(fs, e.path, cb)
        }
    }

    private suspend fun renderTable(
        rows: List<Row>,
        opts: Opts,
        blockSize: Long,
        ctx: CommandContext,
    ) {
        if (opts.posixOutput) {
            // POSIX -P: single line per row, fields separated by whitespace.
            // Header line first.
            if (opts.inodes) {
                ctx.stdout.writeLine(
                    "Filesystem ${if (opts.showType) "Type " else ""}Inodes IUsed IFree IUse% Mounted on",
                )
            } else {
                val blockHeader =
                    when {
                        opts.human -> "Size"
                        blockSize == 1024L -> "1024-blocks"
                        blockSize == 1024L * 1024L -> "1M-blocks"
                        else -> "$blockSize-blocks"
                    }
                ctx.stdout.writeLine(
                    "Filesystem ${if (opts.showType) "Type " else ""}$blockHeader Used Available Capacity Mounted on",
                )
            }
            for (r in rows) ctx.stdout.writeLine(formatRow(r, opts, blockSize, fixedWidth = false))
            return
        }

        // Default tabular: compute column widths.
        val formatted = rows.map { Pair(it, formatColumns(it, opts, blockSize)) }
        val cols =
            if (opts.outputCols != null && opts.outputCols.isNotEmpty()) {
                opts.outputCols.map { headerFor(it, opts, blockSize) }
            } else {
                listOf("Filesystem") +
                    (if (opts.showType) listOf("Type") else emptyList()) +
                    (
                        if (opts.inodes) {
                            listOf("Inodes", "IUsed", "IFree", "IUse%")
                        } else {
                            val bh =
                                when {
                                    opts.human -> "Size"
                                    blockSize == 1024L -> "1K-blocks"
                                    blockSize == 1024L * 1024L -> "1M-blocks"
                                    else -> "$blockSize-blocks"
                                }
                            listOf(bh, "Used", "Available", "Use%")
                        }
                    ) +
                    listOf("Mounted on")
            }

        val widths =
            IntArray(cols.size) { idx ->
                val headerW = cols[idx].length
                val cellW = formatted.maxOfOrNull { it.second[idx].length } ?: 0
                maxOf(headerW, cellW)
            }
        ctx.stdout.writeLine(joinRow(cols, widths))
        for ((_, cells) in formatted) ctx.stdout.writeLine(joinRow(cells, widths))
    }

    private suspend fun renderTotal(
        row: Row,
        opts: Opts,
        blockSize: Long,
        ctx: CommandContext,
    ) {
        ctx.stdout.writeLine(formatRow(row, opts, blockSize, fixedWidth = false))
    }

    private fun formatColumns(
        r: Row,
        opts: Opts,
        blockSize: Long,
    ): List<String> {
        // `--output=LIST` selects columns by GNU column name. When set, we
        // emit exactly those (in the requested order); otherwise emit the
        // default layout determined by -i / -T.
        if (opts.outputCols != null && opts.outputCols.isNotEmpty()) {
            return opts.outputCols.map { col -> renderNamedColumn(col, r, opts, blockSize) }
        }
        val cells = mutableListOf<String>()
        cells += r.filesystem
        if (opts.showType) cells += r.type
        if (opts.inodes) {
            cells += r.totalInodes.toString()
            cells += r.usedInodes.toString()
            cells += (r.totalInodes - r.usedInodes).coerceAtLeast(0L).toString()
            cells += "-"
        } else {
            cells += renderBlocks(r.totalBytes, blockSize, opts.human)
            cells += renderBlocks(r.usedBytes, blockSize, opts.human)
            // Available is reported as a number so scripts that pipe through
            // awk/grep can compute thresholds without choking on a literal
            // dash. For in-memory mounts with no fixed capacity we report
            // 0 (an honest "out of capacity headroom" signal).
            cells += renderBlocks(r.availBytes, blockSize, opts.human)
            cells += if (r.totalBytes > 0L) "${(r.usedBytes * 100L / r.totalBytes).coerceIn(0L, 100L)}%" else "-"
        }
        cells += r.mountPoint
        return cells
    }

    /** GNU `--output=LIST` header label per column name. */
    private fun headerFor(
        name: String,
        opts: Opts,
        blockSize: Long,
    ): String =
        when (name) {
            "source" -> {
                "Filesystem"
            }

            "fstype" -> {
                "Type"
            }

            "itotal" -> {
                "Inodes"
            }

            "iused" -> {
                "IUsed"
            }

            "iavail" -> {
                "IFree"
            }

            "ipcent" -> {
                "IUse%"
            }

            "size" -> {
                when {
                    opts.human -> "Size"
                    blockSize == 1024L -> "1K-blocks"
                    blockSize == 1024L * 1024L -> "1M-blocks"
                    else -> "$blockSize-blocks"
                }
            }

            "used" -> {
                "Used"
            }

            "avail" -> {
                "Available"
            }

            "pcent" -> {
                "Use%"
            }

            "file" -> {
                "File"
            }

            "target" -> {
                "Mounted on"
            }

            else -> {
                name
            }
        }

    /** GNU `--output=LIST` column-name dispatch. Unknown names render as "-". */
    private fun renderNamedColumn(
        name: String,
        r: Row,
        opts: Opts,
        blockSize: Long,
    ): String =
        when (name) {
            "source" -> r.filesystem
            "fstype" -> r.type
            "itotal" -> r.totalInodes.toString()
            "iused" -> r.usedInodes.toString()
            "iavail" -> (r.totalInodes - r.usedInodes).coerceAtLeast(0L).toString()
            "ipcent" -> if (r.totalInodes > 0L) "${r.usedInodes * 100L / r.totalInodes}%" else "-"
            "size" -> renderBlocks(r.totalBytes, blockSize, opts.human)
            "used" -> renderBlocks(r.usedBytes, blockSize, opts.human)
            "avail" -> renderBlocks(r.availBytes, blockSize, opts.human)
            "pcent" -> if (r.totalBytes > 0L) "${(r.usedBytes * 100L / r.totalBytes).coerceIn(0L, 100L)}%" else "-"
            "file" -> r.mountPoint
            "target" -> r.mountPoint
            else -> "-"
        }

    private fun formatRow(
        r: Row,
        opts: Opts,
        blockSize: Long,
        @Suppress("UNUSED_PARAMETER") fixedWidth: Boolean,
    ): String = formatColumns(r, opts, blockSize).joinToString(" ")

    private fun joinRow(
        cells: List<String>,
        widths: IntArray,
    ): String = cells.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString(" ").trimEnd()

    private fun renderBlocks(
        bytes: Long,
        blockSize: Long,
        human: Boolean,
    ): String {
        if (human) return humanReadable(bytes)
        if (bytes == 0L) return "0"
        return ((bytes + blockSize - 1) / blockSize).toString()
    }

    // ---- options ----

    private data class Opts(
        val blockSize: Long,
        val human: Boolean,
        val posixOutput: Boolean,
        val inodes: Boolean,
        val typeFilter: String?,
        val showType: Boolean,
        val totalRow: Boolean,
        val showHelp: Boolean,
        val operands: List<String>,
        /** GNU `--output=LIST` column selector; null means default columns. */
        val outputCols: List<String>? = null,
    )

    private data class Row(
        val filesystem: String,
        val type: String,
        val totalBytes: Long,
        val usedBytes: Long,
        val availBytes: Long,
        val totalInodes: Long,
        val usedInodes: Long,
        val mountPoint: String,
        val knownCapacity: Boolean,
    )

    private data class MountInfo(
        val mountPoint: String,
        val filesystem: String,
        val type: String,
        val fs: FileSystem,
        val readOnly: Boolean,
    )

    private suspend fun parseOpts(
        args: List<String>,
        ctx: CommandContext,
    ): Opts? {
        var blockSize = 1024L
        var human = false
        var posixOutput = false
        var inodes = false
        var typeFilter: String? = null
        var showType = false
        var totalRow = false
        var showHelp = false
        var outputCols: List<String>? = null
        val operands = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--") {
                operands += args.drop(i + 1)
                break
            }
            when {
                a == "--help" -> {
                    showHelp = true
                    i++
                    continue
                }

                a == "--total" -> {
                    totalRow = true
                    i++
                    continue
                }

                a.startsWith("--output=") -> {
                    outputCols =
                        a
                            .substringAfter('=')
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    i++
                    continue
                }

                a == "--output" -> {
                    outputCols = emptyList() // bare `--output` = all columns
                    i++
                    continue
                }

                a.startsWith("--block-size=") -> {
                    val v = a.substringAfter('=')
                    val n =
                        parseBlockSize(v) ?: run {
                            ctx.stderr.writeUtf8("df: invalid block size: '$v'\n")
                            return null
                        }
                    blockSize = n
                    i++
                    continue
                }

                a.length >= 2 && a[0] == '-' && a[1] != '-' -> {
                    val body = a.drop(1)
                    var bi = 0
                    while (bi < body.length) {
                        val ch = body[bi]
                        when (ch) {
                            'k' -> {
                                blockSize = 1024L
                            }

                            'm' -> {
                                blockSize = 1024L * 1024L
                            }

                            'h' -> {
                                human = true
                            }

                            'P' -> {
                                posixOutput = true
                            }

                            'i' -> {
                                inodes = true
                            }

                            'T' -> {
                                showType = true
                            }

                            'a' -> { /* accepted */ }

                            't' -> {
                                val value =
                                    if (bi + 1 < body.length) {
                                        body.substring(bi + 1).also { bi = body.length }
                                    } else if (i + 1 < args.size) {
                                        args[++i]
                                    } else {
                                        ctx.stderr.writeUtf8("df: option -t requires an argument\n")
                                        return null
                                    }
                                typeFilter = value
                            }

                            'B' -> {
                                val value =
                                    if (bi + 1 < body.length) {
                                        body.substring(bi + 1).also { bi = body.length }
                                    } else if (i + 1 < args.size) {
                                        args[++i]
                                    } else {
                                        ctx.stderr.writeUtf8("df: option -B requires an argument\n")
                                        return null
                                    }
                                val n =
                                    parseBlockSize(value) ?: run {
                                        ctx.stderr.writeUtf8("df: invalid block size: '$value'\n")
                                        return null
                                    }
                                blockSize = n
                            }

                            else -> {
                                ctx.stderr.writeUtf8("df: invalid option -- '$ch'\n")
                                return null
                            }
                        }
                        bi++
                    }
                    i++
                    continue
                }

                else -> {
                    operands += a
                    i++
                }
            }
        }
        return Opts(
            blockSize = blockSize,
            human = human,
            posixOutput = posixOutput,
            inodes = inodes,
            typeFilter = typeFilter,
            showType = showType,
            totalRow = totalRow,
            showHelp = showHelp,
            operands = operands,
            outputCols = outputCols,
        )
    }

    private companion object {
        const val HELP: String =
            """Usage: df [options] [PATH...]

Report filesystem block-and-inode usage.

  -k         1024-byte blocks (default)
  -m         1048576-byte blocks
  -B SIZE    custom block size
  -h         human-readable sizes
  -P         POSIX-portable output
  -i         inode counts instead of block counts
  -T         add a Type column
  -t TYPE    show only filesystems whose type contains TYPE
  -a         include all filesystems (accepted, no-op)
  --total    add a grand-total row
  --help     show this help
"""
    }
}

internal fun parseBlockSize(value: String): Long? {
    if (value.isEmpty()) return null
    var endIdx = value.length
    while (endIdx > 0 && !value[endIdx - 1].isDigit()) endIdx--
    if (endIdx == 0) return null
    val numPart = value.substring(0, endIdx)
    val suffix = value.substring(endIdx).uppercase()
    val num = numPart.toLongOrNull() ?: return null
    if (num <= 0L) return null
    val factor: Long =
        when (suffix) {
            "", "B" -> 1L
            "K", "KB" -> 1000L
            "KIB" -> 1024L
            "M", "MB" -> 1000L * 1000L
            "MIB" -> 1024L * 1024L
            "G", "GB" -> 1000L * 1000L * 1000L
            "GIB" -> 1024L * 1024L * 1024L
            "T", "TB" -> 1000L * 1000L * 1000L * 1000L
            "TIB" -> 1024L * 1024L * 1024L * 1024L
            "P", "PB" -> 1000L * 1000L * 1000L * 1000L * 1000L
            "PIB" -> 1024L * 1024L * 1024L * 1024L * 1024L
            else -> return null
        }
    return num * factor
}

internal fun humanReadable(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString()
    val units = arrayOf("K", "M", "G", "T", "P")
    var v = bytes.toDouble() / 1024.0
    var idx = 0
    while (v >= 1024.0 && idx < units.lastIndex) {
        v /= 1024.0
        idx++
    }
    val rounded = (v * 10).toLong()
    val whole = rounded / 10
    val frac = rounded % 10
    val numStr = if (whole >= 10) whole.toString() else "$whole.$frac"
    return "$numStr${units[idx]}"
}
