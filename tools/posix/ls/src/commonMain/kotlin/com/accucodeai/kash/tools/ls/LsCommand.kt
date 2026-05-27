package com.accucodeai.kash.tools.ls

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.ansi.AnsiStyler
import com.accucodeai.kash.api.ansi.ColorMode
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.fs.NotADirectory
import com.accucodeai.kash.fs.Paths
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `ls [-1aAdFhlRSrt] FILE...`. Output format follows the POSIX
 * XCU `ls` utility (https://pubs.opengroup.org/onlinepubs/9699919799/utilities/ls.html):
 *
 *  - short form: one entry per line (matches `ls | …` in bash — we don't
 *    column-wrap; stdout is treated as a pipe).
 *  - `-l`: file-mode, link-count, owner, group, size, mtime, name.
 *    Mtime prefers `MMM DD HH:MM` for files modified in the last six
 *    months relative to [clock]; falls back to `MMM DD  YYYY` otherwise.
 *  - `total NNN` block line precedes long listings, counted in 1024-byte
 *    units (POSIX permits 512 or 1024 — we picked the GNU default).
 *
 * The kash [com.accucodeai.kash.fs.FileSystem] doesn't model symlinks or
 * special files yet, so the type column is always `d` or `-` and the
 * symlink-arrow branch is unreachable. The plumbing for those is there;
 * once `ln -s` lands the same rendering picks them up.
 */
public class LsCommand :
    Command,
    CommandSpec {
    override val name: String = "ls"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    /**
     * Clock used to decide the "in the last six months" rule for `-l`. Injected
     * so tests can hold time constant; default reads the current epoch.
     */
    public var clock: () -> Long = { defaultClock() }

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts =
            parseOpts(args)
                ?: run {
                    ctx.stderr.writeUtf8("ls: invalid option\n")
                    return CommandResult(exitCode = 2)
                }

        val operands = opts.operands.ifEmpty { listOf(ctx.process.cwd) }
        val now = clock()
        // Local zone so mtimes render as wall-clock in the host's
        // zone, not UTC. Same source `date` and `git log` consult —
        // the machine's ShellClock.
        val tz =
            ctx.process.machine.clock
                .localTimeZone()
        val styler = Ansi.stylerFor(ctx, mode = opts.color)
        val palette = LsColors.parse(ctx.process.env["LS_COLORS"])
        var exit = 0

        // Split args into files (rendered together at the top) and dirs
        // (each rendered with its own header when multiple are given).
        val fileStats = mutableListOf<FileStat>()
        val dirStats = mutableListOf<Pair<String, FileStat>>()
        for (operand in operands) {
            val path = Paths.resolve(ctx.process.cwd, operand)
            try {
                val stat = ctx.process.fs.stat(path)
                if (stat.type == FileType.DIRECTORY && !opts.dirOnly) {
                    dirStats += operand to stat
                } else {
                    fileStats += stat.copy(path = operand)
                }
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("ls: cannot access '$operand': No such file or directory\n")
                exit = 2
            }
        }

        if (fileStats.isNotEmpty()) {
            renderEntries(sortEntries(fileStats, opts), opts, now, tz, ctx, styler, palette, blockHeader = false)
        }

        val multiHeader = dirStats.size > 1 || (fileStats.isNotEmpty() && dirStats.isNotEmpty())
        for ((idx, pair) in dirStats.withIndex()) {
            val (operand, _) = pair
            if (fileStats.isNotEmpty() || idx > 0) ctx.stdout.writeLine("")
            if (multiHeader) ctx.stdout.writeLine("$operand:")
            try {
                listDir(Paths.resolve(ctx.process.cwd, operand), operand, opts, now, tz, ctx, styler, palette)
            } catch (_: NotADirectory) {
                ctx.stderr.writeUtf8("ls: cannot access '$operand': Not a directory\n")
                exit = 2
            }
        }
        return CommandResult(exitCode = exit)
    }

    private suspend fun listDir(
        absPath: String,
        displayPath: String,
        opts: LsOpts,
        now: Long,
        tz: TimeZone,
        ctx: CommandContext,
        styler: AnsiStyler,
        palette: LsColors,
    ) {
        val raw =
            ctx.process.fs.listStat(absPath) +
                (if (opts.all) dotDirEntriesFor(absPath, ctx) else emptyList())
        val sorted =
            raw
                // Real-dirent dot entries (`.hidden`) still hide without -a;
                // the synthesized `.`/`..` entries we just added are full
                // paths ending in `/.` or `/..`, so their basenames start
                // with `.` and survive only when -a turns the filter off.
                .filter { opts.all || !it.path.substringAfterLast('/').startsWith('.') }
                .let { sortEntries(it, opts) }
        // Show basenames for entries listed inside a directory. The full
        // `sorted` list is kept for recursion below.
        val displayed = sorted.map { it.copy(path = it.path.substringAfterLast('/')) }
        renderEntries(displayed, opts, now, tz, ctx, styler, palette, blockHeader = true)
        if (opts.recursive) {
            for (d in sorted) {
                if (d.type != FileType.DIRECTORY) continue
                val nm = d.path.substringAfterLast('/')
                if (nm == "." || nm == "..") continue
                ctx.stdout.writeLine("")
                val child = if (displayPath.endsWith('/')) "$displayPath$nm" else "$displayPath/$nm"
                ctx.stdout.writeLine("$child:")
                listDir(d.path, child, opts, now, tz, ctx, styler, palette)
            }
        }
    }

    private suspend fun renderEntries(
        entries: List<FileStat>,
        opts: LsOpts,
        now: Long,
        tz: TimeZone,
        ctx: CommandContext,
        styler: AnsiStyler,
        palette: LsColors,
        blockHeader: Boolean,
    ) {
        if (opts.long) {
            if (blockHeader) {
                val total = entries.sumOf { ceilDivKB(it.size) }
                ctx.stdout.writeLine("total $total")
            }
            val widths = columnWidths(entries, opts)
            for (e in entries) ctx.stdout.writeLine(formatLong(e, widths, opts, now, tz, styler, palette))
        } else {
            for (e in entries) ctx.stdout.writeLine(coloredName(e, styler, palette) + typeSuffix(e, opts))
        }
    }

    // ---- option parsing -----------------------------------------------------

    private data class LsOpts(
        val all: Boolean,
        val long: Boolean,
        val dirOnly: Boolean,
        val recursive: Boolean,
        val sortBy: SortKey,
        val reverse: Boolean,
        val classify: Boolean,
        val human: Boolean,
        val color: ColorMode,
        val operands: List<String>,
    )

    private enum class SortKey { NAME, MTIME, SIZE }

    private fun parseOpts(args: List<String>): LsOpts? {
        var all = false
        var long = false
        var dirOnly = false
        var recursive = false
        var sortBy = SortKey.NAME
        var reverse = false
        var classify = false
        var human = false
        var color = ColorMode.NEVER
        val operands = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--") {
                operands += args.drop(i + 1)
                return LsOpts(all, long, dirOnly, recursive, sortBy, reverse, classify, human, color, operands)
            }
            // Long options. `--color`, `--color=WHEN`, and `--color WHEN` are
            // all accepted (matches GNU `ls`). Bare `--color` is `--color=auto`.
            if (a.startsWith("--color")) {
                val parsed: ColorMode? =
                    when {
                        a == "--color" -> ColorMode.AUTO
                        a.startsWith("--color=") -> ColorMode.parse(a.substringAfter('='))
                        else -> null
                    }
                if (parsed == null) return null
                color = parsed
                i++
                continue
            }
            if (a.length >= 2 && a[0] == '-') {
                for (ch in a.drop(1)) {
                    when (ch) {
                        '1' -> long = false
                        'a', 'A' -> all = true
                        'd' -> dirOnly = true
                        'F' -> classify = true
                        'h' -> human = true
                        'l' -> long = true
                        'R' -> recursive = true
                        'r' -> reverse = true
                        'S' -> sortBy = SortKey.SIZE
                        't' -> sortBy = SortKey.MTIME
                        else -> return null
                    }
                }
            } else {
                operands += a
            }
            i++
        }
        return LsOpts(all, long, dirOnly, recursive, sortBy, reverse, classify, human, color, operands)
    }

    // ---- sorting ------------------------------------------------------------

    private fun sortEntries(
        entries: List<FileStat>,
        opts: LsOpts,
    ): List<FileStat> {
        val byName = entries.sortedBy { it.path.substringAfterLast('/') }
        val sorted =
            when (opts.sortBy) {
                SortKey.NAME -> {
                    byName
                }

                SortKey.MTIME -> {
                    byName.sortedByDescending { it.mtimeEpochSeconds }
                }

                SortKey.SIZE -> {
                    byName.sortedByDescending { it.size }
                }
            }
        return if (opts.reverse) sorted.reversed() else sorted
    }

    // ---- rendering ----------------------------------------------------------

    private data class ColumnWidths(
        val nlink: Int,
        val owner: Int,
        val group: Int,
        val size: Int,
    )

    private fun columnWidths(
        entries: List<FileStat>,
        opts: LsOpts,
    ): ColumnWidths =
        ColumnWidths(
            nlink = entries.maxOfOrNull { it.nlink.toString().length } ?: 1,
            owner = entries.maxOfOrNull { it.ownerName.length } ?: 1,
            group = entries.maxOfOrNull { it.groupName.length } ?: 1,
            size = entries.maxOfOrNull { (if (opts.human) humanSize(it.size) else it.size.toString()).length } ?: 1,
        )

    private fun formatLong(
        e: FileStat,
        w: ColumnWidths,
        opts: LsOpts,
        now: Long,
        tz: TimeZone,
        styler: AnsiStyler,
        palette: LsColors,
    ): String {
        val mode = formatModeFor(e.type, e.mode)
        val sizeStr = if (opts.human) humanSize(e.size) else e.size.toString()
        val time = formatTime(e.mtimeEpochSeconds, now, tz)
        val name = coloredName(e, styler, palette) + typeSuffix(e, opts)
        val arrow = e.symlinkTarget?.let { " -> $it" } ?: ""
        return buildString {
            append(mode)
            append(' ')
            append(e.nlink.toString().padStart(w.nlink))
            append(' ')
            append(e.ownerName.padEnd(w.owner))
            append(' ')
            append(e.groupName.padEnd(w.group))
            append(' ')
            append(sizeStr.padStart(w.size))
            append(' ')
            append(time)
            append(' ')
            append(name)
            append(arrow)
        }
    }

    private fun displayName(e: FileStat): String = e.path

    /**
     * POSIX `ls -a` lists `.` and `..` explicitly, even though kash's
     * VFS doesn't return them from `listStat`. We synthesize them here
     * by re-statting the directory itself ([absPath]) and its parent.
     *
     * Paths are stored as `$absPath/.` and `$absPath/..` so the existing
     * basename extraction (`substringAfterLast('/')`) downstream yields
     * `.` and `..` for display, while the path-shape stays consistent
     * with [com.accucodeai.kash.fs.FileSystem.listStat]'s output for the
     * sort + filter pipeline.
     *
     * If either stat fails (root has no parent; permission errors), the
     * entry is dropped — same posture as bash in those situations.
     */
    private fun dotDirEntriesFor(
        absPath: String,
        ctx: CommandContext,
    ): List<FileStat> {
        val base = if (absPath == "/") "" else absPath
        val out = mutableListOf<FileStat>()
        try {
            out +=
                ctx.process.fs
                    .stat(absPath)
                    .copy(path = "$base/.")
        } catch (_: Throwable) {
        }
        val parent =
            if (absPath == "/") {
                "/"
            } else {
                absPath.substringBeforeLast('/', missingDelimiterValue = "/").ifEmpty { "/" }
            }
        try {
            out +=
                ctx.process.fs
                    .stat(parent)
                    .copy(path = "$base/..")
        } catch (_: Throwable) {
        }
        return out
    }

    /**
     * Apply coloring driven by [palette] (the parsed `$LS_COLORS`, with
     * GNU built-in defaults filled in for keys the user didn't override).
     * Extension globs win over the type-key fallback for regular files.
     */
    private fun coloredName(
        e: FileStat,
        styler: AnsiStyler,
        palette: LsColors,
    ): String {
        if (!styler.on) return e.path
        return styler.style(e.path, palette.colorFor(e))
    }

    private fun typeSuffix(
        e: FileStat,
        opts: LsOpts,
    ): String = if (opts.classify) typeSuffixFor(e) else ""

    private fun ceilDivKB(bytes: Long): Long {
        if (bytes <= 0L) return 0L
        return (bytes + 1023L) / 1024L
    }

    private fun humanSize(bytes: Long): String = humanSizeStr(bytes)

    /**
     * POSIX rule: if mtime is in the last six months (and not in the future),
     * format as `MMM dd HH:MM`; otherwise `MMM dd  YYYY` (two spaces between
     * day and year). "Six months" = ~15724800 seconds. See
     * https://pubs.opengroup.org/onlinepubs/9699919799/utilities/ls.html
     */
    private fun formatTime(
        mtime: Long,
        now: Long,
        tz: TimeZone,
    ): String {
        // Convert mtime → local wall-clock in [tz]. kotlinx-datetime
        // handles UTC math, the tz offset, and DST — same conversion
        // `date` and `git log` go through. The "recent" window is
        // compared in UTC since both sides are epoch seconds.
        val ldt = Instant.fromEpochSeconds(mtime).toLocalDateTime(tz)
        val mon = MONTHS[ldt.month.ordinal]
        val dayStr = ldt.day.toString().padStart(2)
        val recent = mtime in (now - 6L * 30 * 24 * 3600) until (now + 1)
        return if (recent) {
            val hh = ldt.hour.toString().padStart(2, '0')
            val mm = ldt.minute.toString().padStart(2, '0')
            "$mon $dayStr $hh:$mm"
        } else {
            val yy = ldt.year.toString().padStart(4)
            "$mon $dayStr  $yy"
        }
    }

    private companion object {
        val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    }
}

private fun defaultClock(): Long = Clock.System.now().epochSeconds

// Internal pure helpers — split out for direct unit testing. Each is callable
// from `LsCommandTest` without going through the full command path.

/**
 * POSIX file-mode string: one type char + three rwx triples. setuid/setgid
 * replace the user/group x slot; sticky replaces the other x slot. Lowercase
 * when the slot is also executable, uppercase otherwise. See
 * https://pubs.opengroup.org/onlinepubs/9699919799/utilities/ls.html
 */
internal fun formatModeFor(
    type: FileType,
    mode: Int,
): String {
    val t =
        when (type) {
            FileType.DIRECTORY -> 'd'
            FileType.SYMLINK -> 'l'
            FileType.FIFO -> 'p'
            FileType.SOCKET -> 's'
            FileType.BLOCK -> 'b'
            FileType.CHAR -> 'c'
            FileType.REGULAR -> '-'
        }
    val setuid = mode and 0b100_000_000_000 != 0
    val setgid = mode and 0b010_000_000_000 != 0
    val sticky = mode and 0b001_000_000_000 != 0
    val u = permTripleFor(mode shr 6 and 0b111, special = setuid, stickyChar = null)
    val g = permTripleFor(mode shr 3 and 0b111, special = setgid, stickyChar = null)
    val o = permTripleFor(mode and 0b111, special = false, stickyChar = if (sticky) 't' to 'T' else null)
    return "$t$u$g$o"
}

private fun permTripleFor(
    bits: Int,
    special: Boolean,
    stickyChar: Pair<Char, Char>?,
): String {
    val r = if (bits and 0b100 != 0) 'r' else '-'
    val w = if (bits and 0b010 != 0) 'w' else '-'
    val x = bits and 0b001 != 0
    val third =
        when {
            stickyChar != null -> if (x) stickyChar.first else stickyChar.second
            special && x -> 's'
            special -> 'S'
            x -> 'x'
            else -> '-'
        }
    return "$r$w$third"
}

internal fun humanSizeStr(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString()
    val units = arrayOf("K", "M", "G", "T", "P")
    var v = bytes.toDouble() / 1024.0
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    val rounded = (v * 10).toLong()
    val whole = rounded / 10
    val frac = rounded % 10
    // GNU `ls -h` always shows one decimal when the number is < 10 ("1.0K"),
    // otherwise drops it ("10K", "234M").
    val numStr = if (whole >= 10) whole.toString() else "$whole.$frac"
    return "$numStr${units[i]}"
}

internal fun typeSuffixFor(e: FileStat): String =
    when (e.type) {
        FileType.DIRECTORY -> "/"
        FileType.SYMLINK -> "@"
        FileType.FIFO -> "|"
        FileType.SOCKET -> "="
        FileType.REGULAR -> if (e.mode and 0b001_001_001 != 0) "*" else ""
        FileType.BLOCK, FileType.CHAR -> ""
    }
