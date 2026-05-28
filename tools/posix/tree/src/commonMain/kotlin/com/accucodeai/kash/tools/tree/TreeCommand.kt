package com.accucodeai.kash.tools.tree

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.ansi.AnsiStyler
import com.accucodeai.kash.api.ansi.ColorMode
import com.accucodeai.kash.api.ansi.Sgr
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.matchGlob
import com.accucodeai.kash.fs.DirWalkGuard
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.fs.Paths

/**
 * `tree` — print a directory hierarchy as an indented tree.
 *
 * Not in POSIX; follows the conventional `tree(1)` flag set widely
 * reflexed in scripts. KDoc and error strings are clean-room.
 *
 * Implemented flags:
 *   -L LEVEL       max depth (LEVEL >= 1)
 *   -d             directories only
 *   -a             include hidden entries (dotfiles)
 *   -f             print each entry's full path instead of basename
 *   -F             classify (append `/`, `*`, `@`)
 *   -i             omit indent lines (just names)
 *   -s             show size in bytes
 *   -h             human-readable sizes (1.2K, 3.4M)
 *   --noreport     skip the trailing "N directories, M files" line
 *   -n / -C        disable / force color (default plain)
 *   --color[=WHEN] color mode (auto/always/never)
 *   -I PATTERN     exclude entries matching glob PATTERN
 *   -P PATTERN     include only entries matching glob PATTERN
 *   --dirsfirst    list directories before files at each level
 *   -r             reverse alphabetical sort
 *   -t             sort by mtime (newest first)
 *   --help, --version
 *
 * The default output uses unicode box-drawing connectors (`├──`, `└──`,
 * `│   `). The synthesized root header is the operand as given (`.` if
 * none, otherwise each PATH).
 */
public class TreeCommand :
    Command,
    CommandSpec {
    override val name: String = "tree"
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
        if (opts.showVersion) {
            ctx.stdout.writeLine("tree (kash) 1.0")
            return CommandResult(exitCode = 0)
        }

        val operands = opts.operands.ifEmpty { listOf(".") }
        val styler = Ansi.stylerFor(ctx, mode = opts.color)
        var dirs = 0L
        var files = 0L
        var exit = 0

        for ((idx, operand) in operands.withIndex()) {
            if (idx > 0) ctx.stdout.writeLine("")
            val abs = Paths.resolve(ctx.cwd, operand)
            val rootStat =
                try {
                    ctx.process.fs.stat(abs)
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("tree: $operand: No such file or directory\n")
                    exit = 1
                    continue
                }
            // The root header line. -f never prefixes the root with size or
            // classifies — that's emitted bare (matches upstream).
            ctx.stdout.writeLine(renderHeader(operand, rootStat, opts, styler))
            if (rootStat.type == FileType.DIRECTORY) {
                val counts = walk(abs, operand, 1, opts, ctx, styler, prefix = "", guard = DirWalkGuard(ctx.process.fs))
                dirs += counts.dirs
                files += counts.files
            }
        }

        if (!opts.noReport) {
            ctx.stdout.writeLine("")
            ctx.stdout.writeLine(
                "$dirs ${if (dirs == 1L) "directory" else "directories"}, $files ${if (files == 1L) "file" else "files"}",
            )
        }
        return CommandResult(exitCode = exit)
    }

    private data class Counts(
        var dirs: Long = 0,
        var files: Long = 0,
    )

    /**
     * Depth-first traversal. `prefix` accumulates the vertical-bar pattern
     * (e.g. `│   │   `) that precedes each child's branch connector.
     * Returns total directory and file counts visited under [absPath].
     */
    private suspend fun walk(
        absPath: String,
        displayPath: String,
        depth: Int,
        opts: TreeOpts,
        ctx: CommandContext,
        styler: AnsiStyler,
        prefix: String,
        guard: DirWalkGuard,
    ): Counts {
        val counts = Counts()
        if (opts.maxDepth != null && depth > opts.maxDepth) return counts

        val raw =
            try {
                ctx.process.fs.listStat(absPath)
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("tree: $displayPath: ${e.message ?: "cannot read directory"}\n")
                return counts
            }

        val filtered =
            raw
                .asSequence()
                .filter { e ->
                    val name = baseName(e.path)
                    when {
                        !opts.all && name.startsWith('.') -> false

                        opts.dirOnly && e.type != FileType.DIRECTORY -> false

                        opts.exclude != null && matchGlob(opts.exclude, name) -> false

                        // -P applies only to files; dirs are still descended.
                        opts.include != null && e.type != FileType.DIRECTORY && !matchGlob(opts.include, name) -> false

                        else -> true
                    }
                }.toList()

        val sorted = sortEntries(filtered, opts)
        for ((i, entry) in sorted.withIndex()) {
            val isLast = i == sorted.size - 1
            val connector =
                if (opts.noIndent) {
                    ""
                } else if (isLast) {
                    LAST
                } else {
                    BRANCH
                }
            val name = baseName(entry.path)
            val displayName =
                if (opts.fullPath) {
                    if (displayPath.endsWith('/')) "$displayPath$name" else "$displayPath/$name"
                } else {
                    name
                }
            val colored = colorize(displayName, entry, styler)
            val suffix =
                buildString {
                    if (opts.classify) append(typeSuffixFor(entry))
                    if (entry.type == FileType.SYMLINK && entry.symlinkTarget != null) {
                        append(" -> ")
                        append(entry.symlinkTarget)
                    }
                }
            val sizeTag = renderSizeTag(entry, opts)
            ctx.stdout.writeLine("$prefix$connector$sizeTag$colored$suffix")

            if (entry.type == FileType.DIRECTORY) {
                counts.dirs++
                // Recurse only into real directories — a symlinked dir is shown
                // above but not descended (avoids symlink-cycle loops).
                if (guard.shouldDescend(entry.path, depth + 1)) {
                    val newPrefix =
                        if (opts.noIndent) {
                            ""
                        } else {
                            prefix + if (isLast) GAP else PIPE
                        }
                    val sub = walk(entry.path, displayName, depth + 1, opts, ctx, styler, newPrefix, guard)
                    counts.dirs += sub.dirs
                    counts.files += sub.files
                }
            } else {
                counts.files++
            }
        }
        return counts
    }

    private fun sortEntries(
        entries: List<FileStat>,
        opts: TreeOpts,
    ): List<FileStat> {
        val byName = entries.sortedBy { baseName(it.path) }
        var sorted: List<FileStat> =
            if (opts.byMtime) byName.sortedByDescending { it.mtimeEpochSeconds } else byName
        if (opts.reverse) sorted = sorted.reversed()
        if (opts.dirsFirst) {
            val dirs = sorted.filter { it.type == FileType.DIRECTORY }
            val rest = sorted.filter { it.type != FileType.DIRECTORY }
            sorted = dirs + rest
        }
        return sorted
    }

    private fun renderHeader(
        operand: String,
        stat: FileStat,
        opts: TreeOpts,
        styler: AnsiStyler,
    ): String {
        val name = colorize(operand, stat, styler)
        val suffix = if (opts.classify) typeSuffixFor(stat) else ""
        return name + suffix
    }

    private fun renderSizeTag(
        entry: FileStat,
        opts: TreeOpts,
    ): String =
        when {
            opts.human -> "[${humanSize(entry.size).padStart(4)}]  "
            opts.size -> "[${entry.size.toString().padStart(11)}]  "
            else -> ""
        }

    private fun colorize(
        name: String,
        stat: FileStat,
        styler: AnsiStyler,
    ): String {
        if (!styler.on) return name
        return when (stat.type) {
            FileType.DIRECTORY -> {
                styler.style(name, Sgr.BOLD, Sgr.FG_BLUE)
            }

            FileType.SYMLINK -> {
                styler.style(name, Sgr.BOLD, Sgr.FG_CYAN)
            }

            FileType.FIFO, FileType.SOCKET, FileType.BLOCK, FileType.CHAR -> {
                styler.style(name, Sgr.FG_YELLOW)
            }

            FileType.REGULAR -> {
                if (stat.mode and 0b001_001_001 != 0) {
                    styler.style(name, Sgr.BOLD, Sgr.FG_GREEN)
                } else {
                    name
                }
            }
        }
    }

    private fun typeSuffixFor(e: FileStat): String =
        when (e.type) {
            FileType.DIRECTORY -> "/"
            FileType.SYMLINK -> "@"
            FileType.FIFO -> "|"
            FileType.SOCKET -> "="
            FileType.REGULAR -> if (e.mode and 0b001_001_001 != 0) "*" else ""
            FileType.BLOCK, FileType.CHAR -> ""
        }

    private fun baseName(path: String): String = path.substringAfterLast('/').ifEmpty { path }

    // ---- option parsing ----

    private data class TreeOpts(
        val maxDepth: Int?,
        val dirOnly: Boolean,
        val all: Boolean,
        val fullPath: Boolean,
        val classify: Boolean,
        val noIndent: Boolean,
        val size: Boolean,
        val human: Boolean,
        val noReport: Boolean,
        val color: ColorMode,
        val exclude: String?,
        val include: String?,
        val dirsFirst: Boolean,
        val reverse: Boolean,
        val byMtime: Boolean,
        val showHelp: Boolean,
        val showVersion: Boolean,
        val operands: List<String>,
    )

    private suspend fun parseOpts(
        args: List<String>,
        ctx: CommandContext,
    ): TreeOpts? {
        var maxDepth: Int? = null
        var dirOnly = false
        var all = false
        var fullPath = false
        var classify = false
        var noIndent = false
        var size = false
        var human = false
        var noReport = false
        var color = ColorMode.NEVER
        var exclude: String? = null
        var include: String? = null
        var dirsFirst = false
        var reverse = false
        var byMtime = false
        var showHelp = false
        var showVersion = false
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

                a == "--version" -> {
                    showVersion = true
                    i++
                    continue
                }

                a == "--noreport" -> {
                    noReport = true
                    i++
                    continue
                }

                a == "--dirsfirst" -> {
                    dirsFirst = true
                    i++
                    continue
                }

                a == "--color" -> {
                    color = ColorMode.AUTO
                    i++
                    continue
                }

                a.startsWith("--color=") -> {
                    val c = ColorMode.parse(a.substringAfter('='))
                    if (c == null) {
                        ctx.stderr.writeUtf8("tree: invalid color mode '${a.substringAfter('=')}'\n")
                        return null
                    }
                    color = c
                    i++
                    continue
                }

                a.length >= 2 && a[0] == '-' && a[1] != '-' -> {
                    val body = a.drop(1)
                    var bi = 0
                    while (bi < body.length) {
                        val ch = body[bi]
                        when (ch) {
                            'L' -> {
                                val value =
                                    if (bi + 1 < body.length) {
                                        body.substring(bi + 1).also { bi = body.length }
                                    } else if (i + 1 < args.size) {
                                        args[++i]
                                    } else {
                                        ctx.stderr.writeUtf8("tree: option -L requires an argument\n")
                                        return null
                                    }
                                val n = value.toIntOrNull()
                                if (n == null || n < 1) {
                                    ctx.stderr.writeUtf8("tree: invalid level: '$value'\n")
                                    return null
                                }
                                maxDepth = n
                            }

                            'I' -> {
                                val value =
                                    if (bi + 1 < body.length) {
                                        body.substring(bi + 1).also { bi = body.length }
                                    } else if (i + 1 < args.size) {
                                        args[++i]
                                    } else {
                                        ctx.stderr.writeUtf8("tree: option -I requires an argument\n")
                                        return null
                                    }
                                exclude = value
                            }

                            'P' -> {
                                val value =
                                    if (bi + 1 < body.length) {
                                        body.substring(bi + 1).also { bi = body.length }
                                    } else if (i + 1 < args.size) {
                                        args[++i]
                                    } else {
                                        ctx.stderr.writeUtf8("tree: option -P requires an argument\n")
                                        return null
                                    }
                                include = value
                            }

                            'd' -> {
                                dirOnly = true
                            }

                            'a' -> {
                                all = true
                            }

                            'f' -> {
                                fullPath = true
                            }

                            'F' -> {
                                classify = true
                            }

                            'i' -> {
                                noIndent = true
                            }

                            's' -> {
                                size = true
                            }

                            'h' -> {
                                human = true
                            }

                            'n' -> {
                                color = ColorMode.NEVER
                            }

                            'C' -> {
                                color = ColorMode.ALWAYS
                            }

                            'r' -> {
                                reverse = true
                            }

                            't' -> {
                                byMtime = true
                            }

                            else -> {
                                ctx.stderr.writeUtf8("tree: invalid option -- '$ch'\n")
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
        return TreeOpts(
            maxDepth = maxDepth,
            dirOnly = dirOnly,
            all = all,
            fullPath = fullPath,
            classify = classify,
            noIndent = noIndent,
            size = size,
            human = human,
            noReport = noReport,
            color = color,
            exclude = exclude,
            include = include,
            dirsFirst = dirsFirst,
            reverse = reverse,
            byMtime = byMtime,
            showHelp = showHelp,
            showVersion = showVersion,
            operands = operands,
        )
    }

    private companion object {
        const val BRANCH = "├── "
        const val LAST = "└── "
        const val PIPE = "│   "
        const val GAP = "    "
        const val HELP: String =
            """Usage: tree [options] [PATH...]

Print directories as an indented tree.

  -L N         descend at most N levels
  -d           list directories only
  -a           include hidden entries (.foo)
  -f           print the full path of each entry
  -F           classify entries (append /, *, @)
  -i           omit indent lines
  -s           print size in bytes
  -h           human-readable sizes
  --noreport   omit trailing summary line
  -n           never use color
  -C           always use color
  --color=WHEN auto | always | never
  -I PATTERN   exclude entries matching glob PATTERN
  -P PATTERN   show only entries matching glob PATTERN
  --dirsfirst  list directories before files
  -r           reverse the sort order
  -t           sort by modification time (newest first)
  --help       this help
  --version    version info
"""
    }
}

internal fun humanSize(bytes: Long): String {
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
