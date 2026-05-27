package com.accucodeai.kash.tools.pgrep

import com.accucodeai.kash.api.KashProcess

/**
 * Shared filtering primitives for `pgrep` and `pkill`. Both tools walk the
 * same [com.accucodeai.kash.api.KashMachine.processTable] that `ps` reads,
 * apply the same filter set (patterns, `-u`, `-x`, `-i`, `-v`, `-f`), and
 * then either print the survivors or signal them.
 *
 * The "process name" matched against by default is [KashProcess.commandName].
 * With `-f`, the matcher folds `argv` (or commandName if argv is empty) into
 * a single space-joined string and matches that.
 *
 * Patterns are POSIX ERE — we lean on Kotlin's built-in [Regex] which is
 * close enough for the grep/kill recipe space (no `[[:alpha:]]`-style
 * bracket-class shorthand, but `.`, `*`, `+`, `?`, `|`, `(...)`, `[...]`,
 * `^`, `$` all work). Invalid syntax surfaces as a [PatternException]
 * which the command translates to a usage error (exit 2).
 */
public class PgrepFilter(
    public val patterns: List<String>,
    public val matchFull: Boolean = false,
    public val exact: Boolean = false,
    public val caseInsensitive: Boolean = false,
    public val invert: Boolean = false,
    public val user: String? = null,
) {
    private val regexes: List<Regex> =
        try {
            val opts =
                if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
            patterns.map { pat ->
                val src = if (exact) "^(?:$pat)$" else pat
                Regex(src, opts)
            }
        } catch (e: Exception) {
            throw PatternException(e.message ?: "invalid regex")
        }

    /** True if [p] passes every active criterion. */
    public fun matches(p: KashProcess): Boolean {
        if (user != null && !userMatches(p, user)) return false
        if (regexes.isEmpty()) {
            // Filtering by -u only: accept (invert flips).
            return !invert
        }
        val haystack =
            if (matchFull) {
                if (p.argv.isNotEmpty()) p.argv.joinToString(" ") else p.commandName
            } else {
                p.commandName.ifEmpty { p.argv.firstOrNull() ?: "" }
            }
        // All patterns must match (procps-ng AND semantics).
        val hit = regexes.all { it.containsMatchIn(haystack) }
        return if (invert) !hit else hit
    }

    private fun userMatches(
        @Suppress("UNUSED_PARAMETER") p: KashProcess,
        wanted: String,
    ): Boolean {
        // kash has no per-process username; uid 1000 == "user" by convention
        // (see SingleUserDatabase). Accept "user" or "1000" or a bare numeric
        // matching realUid; reject anything else.
        if (wanted == "user") return true
        val n = wanted.toIntOrNull()
        return n != null && (n == 1000 || n == p.realUid)
    }
}

public class PatternException(
    message: String,
) : RuntimeException(message)
