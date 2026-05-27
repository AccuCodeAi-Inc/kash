package com.accucodeai.kash.tools.cal

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import kotlinx.datetime.toLocalDateTime

/**
 * POSIX `cal` — print a calendar.
 *
 * Forms:
 *  - `cal`                     → current month.
 *  - `cal YYYY`                → full 12-month layout for the given year.
 *  - `cal MM YYYY`             → that month.
 *
 * Flags:
 *  - `-1`              current month only (the default).
 *  - `-3`              previous + current + next month side-by-side.
 *  - `-y`              the whole current year.
 *  - `-m`              Monday is the first day of the week.
 *  - `-s`              Sunday is the first day of the week (default).
 *  - `-j`              show Julian day-of-year numbers (3-digit, 4-col cells).
 *  - `-h`, `--help`    short usage to stdout, exit 0.
 *  - `-V`, `--version` version string to stdout, exit 0.
 *
 * The "today" cell is NOT highlighted — would require terminal escape support
 * that depends on color settings we don't model.
 *
 * Year range supported: 1..9999. Year 0 is rejected (POSIX Gregorian extends
 * back-projected; we conservatively refuse it to avoid silently emitting
 * year-zero calendars).
 */
public class CalCommand :
    Command,
    CommandSpec {
    override val name: String = "cal"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var mondayFirst = false
        var showThree = false
        var showYear = false
        var julian = false
        val positional = mutableListOf<String>()
        var endOfOpts = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                endOfOpts -> {
                    positional += a
                }

                a == "--" -> {
                    endOfOpts = true
                }

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8(helpText())
                    return CommandResult(exitCode = 0)
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("cal (kash)\n")
                    return CommandResult(exitCode = 0)
                }

                a == "-1" -> {
                    Unit
                }

                a == "-3" -> {
                    showThree = true
                }

                a == "-y" -> {
                    showYear = true
                }

                a == "-m" -> {
                    mondayFirst = true
                }

                a == "-s" -> {
                    mondayFirst = false
                }

                a == "-j" -> {
                    julian = true
                }

                a.startsWith("-") && a.length > 1 && a != "-" -> {
                    // Bundled short opts (e.g. -mj, -3j).
                    for (c in a.drop(1)) {
                        when (c) {
                            '1' -> {
                                Unit
                            }

                            '3' -> {
                                showThree = true
                            }

                            'y' -> {
                                showYear = true
                            }

                            'm' -> {
                                mondayFirst = true
                            }

                            's' -> {
                                mondayFirst = false
                            }

                            'j' -> {
                                julian = true
                            }

                            else -> {
                                ctx.stderr.writeUtf8("cal: invalid option -- '$c'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                    }
                }

                else -> {
                    positional += a
                }
            }
            i++
        }

        // Resolve "today" in the machine's local zone. Doing this in UTC
        // would show the wrong date for hosts close to a day boundary
        // (e.g. 21:00 PDT is already tomorrow in UTC). kotlinx-datetime
        // handles the offset — same conversion `date`/`ls`/`git log` use.
        val clock = ctx.process.machine.clock
        val today = clock.now().toLocalDateTime(clock.localTimeZone()).date
        val todayY = today.year
        val todayM = today.month.ordinal + 1

        // Decode operands.
        val targetMonth: Int?
        val targetYear: Int
        val yearLayout: Boolean
        when (positional.size) {
            0 -> {
                targetMonth = todayM
                targetYear = todayY
                yearLayout = showYear
            }

            1 -> {
                val y =
                    positional[0].toIntOrNull() ?: run {
                        ctx.stderr.writeUtf8("cal: not a valid year: '${positional[0]}'\n")
                        return CommandResult(exitCode = 2)
                    }
                if (y < 1 || y > 9999) {
                    ctx.stderr.writeUtf8("cal: year '$y' out of range\n")
                    return CommandResult(exitCode = 2)
                }
                targetYear = y
                targetMonth = null
                yearLayout = true
            }

            2 -> {
                val m =
                    positional[0].toIntOrNull() ?: run {
                        ctx.stderr.writeUtf8("cal: not a valid month: '${positional[0]}'\n")
                        return CommandResult(exitCode = 2)
                    }
                val y =
                    positional[1].toIntOrNull() ?: run {
                        ctx.stderr.writeUtf8("cal: not a valid year: '${positional[1]}'\n")
                        return CommandResult(exitCode = 2)
                    }
                if (m !in 1..12) {
                    ctx.stderr.writeUtf8("cal: month '$m' out of range\n")
                    return CommandResult(exitCode = 2)
                }
                if (y < 1 || y > 9999) {
                    ctx.stderr.writeUtf8("cal: year '$y' out of range\n")
                    return CommandResult(exitCode = 2)
                }
                targetYear = y
                targetMonth = m
                yearLayout = false
            }

            else -> {
                ctx.stderr.writeUtf8("cal: too many arguments\n")
                return CommandResult(exitCode = 2)
            }
        }

        val output =
            when {
                yearLayout -> {
                    renderYear(targetYear, mondayFirst, julian)
                }

                showThree && targetMonth != null -> {
                    val (py, pm) = prevMonth(targetYear, targetMonth)
                    val (ny, nm) = nextMonth(targetYear, targetMonth)
                    renderMonthsSideBySide(
                        listOf(py to pm, targetYear to targetMonth, ny to nm),
                        mondayFirst,
                        julian,
                        showYearInTitle = true,
                    )
                }

                else -> {
                    renderSingleMonth(targetYear, targetMonth!!, mondayFirst, julian)
                }
            }

        ctx.stdout.writeUtf8(output)
        return CommandResult(exitCode = 0)
    }

    private fun helpText(): String =
        buildString {
            append("Usage: cal [-13jmsy] [[MONTH] YEAR]\n")
            append("Print a calendar.\n")
            append("\n")
            append("  -1            show only one month (default)\n")
            append("  -3            show previous, current, and next month\n")
            append("  -j            show Julian day-of-year numbers\n")
            append("  -m            Monday as first day of week\n")
            append("  -s            Sunday as first day of week (default)\n")
            append("  -y            show the whole current year\n")
            append("  -h, --help    display this help and exit\n")
            append("  -V, --version output version information and exit\n")
        }
}

// ============================================================================
// Pure helpers — testable without a CommandContext.
// ============================================================================

/** Days in [month] of [year], honoring leap-year rules. */
internal fun daysInMonth(
    year: Int,
    month: Int,
): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> error("bad month $month")
    }

internal fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

/** Day-of-week for the 1st of [year]/[month] — 0=Sunday..6=Saturday. */
internal fun dayOfWeekFirst(
    year: Int,
    month: Int,
): Int {
    // Zeller's congruence, Gregorian. Convert Jan/Feb to months 13/14 of prev year.
    var y = year
    var m = month
    if (m < 3) {
        m += 12
        y -= 1
    }
    val k = y % 100
    val j = y / 100
    val h = (1 + (13 * (m + 1)) / 5 + k + k / 4 + j / 4 + 5 * j) % 7
    // Zeller: 0=Saturday..6=Friday. Convert to 0=Sunday..6=Saturday.
    return (h + 6) % 7
}

/** Day-of-year (1-based) of [year]/[month]/[day]. */
internal fun dayOfYear(
    year: Int,
    month: Int,
    day: Int,
): Int {
    var d = day
    for (m in 1 until month) d += daysInMonth(year, m)
    return d
}

internal fun prevMonth(
    y: Int,
    m: Int,
): Pair<Int, Int> = if (m == 1) (y - 1) to 12 else y to (m - 1)

internal fun nextMonth(
    y: Int,
    m: Int,
): Pair<Int, Int> = if (m == 12) (y + 1) to 1 else y to (m + 1)

internal val MONTH_NAMES: List<String> =
    listOf(
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December",
    )

internal val WEEKDAYS_SUN: List<String> = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
internal val WEEKDAYS_MON: List<String> = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

/** Civil date for [epochSeconds] — Howard Hinnant's algorithm. UTC. */
internal fun epochSecondsToCivil(epochSeconds: Long): Triple<Int, Int, Int> {
    val days = floorDiv(epochSeconds, 86400L)
    val z = days + 719468L
    val era = floorDiv(if (z >= 0) z else z - 146096L, 146097L)
    val doe = (z - era * 146097L).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + (era * 400L).toInt()
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return Triple(year, m, d)
}

private fun floorDiv(
    a: Long,
    b: Long,
): Long {
    val q = a / b
    return if ((a xor b) < 0L && q * b != a) q - 1 else q
}

// ---------------------------------------------------------------------------
// Rendering. Day-cells are 2-char (or 3-char for Julian); columns separated by
// a single space — so a cell-width is 3 (or 4) including the trailing space.
// Width of a month block is (7 cells * cellWidth) - 1.
// ---------------------------------------------------------------------------

internal fun cellWidth(julian: Boolean): Int = if (julian) 4 else 3

internal fun blockWidth(julian: Boolean): Int = 7 * cellWidth(julian) - 1

internal fun renderSingleMonth(
    year: Int,
    month: Int,
    mondayFirst: Boolean,
    julian: Boolean,
): String {
    val lines = monthBlock(year, month, mondayFirst, julian, includeYearInTitle = true)
    return lines.joinToString(separator = "\n", postfix = "\n")
}

internal fun renderYear(
    year: Int,
    mondayFirst: Boolean,
    julian: Boolean,
): String {
    val sb = StringBuilder()
    val blockW = blockWidth(julian)
    val perRow = if (julian) 2 else 3 // Julian layout is wider; 2 cols fits ~80 chars.
    // Year header centered over the whole grid.
    val totalWidth = perRow * blockW + (perRow - 1) * 2
    val title = year.toString()
    val pad = ((totalWidth - title.length) / 2).coerceAtLeast(0)
    sb.append(" ".repeat(pad)).append(title).append('\n')
    val months = (1..12).map { year to it }
    var i = 0
    while (i < months.size) {
        val group = months.subList(i, (i + perRow).coerceAtMost(months.size))
        val block = renderMonthsSideBySide(group, mondayFirst, julian, showYearInTitle = false)
        sb.append(block)
        i += perRow
        if (i < months.size) sb.append('\n')
    }
    return sb.toString()
}

internal fun renderMonthsSideBySide(
    months: List<Pair<Int, Int>>,
    mondayFirst: Boolean,
    julian: Boolean,
    showYearInTitle: Boolean,
): String {
    val blocks =
        months.map { (y, m) ->
            monthBlock(y, m, mondayFirst, julian, includeYearInTitle = showYearInTitle)
        }
    val maxLines = blocks.maxOf { it.size }
    val blockW = blockWidth(julian)
    val sb = StringBuilder()
    for (row in 0 until maxLines) {
        val cells =
            blocks.map { lines ->
                val line = if (row < lines.size) lines[row] else ""
                line.padEnd(blockW)
            }
        sb.append(cells.joinToString("  "))
        sb.append('\n')
    }
    return sb.toString()
}

/**
 * Render one month as a list of strings (no trailing newlines). Line 0 is
 * the title; line 1 is the weekday header; subsequent lines are weeks.
 */
internal fun monthBlock(
    year: Int,
    month: Int,
    mondayFirst: Boolean,
    julian: Boolean,
    includeYearInTitle: Boolean,
): List<String> {
    val width = blockWidth(julian)
    val title =
        if (includeYearInTitle) "${MONTH_NAMES[month - 1]} $year" else MONTH_NAMES[month - 1]
    val pad = ((width - title.length) / 2).coerceAtLeast(0)
    val titleLine = " ".repeat(pad) + title
    val header =
        (if (mondayFirst) WEEKDAYS_MON else WEEKDAYS_SUN).joinToString(" ") {
            if (julian) " $it" else it
        }

    val lines = mutableListOf<String>()
    lines += titleLine.padEnd(width)
    lines += header.padEnd(width)

    val firstDow = dayOfWeekFirst(year, month) // 0=Sun..6=Sat
    val firstOffset = if (mondayFirst) (firstDow + 6) % 7 else firstDow
    val days = daysInMonth(year, month)
    val cw = cellWidth(julian)

    var day = 1
    var col = 0
    val row = StringBuilder()
    repeat(firstOffset) {
        row.append(" ".repeat(cw))
        col++
    }
    val baseDoy = if (julian) dayOfYear(year, month, 1) else 0
    while (day <= days) {
        val label =
            if (julian) {
                (baseDoy + day - 1).toString().padStart(3, ' ')
            } else {
                day.toString().padStart(2, ' ')
            }
        row.append(label)
        col++
        if (col == 7) {
            lines += row.toString().trimEnd()
            row.clear()
            col = 0
        } else {
            row.append(' ')
        }
        day++
    }
    if (col != 0) {
        // Pad trailing slots so the line has consistent width.
        while (col < 7) {
            row.append(" ".repeat(cw))
            col++
        }
        lines += row.toString().trimEnd()
    }
    // Some `cal` implementations always emit 6 week-rows for a fixed visual
    // height; we don't — saves a blank line for short months.
    return lines
}
