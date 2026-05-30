package com.accucodeai.kash.tools.du

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.matchGlob
import com.accucodeai.kash.fs.DirWalkGuard
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.fs.Paths

/**
 * `du` — POSIX disk-usage utility plus the widely-relied-on GNU
 * extensions. Reports the *block count* consumed by files and
 * directories. Default block size is 1024 bytes (POSIX `-k`); `-h`
 * humanizes; `-b` reports raw bytes; `-B SIZE` accepts a custom block.
 *
 * Output format per dir: `<blocks><TAB><path>`, one line per visited
 * directory (with `-a`, one line per file too). Subdirectory totals
 * roll up into their parent unless `-S` is set.
 *
 * Note on the in-memory FS: kash files have no on-disk block layout,
 * so we approximate "blocks" by `ceil(size / blockSize)`. `-b` /
 * `--apparent-size` reports the raw byte total instead.
 *
 * Flags:
 *   -a                     list per-file totals, not just dirs
 *   -s                     summary mode (one line per operand)
 *   -k                     1K blocks (POSIX default)
 *   -m                     1M blocks
 *   -b                     bytes (alias for --apparent-size -B 1)
 *   -h                     human-readable
 *   -H                     follow symlinks on the command line
 *   -L                     follow all symlinks
 *   -P                     do not follow symlinks (default)
 *   -x                     do not cross mount boundaries (accepted; stubbed)
 *   -c                     produce a trailing `total` line
 *   -d N / --max-depth=N   limit reported depth
 *   -B SIZE / --block-size=SIZE  custom block size
 *   --apparent-size        report byte size, not block-rounded
 *   -S / --separate-dirs   don't roll subdir sizes into parents
 *   -0 / --null            NUL-terminate each line
 *   --exclude=PATTERN      glob exclude (basename match)
 *   --time                 append mtime column
 *   --help                 help
 */
public class DuCommand :
    Command,
    CommandSpec {
    override val name: String = "du"
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

        val operands = opts.operands.ifEmpty { listOf(".") }
        var exit = 0
        var grandTotal = 0L

        for (operand in operands) {
            val abs = Paths.resolve(ctx.cwd, operand)
            val stat =
                try {
                    if (opts.followCmdLine || opts.followAll) {
                        ctx.process.fs.stat(abs)
                    } else {
                        ctx.process.fs.statLink(abs)
                    }
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("du: cannot access '$operand': No such file or directory\n")
                    exit = 1
                    continue
                } catch (e: Exception) {
                    ctx.stderr.writeUtf8("du: cannot access '$operand': ${e.message ?: "error"}\n")
                    exit = 1
                    continue
                }

            val guard = DirWalkGuard(ctx.process.fs, followSymlinks = true)
            val total = walk(abs, operand, stat, depth = 0, opts, ctx, guard)
            grandTotal += total
        }
        if (opts.totalLine) {
            emit(opts, grandTotal, "total", ctx, mtime = 0L)
        }
        return CommandResult(exitCode = exit)
    }

    /**
     * Recursively walk [absPath]. Returns this subtree's total in the
     * configured unit (blocks or bytes — picked by [Opts.byteUnit]).
     * Emits a line for each directory (or file under -a) whose depth
     * is within [Opts.maxDepth].
     */
    private suspend fun walk(
        absPath: String,
        displayPath: String,
        stat: FileStat,
        depth: Int,
        opts: Opts,
        ctx: CommandContext,
        guard: DirWalkGuard,
    ): Long {
        val name = displayPath.substringAfterLast('/').ifEmpty { displayPath }
        if (opts.exclude.any { matchGlob(it, name) }) return 0L

        if (stat.type != FileType.DIRECTORY) {
            val size = sizeOf(stat, opts)
            // POSIX: a file operand always produces a line (it's the only
            // thing this du invocation has to say about it). For files
            // *inside* a recursive walk, -a is required to print them.
            val isOperand = depth == 0
            val show =
                isOperand ||
                    (
                        opts.allFiles &&
                            !opts.summarize &&
                            (opts.maxDepth == null || depth <= opts.maxDepth)
                    )
            if (show) {
                emit(opts, size, displayPath, ctx, stat.mtimeEpochSeconds)
            }
            return size
        }

        // Directory — recurse, then emit our roll-up. The dir-entry's
        // own block count is the size of its directory inode; the
        // in-memory FS reports size=0 for directories which matches
        // what real tmpfs typically shows.
        var subtotal = sizeOf(stat, opts)
        val children =
            try {
                ctx.process.fs.listStat(absPath)
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("du: cannot read directory '$displayPath': ${e.message ?: "error"}\n")
                return subtotal
            }

        for (child in children) {
            val cname = child.path.substringAfterLast('/')
            val cdisp = if (displayPath.endsWith('/')) "$displayPath$cname" else "$displayPath/$cname"
            // -L follows symlinks. A followed symlinked DIRECTORY is descended
            // only if the cycle guard allows; otherwise we keep it as a leaf so
            // the walk can't loop. (Default mode keeps `child`'s lstat type, so
            // symlinks never reach the directory branch in the first place.)
            val cstat =
                if (opts.followAll && child.type == FileType.SYMLINK) {
                    val followed =
                        runCatching { ctx.process.fs.stat(child.path) }.getOrElse { child }
                    if (followed.type == FileType.DIRECTORY &&
                        !guard.shouldDescend(child.path, depth + 1)
                    ) {
                        child
                    } else {
                        followed
                    }
                } else {
                    child
                }
            subtotal += walk(child.path, cdisp, cstat, depth + 1, opts, ctx, guard)
        }

        val show =
            !opts.summarize &&
                (opts.maxDepth == null || depth <= opts.maxDepth)
        val reported = if (opts.separateDirs) sizeOf(stat, opts) else subtotal
        if (show) {
            emit(opts, reported, displayPath, ctx, stat.mtimeEpochSeconds)
        }
        // Summary mode: only emit at the operand level (depth == 0).
        if (opts.summarize && depth == 0) {
            emit(opts, subtotal, displayPath, ctx, stat.mtimeEpochSeconds)
        }
        return subtotal
    }

    /**
     * Bytes when `--apparent-size`/`-b`; otherwise block-rounded
     * ceil(size / blockSize). Directories report their stat size as-is
     * (typically 0 from `InMemoryFs`).
     */
    private fun sizeOf(
        stat: FileStat,
        opts: Opts,
    ): Long {
        if (opts.byteUnit) return stat.size
        val bs = opts.blockSize
        return if (stat.size <= 0L) 0L else (stat.size + bs - 1L) / bs
    }

    private suspend fun emit(
        opts: Opts,
        amount: Long,
        path: String,
        ctx: CommandContext,
        mtime: Long,
    ) {
        val terminator = if (opts.nullTerm) Ansi.NUL else "\n"
        val value =
            when {
                opts.human -> humanReadable(if (opts.byteUnit) amount else amount * opts.blockSize)
                opts.byteUnit -> amount.toString()
                else -> amount.toString()
            }
        val time = if (opts.showTime) "\t${formatMtime(mtime)}" else ""
        ctx.stdout.writeUtf8("$value\t$path$time$terminator")
    }

    private fun formatMtime(epoch: Long): String {
        // ISO-ish YYYY-MM-DD HH:MM — pure arithmetic (Howard Hinnant).
        val safe = if (epoch < 0) 0L else epoch
        val days = safe / 86400
        val tod = safe % 86400
        val hour = (tod / 3600).toInt()
        val minute = ((tod % 3600) / 60).toInt()
        val z = days + 719468L
        val era = if (z >= 0) z / 146097 else (z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (if (m <= 2) y + 1 else y).toInt()
        val mm = m.toString().padStart(2, '0')
        val dd = d.toString().padStart(2, '0')
        val hh = hour.toString().padStart(2, '0')
        val mi = minute.toString().padStart(2, '0')
        return "$year-$mm-$dd $hh:$mi"
    }

    // ---- options ----

    private data class Opts(
        val allFiles: Boolean,
        val summarize: Boolean,
        val blockSize: Long,
        val byteUnit: Boolean,
        val human: Boolean,
        val followCmdLine: Boolean,
        val followAll: Boolean,
        val totalLine: Boolean,
        val maxDepth: Int?,
        val separateDirs: Boolean,
        val nullTerm: Boolean,
        val exclude: List<String>,
        val showTime: Boolean,
        val showHelp: Boolean,
        val operands: List<String>,
    )

    private suspend fun parseOpts(
        args: List<String>,
        ctx: CommandContext,
    ): Opts? {
        var allFiles = false
        var summarize = false
        var blockSize = 1024L
        var byteUnit = false
        var human = false
        var followCmdLine = false
        var followAll = false
        var totalLine = false
        var maxDepth: Int? = null
        var separateDirs = false
        var nullTerm = false
        val exclude = mutableListOf<String>()
        var showTime = false
        var showHelp = false
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

                a == "--apparent-size" -> {
                    byteUnit = true
                    blockSize = 1L
                    i++
                    continue
                }

                a == "--null" -> {
                    nullTerm = true
                    i++
                    continue
                }

                a == "--separate-dirs" -> {
                    separateDirs = true
                    i++
                    continue
                }

                a == "--time" -> {
                    showTime = true
                    i++
                    continue
                }

                a.startsWith("--max-depth=") -> {
                    val v = a.substringAfter('=')
                    val n = v.toIntOrNull()
                    if (n == null || n < 0) {
                        ctx.stderr.writeUtf8("du: invalid max depth: '$v'\n")
                        return null
                    }
                    maxDepth = n
                    i++
                    continue
                }

                a.startsWith("--block-size=") -> {
                    val v = a.substringAfter('=')
                    val n = parseBlockSize(v)
                    if (n == null) {
                        ctx.stderr.writeUtf8("du: invalid block size: '$v'\n")
                        return null
                    }
                    blockSize = n
                    byteUnit = false
                    i++
                    continue
                }

                a.startsWith("--exclude=") -> {
                    exclude += a.substringAfter('=')
                    i++
                    continue
                }

                a == "--exclude" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("du: --exclude requires an argument\n")
                        return null
                    }
                    exclude += args[++i]
                    i++
                    continue
                }

                a == "--total" -> {
                    totalLine = true
                    i++
                    continue
                }

                a.length >= 2 && a[0] == '-' && a[1] != '-' -> {
                    val body = a.drop(1)
                    var bi = 0
                    while (bi < body.length) {
                        val ch = body[bi]
                        when (ch) {
                            'a' -> {
                                allFiles = true
                            }

                            's' -> {
                                summarize = true
                            }

                            'k' -> {
                                blockSize = 1024L
                                byteUnit = false
                            }

                            'm' -> {
                                blockSize = 1024L * 1024L
                                byteUnit = false
                            }

                            'b' -> {
                                byteUnit = true
                                blockSize = 1L
                            }

                            'h' -> {
                                human = true
                            }

                            'H' -> {
                                followCmdLine = true
                            }

                            'L' -> {
                                followAll = true
                            }

                            'P' -> {
                                followAll = false
                                followCmdLine = false
                            }

                            'x' -> {
                                // accepted; we don't currently model mount boundaries
                                // beyond what `process.fs` already routes.
                            }

                            'c' -> {
                                totalLine = true
                            }

                            'S' -> {
                                separateDirs = true
                            }

                            '0' -> {
                                nullTerm = true
                            }

                            'd' -> {
                                val value =
                                    if (bi + 1 < body.length) {
                                        body.substring(bi + 1).also { bi = body.length }
                                    } else if (i + 1 < args.size) {
                                        args[++i]
                                    } else {
                                        ctx.stderr.writeUtf8("du: option -d requires an argument\n")
                                        return null
                                    }
                                val n = value.toIntOrNull()
                                if (n == null || n < 0) {
                                    ctx.stderr.writeUtf8("du: invalid max depth: '$value'\n")
                                    return null
                                }
                                maxDepth = n
                            }

                            'B' -> {
                                val value =
                                    if (bi + 1 < body.length) {
                                        body.substring(bi + 1).also { bi = body.length }
                                    } else if (i + 1 < args.size) {
                                        args[++i]
                                    } else {
                                        ctx.stderr.writeUtf8("du: option -B requires an argument\n")
                                        return null
                                    }
                                val n = parseBlockSize(value)
                                if (n == null) {
                                    ctx.stderr.writeUtf8("du: invalid block size: '$value'\n")
                                    return null
                                }
                                blockSize = n
                                byteUnit = false
                            }

                            else -> {
                                ctx.stderr.writeUtf8("du: invalid option -- '$ch'\n")
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
            allFiles = allFiles,
            summarize = summarize,
            blockSize = blockSize,
            byteUnit = byteUnit,
            human = human,
            followCmdLine = followCmdLine,
            followAll = followAll,
            totalLine = totalLine,
            maxDepth = maxDepth,
            separateDirs = separateDirs,
            nullTerm = nullTerm,
            exclude = exclude,
            showTime = showTime,
            showHelp = showHelp,
            operands = operands,
        )
    }

    private companion object {
        const val HELP: String =
            """Usage: du [options] [PATH...]

Summarize disk usage of files and directories.

  -a              also list per-file totals
  -s              one line per operand (no recursion output)
  -k              1024-byte blocks (default)
  -m              1048576-byte blocks
  -b              raw bytes (implies --apparent-size)
  -h              human-readable sizes
  -H              follow symlinks listed on the command line
  -L              follow all symlinks
  -P              do not follow symlinks (default)
  -x              stay on one filesystem
  -c              produce a trailing total line
  -d N            limit display depth to N
  -B SIZE         custom block size (accepts K, M, G, etc.)
  -S              do not include subdirectories in parent totals
  -0              NUL-terminate lines instead of newline
  --apparent-size    report byte size, not block-rounded
  --max-depth=N      same as -d N
  --block-size=SIZE  same as -B SIZE
  --exclude=PAT      skip entries whose basename matches glob PAT
  --time             append modification time
  --total            same as -c
  --null             same as -0
  --help             show this help
"""
    }
}

/**
 * Parse a coreutils-style block-size argument: a plain integer or an
 * integer followed by a unit (`K`, `M`, `G`, `T`, `P`, optional `B`).
 * Returns null on a parse error. Case-insensitive; `KiB` (powers of
 * 1024) and `KB` (powers of 1000) are both supported.
 */
internal fun parseBlockSize(value: String): Long? {
    if (value.isEmpty()) return null
    var endIdx = value.length
    while (endIdx > 0 && !value[endIdx - 1].isDigit()) endIdx--
    if (endIdx == 0) return null
    val numPart = value.substring(0, endIdx)
    val suffix = value.substring(endIdx).uppercase()
    val num = numPart.toLongOrNull() ?: return null
    if (num <= 0L) return null
    val (factor, _) =
        when (suffix) {
            "", "B" -> 1L to false
            "K", "KB" -> 1000L to false
            "KIB" -> 1024L to true
            "M", "MB" -> 1000L * 1000L to false
            "MIB" -> 1024L * 1024L to true
            "G", "GB" -> 1000L * 1000L * 1000L to false
            "GIB" -> 1024L * 1024L * 1024L to true
            "T", "TB" -> 1000L * 1000L * 1000L * 1000L to false
            "TIB" -> 1024L * 1024L * 1024L * 1024L to true
            "P", "PB" -> 1000L * 1000L * 1000L * 1000L * 1000L to false
            "PIB" -> 1024L * 1024L * 1024L * 1024L * 1024L to true
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
