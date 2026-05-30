package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.tools.git.plumbing.CommitPayload

/**
 * Subset of git's `pretty=format:` placeholder grammar covering the
 * vocabulary scripts actually use. Unknown placeholders pass through
 * verbatim, matching git's lenient behavior.
 *
 * Supported:
 *  - `%H` full commit sha · `%h` short (7-char)
 *  - `%T` full tree sha · `%t` short
 *  - `%P` parent shas (space-separated) · `%p` short forms
 *  - `%an`/`%ae`/`%ad`/`%ai`/`%at` author name/email/date/iso/unix-ts
 *  - `%cn`/`%ce`/`%cd`/`%ci`/`%ct` committer counterparts
 *  - `%s` subject (first line of message) · `%b` body · `%B` full message
 *  - `%n` literal newline · `%%` literal `%`
 *
 * Date formats:
 *  - `%ad`/`%cd` → git's `default` form: `<unix-ts> <tz>`
 *  - `%ai`/`%ci` → ISO 8601: `YYYY-MM-DD HH:MM:SS <tz>`
 *
 * Real git supports color codes, padding directives, and a few dozen
 * other placeholders. They land if and when an LLM script actually
 * needs them — leaving them out keeps the implementation small.
 */
public fun renderPrettyFormat(
    format: String,
    sha: String,
    commit: CommitPayload,
    @Suppress("UNUSED_PARAMETER") abbrevLen: Int = 7,
    decoration: String = "",
): String {
    // %h/%t/%p always abbreviate to git's default (7) regardless of
    // --abbrev-commit, which only affects the `commit <sha>` header line
    // and `--oneline`. abbrevLen is accepted for call-site symmetry.
    val shortLen = 7
    val sb = StringBuilder()
    var i = 0
    while (i < format.length) {
        val c = format[i]
        if (c != '%' || i + 1 >= format.length) {
            sb.append(c)
            i++
            continue
        }
        val next = format[i + 1]
        when (next) {
            'H' -> {
                sb.append(sha)
            }

            'h' -> {
                sb.append(sha.substring(0, shortLen))
            }

            'd' -> {
                // Decoration with leading " (" + ")" wrapper, or empty.
                if (decoration.isNotEmpty()) sb.append(" (").append(decoration).append(')')
            }

            'D' -> {
                sb.append(decoration)
            }

            'T' -> {
                sb.append(commit.tree)
            }

            't' -> {
                sb.append(commit.tree.substring(0, shortLen))
            }

            'P' -> {
                sb.append(commit.parents.joinToString(" "))
            }

            'p' -> {
                sb.append(commit.parents.joinToString(" ") { it.substring(0, shortLen) })
            }

            's' -> {
                sb.append(commit.message.substringBefore('\n'))
            }

            'b' -> {
                // Body is everything after subject + blank line. Real git
                // strips the trailing newline.
                val msg = commit.message
                val firstNl = msg.indexOf('\n')
                if (firstNl < 0 || firstNl == msg.length - 1) {
                    // No body.
                } else {
                    val rest = msg.substring(firstNl + 1).trimStart('\n')
                    sb.append(rest.trimEnd('\n'))
                }
            }

            'B' -> {
                sb.append(commit.message.trimEnd('\n'))
            }

            'n' -> {
                sb.append('\n')
            }

            '%' -> {
                sb.append('%')
            }

            'a' -> {
                if (i + 2 >= format.length) {
                    sb.append(c).append(next)
                    i++
                    continue
                }
                when (format[i + 2]) {
                    'n' -> {
                        sb.append(commit.author.name)
                    }

                    'e' -> {
                        sb.append(commit.author.email)
                    }

                    'd' -> {
                        sb.append("${commit.author.whenSeconds} ${commit.author.tz}")
                    }

                    'i' -> {
                        sb.append(isoDate(commit.author.whenSeconds, commit.author.tz))
                    }

                    't' -> {
                        sb.append(commit.author.whenSeconds.toString())
                    }

                    else -> {
                        sb.append(c).append(next).append(format[i + 2])
                        i += 3
                        continue
                    }
                }
                i += 3
                continue
            }

            'c' -> {
                if (i + 2 >= format.length) {
                    sb.append(c).append(next)
                    i++
                    continue
                }
                when (format[i + 2]) {
                    'n' -> {
                        sb.append(commit.committer.name)
                    }

                    'e' -> {
                        sb.append(commit.committer.email)
                    }

                    'd' -> {
                        sb.append("${commit.committer.whenSeconds} ${commit.committer.tz}")
                    }

                    'i' -> {
                        sb.append(isoDate(commit.committer.whenSeconds, commit.committer.tz))
                    }

                    't' -> {
                        sb.append(commit.committer.whenSeconds.toString())
                    }

                    else -> {
                        sb.append(c).append(next).append(format[i + 2])
                        i += 3
                        continue
                    }
                }
                i += 3
                continue
            }

            else -> {
                sb.append(c).append(next)
                i += 2
                continue
            }
        }
        i += 2
    }
    return sb.toString()
}

/**
 * Format unix seconds + git's `±HHMM` offset as `YYYY-MM-DD HH:MM:SS +HHMM`.
 * Pure-Kotlin so it works on both JVM and wasmJs without depending on
 * java.time.
 */
private fun isoDate(
    unixSec: Long,
    tz: String,
): String {
    val sign = if (tz.startsWith("-")) -1 else 1
    val tzMin = tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt()
    val localSec = unixSec + sign * tzMin * 60L
    val days = (localSec / 86400L)
    val secOfDay = ((localSec % 86400L) + 86400L) % 86400L
    val hour = (secOfDay / 3600L).toInt()
    val minute = ((secOfDay % 3600L) / 60L).toInt()
    val second = (secOfDay % 60L).toInt()
    val (y, m, d) = ymdFromEpochDays(days)
    return buildString {
        append(y.toString().padStart(4, '0'))
        append('-')
        append(m.toString().padStart(2, '0'))
        append('-')
        append(d.toString().padStart(2, '0'))
        append(' ')
        append(hour.toString().padStart(2, '0'))
        append(':')
        append(minute.toString().padStart(2, '0'))
        append(':')
        append(second.toString().padStart(2, '0'))
        append(' ')
        append(tz)
    }
}

/** Convert days-since-Unix-epoch to a (year, month, day) civil date. */
private fun ymdFromEpochDays(days: Long): Triple<Int, Int, Int> {
    // Howard Hinnant's "days_from_civil"-inverse, public-domain algorithm.
    val z = days + 719468L
    val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
    val doe = (z - era * 146097L).toInt()
    val yoe = ((doe - doe / 1460 + doe / 36524 - doe / 146096) / 365)
    val y = yoe.toLong() + era * 400L
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val yr = if (m <= 2) y + 1 else y
    return Triple(yr.toInt(), m, d)
}

/**
 * Resolve a `--pretty=<preset>` alias to the equivalent format string.
 * Aliases match real git's defaults closely; the long form is the
 * default `git log` output we already had.
 */
public fun prettyPreset(name: String): String? =
    when (name) {
        "oneline" -> {
            "%h %s"
        }

        "short" -> {
            "commit %H%nAuthor: %an <%ae>%n%n    %s%n"
        }

        "medium" -> {
            "commit %H%nAuthor: %an <%ae>%nDate:   %ad%n%n    %s%n%n    %b%n"
        }

        "full" -> {
            "commit %H%nAuthor: %an <%ae>%nCommit: %cn <%ce>%n%n    %s%n%n    %b%n"
        }

        "fuller" -> {
            "commit %H%nAuthor:     %an <%ae>%nAuthorDate: %ai%nCommit:     %cn <%ce>%nCommitDate: %ci%n%n    %s%n%n    %b%n"
        }

        else -> {
            null
        }
    }

/**
 * Parse a `git log --since=/--until=` date argument to a unix-epoch
 * second count. Supports the deterministic absolute forms scripts and
 * differential tests can rely on:
 *  - `@<unix-ts>` — explicit epoch seconds
 *  - `YYYY-MM-DD`
 *  - `YYYY-MM-DD HH:MM[:SS]` and `YYYY-MM-DDTHH:MM[:SS]`
 *  - an optional trailing ` ±HHMM` / `±HH:MM` / `Z` zone (default UTC)
 *
 * Relative forms (`2 days ago`, `yesterday`, `3.weeks`) are resolved
 * against [nowSeconds] when supplied; without a clock we can only do
 * the common fixed-keyword/`N unit ago` cases. Returns null on anything
 * we can't interpret (caller then ignores the bound, mirroring git's
 * lenient "unparseable → no filter" leaning where practical).
 */
public fun parseGitDate(
    raw: String,
    nowSeconds: Long? = null,
): Long? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    if (s.startsWith("@")) {
        return s
            .substring(1)
            .trim()
            .substringBefore(' ')
            .toLongOrNull()
    }

    // Relative forms: "N <unit> ago", "yesterday", "now".
    parseRelativeDate(s, nowSeconds)?.let { return it }

    // Absolute: split off a trailing zone token if present.
    var body = s
    var tzOffsetSec = 0
    val zoneMatch = Regex("""\s*(Z|[+-]\d{2}:?\d{2})$""").find(body)
    if (zoneMatch != null) {
        val z = zoneMatch.groupValues[1]
        tzOffsetSec =
            if (z == "Z") {
                0
            } else {
                val sign = if (z[0] == '-') -1 else 1
                val digits = z.substring(1).replace(":", "")
                sign * (digits.substring(0, 2).toInt() * 3600 + digits.substring(2, 4).toInt() * 60)
            }
        body = body.substring(0, zoneMatch.range.first).trim()
    }

    val dateTime =
        Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?$""").find(body)
            ?: return null
    val g = dateTime.groupValues
    val y = g[1].toInt()
    val mo = g[2].toInt()
    val d = g[3].toInt()
    val hh = g[4].toIntOrNull() ?: 0
    val mm = g[5].toIntOrNull() ?: 0
    val ss = g[6].toIntOrNull() ?: 0
    val days = epochDaysFromYmd(y, mo, d)
    return days * 86400L + hh * 3600L + mm * 60L + ss - tzOffsetSec
}

private fun parseRelativeDate(
    s: String,
    nowSeconds: Long?,
): Long? {
    if (nowSeconds == null) return null
    val lower = s.lowercase().trim()
    if (lower == "now") return nowSeconds
    if (lower == "yesterday") return nowSeconds - 86400L
    val m =
        Regex("""^(\d+)\s*(second|minute|hour|day|week|month|year)s?(?:\s+ago)?$""").find(lower)
            ?: return null
    val n = m.groupValues[1].toLong()
    val unitSec =
        when (m.groupValues[2]) {
            "second" -> 1L

            "minute" -> 60L

            "hour" -> 3600L

            "day" -> 86400L

            "week" -> 604800L

            "month" -> 2592000L

            // 30 days, matching git's approx
            "year" -> 31536000L

            // 365 days
            else -> return null
        }
    return nowSeconds - n * unitSec
}

/** Days since Unix epoch for a civil (y, m, d) date (Howard Hinnant). */
private fun epochDaysFromYmd(
    y: Int,
    m: Int,
    d: Int,
): Long {
    val yy = if (m <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = (yy - era * 400).toLong()
    val mp = if (m > 2) m - 3 else m + 9
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097L + doe - 719468L
}

/**
 * Convert a person stamp's stored `whenSeconds` (already epoch UTC) to
 * a UTC-comparable epoch second. git compares `--since`/`--until` against
 * the committer date's absolute instant, so the stored epoch is what we
 * compare — the tz string only affects display.
 */
internal fun commitInstant(whenSeconds: Long): Long = whenSeconds
