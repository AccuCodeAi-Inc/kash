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
): String {
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
                sb.append(sha.substring(0, 7))
            }

            'T' -> {
                sb.append(commit.tree)
            }

            't' -> {
                sb.append(commit.tree.substring(0, 7))
            }

            'P' -> {
                sb.append(commit.parents.joinToString(" "))
            }

            'p' -> {
                sb.append(commit.parents.joinToString(" ") { it.substring(0, 7) })
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
