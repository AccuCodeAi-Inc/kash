package com.accucodeai.kash.tools.touch

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths

/**
 * POSIX [`touch`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/touch.html).
 *
 * Creates each operand as an empty file if it doesn't exist; updates the
 * modification time of existing files. The FS picks the timestamp on plain
 * `touch FILE`; `-t`, `-d STRING`, and `-r REF` set it explicitly via
 * [com.accucodeai.kash.fs.FileSystem.setMtime].
 *
 * Read-only mounts (`HOST_BORROW`, `ENGINE_CACHE`) reject `setMtime` with a
 * clear error — no silent no-op.
 *
 * Supported options:
 *  - `-c` / `--no-create` — do not create the file if it doesn't exist.
 *  - `-a`, `-m` — accepted; we don't model atime separately, so both update
 *    mtime. `--time=access` / `--time=modify` long forms accepted.
 *  - `-r REF` / `--reference=REF` — use REF's mtime.
 *  - `-d STRING` / `--date=STRING` — parse STRING as a timestamp.
 *  - `-t STAMP` — POSIX `[[CC]YY]MMDDhhmm[.SS]` (UTC).
 *
 * Exit 0 on success, 1 on any per-operand error.
 */
public class TouchCommand :
    Command,
    CommandSpec {
    override val name: String = "touch"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.STATEFUL)
    override val command: Command get() = this

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var noCreate = false
        var refPath: String? = null
        var dateStr: String? = null
        var stamp: String? = null
        val operands = mutableListOf<String>()
        var endOfOptions = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                endOfOptions -> {
                    operands += a
                }

                a == "--" -> {
                    endOfOptions = true
                }

                a == "--no-create" -> {
                    noCreate = true
                }

                a == "-a" || a == "-m" || a == "--time=access" || a == "--time=modify" -> {
                    Unit
                }

                a == "-r" || a == "--reference" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("touch: option requires an argument -- 'r'\n")
                        return CommandResult(exitCode = 2)
                    }
                    refPath = args[i + 1]
                    i++
                }

                a == "-d" || a == "--date" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("touch: option requires an argument -- 'd'\n")
                        return CommandResult(exitCode = 2)
                    }
                    dateStr = args[i + 1]
                    i++
                }

                a == "-t" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("touch: option requires an argument -- 't'\n")
                        return CommandResult(exitCode = 2)
                    }
                    stamp = args[i + 1]
                    i++
                }

                a.startsWith("--reference=") -> {
                    refPath = a.substring("--reference=".length)
                }

                a.startsWith("--date=") -> {
                    dateStr = a.substring("--date=".length)
                }

                a == "-" -> {
                    operands += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    for (ch in a.drop(1)) {
                        when (ch) {
                            'c' -> {
                                noCreate = true
                            }

                            'a', 'm', 'f' -> {
                                Unit
                            }

                            else -> {
                                ctx.stderr.writeUtf8("touch: invalid option -- '$ch'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                    }
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        if (operands.isEmpty()) {
            ctx.stderr.writeUtf8("touch: missing operand\n")
            return CommandResult(exitCode = 1)
        }

        // Resolve the requested mtime up front. Null = "let the FS decide".
        val explicitMtime: Long? =
            when {
                refPath != null -> {
                    val abs = Paths.resolve(ctx.process.cwd, refPath)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("touch: $refPath: No such file or directory\n")
                        return CommandResult(exitCode = 1)
                    }
                    ctx.process.fs
                        .stat(abs)
                        .mtimeEpochSeconds
                }

                stamp != null -> {
                    parsePosixStamp(stamp) ?: run {
                        ctx.stderr.writeUtf8("touch: invalid date format '$stamp'\n")
                        return CommandResult(exitCode = 1)
                    }
                }

                dateStr != null -> {
                    parseDateString(dateStr) ?: run {
                        ctx.stderr.writeUtf8("touch: invalid date '$dateStr'\n")
                        return CommandResult(exitCode = 1)
                    }
                }

                else -> {
                    null
                }
            }

        var anyError = false
        for (operand in operands) {
            val abs = Paths.resolve(ctx.process.cwd, operand)
            val existed = ctx.process.fs.exists(abs)
            if (!existed) {
                if (noCreate) continue
                val parent = Paths.parent(abs)
                if (!ctx.process.fs.exists(parent)) {
                    ctx.stderr.writeUtf8("touch: $operand: No such file or directory\n")
                    anyError = true
                    continue
                }
                if (!ctx.process.fs.isDirectory(parent)) {
                    ctx.stderr.writeUtf8("touch: $operand: Not a directory\n")
                    anyError = true
                    continue
                }
                try {
                    ctx.process.fs.writeBytes(abs, ByteArray(0), mode = 0b110_110_110 and ctx.process.umask.inv())
                } catch (e: Exception) {
                    ctx.stderr.writeUtf8("touch: $operand: ${e.message ?: "I/O error"}\n")
                    anyError = true
                    continue
                }
            }
            if (explicitMtime != null) {
                try {
                    ctx.process.fs.setMtime(abs, explicitMtime)
                } catch (_: UnsupportedOperationException) {
                    ctx.stderr.writeUtf8("touch: $operand: Read-only file system\n")
                    anyError = true
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("touch: $operand: No such file or directory\n")
                    anyError = true
                } catch (e: Exception) {
                    ctx.stderr.writeUtf8("touch: $operand: ${e.message ?: "I/O error"}\n")
                    anyError = true
                }
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }
}

/**
 * Parse POSIX `-t` timestamp: `[[CC]YY]MMDDhhmm[.SS]`. Returns null on
 * malformed input. Interprets the time as UTC.
 */
internal fun parsePosixStamp(s: String): Long? {
    val (body, secStr) =
        if ('.' in s) {
            val idx = s.indexOf('.')
            s.substring(0, idx) to s.substring(idx + 1)
        } else {
            s to "0"
        }
    val sec = secStr.toIntOrNull() ?: return null
    if (sec !in 0..60) return null
    // Body length: MMDDhhmm=8, YYMMDDhhmm=10, CCYYMMDDhhmm=12.
    if (body.length !in setOf(8, 10, 12) || !body.all { it.isDigit() }) return null
    val (yearStr, rest) =
        when (body.length) {
            12 -> {
                body.substring(0, 4) to body.substring(4)
            }

            10 -> {
                val yy = body.substring(0, 2).toInt()
                // POSIX: 69..99 → 1900s, 00..68 → 2000s.
                val full = if (yy in 69..99) 1900 + yy else 2000 + yy
                full.toString() to body.substring(2)
            }

            8 -> {
                // No year — use current year. We don't have a clock plumbed
                // through; default to 1970 so behavior is deterministic and
                // the user gets surprised loudly rather than silently.
                "1970" to body
            }

            else -> {
                return null
            }
        }
    val year = yearStr.toInt()
    val mo = rest.substring(0, 2).toInt()
    val d = rest.substring(2, 4).toInt()
    val h = rest.substring(4, 6).toInt()
    val mi = rest.substring(6, 8).toInt()
    if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59) return null
    return toEpochSeconds(year, mo, d, h, mi, sec)
}

/**
 * Best-effort parser for `-d STRING`. Recognizes:
 *  - `@<seconds>` — raw epoch.
 *  - `YYYY-MM-DD[ T]HH:MM:SS` — ISO-ish.
 *  - `YYYY-MM-DD` — date only, midnight UTC.
 */
internal fun parseDateString(s: String): Long? {
    val t = s.trim()
    if (t.startsWith("@")) return t.substring(1).toLongOrNull()
    val parts = t.split(' ', 'T', limit = 2)
    val datePart = parts[0]
    val timePart = if (parts.size == 2) parts[1] else "00:00:00"
    val dateBits = datePart.split('-')
    if (dateBits.size != 3) return null
    val year = dateBits[0].toIntOrNull() ?: return null
    val mo = dateBits[1].toIntOrNull() ?: return null
    val d = dateBits[2].toIntOrNull() ?: return null
    val timeBits = timePart.removeSuffix("Z").split(':')
    if (timeBits.size !in 2..3) return null
    val h = timeBits[0].toIntOrNull() ?: return null
    val mi = timeBits[1].toIntOrNull() ?: return null
    val sec = if (timeBits.size == 3) timeBits[2].toIntOrNull() ?: return null else 0
    if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || sec !in 0..60) return null
    return toEpochSeconds(year, mo, d, h, mi, sec)
}

/**
 * Convert (UTC) civil date to epoch seconds via Howard Hinnant's `days_from_civil`
 * algorithm — branch-free, correct for the full Gregorian range.
 */
internal fun toEpochSeconds(
    y: Int,
    mo: Int,
    d: Int,
    h: Int,
    mi: Int,
    s: Int,
): Long {
    val year = if (mo <= 2) y - 1 else y
    val era = (if (year >= 0) year else year - 399) / 400
    val yoe = year - era * 400
    val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097L + doe.toLong() - 719468L
    return days * 86400L + h * 3600L + mi * 60L + s.toLong()
}
