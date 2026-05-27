package com.accucodeai.kash.shared.regex

import com.google.re2j.Pattern

/**
 * RE2/J-backed [LinearRegex]. Linear time in input length regardless of pattern —
 * no catastrophic backtracking.
 *
 * Tradeoff: RE2 deliberately omits back-references and a few PCRE extensions.
 * Patterns that need those will throw at compile time, which is the correct
 * failure mode for a tool that must run on untrusted input.
 */
public actual class LinearRegex actual constructor(
    pattern: String,
    flags: String,
) {
    private val compiled: Pattern

    init {
        var bits = 0
        for (c in flags) {
            when (c) {
                'i' -> bits = bits or Pattern.CASE_INSENSITIVE

                'm' -> bits = bits or Pattern.MULTILINE

                's' -> bits = bits or Pattern.DOTALL

                'n' -> Unit

                // jq's "no implicit captures" — best-effort no-op
                'x' -> throw IllegalArgumentException("extended (x) regex flag not supported by RE2/J")

                'g', 'l', 'p' -> Unit

                // interpreted at call site / not relevant here
                else -> throw IllegalArgumentException("unknown regex flag: `$c`")
            }
        }
        compiled = Pattern.compile(pattern, bits)
    }

    public actual fun containsMatch(s: String): Boolean = compiled.matcher(s).find()

    public actual fun findFirst(s: String): RegexMatch? {
        val m = compiled.matcher(s)
        return if (m.find()) buildMatch(m, s) else null
    }

    public actual fun findAll(s: String): Sequence<RegexMatch> =
        sequence {
            val m = compiled.matcher(s)
            var lastEnd = -1
            while (m.find()) {
                // Skip zero-width matches at the same offset to avoid an infinite loop.
                if (m.start() == m.end() && m.start() == lastEnd) {
                    if (m.end() >= s.length) return@sequence
                    // Force advance past the zero-width point.
                    if (!m.find(m.end() + 1)) return@sequence
                }
                yield(buildMatch(m, s))
                lastEnd = m.end()
            }
        }

    private fun buildMatch(
        m: com.google.re2j.Matcher,
        s: String,
    ): RegexMatch {
        val captures = ArrayList<RegexCapture>(m.groupCount())
        val nameByGroup = namedGroupsInverse()
        for (g in 1..m.groupCount()) {
            val start = m.start(g)
            val end = m.end(g)
            captures +=
                if (start < 0) {
                    RegexCapture(offset = -1, length = 0, text = null, name = nameByGroup[g])
                } else {
                    RegexCapture(
                        offset = start,
                        length = end - start,
                        text = s.substring(start, end),
                        name = nameByGroup[g],
                    )
                }
        }
        return RegexMatch(
            offset = m.start(),
            length = m.end() - m.start(),
            text = m.group(),
            captures = captures,
        )
    }

    private fun namedGroupsInverse(): Map<Int, String> {
        val ng = compiled.namedGroups()
        if (ng.isEmpty()) return emptyMap()
        val out = HashMap<Int, String>(ng.size)
        for ((name, idx) in ng) out[idx] = name
        return out
    }
}
