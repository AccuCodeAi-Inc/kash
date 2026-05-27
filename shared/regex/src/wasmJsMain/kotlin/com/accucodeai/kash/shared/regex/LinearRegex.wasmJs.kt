package com.accucodeai.kash.shared.regex

/**
 * wasmJs [LinearRegex] actual — delegates to `kotlin.text.Regex`, which on
 * this target compiles to JavaScript `RegExp`. That is **not** linear-time:
 * JS RegExp is backtracking and is ReDoS-vulnerable. We accept this tradeoff
 * because in the browser the kash machine runs inside the user's own tab —
 * the user is the only attacker, and a pathological pattern at worst hangs
 * their own tab. If we ever expose kash as a server-side wasm sandbox this
 * needs revisiting (port RE2 to Kotlin, or front it with a JS RE2 binding).
 */
public actual class LinearRegex actual constructor(
    pattern: String,
    flags: String,
) {
    private val compiled: Regex

    init {
        val opts = mutableSetOf<RegexOption>()
        for (c in flags) {
            when (c) {
                'i' -> opts += RegexOption.IGNORE_CASE
                'm' -> opts += RegexOption.MULTILINE
                's' -> opts += RegexOption.DOT_MATCHES_ALL
                'n' -> Unit
                'x' -> throw IllegalArgumentException("extended (x) regex flag not supported on wasmJs")
                'g', 'l', 'p' -> Unit
                else -> throw IllegalArgumentException("unknown regex flag: `$c`")
            }
        }
        compiled = Regex(pattern, opts)
    }

    public actual fun containsMatch(s: String): Boolean = compiled.containsMatchIn(s)

    public actual fun findFirst(s: String): RegexMatch? = compiled.find(s)?.let { buildMatch(it) }

    public actual fun findAll(s: String): Sequence<RegexMatch> =
        sequence {
            var lastEnd = -1
            var m = compiled.find(s)
            while (m != null) {
                if (m.range.first == m.range.last + 1 && m.range.first == lastEnd) {
                    // zero-width at same offset — advance past it
                    val next = m.range.first + 1
                    if (next > s.length) return@sequence
                    m = compiled.find(s, next)
                    continue
                }
                yield(buildMatch(m))
                lastEnd = m.range.last + 1
                m = m.next()
            }
        }

    private fun buildMatch(m: MatchResult): RegexMatch {
        val captures = ArrayList<RegexCapture>(m.groupValues.size - 1)
        // kotlin.text.MatchResult exposes named groups via `.groups[name]`, but
        // there is no public way to enumerate names back from group indices on
        // wasmJs. We leave name=null; jq's call sites that need named captures
        // will need a future fix (track names alongside compilation).
        for (g in 1 until m.groupValues.size) {
            val grp = m.groups[g]
            captures +=
                if (grp == null) {
                    RegexCapture(offset = -1, length = 0, text = null, name = null)
                } else {
                    RegexCapture(
                        offset = grp.range.first,
                        length = grp.range.last - grp.range.first + 1,
                        text = grp.value,
                        name = null,
                    )
                }
        }
        return RegexMatch(
            offset = m.range.first,
            length = m.range.last - m.range.first + 1,
            text = m.value,
            captures = captures,
        )
    }
}
