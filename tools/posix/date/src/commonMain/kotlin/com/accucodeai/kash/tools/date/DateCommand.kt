package com.accucodeai.kash.tools.date

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * POSIX 🅄 `date` — print or set the system date.
 *
 * Setting the system date is unsupported (kash has no system to set);
 * this command only prints. POSIX `date [-u] [+format]` is fully covered;
 * common GNU extensions (`%s`, `%F`, `%N`, `%z`, `-d STRING`, `-Iext`,
 * `-R`) ship too because real scripts use them.
 *
 * Reads time from `ctx.process.machine.clock` so conformance tests pin
 * the wall instant and `+%s` round-trips deterministically.
 *
 * Time zone: respects `$TZ`. `TZ=UTC0`, `TZ=UTC`, or empty `TZ` → UTC;
 * other values fall back to UTC with no warning (full IANA tz handling
 * is out of scope for v1 — POSIX TZ-rules parsing is multi-week work
 * and none of the test corpora exercise non-UTC zones).
 *
 * Locale is fixed to `C`: weekday/month names are baked-in English
 * abbreviations. Matches the conformance environment (`LC_ALL=C`).
 */
public class DateCommand :
    Command,
    CommandSpec {
    override val name: String = "date"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        // Parse options in a single pass. Operands starting with `+` are the
        // format string (only one allowed). POSIX says non-option operands
        // without `+` may set the system time; we don't support that — exit 1
        // with a diagnostic.
        var utc = false
        var dateString: String? = null
        var refFile: String? = null
        var format: String? = null
        var isoExt: String? = null
        var rfc2822 = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--" -> {
                    i++
                    break
                }

                a == "-u" || a == "--utc" || a == "--universal" -> {
                    utc = true
                    i++
                }

                a == "-R" || a == "--rfc-2822" || a == "--rfc-email" -> {
                    rfc2822 = true
                    i++
                }

                a == "-d" || a == "--date" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("date: option requires an argument -- 'd'\n")
                        return CommandResult(exitCode = 2)
                    }
                    dateString = args[i + 1]
                    i += 2
                }

                a.startsWith("--date=") -> {
                    dateString = a.removePrefix("--date=")
                    i++
                }

                a == "-r" || a == "--reference" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("date: option requires an argument -- 'r'\n")
                        return CommandResult(exitCode = 2)
                    }
                    refFile = args[i + 1]
                    i += 2
                }

                a.startsWith("--reference=") -> {
                    refFile = a.removePrefix("--reference=")
                    i++
                }

                a == "-I" -> {
                    isoExt = "date"
                    i++
                }

                a.startsWith("-I") -> {
                    isoExt = a.removePrefix("-I")
                    i++
                }

                a.startsWith("--iso-8601") -> {
                    isoExt = if (a == "--iso-8601") "date" else a.removePrefix("--iso-8601=")
                    i++
                }

                a == "--help" -> {
                    ctx.stdout.writeUtf8(USAGE)
                    return CommandResult()
                }

                a == "--version" -> {
                    ctx.stdout.writeUtf8("date (kash) 1.0\n")
                    return CommandResult()
                }

                a.startsWith("+") -> {
                    if (format != null) {
                        ctx.stderr.writeUtf8("date: multiple output formats specified\n")
                        return CommandResult(exitCode = 1)
                    }
                    format = a.substring(1)
                    i++
                }

                a.startsWith("-") -> {
                    ctx.stderr.writeUtf8("date: invalid option -- '${a.removePrefix("-")}'\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    // POSIX: `date MMDDhhmm[[CC]YY][.ss]` sets the time. Not supported.
                    ctx.stderr.writeUtf8("date: setting the date is not supported\n")
                    return CommandResult(exitCode = 1)
                }
            }
        }

        // Resolve the instant being formatted.
        val instant: Instant =
            when {
                refFile != null -> {
                    val resolved =
                        if (refFile.startsWith("/")) {
                            refFile
                        } else {
                            "${ctx.process.cwd.trimEnd('/')}/$refFile"
                        }
                    val mtime =
                        try {
                            ctx.process.fs
                                .stat(resolved)
                                .mtimeEpochSeconds
                        } catch (e: Throwable) {
                            ctx.stderr.writeUtf8("date: $refFile: ${e.message ?: "no such file"}\n")
                            return CommandResult(exitCode = 1)
                        }
                    Instant.fromEpochSeconds(mtime)
                }

                dateString != null -> {
                    parseDateString(dateString) ?: run {
                        ctx.stderr.writeUtf8("date: invalid date '$dateString'\n")
                        return CommandResult(exitCode = 1)
                    }
                }

                else -> {
                    ctx.process.machine.clock
                        .now()
                }
            }

        // Resolve the timezone:
        //  - `-u` / `--utc` / `--universal` → UTC.
        //  - `$TZ` set to a recognized name → that zone (full IANA via
        //    `TimeZone.of`; falls back to UTC on unknown strings).
        //  - else → the machine's local zone from [ShellClock]
        //    (`TimeZone.currentSystemDefault()` in prod, UTC in tests).
        val tz: TimeZone =
            when {
                utc -> {
                    TimeZone.UTC
                }

                ctx.env["TZ"].isNullOrEmpty() -> {
                    ctx.process.machine.clock
                        .localTimeZone()
                }

                else -> {
                    tzFromEnv(ctx.env["TZ"])
                }
            }

        // ISO 8601 (`-I[ext]`) wins over format/RFC because POSIX/GNU treat
        // it that way when both are passed (last one set takes effect).
        val effectiveFormat: String =
            when {
                rfc2822 -> {
                    RFC2822_FORMAT
                }

                isoExt != null -> {
                    isoFormat(isoExt) ?: run {
                        ctx.stderr.writeUtf8("date: invalid argument '$isoExt' for '--iso-8601'\n")
                        return CommandResult(exitCode = 1)
                    }
                }

                format != null -> {
                    format
                }

                else -> {
                    POSIX_DEFAULT_FORMAT
                }
            }

        val rendered =
            try {
                formatInstant(instant, tz, effectiveFormat)
            } catch (e: IllegalArgumentException) {
                ctx.stderr.writeUtf8("date: ${e.message ?: "format error"}\n")
                return CommandResult(exitCode = 1)
            }
        ctx.stdout.writeUtf8(rendered + "\n")
        return CommandResult()
    }
}

private const val POSIX_DEFAULT_FORMAT = "%a %b %e %H:%M:%S %Z %Y"
private const val RFC2822_FORMAT = "%a, %d %b %Y %H:%M:%S %z"

private fun isoFormat(ext: String): String? =
    when (ext) {
        "date" -> "%Y-%m-%d"
        "hours" -> "%Y-%m-%dT%H%z"
        "minutes" -> "%Y-%m-%dT%H:%M%z"
        "seconds" -> "%Y-%m-%dT%H:%M:%S%z"
        "ns" -> "%Y-%m-%dT%H:%M:%S,%N%z"
        else -> null
    }

/**
 * `-d` accepts a wide string-grammar. We support a useful subset:
 *
 *  - `@SECONDS` — Unix epoch.
 *  - `YYYY-MM-DD` — date only; midnight UTC.
 *  - `YYYY-MM-DD HH:MM[:SS]` — space-separated date/time; treated as UTC.
 *  - `YYYY-MM-DDTHH:MM:SS[Z]` — ISO 8601.
 *
 * Relative-time grammar (`yesterday`, `now - 1 hour`, `next Monday`, …) is
 * intentionally not supported — it's a large grammar and out of scope for
 * v1. Callers requesting unsupported forms see `date: invalid date '…'`.
 */
internal fun parseDateString(s: String): Instant? {
    if (s.startsWith("@")) {
        val secs = s.drop(1).toLongOrNull() ?: return null
        return Instant.fromEpochSeconds(secs)
    }
    // Plain `YYYY-MM-DD` → midnight UTC.
    if (s.length == 10 && s[4] == '-' && s[7] == '-') {
        return try {
            Instant.parse("${s}T00:00:00Z")
        } catch (_: Throwable) {
            null
        }
    }
    // `YYYY-MM-DD HH:MM[:SS]` → swap space for `T`, append seconds if absent.
    if (s.length >= 16 && s[4] == '-' && s[7] == '-' && s[10] == ' ' && s[13] == ':') {
        val withT = s.replaceFirst(' ', 'T')
        val withSecs = if (withT.length == 16) "$withT:00" else withT
        return try {
            Instant.parse("${withSecs}Z")
        } catch (_: Throwable) {
            null
        }
    }
    return try {
        Instant.parse(s.let { if (it.endsWith("Z")) it else "${it}Z" })
    } catch (_: Throwable) {
        null
    }
}

/**
 * Resolve `$TZ` to a [TimeZone]. The conformance env sets `TZ=UTC0`
 * (POSIX-style "UTC, offset 0"); empty / `UTC` / `UTC0` → UTC.
 *
 * Other values are tried as IANA zone ids via [TimeZone.of] — so
 * `TZ=America/Los_Angeles`, `TZ=Europe/Paris`, `TZ=UTC+05:30` etc.
 * all work transparently. Unknown ids fall back to UTC silently
 * (full POSIX TZ-string parsing `STD3DST,M3.2.0,M11.1.0` is still
 * out of scope; that's what bash itself uses tzdata for).
 */
internal fun tzFromEnv(tz: String?): TimeZone {
    if (tz.isNullOrEmpty()) return TimeZone.UTC
    return when (tz) {
        "UTC", "UTC0", "GMT", "GMT0" -> TimeZone.UTC
        else -> runCatching { TimeZone.of(tz) }.getOrDefault(TimeZone.UTC)
    }
}

/** Format [instant] in [tz] using a POSIX `+FORMAT` string. */
internal fun formatInstant(
    instant: Instant,
    tz: TimeZone,
    format: String,
): String {
    val ldt: LocalDateTime = instant.toLocalDateTime(tz)
    val sb = StringBuilder()
    var i = 0
    while (i < format.length) {
        val c = format[i]
        if (c != '%' || i + 1 >= format.length) {
            sb.append(c)
            i++
            continue
        }
        // GNU "no-pad" flag: `%-d`, `%-H`, `%-M`, etc. Render normally
        // then strip the leading zero(s) from the produced digits.
        if (format[i + 1] == '-' && i + 2 < format.length) {
            val spec = format[i + 2]
            i += 3
            val mark = sb.length
            appendConversion(sb, spec, ldt, instant, tz)
            stripLeadingZeros(sb, mark)
            continue
        }
        val spec = format[i + 1]
        i += 2
        appendConversion(sb, spec, ldt, instant, tz)
    }
    return sb.toString()
}

/** Strip a run of leading `0`s from the substring starting at [from], leaving
 *  at least one digit. Used by GNU `%-X` no-pad. Non-digit substrings (e.g. a
 *  literal `%-X` echoed for an unknown spec) are left alone. */
private fun stripLeadingZeros(
    sb: StringBuilder,
    from: Int,
) {
    var p = from
    while (p < sb.length - 1 && sb[p] == '0' && sb[p + 1].isDigit()) {
        sb.deleteAt(p)
    }
}

private val WEEKDAY_ABBR = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WEEKDAY_FULL = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val MONTH_ABBR = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTH_FULL =
    arrayOf(
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

private fun appendConversion(
    sb: StringBuilder,
    spec: Char,
    ldt: LocalDateTime,
    instant: Instant,
    tz: TimeZone,
) {
    // bash spec covers POSIX + the GNU extensions scripts actually use.
    when (spec) {
        // --- POSIX ---
        'a' -> {
            sb.append(WEEKDAY_ABBR[ldt.dayOfWeek.ordinal])
        }

        'A' -> {
            sb.append(WEEKDAY_FULL[ldt.dayOfWeek.ordinal])
        }

        'b', 'h' -> {
            sb.append(MONTH_ABBR[ldt.month.ordinal])
        }

        'B' -> {
            sb.append(MONTH_FULL[ldt.month.ordinal])
        }

        'c' -> {
            // Locale-default; POSIX C locale ≈ `%a %b %e %H:%M:%S %Y`.
            sb.append(formatInstant(instant, tz, "%a %b %e %H:%M:%S %Y"))
        }

        'C' -> {
            sb.append((ldt.year / 100).toString().padStart(2, '0'))
        }

        'd' -> {
            sb.append(ldt.day.toString().padStart(2, '0'))
        }

        'D' -> {
            sb.append(formatInstant(instant, tz, "%m/%d/%y"))
        }

        'e' -> {
            sb.append(ldt.day.toString().padStart(2, ' '))
        }

        'H' -> {
            sb.append(ldt.hour.toString().padStart(2, '0'))
        }

        'I' -> {
            val h12 = ((ldt.hour + 11) % 12) + 1
            sb.append(h12.toString().padStart(2, '0'))
        }

        'j' -> {
            sb.append(ldt.dayOfYear.toString().padStart(3, '0'))
        }

        'm' -> {
            sb.append((ldt.month.ordinal + 1).toString().padStart(2, '0'))
        }

        'M' -> {
            sb.append(ldt.minute.toString().padStart(2, '0'))
        }

        'n' -> {
            sb.append('\n')
        }

        'p' -> {
            sb.append(if (ldt.hour < 12) "AM" else "PM")
        }

        'r' -> {
            sb.append(formatInstant(instant, tz, "%I:%M:%S %p"))
        }

        'S' -> {
            sb.append(ldt.second.toString().padStart(2, '0'))
        }

        't' -> {
            sb.append('\t')
        }

        'T' -> {
            sb.append(formatInstant(instant, tz, "%H:%M:%S"))
        }

        'u' -> {
            sb.append((ldt.dayOfWeek.ordinal + 1).toString())
        }

        // Mon=1..Sun=7
        'U' -> {
            sb.append(weekOfYearSundayBased(ldt).toString().padStart(2, '0'))
        }

        'V' -> {
            sb.append(isoWeek(ldt).toString().padStart(2, '0'))
        }

        'w' -> {
            // Sun=0..Sat=6 — kotlinx DayOfWeek ordinal is Mon=0..Sun=6,
            // so map Sun→0, Mon→1, …, Sat→6.
            val w = if (ldt.dayOfWeek.ordinal == 6) 0 else ldt.dayOfWeek.ordinal + 1
            sb.append(w.toString())
        }

        'W' -> {
            sb.append(weekOfYearMondayBased(ldt).toString().padStart(2, '0'))
        }

        'x' -> {
            sb.append(formatInstant(instant, tz, "%m/%d/%y"))
        }

        'X' -> {
            sb.append(formatInstant(instant, tz, "%H:%M:%S"))
        }

        'y' -> {
            sb.append((ldt.year % 100).toString().padStart(2, '0'))
        }

        'Y' -> {
            sb.append(ldt.year.toString())
        }

        'Z' -> {
            sb.append(tzAbbreviation(tz))
        }

        '%' -> {
            sb.append('%')
        }

        // --- GNU extensions ---
        'F' -> {
            sb.append(formatInstant(instant, tz, "%Y-%m-%d"))
        }

        's' -> {
            sb.append(instant.epochSeconds.toString())
        }

        'N' -> {
            sb.append(instant.nanosecondsOfSecond.toString().padStart(9, '0'))
        }

        'z' -> {
            sb.append(tzNumericOffset(tz, instant))
        }

        else -> {
            // Unknown conversion — POSIX says behavior is undefined. We emit
            // the literal `%X` rather than throwing, so a typo doesn't kill
            // the whole render.
            sb.append('%').append(spec)
        }
    }
}

private fun weekOfYearSundayBased(ldt: LocalDateTime): Int {
    // %U: week 01 starts on the first Sunday of the year. Days before that → week 00.
    val jan1Dow = LocalDateTime(ldt.year, 1, 1, 0, 0, 0).dayOfWeek.ordinal // Mon=0..Sun=6
    val daysToFirstSunday = if (jan1Dow == 6) 0 else (6 - jan1Dow)
    val firstSundayDoy = 1 + daysToFirstSunday
    val doy = ldt.dayOfYear
    return if (doy < firstSundayDoy) 0 else ((doy - firstSundayDoy) / 7) + 1
}

private fun weekOfYearMondayBased(ldt: LocalDateTime): Int {
    // %W: week 01 starts on the first Monday of the year.
    val jan1Dow = LocalDateTime(ldt.year, 1, 1, 0, 0, 0).dayOfWeek.ordinal // Mon=0..Sun=6
    val daysToFirstMonday = if (jan1Dow == 0) 0 else (7 - jan1Dow)
    val firstMondayDoy = 1 + daysToFirstMonday
    val doy = ldt.dayOfYear
    return if (doy < firstMondayDoy) 0 else ((doy - firstMondayDoy) / 7) + 1
}

private fun isoWeek(ldt: LocalDateTime): Int {
    // ISO 8601: week 01 contains the year's first Thursday.
    // Algorithm per Wikipedia.
    val dow = ldt.dayOfWeek.ordinal + 1 // Mon=1..Sun=7
    val doy = ldt.dayOfYear
    val w = (doy - dow + 10) / 7
    return when {
        w < 1 -> {
            // Belongs to the last week of the previous year.
            val prevYear = ldt.year - 1
            val isLeap = isLeapYear(prevYear)
            val prevDays = if (isLeap) 366 else 365
            val prevDec31Dow = LocalDateTime(prevYear, 12, 31, 0, 0, 0).dayOfWeek.ordinal + 1
            ((prevDays + (prevDec31Dow - 4)) / 7).coerceAtLeast(52)
        }

        w > 52 -> {
            // Might still belong to current year — check whether Dec 31 is a
            // Thursday or later (in which case week 53 exists).
            val dec31Dow = LocalDateTime(ldt.year, 12, 31, 0, 0, 0).dayOfWeek.ordinal + 1
            if (dec31Dow >= 4) 53 else 1
        }

        else -> {
            w
        }
    }
}

private fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

private fun tzAbbreviation(tz: TimeZone): String =
    when {
        tz == TimeZone.UTC -> {
            "UTC"
        }

        // Fixed-offset zones don't have an IANA name (e.g. the user
        // resolved one via `UtcOffset` directly). Render the offset
        // like `UTC-07:00` so we're not misleadingly labelling a
        // non-UTC stamp as "UTC".
        tz is FixedOffsetTimeZone -> {
            val total = tz.offset.totalSeconds
            if (total == 0) {
                "UTC"
            } else {
                val sign = if (total < 0) '-' else '+'
                val abs = if (total < 0) -total else total
                val h = abs / 3600
                val m = (abs % 3600) / 60
                "UTC$sign${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
            }
        }

        // IANA zone — show its id. kotlinx-datetime doesn't expose
        // the runtime-localized abbreviation ("PDT" / "EDT") that
        // glibc's `%Z` produces; the zone id ("America/Los_Angeles")
        // is the closest we can do portably and is what we'd want
        // anyway for log lines that need to be unambiguous across
        // hosts.
        else -> {
            tz.id
        }
    }

private fun tzNumericOffset(
    tz: TimeZone,
    instant: Instant,
): String = tz.offsetAt(instant).format(UtcOffset.Formats.FOUR_DIGITS)

private const val USAGE: String =
    """Usage: date [OPTION]... [+FORMAT]
Display the current time in the given FORMAT, or set the system date.

  -d, --date=STRING        display time described by STRING, not 'now'
  -I[FMT]                  output date/time in ISO 8601 format
  -R, --rfc-2822           output date and time in RFC 2822 format
  -r, --reference=FILE     display the last modification time of FILE
  -u, --utc, --universal   print or set Coordinated Universal Time (UTC)
      --help               display this help and exit
      --version            output version information and exit

FORMAT controls the output. Recognized sequences:
  %%   a literal %                    %F   full date; same as %Y-%m-%d
  %a   abbreviated weekday name       %H   hour (00..23)
  %A   full weekday name              %I   hour (01..12)
  %b   abbreviated month name         %j   day of year (001..366)
  %B   full month name                %m   month (01..12)
  %c   locale's date and time         %M   minute (00..59)
  %C   century                        %n   newline
  %d   day of month (01..31)          %N   nanoseconds (000000000..999999999)
  %D   date; same as %m/%d/%y         %p   locale AM or PM
  %e   day of month, space-padded     %r   12-hour time
  %h   same as %b                     %s   seconds since 1970-01-01 00:00:00 UTC
                                      %S   second (00..60)
                                      %t   a tab
                                      %T   24-hour time; same as %H:%M:%S
                                      %u   day of week (1..7); 1 is Monday
                                      %U   week of year, Sunday as first day
                                      %V   ISO 8601 week of year
                                      %w   day of week (0..6); 0 is Sunday
                                      %W   week of year, Monday as first day
                                      %x   locale's date
                                      %X   locale's time
                                      %y   last two digits of year (00..99)
                                      %Y   year
                                      %z   numeric time zone (e.g., +0100)
                                      %Z   time zone abbreviation
"""
