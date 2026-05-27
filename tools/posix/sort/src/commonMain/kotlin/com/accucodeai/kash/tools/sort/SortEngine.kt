package com.accucodeai.kash.tools.sort

/**
 * Pure, in-memory implementation of the POSIX `sort` subset documented in
 * [[SortOptions]]. The engine collects every line first, then sorts and emits.
 *
 * Limitation: v1 holds the entire input in memory. Files larger than the
 * JVM heap will OOM. External merge-sort is deferred — document in
 * [STATUS.md][../STATUS.md] before lifting that limit.
 */
public object SortEngine {
    /**
     * Sort [lines] according to [options]. Returns a new list; input is not mutated.
     */
    public fun sort(
        lines: List<String>,
        options: SortOptions,
    ): List<String> {
        if (lines.isEmpty()) return emptyList()

        // POSIX guarantees stable sort when configured keys compare equal.
        // We use Kotlin's stable sortedWith.
        val comparator = buildComparator(options)
        val sorted = lines.sortedWith(comparator)

        if (!options.unique) return sorted

        // -u: drop subsequent lines that compare equal under the same keys.
        val keyEq = Comparator<String> { a, b -> compareByKeys(a, b, options) }
        val out = ArrayList<String>(sorted.size)
        var prev: String? = null
        for (line in sorted) {
            if (prev == null || keyEq.compare(prev, line) != 0) {
                out += line
                prev = line
            }
        }
        return out
    }

    private fun buildComparator(opt: SortOptions): Comparator<String> {
        val base = Comparator<String> { a, b -> compareByKeys(a, b, opt) }
        return if (opt.reverse) base.reversed() else base
    }

    internal fun compareByKeys(
        a: String,
        b: String,
        opt: SortOptions,
    ): Int {
        if (opt.keys.isEmpty()) {
            return compareValues(a, b, opt.numeric)
        }
        val aFields = splitFields(a, opt.separator)
        val bFields = splitFields(b, opt.separator)
        for (k in opt.keys) {
            val ak = extractKey(a, aFields, k, opt.separator)
            val bk = extractKey(b, bFields, k, opt.separator)
            val c = compareValues(ak, bk, opt.numeric)
            if (c != 0) return c
        }
        return 0
    }

    private fun compareValues(
        a: String,
        b: String,
        numeric: Boolean,
    ): Int {
        if (!numeric) return a.compareTo(b)
        val da = parseNumeric(a)
        val db = parseNumeric(b)
        return da.compareTo(db)
    }

    /**
     * POSIX numeric: leading blanks, optional sign, digits with optional fractional part.
     * Unparseable input compares as 0. NaN is avoided by returning 0 on parse failure.
     */
    internal fun parseNumeric(s: String): Double {
        var i = 0
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        val start = i
        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
        var sawDigit = false
        while (i < s.length && s[i].isDigit()) {
            i++
            sawDigit = true
        }
        if (i < s.length && s[i] == '.') {
            i++
            while (i < s.length && s[i].isDigit()) {
                i++
                sawDigit = true
            }
        }
        if (!sawDigit) return 0.0
        return s.substring(start, i).toDoubleOrNull() ?: 0.0
    }

    /**
     * Split a line into 1-based field strings.
     *
     * Default (no -t): a field is a maximal non-blank run. POSIX says the
     * leading blanks belong to the *following* field for key extraction —
     * we approximate this by storing the run-of-blanks-then-nonblank slice
     * via [splitDefaultWithLeading] so .C offsets line up with `sort -k`.
     *
     * Explicit -t: fields are split by the separator (no merging of adjacent
     * separators; empty fields are kept).
     */
    internal fun splitFields(
        line: String,
        sep: Char?,
    ): List<String> = if (sep == null) splitDefaultWithLeading(line) else splitBySeparator(line, sep)

    private fun splitDefaultWithLeading(line: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            val blankStart = i
            while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
            val nonBlankStart = i
            while (i < line.length && line[i] != ' ' && line[i] != '\t') i++
            if (i == blankStart) break
            // First field omits leading blanks; subsequent fields include them.
            val fieldStart = if (out.isEmpty()) nonBlankStart else blankStart
            out += line.substring(fieldStart, i)
        }
        return out
    }

    private fun splitBySeparator(
        line: String,
        sep: Char,
    ): List<String> {
        if (line.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var start = 0
        for (i in line.indices) {
            if (line[i] == sep) {
                out += line.substring(start, i)
                start = i + 1
            }
        }
        out += line.substring(start)
        return out
    }

    /**
     * Extract the substring used as the comparison key for [k] from a line
     * already pre-split into [fields].
     */
    internal fun extractKey(
        line: String,
        fields: List<String>,
        k: KeySpec,
        sep: Char?,
    ): String {
        if (fields.isEmpty()) return ""
        val sf = (k.startField - 1).coerceAtMost(fields.size - 1)
        if (k.startField > fields.size) return ""
        val startField = fields[sf]
        val sc = (k.startChar - 1).coerceIn(0, startField.length)

        // End boundary
        val ef =
            if (k.endField == null) {
                fields.size - 1
            } else {
                (k.endField - 1).coerceAtMost(fields.size - 1)
            }
        if (ef < sf) return ""

        return if (sf == ef) {
            val endChar =
                if (k.endField == null) {
                    startField.length
                } else {
                    k.endChar?.coerceAtMost(startField.length) ?: startField.length
                }
            if (endChar <= sc) "" else startField.substring(sc, endChar)
        } else {
            val sb = StringBuilder()
            sb.append(startField, sc, startField.length)
            for (mid in (sf + 1) until ef) {
                if (sep != null) sb.append(sep)
                sb.append(fields[mid])
            }
            val endField = fields[ef]
            val endChar =
                if (k.endField == null) {
                    endField.length
                } else {
                    k.endChar?.coerceAtMost(endField.length) ?: endField.length
                }
            if (sep != null) sb.append(sep)
            sb.append(endField, 0, endChar)
            sb.toString()
        }
    }
}
