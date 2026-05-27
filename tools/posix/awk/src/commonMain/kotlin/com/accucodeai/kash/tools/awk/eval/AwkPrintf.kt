package com.accucodeai.kash.tools.awk.eval

/**
 * Minimal printf formatter for awk. Supports `%d %i %o %x %X %u %c %s %e
 * %E %f %g %G %%` with optional width (including `*`), precision, and the
 * flags `-`, `+`, ` `, `0`, `#`. Not feature-complete against gawk, but
 * covers the cases that show up in real awk one-liners.
 *
 * Kept hand-rolled rather than delegating to Kotlin's `String.format` so
 * the awk-specific quirks (POSIX `%c` with strings vs numbers, `%g`
 * trailing-zero stripping that already lives in [formatGeneral]) stay
 * under our control.
 */
internal fun formatPrintf(
    fmt: String,
    args: List<AwkValue>,
): String {
    val out = StringBuilder()
    var ai = 0
    var i = 0
    while (i < fmt.length) {
        val c = fmt[i]
        if (c != '%') {
            out.append(c)
            i++
            continue
        }
        if (i + 1 < fmt.length && fmt[i + 1] == '%') {
            out.append('%')
            i += 2
            continue
        }
        // Parse a single conversion specifier: % [flags] [width] [.prec] conv
        var j = i + 1
        val flags = StringBuilder()
        while (j < fmt.length && fmt[j] in "-+ #0") {
            flags.append(fmt[j])
            j++
        }
        var width = 0
        var hasWidth = false
        if (j < fmt.length && fmt[j] == '*') {
            if (ai < args.size) width = args[ai++].toAwkNumber().toInt()
            hasWidth = true
            j++
        } else {
            while (j < fmt.length && fmt[j].isDigit()) {
                width = width * 10 + (fmt[j] - '0')
                hasWidth = true
                j++
            }
        }
        var prec = -1
        if (j < fmt.length && fmt[j] == '.') {
            j++
            prec = 0
            if (j < fmt.length && fmt[j] == '*') {
                if (ai < args.size) prec = args[ai++].toAwkNumber().toInt()
                j++
            } else {
                while (j < fmt.length && fmt[j].isDigit()) {
                    prec = prec * 10 + (fmt[j] - '0')
                    j++
                }
            }
        }
        if (j >= fmt.length) {
            // Truncated specifier — emit verbatim and stop.
            out.append(fmt.substring(i))
            return out.toString()
        }
        val conv = fmt[j]
        j++
        val arg = if (ai < args.size) args[ai++] else Uninit
        val rendered = renderConversion(conv, flags.toString(), if (hasWidth) width else -1, prec, arg)
        out.append(rendered)
        i = j
    }
    return out.toString()
}

private fun renderConversion(
    conv: Char,
    flags: String,
    width: Int,
    prec: Int,
    arg: AwkValue,
): String {
    val padLeft = '-' !in flags
    val zeroPad = '0' in flags && padLeft
    val showSign = '+' in flags
    val spaceForPos = ' ' in flags

    val body: String =
        when (conv) {
            'd', 'i' -> {
                val v = arg.toAwkNumber().toLong()
                val mag =
                    kotlin.math
                        .abs(v)
                        .toString()
                        .padStart(if (prec >= 0) prec else 0, '0')
                if (v < 0) {
                    "-$mag"
                } else if (showSign) {
                    "+$mag"
                } else if (spaceForPos) {
                    " $mag"
                } else {
                    mag
                }
            }

            'o' -> {
                arg.toAwkNumber().toLong().toString(8)
            }

            'x' -> {
                arg.toAwkNumber().toLong().toString(16)
            }

            'X' -> {
                arg
                    .toAwkNumber()
                    .toLong()
                    .toString(16)
                    .uppercase()
            }

            'u' -> {
                arg.toAwkNumber().toLong().toString()
            }

            'c' -> {
                when (arg) {
                    is StrVal -> {
                        arg.s.take(1)
                    }

                    is NumVal -> {
                        arg.n
                            .toInt()
                            .toChar()
                            .toString()
                    }

                    is Uninit -> {
                        ""
                    }
                }
            }

            's' -> {
                val s = arg.toAwkString("%.6g")
                if (prec >= 0) s.take(prec) else s
            }

            'e', 'E' -> {
                formatScientific(arg.toAwkNumber(), if (prec >= 0) prec else 6, conv == 'E')
            }

            'f' -> {
                formatFixed(arg.toAwkNumber(), if (prec >= 0) prec else 6)
            }

            'g', 'G' -> {
                formatGeneral(arg.toAwkNumber(), if (prec >= 0) prec else 6, conv == 'G')
            }

            else -> {
                "%$conv"
            }
        }

    if (width <= 0 || body.length >= width) return body
    val padChar = if (zeroPad && conv in "diouxXeEfgG") '0' else ' '
    return if (padLeft) body.padStart(width, padChar) else body.padEnd(width, ' ')
}

// Hoist the helpers from AwkValue so the printf formatter doesn't depend on its private state.
private fun formatFixed(
    n: Double,
    prec: Int,
): String {
    // Same scale-then-round approach as `AwkValue.formatFixed` — see the
    // KDoc there for why we can't use `.toLong()` on the floating result
    // (truncate-toward-zero loses the rounding direction).
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
        return "$mant${if (upper) "E" else "e"}+00"
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
    val eIdx = raw.indexOfFirst { it == 'e' || it == 'E' }
    val mant = if (eIdx >= 0) raw.substring(0, eIdx) else raw
    val tail = if (eIdx >= 0) raw.substring(eIdx) else ""
    if ('.' !in mant) return raw
    var trimmed = mant.trimEnd('0')
    if (trimmed.endsWith('.')) trimmed = trimmed.dropLast(1)
    return trimmed + tail
}

private fun pow10(n: Int): Double {
    var r = 1.0
    if (n >= 0) repeat(n) { r *= 10.0 } else repeat(-n) { r /= 10.0 }
    return r
}
