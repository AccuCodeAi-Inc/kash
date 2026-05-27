package com.accucodeai.kash.tools.posix.stat

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.fs.Paths

/**
 * POSIX 2024 / GNU-style `stat` — prints filesystem metadata via
 * [FileSystem.stat] (or [FileSystem.statLink] when `-h`/`--no-dereference` is
 * given). Format-driven via `-c FORMAT`; bare invocation prints a
 * human-readable multi-line block; `-t` prints a terse single-line dump.
 *
 * Supported format specifiers (subset of GNU stat):
 *
 * | Spec | Meaning |
 * |------|---------|
 * | %n   | Name (path as given) |
 * | %s   | Size in bytes |
 * | %F   | File type (`regular file`, `directory`, `symbolic link`, …) |
 * | %a   | Access rights, octal (no leading zero) |
 * | %A   | Access rights, human (`-rwxr-xr-x`) |
 * | %h   | Hard-link count |
 * | %U   | Owner user name |
 * | %G   | Owner group name |
 * | %u   | Owner uid (0 — kash has a single-user model) |
 * | %g   | Owner gid (0) |
 * | %y   | Last modification time, human-readable UTC (`YYYY-MM-DD HH:MM:SS`) |
 * | %Y   | Last modification time, epoch seconds |
 * | %i   | Inode number (path hash — stable per-path proxy) |
 * | %d   | Device number (0 — no device model) |
 * | %N   | Quoted name (and symlink target for links) |
 * | %%   | Literal `%` |
 *
 * Specs we don't yet track (`%x`/`%X` atime, `%z`/`%Z` ctime, `%b`/`%B`
 * block counts, `%t`/`%T` device major/minor, `%w`/`%W` btime) render as
 * `?` for non-numeric or `0` for numeric specs so scripts that scan for
 * them get a stable token.
 */
public class StatCommand :
    Command,
    CommandSpec {
    override val name: String = "stat"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var format: String? = null
        var terse = false
        var dereference = true // -L is default
        val paths = mutableListOf<String>()
        var endOfOptions = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                endOfOptions -> {
                    paths += a
                }

                a == "--" -> {
                    endOfOptions = true
                }

                a == "-c" || a == "--format" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("stat: option requires an argument -- '${a.trimStart('-')}'\n")
                        return CommandResult(exitCode = 2)
                    }
                    format = args[i + 1]
                    i++
                }

                a.startsWith("--format=") -> {
                    format = a.removePrefix("--format=")
                }

                a.startsWith("--printf=") -> {
                    // GNU: --printf interprets backslash escapes and doesn't append a newline.
                    // We treat it like --format (escapes are already handled by our renderer).
                    format = a.removePrefix("--printf=")
                }

                a.startsWith("-c") && a.length > 2 -> {
                    format = a.substring(2)
                }

                a == "-t" || a == "--terse" -> {
                    terse = true
                }

                a == "-L" || a == "--dereference" -> {
                    dereference = true
                }

                a == "-h" || a == "--no-dereference" -> {
                    dereference = false
                }

                a == "-f" || a == "--file-system" -> {
                    ctx.stderr.writeUtf8("stat: -f/--file-system not supported in kash\n")
                    return CommandResult(exitCode = 2)
                }

                a == "--help" -> {
                    ctx.stdout.writeUtf8(HELP_TEXT)
                    return CommandResult(exitCode = 0)
                }

                a == "-" -> {
                    paths += a
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("stat: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Cluster of short flags.
                    for (ch in a.drop(1)) {
                        when (ch) {
                            't' -> {
                                terse = true
                            }

                            'L' -> {
                                dereference = true
                            }

                            'h' -> {
                                dereference = false
                            }

                            else -> {
                                ctx.stderr.writeUtf8("stat: invalid option -- '$ch'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                    }
                }

                else -> {
                    paths += a
                }
            }
            i++
        }

        if (paths.isEmpty()) {
            ctx.stderr.writeUtf8("stat: missing operand\n")
            return CommandResult(exitCode = 1)
        }

        var anyError = false
        for (path in paths) {
            val abs = Paths.resolve(ctx.process.cwd, path)
            val stat =
                try {
                    if (dereference) ctx.process.fs.stat(abs) else ctx.process.fs.statLink(abs)
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("stat: cannot stat '$path': No such file or directory\n")
                    anyError = true
                    continue
                }
            val displayed = stat.copy(path = path)
            val rendered =
                when {
                    format != null -> renderFormat(format, displayed)
                    terse -> renderTerse(displayed)
                    else -> renderDefault(displayed)
                }
            ctx.stdout.writeLine(rendered)
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private companion object {
        const val HELP_TEXT =
            """Usage: stat [OPTION]... FILE...
Display file status.

  -c, --format=FORMAT  use the specified FORMAT instead of the default
      --printf=FORMAT  like --format but no implicit newline
  -L, --dereference    follow symbolic links (default)
  -h, --no-dereference do not follow symbolic links
  -t, --terse          print the information in terse form
      --help           display this help and exit

Format specifiers: %n %s %F %a %A %h %U %G %u %g %y %Y %i %d %N %%
"""
    }
}

internal fun fileTypeName(type: FileType): String =
    when (type) {
        FileType.REGULAR -> "regular file"
        FileType.DIRECTORY -> "directory"
        FileType.SYMLINK -> "symbolic link"
        FileType.FIFO -> "fifo"
        FileType.SOCKET -> "socket"
        FileType.BLOCK -> "block special file"
        FileType.CHAR -> "character special file"
    }

/** Octal mode without leading zero — bash-stat convention. e.g. `644`, `755`. */
internal fun modeOctal(mode: Int): String = (mode and 0xFFF).toString(8)

/** Human-readable mode string: `-rwxr-xr-x`. Mirrors POSIX ls -l. */
internal fun modeHuman(
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
    val u = triple(mode shr 6 and 0b111, special = setuid)
    val g = triple(mode shr 3 and 0b111, special = setgid)
    val o = tripleSticky(mode and 0b111, sticky = sticky)
    return "$t$u$g$o"
}

private fun triple(
    bits: Int,
    special: Boolean,
): String {
    val r = if (bits and 0b100 != 0) 'r' else '-'
    val w = if (bits and 0b010 != 0) 'w' else '-'
    val x = bits and 0b001 != 0
    val third =
        when {
            special && x -> 's'
            special -> 'S'
            x -> 'x'
            else -> '-'
        }
    return "$r$w$third"
}

private fun tripleSticky(
    bits: Int,
    sticky: Boolean,
): String {
    val r = if (bits and 0b100 != 0) 'r' else '-'
    val w = if (bits and 0b010 != 0) 'w' else '-'
    val x = bits and 0b001 != 0
    val third =
        when {
            sticky && x -> 't'
            sticky -> 'T'
            x -> 'x'
            else -> '-'
        }
    return "$r$w$third"
}

/**
 * Inode proxy: kash FS doesn't track inodes, but `stat -c %i` callers
 * want a stable per-path token. Use a non-negative path hash so the same
 * path always returns the same value within a session.
 */
internal fun inodeFor(path: String): Long {
    var h = 1469598103934665603uL // FNV-1a 64
    for (c in path) {
        h = h xor (c.code.toULong() and 0xFFuL)
        h = h * 1099511628211uL
    }
    return (h and Long.MAX_VALUE.toULong()).toLong()
}

/**
 * Format epoch seconds as `YYYY-MM-DD HH:MM:SS` UTC. Branch-free civil
 * date math (Hinnant's algorithm).
 */
internal fun formatMtimeHuman(epoch: Long): String {
    // Floor division (KMP-portable — no Math.floorDiv).
    val daysFromEpoch =
        if (epoch >= 0 || epoch % 86400L == 0L) {
            epoch / 86400L
        } else {
            epoch / 86400L - 1L
        }
    val secOfDay = (epoch - daysFromEpoch * 86400L).toInt()
    val (y, mo, d) = civilFromDays(daysFromEpoch)
    val h = secOfDay / 3600
    val mi = secOfDay / 60 % 60
    val s = secOfDay % 60
    return buildString(19) {
        append(y.toString().padStart(4, '0'))
        append('-').append(mo.toString().padStart(2, '0'))
        append('-').append(d.toString().padStart(2, '0'))
        append(' ').append(h.toString().padStart(2, '0'))
        append(':').append(mi.toString().padStart(2, '0'))
        append(':').append(s.toString().padStart(2, '0'))
    }
}

private fun civilFromDays(z: Long): Triple<Int, Int, Int> {
    val zAdj = z + 719468L
    val era = (if (zAdj >= 0) zAdj else zAdj - 146096L) / 146097L
    val doe = (zAdj - era * 146097L).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era.toInt() * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return Triple(year, m, d)
}

internal fun renderFormat(
    fmt: String,
    info: FileStat,
): String {
    val sb = StringBuilder(fmt.length)
    var i = 0
    while (i < fmt.length) {
        val c = fmt[i]
        if (c == '\\' && i + 1 < fmt.length) {
            when (fmt[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                '0' -> sb.append(' ')
                '\\' -> sb.append('\\')
                '"' -> sb.append('"')
                else -> sb.append(c).append(fmt[i + 1])
            }
            i += 2
            continue
        }
        if (c != '%' || i + 1 >= fmt.length) {
            sb.append(c)
            i++
            continue
        }
        val spec = fmt[i + 1]
        when (spec) {
            'n' -> {
                sb.append(info.path)
            }

            's' -> {
                sb.append(info.size)
            }

            'F' -> {
                sb.append(fileTypeName(info.type))
            }

            'a' -> {
                sb.append(modeOctal(info.mode))
            }

            'A' -> {
                sb.append(modeHuman(info.type, info.mode))
            }

            'h' -> {
                sb.append(info.nlink)
            }

            'U' -> {
                sb.append(info.ownerName)
            }

            'G' -> {
                sb.append(info.groupName)
            }

            'u' -> {
                sb.append('0')
            }

            'g' -> {
                sb.append('0')
            }

            'y' -> {
                sb.append(formatMtimeHuman(info.mtimeEpochSeconds))
            }

            'Y' -> {
                sb.append(info.mtimeEpochSeconds)
            }

            'i' -> {
                sb.append(inodeFor(info.path))
            }

            'd' -> {
                sb.append('0')
            }

            'N' -> {
                sb.append('\'').append(info.path).append('\'')
                if (info.type == FileType.SYMLINK && info.symlinkTarget != null) {
                    sb.append(" -> '").append(info.symlinkTarget).append('\'')
                }
            }

            '%' -> {
                sb.append('%')
            }

            // Numeric stubs (legacy / not modeled).
            'b', 'B', 't', 'T', 'W' -> {
                sb.append('0')
            }

            // Non-numeric / time stubs.
            'x', 'X', 'z', 'Z', 'w' -> {
                sb.append('?')
            }

            else -> {
                sb.append('%').append(spec)
            }
        }
        i += 2
    }
    return sb.toString()
}

internal fun renderDefault(info: FileStat): String =
    buildString {
        append("  File: ").append(info.path)
        if (info.type == FileType.SYMLINK && info.symlinkTarget != null) {
            append(" -> ").append(info.symlinkTarget)
        }
        append('\n')
        append("  Size: ").append(info.size)
        append('\t').append("Links: ").append(info.nlink)
        append('\t').append(fileTypeName(info.type)).append('\n')
        append("Access: (")
            .append(modeOctal(info.mode).padStart(4, '0'))
            .append('/')
            .append(modeHuman(info.type, info.mode))
            .append(")  Uid: (    0/  ")
            .append(info.ownerName)
            .append(")   Gid: (    0/  ")
            .append(info.groupName)
            .append(")\n")
        append("Modify: ").append(formatMtimeHuman(info.mtimeEpochSeconds))
    }

/** GNU `-t` terse layout: `name size blocks mode uid gid device inode links majdev mindev atime mtime ctime btime blocksize`. */
internal fun renderTerse(info: FileStat): String =
    buildString {
        append(info.path).append(' ')
        append(info.size).append(' ')
        append(0).append(' ') // blocks (not modeled)
        append(modeOctal(info.mode)).append(' ')
        append(0).append(' ') // uid
        append(0).append(' ') // gid
        append(0).append(' ') // device
        append(inodeFor(info.path)).append(' ')
        append(info.nlink).append(' ')
        append(0).append(' ') // major
        append(0).append(' ') // minor
        append(0).append(' ') // atime
        append(info.mtimeEpochSeconds).append(' ')
        append(0).append(' ') // ctime
        append(0).append(' ') // btime
        append(0) // blocksize
    }
