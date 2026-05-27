package com.accucodeai.kash.tools.awk.eval

import com.accucodeai.kash.tools.awk.AwkRuntimeError

/**
 * Awk's dual-typed scalar. Every value has both a string face and a number
 * face; which one the engine reads depends on the operator context.
 *
 *  - [StrVal] — a string. Carries an optional `numericHint`: POSIX "numeric
 *    strings" (the kind that come from `$1` field splitting or `getline`)
 *    pre-compute their numeric value so comparisons against numbers don't
 *    spuriously fall into string-compare territory.
 *  - [NumVal] — a number. Always renders to a string via `OFMT` (or `%d` if
 *    integral within the safe integer range).
 *  - [Uninit] — never assigned. Numeric face is 0, string face is "" — and
 *    crucially, *both* sides treat it as the appropriate default in
 *    polymorphic comparisons.
 *
 * The full POSIX rule: when both operands of `==` etc. are numeric or are
 * numeric-string, compare as numbers; otherwise as strings. We approximate
 * by treating `NumVal` and `StrVal(numericHint != null)` as "numeric" for
 * comparison purposes.
 */
internal sealed interface AwkValue {
    fun toAwkString(ofmt: String): String

    fun toAwkNumber(): Double

    fun toAwkBool(): Boolean
}

internal data class StrVal(
    val s: String,
    /** Set when the string came from a context where POSIX would treat it
     *  as a numeric string (field splits, getline, command-line `-v`). */
    val numericHint: Double? = null,
) : AwkValue {
    override fun toAwkString(ofmt: String): String = s

    override fun toAwkNumber(): Double = numericHint ?: parseAwkNumber(s)

    override fun toAwkBool(): Boolean = s.isNotEmpty()

    /** True if POSIX would consider this string a "numeric string". */
    val isNumericString: Boolean get() = numericHint != null
}

internal data class NumVal(
    val n: Double,
) : AwkValue {
    override fun toAwkString(ofmt: String): String = formatAwkNumber(n, ofmt)

    override fun toAwkNumber(): Double = n

    override fun toAwkBool(): Boolean = n != 0.0
}

internal data object Uninit : AwkValue {
    override fun toAwkString(ofmt: String): String = ""

    override fun toAwkNumber(): Double = 0.0

    override fun toAwkBool(): Boolean = false
}

/**
 * Parse the leading numeric prefix of [s], POSIX-style. An empty / non-numeric
 * string yields 0.0. Trailing non-numeric chars are silently dropped — `"12abc"`
 * becomes `12.0`.
 */
internal fun parseAwkNumber(s: String): Double {
    val t = s.trimStart()
    if (t.isEmpty()) return 0.0
    // Find the longest numeric prefix. POSIX awk accepts an optional sign,
    // integer part, fractional part, and exponent.
    var i = 0
    if (i < t.length && (t[i] == '+' || t[i] == '-')) i++
    var sawDigit = false
    while (i < t.length && t[i].isDigit()) {
        sawDigit = true
        i++
    }
    if (i < t.length && t[i] == '.') {
        i++
        while (i < t.length && t[i].isDigit()) {
            sawDigit = true
            i++
        }
    }
    if (sawDigit && i < t.length && (t[i] == 'e' || t[i] == 'E')) {
        val expStart = i
        i++
        if (i < t.length && (t[i] == '+' || t[i] == '-')) i++
        val mantissaEnd = i
        while (i < t.length && t[i].isDigit()) i++
        // If there were no exponent digits, the e/E doesn't belong.
        if (i == mantissaEnd) i = expStart
    }
    if (!sawDigit) return 0.0
    return t.substring(0, i).toDoubleOrNull() ?: 0.0
}

/**
 * Try to interpret a string as a *full* number — used to decide whether an
 * unhinted string should be promoted to "numeric string" for comparison.
 * Returns the parsed value, or `null` if any trailing non-whitespace
 * characters remain.
 *
 * Note: we can't just delegate to `String.toDoubleOrNull()`. Kotlin/JVM's
 * `Double.parseDouble` accepts Java floating-point literal suffixes —
 * `"4d"`, `"4D"`, `"4f"`, `"4F"` all parse to 4.0 — which would make
 * `$1 == "4d"` compare numerically against integers per POSIX's numeric-
 * string rule, producing the wrong answer. We enforce that every char
 * after the optional sign is a digit, `.`, or part of an `eE±N` exponent.
 */
internal fun tryFullNumber(s: String): Double? {
    val t = s.trim()
    if (t.isEmpty()) return null
    var i = 0
    if (i < t.length && (t[i] == '+' || t[i] == '-')) i++
    var sawDigit = false
    while (i < t.length && t[i].isDigit()) {
        sawDigit = true
        i++
    }
    if (i < t.length && t[i] == '.') {
        i++
        while (i < t.length && t[i].isDigit()) {
            sawDigit = true
            i++
        }
    }
    if (i < t.length && (t[i] == 'e' || t[i] == 'E')) {
        i++
        if (i < t.length && (t[i] == '+' || t[i] == '-')) i++
        if (i >= t.length || !t[i].isDigit()) return null
        while (i < t.length && t[i].isDigit()) i++
    }
    if (!sawDigit || i != t.length) return null
    return t.toDoubleOrNull()
}

/**
 * Render a number the way awk does: integers print without a decimal,
 * non-integers print via `OFMT` (default `"%.6g"`).
 */
internal fun formatAwkNumber(
    n: Double,
    ofmt: String,
): String {
    if (n.isNaN()) return "nan"
    if (n.isInfinite()) return if (n > 0) "inf" else "-inf"
    // Integral values render as integers — but only if they fit in Long
    // range. Outside that, fall back to OFMT.
    if (n == n.toLong().toDouble() && n >= Long.MIN_VALUE.toDouble() && n <= Long.MAX_VALUE.toDouble()) {
        return n.toLong().toString()
    }
    return formatFloat(n, ofmt)
}

/**
 * Apply a single printf-style format (typically OFMT = "%.6g" or CONVFMT)
 * to a number. We don't ship a full printf yet — this implements just the
 * %f / %e / %g forms with optional precision, since that's all OFMT/CONVFMT
 * use in practice.
 */
internal fun formatFloat(
    n: Double,
    fmt: String,
): String {
    // Minimal "%.<prec><f|e|g>" support — enough for OFMT/CONVFMT defaults.
    // For anything more elaborate the printf builtin (when it lands) gets
    // its own implementation; this helper is intentionally narrow.
    val m = Regex("""%\.(\d+)([feEgG])""").matchEntire(fmt) ?: return n.toString()
    val prec = m.groupValues[1].toInt()
    return when (m.groupValues[2]) {
        "f" -> formatFixed(n, prec)
        "e", "E" -> formatScientific(n, prec, upper = m.groupValues[2] == "E")
        "g", "G" -> formatGeneral(n, prec, upper = m.groupValues[2] == "G")
        else -> n.toString()
    }
}

private fun formatFixed(
    n: Double,
    prec: Int,
): String {
    // Round to `prec` fractional digits, then format the result as a
    // fixed-precision decimal string. We round the *integer* representation
    // of the value scaled by 10^prec so the conversion preserves the
    // rounded value exactly — earlier versions did `rounded.toLong()` to
    // get the integer part, which truncates toward zero and loses the
    // rounding direction (sqrt(2) at prec=4 rounded to 14142, toLong of
    // 1.4142 was 1, fractional reconstructed as ((1.4142 - 1) * 10000)
    // .toLong() = 4141 — off by one).
    val multiplier = pow10(prec)
    val scaled = kotlin.math.round(kotlin.math.abs(n) * multiplier).toLong()
    val sign = if (n < 0) "-" else ""
    if (prec == 0) return sign + scaled.toString()
    val divisor = pow10(prec).toLong()
    val whole = scaled / divisor
    val frac = scaled % divisor
    val fracStr = frac.toString().padStart(prec, '0')
    return "$sign$whole.$fracStr"
}

private fun formatScientific(
    n: Double,
    prec: Int,
    upper: Boolean,
): String {
    if (n == 0.0) {
        val mant = if (prec == 0) "0" else "0." + "0".repeat(prec)
        return "${mant}${if (upper) "E" else "e"}+00"
    }
    val abs = kotlin.math.abs(n)
    val exp = kotlin.math.floor(kotlin.math.log10(abs)).toInt()
    val mantissa = abs / pow10(exp)
    val sign = if (n < 0) "-" else ""
    val mantissaStr = formatFixed(mantissa, prec)
    val expSign = if (exp < 0) "-" else "+"
    val expStr =
        kotlin.math
            .abs(exp)
            .toString()
            .padStart(2, '0')
    val e = if (upper) "E" else "e"
    return "$sign$mantissaStr$e$expSign$expStr"
}

private fun formatGeneral(
    n: Double,
    prec: Int,
    upper: Boolean,
): String {
    // %g picks %e or %f based on magnitude. Precision is the number of
    // significant digits. POSIX says: use %e if exponent < -4 or >= prec,
    // else use %f with precision = prec - 1 - exponent. Then strip trailing
    // zeros from the fraction (and the decimal point if needed).
    if (n == 0.0) return "0"
    val abs = kotlin.math.abs(n)
    val exp = kotlin.math.floor(kotlin.math.log10(abs)).toInt()
    val effectivePrec = if (prec == 0) 1 else prec
    val raw =
        if (exp < -4 || exp >= effectivePrec) {
            formatScientific(n, effectivePrec - 1, upper)
        } else {
            formatFixed(n, effectivePrec - 1 - exp)
        }
    return stripTrailingZeros(raw)
}

private fun stripTrailingZeros(s: String): String {
    val eIdx = s.indexOfFirst { it == 'e' || it == 'E' }
    val mant = if (eIdx >= 0) s.substring(0, eIdx) else s
    val exp = if (eIdx >= 0) s.substring(eIdx) else ""
    if ('.' !in mant) return s
    var trimmed = mant.trimEnd('0')
    if (trimmed.endsWith('.')) trimmed = trimmed.dropLast(1)
    return trimmed + exp
}

private fun pow10(n: Int): Double {
    var r = 1.0
    if (n >= 0) {
        repeat(n) { r *= 10.0 }
    } else {
        repeat(-n) { r /= 10.0 }
    }
    return r
}

/**
 * Wrap [s] into an [AwkValue] with POSIX "numeric string" detection — if
 * the entire trimmed string is parseable as a number, the value carries
 * a numeric hint so comparisons against numbers act numerically.
 */
internal fun stringValueWithHint(s: String): AwkValue {
    val n = tryFullNumber(s)
    return if (n != null) StrVal(s, numericHint = n) else StrVal(s)
}

/** Convenience: assert [v] is an array name, throwing if it's a scalar. */
internal fun requireScalar(
    v: AwkValue?,
    name: String,
): AwkValue {
    if (v == null) return Uninit
    return v
}

/** Internal error for runtime issues that should surface as `AwkRuntimeError`. */
internal fun runtimeError(message: String): Nothing = throw AwkRuntimeError(message)
