package com.accucodeai.kash.tools.printf

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.io.writeUtf8
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * `printf` builtin: `printf -v VAR FORMAT ARGS...`. The format string is scanned
 * byte-by-byte: `%` introduces a conversion spec (flags + width + precision +
 * conv char), and `\` introduces a C-style escape that emits a literal char.
 * The format is recycled across remaining args, bash-style; recycling stops
 * once an iteration consumes no args (avoids infinite loops on no-consumer
 * formats and unknown conversions). Output is written to stdout, or stored in
 * the variable named by `-v` if that option is given.
 */
public class PrintfCommand :
    Command,
    CommandSpec {
    override val name: String = "printf"
    override val kind: CommandKind = CommandKind.BUILTIN
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.BASH_BUILTIN)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var ai = 0
        var varName: String? = null
        // Option parsing: only -v and -- are recognized. Any other `-X` is an error.
        while (ai < args.size) {
            val a = args[ai]
            when {
                a == "--" -> {
                    ai++
                    break
                }

                a == "-v" -> {
                    if (ai + 1 >= args.size) {
                        ctx.stderr.writeUtf8(
                            "printf: -v: option requires an argument\nprintf: usage: printf [-v var] format [arguments]\n",
                        )
                        return CommandResult(exitCode = 2)
                    }
                    varName = args[ai + 1]
                    // Security: a `$(...)` in the bracketed subscript means
                    // the user passed a command-substitution-bearing value
                    // (e.g. `printf -v a["\$subscript"]` with $subscript
                    // a literal `\$(...)` string). Bash's array_expand_once
                    // baseline refuses to re-evaluate that — diagnose with
                    // the arith-error shape so the normalize rule folds it
                    // to <ARITH_ERR>.
                    val lb = varName.indexOf('[')
                    val rb = varName.lastIndexOf(']')
                    if (lb > 0 && rb == varName.length - 1) {
                        val sub = varName.substring(lb + 1, rb)
                        if ("\$(" in sub) {
                            ctx.stderr.writeUtf8(
                                "${ctx.shellDiagPrefix}$sub: arithmetic syntax error: operand expected (error token is \"$sub\")\n",
                            )
                            return CommandResult(exitCode = 1)
                        }
                        // Bash rejects a subscript that has an unbalanced
                        // quote char after expansion (e.g. an apostrophe key
                        // `a[80's]`) with `printf: ...: not a valid identifier`
                        // — but only when `assoc_expand_once` is OFF (the
                        // default), where the subscript is re-expanded a
                        // second time and the stray quote breaks the re-parse.
                        // With the shopt ON the single-expansion key is valid.
                        val singles = sub.count { it == '\'' }
                        val doubles = sub.count { it == '"' }
                        if ((singles % 2 != 0 || doubles % 2 != 0) && !ctx.assocExpandOnce) {
                            ctx.stderr.writeUtf8("${ctx.shellDiagPrefix}printf: `$varName': not a valid identifier\n")
                            return CommandResult(exitCode = 2)
                        }
                    }
                    // Greedy single-key: an unquoted array-reference arg whose
                    // base is associative takes the post-expansion subscript
                    // as a single literal key to the closing `]` under
                    // assoc_expand_once — so `printf -v A[$rk]` (rk=']') stores
                    // key `]`. Skip the inner-`]` identifier rejection there;
                    // quoted/non-reference/indexed targets stay rejected.
                    val greedyAssoc =
                        lb > 0 &&
                            rb == varName.length - 1 &&
                            ctx.assocExpandOnce &&
                            (ai + 1) in ctx.arrayRefArgs &&
                            ctx.isAssocArray?.invoke(varName.substring(0, lb)) == true
                    if (!greedyAssoc && !isValidIdentifier(varName)) {
                        ctx.stderr.writeUtf8("${ctx.shellDiagPrefix}printf: `$varName': not a valid identifier\n")
                        return CommandResult(exitCode = 2)
                    }
                    ai += 2
                }

                a.startsWith("-") && a.length > 1 -> {
                    val opt = a.substring(1, 2)
                    // POSIX-shell convention: only the *error* line is
                    // prefixed; the trailing usage banner is the same form
                    // bash emits and stays unprefixed.
                    ctx.stderr.writeUtf8(
                        "${ctx.shellDiagPrefix}printf: -$opt: invalid option\n" +
                            "printf: usage: printf [-v var] format [arguments]\n",
                    )
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    break
                }
            }
        }

        if (ai >= args.size) {
            // Bare `printf` (or `printf --`) with no operands: standalone
            // usage banner, no shell-diagnostic prefix (matches bash).
            ctx.stderr.writeUtf8("printf: usage: printf [-v var] format [arguments]\n")
            return CommandResult(exitCode = 2)
        }

        val fmt = args[ai]
        val rest = args.drop(ai + 1)
        val out = StringBuilder()
        val err = StringBuilder()
        var argIdx = 0
        var exitCode = 0
        var stop = false

        fun nextArg(): String? = rest.getOrNull(argIdx).also { if (argIdx < rest.size) argIdx++ }

        fun toLongArg(s: String?): Long {
            val v = s ?: return 0L
            if (v.isEmpty()) {
                err.append("${ctx.shellDiagPrefix}printf: : invalid number\n")
                exitCode = 1
                return 0L
            }
            // bash quirk: leading `'` or `"` means take the next char as a literal int
            if (v[0] == '\'' || v[0] == '"') {
                return if (v.length >= 2) v[1].code.toLong() else 0L
            }
            // try plain decimal / hex (0x) / octal (leading 0) via toLong with base inference
            val parsed = tryParseLong(v)
            if (parsed != null) return parsed
            err.append("${ctx.shellDiagPrefix}printf: $v: invalid number\n")
            exitCode = 1
            return 0L
        }

        fun toDoubleArg(s: String?): Double {
            val v = s ?: return 0.0
            if (v.isEmpty()) return 0.0
            if (v[0] == '\'' || v[0] == '"') {
                return if (v.length >= 2) v[1].code.toDouble() else 0.0
            }
            return v.toDoubleOrNull() ?: tryParseLong(v)?.toDouble() ?: run {
                err.append("${ctx.shellDiagPrefix}printf: $v: invalid number\n")
                exitCode = 1
                0.0
            }
        }

        fun renderOnce() {
            var i = 0
            while (i < fmt.length && !stop) {
                val c = fmt[i]
                if (c == '\\' && i + 1 < fmt.length) {
                    val r = decodeFormatEscape(fmt, i)
                    if (r.diagnostic != null) {
                        err.append("${ctx.shellDiagPrefix}printf: ").append(r.diagnostic).append('\n')
                    }
                    out.append(r.text)
                    i += r.consumed
                    continue
                }
                if (c != '%') {
                    out.append(c)
                    i++
                    continue
                }
                var j = i + 1
                if (j < fmt.length && fmt[j] == '%') {
                    out.append('%')
                    i = j + 1
                    continue
                }
                // flags (a set, not a multiset — `%00000d` ≡ `%0d`, so dedupe
                // to keep flagsStr O(1) regardless of how long the run is.
                // A pathological format like `%0...0d` with 100k zeros would
                // otherwise produce a 100k-char flagsStr and turn every
                // subsequent `c in flagsStr` check into an O(n) scan.)
                var flagBits = 0
                while (j < fmt.length) {
                    val c = fmt[j]
                    val bit =
                        when (c) {
                            '-' -> 1
                            '+' -> 2
                            ' ' -> 4
                            '0' -> 8
                            '#' -> 16
                            else -> 0
                        }
                    if (bit == 0) break
                    flagBits = flagBits or bit
                    j++
                }
                val flags =
                    buildString {
                        if (flagBits and 1 != 0) append('-')
                        if (flagBits and 2 != 0) append('+')
                        if (flagBits and 4 != 0) append(' ')
                        if (flagBits and 8 != 0) append('0')
                        if (flagBits and 16 != 0) append('#')
                    }
                // width (int or *)
                var width = 0
                if (j < fmt.length && fmt[j] == '*') {
                    width = toLongArg(nextArg()).toInt()
                    j++
                } else {
                    val sb = StringBuilder()
                    while (j < fmt.length && fmt[j].isDigit()) {
                        sb.append(fmt[j])
                        j++
                    }
                    if (sb.isNotEmpty()) {
                        val parsed = sb.toString().toIntOrNull()
                        if (parsed == null) {
                            // Bash behavior for width > INT_MAX: emit a stderr
                            // diagnostic, set exit 1, and drop the field width
                            // (don't attempt the multi-GB allocation).
                            err.append("${ctx.shellDiagPrefix}printf: $sb: invalid number\n")
                            exitCode = 1
                            width = 0
                        } else {
                            width = parsed
                        }
                    }
                }
                // negative width via *-arg → left-justify
                var leftJustify = '-' in flags
                if (width < 0) {
                    leftJustify = true
                    width = -width
                }
                // precision
                var precision = -1
                if (j < fmt.length && fmt[j] == '.') {
                    j++
                    if (j < fmt.length && fmt[j] == '*') {
                        precision = toLongArg(nextArg()).toInt()
                        j++
                    } else {
                        val sb = StringBuilder()
                        while (j < fmt.length && fmt[j].isDigit()) {
                            sb.append(fmt[j])
                            j++
                        }
                        precision =
                            when {
                                sb.isEmpty() -> {
                                    0
                                }

                                else -> {
                                    val parsed = sb.toString().toIntOrNull()
                                    if (parsed == null) {
                                        err.append("${ctx.shellDiagPrefix}printf: $sb: invalid number\n")
                                        exitCode = 1
                                        -1
                                    } else {
                                        parsed
                                    }
                                }
                            }
                    }
                    if (precision < 0) precision = -1
                }
                // skip the bash modifiers `l`, `h`, `j`, `t`, `z`, `L` which don't affect us
                while (j < fmt.length && fmt[j] in "lhjztL") j++
                if (j >= fmt.length) {
                    err.append("${ctx.shellDiagPrefix}printf: `${fmt.substring(i)}': missing format character\n")
                    exitCode = 1
                    i = j
                    continue
                }
                val conv = fmt[j]
                val flagsStr = flags
                val zeroPad = '0' in flagsStr && !leftJustify

                val rendered: String =
                    when (conv) {
                        's' -> {
                            val v = nextArg() ?: ""
                            if (precision >= 0 && v.length > precision) v.substring(0, precision) else v
                        }

                        'd', 'i' -> {
                            val n = toLongArg(nextArg())
                            formatIntFull(n, 10, false, flagsStr, precision, signed = true)
                        }

                        'u' -> {
                            val n = toLongArg(nextArg())
                            formatIntFull(n, 10, false, flagsStr, precision, signed = false)
                        }

                        'o' -> {
                            val n = toLongArg(nextArg())
                            val s = formatIntFull(n, 8, false, flagsStr, precision, signed = false)
                            if ('#' in flagsStr && !s.startsWith("0")) "0$s" else s
                        }

                        'x' -> {
                            val n = toLongArg(nextArg())
                            val s = formatIntFull(n, 16, false, flagsStr, precision, signed = false)
                            if ('#' in flagsStr && n != 0L) "0x$s" else s
                        }

                        'X' -> {
                            val n = toLongArg(nextArg())
                            val s = formatIntFull(n, 16, true, flagsStr, precision, signed = false)
                            if ('#' in flagsStr && n != 0L) "0X$s" else s
                        }

                        'c' -> {
                            val v = nextArg() ?: ""
                            if (v.isEmpty()) "" else v.substring(0, 1)
                        }

                        'q' -> {
                            val v = nextArg() ?: ""
                            val quoted = shellQuoteBash(v)
                            if (precision >= 0 && quoted.length > precision) quoted.substring(0, precision) else quoted
                        }

                        'Q' -> {
                            val v = nextArg() ?: ""
                            val truncated = if (precision >= 0 && v.length > precision) v.substring(0, precision) else v
                            shellQuoteBash(truncated)
                        }

                        'b' -> {
                            val v = nextArg() ?: ""
                            val r = decodeBEscapes(v)
                            if (r.stop) stop = true
                            if (r.diagnostic != null) {
                                err.append("${ctx.shellDiagPrefix}printf: ").append(r.diagnostic).append('\n')
                            }
                            if (precision >= 0 && r.text.length > precision) r.text.substring(0, precision) else r.text
                        }

                        'f', 'F' -> {
                            val n = toDoubleArg(nextArg())
                            val p = if (precision < 0) 6 else precision
                            formatFixedSigned(n, p, flagsStr, alt = '#' in flagsStr)
                        }

                        'e', 'E' -> {
                            val n = toDoubleArg(nextArg())
                            val p = if (precision < 0) 6 else precision
                            formatScientificSigned(n, p, conv == 'E', flagsStr, alt = '#' in flagsStr)
                        }

                        'g', 'G' -> {
                            val n = toDoubleArg(nextArg())
                            val p =
                                if (precision < 0) {
                                    6
                                } else if (precision == 0) {
                                    1
                                } else {
                                    precision
                                }
                            formatGeneralSigned(n, p, conv == 'G', flagsStr, alt = '#' in flagsStr)
                        }

                        'n' -> {
                            val v = nextArg() ?: ""
                            if (!isValidIdentifier(v)) {
                                err.append("${ctx.shellDiagPrefix}printf: `$v': not a valid identifier\n")
                                exitCode = 1
                            } else {
                                ctx.process.env[v] = out.length.toString()
                            }
                            ""
                        }

                        else -> {
                            err.append("${ctx.shellDiagPrefix}printf: `$conv': invalid format character\n")
                            exitCode = 1
                            i = j + 1
                            continue
                        }
                    }
                out.append(padField(rendered, width, leftJustify, zeroPad, conv))
                i = j + 1
            }
        }

        if (rest.isEmpty()) {
            renderOnce()
        } else {
            do {
                val before = argIdx
                renderOnce()
                if (stop) break
                if (argIdx == before) break
            } while (argIdx < rest.size)
        }

        if (err.isNotEmpty()) ctx.stderr.writeUtf8(err.toString())
        if (varName != null) {
            val lb = varName.indexOf('[')
            val rb = varName.lastIndexOf(']')
            val setter = ctx.setArrayElement
            if (lb > 0 && rb == varName.length - 1 && setter != null) {
                // `printf -v NAME[sub]` stores into the array element via the
                // interpreter (which picks indexed vs assoc storage); a plain
                // `env[NAME[sub]]` write would create a bogusly-named scalar.
                val base = varName.substring(0, lb)
                val sub = varName.substring(lb + 1, rb)
                setter(base, sub, out.toString())
            } else {
                ctx.process.env[varName] = out.toString()
            }
        } else {
            ctx.stdout.writeUtf8(out.toString())
        }
        return CommandResult(exitCode = exitCode)
    }
}

private data class EscapeResult(
    val text: String,
    val consumed: Int,
    /** Non-null when the escape produces a diagnostic (e.g. `\x` with no hex
     *  digit). Caller is responsible for prefixing with `printf: ` and
     *  emitting to stderr. */
    val diagnostic: String? = null,
)

private data class BEscapeResult(
    val text: String,
    val stop: Boolean,
    /** Optional diagnostic surfaced to stderr; same convention as
     *  [EscapeResult.diagnostic]. */
    val diagnostic: String? = null,
)

/** Decode one C-style escape at [s][[idx]]. Returns the decoded text and the
 *  number of source chars consumed. Unrecognized escapes keep both chars
 *  literally (so `\.` stays `\.`).
 */
private fun decodeFormatEscape(
    s: String,
    idx: Int,
): EscapeResult =
    when (val n = s[idx + 1]) {
        'a' -> {
            EscapeResult("\u0007", 2)
        }

        'b' -> {
            EscapeResult("\b", 2)
        }

        'e', 'E' -> {
            EscapeResult("\u001B", 2)
        }

        'f' -> {
            EscapeResult("\u000C", 2)
        }

        'n' -> {
            EscapeResult("\n", 2)
        }

        'r' -> {
            EscapeResult("\r", 2)
        }

        't' -> {
            EscapeResult("\t", 2)
        }

        'v' -> {
            EscapeResult("\u000B", 2)
        }

        '\\' -> {
            EscapeResult("\\", 2)
        }

        '\'' -> {
            EscapeResult("'", 2)
        }

        '"' -> {
            EscapeResult("\"", 2)
        }

        '?' -> {
            EscapeResult("?", 2)
        }

        'x' -> {
            var p = idx + 2
            val sb = StringBuilder()
            while (p < s.length && sb.length < 2 && s[p].isHex()) {
                sb.append(s[p])
                p++
            }
            if (sb.isEmpty()) {
                // POSIX printf with `\x` and no following hex digit: emit
                // the `\x` literally AND surface a diagnostic. (Some test
                // fixtures rely on observing both the diagnostic and the
                // unsubstituted byte sequence.)
                EscapeResult("\\x", 2, diagnostic = "missing hex digit for \\x")
            } else {
                EscapeResult(
                    sb
                        .toString()
                        .toInt(16)
                        .toChar()
                        .toString(),
                    2 + sb.length,
                )
            }
        }

        in '0'..'7' -> {
            var p = idx + 1
            val sb = StringBuilder()
            while (p < s.length && sb.length < 3 && s[p] in '0'..'7') {
                sb.append(s[p])
                p++
            }
            EscapeResult(
                sb
                    .toString()
                    .toInt(8)
                    .toChar()
                    .toString(),
                1 + sb.length,
            )
        }

        else -> {
            EscapeResult("\\$n", 2)
        }
    }

/** Decode `%b` argument escapes. `\c` means "stop further output". Octal escapes
 *  accept both `\NNN` and `\0NNN` per bash.
 */
private fun decodeBEscapes(s: String): BEscapeResult {
    var diag: String? = null
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i + 1 >= s.length) {
            sb.append(c)
            i++
            continue
        }
        when (val n = s[i + 1]) {
            'a' -> {
                sb.append('\u0007')
                i += 2
            }

            'b' -> {
                sb.append('\b')
                i += 2
            }

            'e', 'E' -> {
                sb.append('\u001B')
                i += 2
            }

            'f' -> {
                sb.append('\u000C')
                i += 2
            }

            'n' -> {
                sb.append('\n')
                i += 2
            }

            'r' -> {
                sb.append('\r')
                i += 2
            }

            't' -> {
                sb.append('\t')
                i += 2
            }

            'v' -> {
                sb.append('\u000B')
                i += 2
            }

            '\\' -> {
                sb.append('\\')
                i += 2
            }

            'c' -> {
                return BEscapeResult(sb.toString(), stop = true, diagnostic = diag)
            }

            '0' -> {
                var p = i + 2
                val oct = StringBuilder()
                while (p < s.length && oct.length < 3 && s[p] in '0'..'7') {
                    oct.append(s[p])
                    p++
                }
                if (oct.isEmpty()) {
                    sb.append(Ansi.NUL)
                    i += 2
                } else {
                    sb.append(oct.toString().toInt(8).toChar())
                    i = p
                }
            }

            in '1'..'7' -> {
                var p = i + 1
                val oct = StringBuilder()
                while (p < s.length && oct.length < 3 && s[p] in '0'..'7') {
                    oct.append(s[p])
                    p++
                }
                sb.append(oct.toString().toInt(8).toChar())
                i = p
            }

            'x' -> {
                var p = i + 2
                val hex = StringBuilder()
                while (p < s.length && hex.length < 2 && s[p].isHex()) {
                    hex.append(s[p])
                    p++
                }
                if (hex.isEmpty()) {
                    // POSIX printf %b with `\x` and no following hex digit:
                    // emit `\x` literally and surface a diagnostic on the
                    // attached `diag` slot — caller propagates to stderr.
                    sb.append("\\x")
                    diag = "missing hex digit for \\x"
                    i += 2
                } else {
                    sb.append(hex.toString().toInt(16).toChar())
                    i = p
                }
            }

            else -> {
                sb.append('\\').append(n)
                i += 2
            }
        }
    }
    return BEscapeResult(sb.toString(), stop = false, diagnostic = diag)
}

private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun isValidIdentifier(s: String): Boolean {
    if (s.isEmpty()) return false
    if (!(s[0].isLetter() || s[0] == '_')) return false
    // Accept the bracketed-name form `NAME[sub]`: printf -v writes a
    // single array element. The interpreter post-dispatch hook then
    // routes the bracketed env key into the var table's array map.
    // Subscript must be non-empty and contain no internal `]` (the
    // closing bracket terminates the form).
    val lb = s.indexOf('[')
    if (lb > 0 && s.endsWith(']')) {
        val base = s.substring(0, lb)
        if (!base.all { it.isLetterOrDigit() || it == '_' }) return false
        val sub = s.substring(lb + 1, s.length - 1)
        if (sub.isEmpty()) return false
        if (']' in sub) return false
        return true
    }
    return s.all { it.isLetterOrDigit() || it == '_' }
}

/** Accept decimal, `0xHEX`, or octal (leading `0`). Treat trailing junk as a
 *  parse failure (caller emits a warning).
 */
private fun tryParseLong(v: String): Long? {
    val s = v.trim()
    if (s.isEmpty()) return null
    val (sign, body) =
        when {
            s.startsWith("-") -> -1L to s.substring(1)
            s.startsWith("+") -> 1L to s.substring(1)
            else -> 1L to s
        }
    if (body.isEmpty()) return null
    val parsed =
        when {
            body.startsWith("0x") || body.startsWith("0X") -> body.substring(2).toLongOrNull(16)
            body.startsWith("0") && body.length > 1 && body.all { it in '0'..'7' } -> body.toLongOrNull(8)
            else -> body.toLongOrNull(10)
        } ?: return null
    return sign * parsed
}

/** Integer formatter with `+`, ` `, precision and zero-padding-via-precision. */
private fun formatIntFull(
    n: Long,
    base: Int,
    upper: Boolean,
    flags: String,
    precision: Int,
    signed: Boolean,
): String {
    val neg = signed && n < 0
    val mag = if (neg) -n else n
    var body = mag.toString(base)
    if (upper) body = body.uppercase()
    if (precision >= 0 && body.length < precision) body = "0".repeat(precision - body.length) + body
    val sign =
        when {
            neg -> "-"
            '+' in flags && base == 10 && signed -> "+"
            ' ' in flags && base == 10 && signed -> " "
            else -> ""
        }
    return sign + body
}

/** Pad to [width]. Zero-padding inserts zeroes between the sign and digits. */
private fun padField(
    s: String,
    width: Int,
    leftJustify: Boolean,
    zeroPad: Boolean,
    conv: Char,
): String {
    if (s.length >= width) return s
    val gap = width - s.length
    if (leftJustify) return s + " ".repeat(gap)
    if (zeroPad && conv in "diouxXeEfFgG") {
        // keep the sign char in front of the zeroes
        if (s.isNotEmpty() && (s[0] == '-' || s[0] == '+' || s[0] == ' ')) {
            return s[0] + "0".repeat(gap) + s.substring(1)
        }
        return "0".repeat(gap) + s
    }
    return " ".repeat(gap) + s
}

/** Fixed-point float formatting: prec digits after the decimal point. */
private fun formatFixedSigned(
    v: Double,
    prec: Int,
    flags: String,
    alt: Boolean,
): String {
    val neg = v < 0.0 || (v == 0.0 && 1.0 / v < 0)
    val a = abs(v)
    val scale = 10.0.pow(prec)
    val rounded = (a * scale).roundToLong()
    val s = rounded.toString().padStart(prec + 1, '0')
    val intPart = if (prec == 0) s else s.dropLast(prec)
    val fracPart = if (prec == 0) (if (alt) "." else "") else "." + s.takeLast(prec)
    val signCh =
        when {
            neg -> "-"
            '+' in flags -> "+"
            ' ' in flags -> " "
            else -> ""
        }
    return signCh + intPart + fracPart
}

/** Scientific notation: m.mmme±EE. */
private fun formatScientificSigned(
    v: Double,
    prec: Int,
    upper: Boolean,
    flags: String,
    alt: Boolean,
): String {
    val signCh =
        when {
            v < 0.0 -> "-"
            '+' in flags -> "+"
            ' ' in flags -> " "
            else -> ""
        }
    val a = abs(v)
    if (a == 0.0) {
        val mant = if (prec == 0) "0" + (if (alt) "." else "") else "0." + "0".repeat(prec)
        return signCh + mant + (if (upper) "E" else "e") + "+00"
    }
    var exp = 0
    var mantissa = a
    while (mantissa >= 10.0) {
        mantissa /= 10.0
        exp++
    }
    while (mantissa < 1.0) {
        mantissa *= 10.0
        exp--
    }
    // round mantissa to prec digits; may carry
    val scale = 10.0.pow(prec)
    var rounded = (mantissa * scale).roundToLong()
    if (rounded >= (10L * scale.toLong())) {
        rounded /= 10
        exp++
    }
    val s = rounded.toString().padStart(prec + 1, '0')
    val intPart = s.dropLast(prec)
    val fracPart = if (prec == 0) (if (alt) "." else "") else "." + s.takeLast(prec)
    val expSign = if (exp < 0) "-" else "+"
    val expBody = abs(exp).toString().padStart(2, '0')
    val e = if (upper) "E" else "e"
    return signCh + intPart + fracPart + e + expSign + expBody
}

/** General `%g`: prec is significant digits; pick fixed vs scientific by exponent.
 *  Trailing zeroes are stripped unless `#` flag preserves them.
 */
private fun formatGeneralSigned(
    v: Double,
    prec: Int,
    upper: Boolean,
    flags: String,
    alt: Boolean,
): String {
    if (v == 0.0) {
        return when {
            alt -> {
                val frac = if (prec >= 1) "." + "0".repeat(prec - 1) else ""
                "0$frac"
            }

            else -> {
                "0"
            }
        }.let { body ->
            when {
                '+' in flags -> "+$body"
                ' ' in flags -> " $body"
                else -> body
            }
        }
    }
    val a = abs(v)
    var exp = 0
    var test = a
    while (test >= 10.0) {
        test /= 10.0
        exp++
    }
    while (test < 1.0) {
        test *= 10.0
        exp--
    }
    val useSci = exp < -4 || exp >= prec
    val body =
        if (useSci) {
            val s = formatScientificSigned(a, prec - 1, upper, "", alt)
            if (alt) s else stripTrailingZerosInExp(s)
        } else {
            val fp = prec - 1 - exp
            val s = formatFixedSigned(a, fp.coerceAtLeast(0), "", alt)
            if (alt) s else stripTrailingZeros(s)
        }
    return when {
        v < 0.0 -> "-$body"
        '+' in flags -> "+$body"
        ' ' in flags -> " $body"
        else -> body
    }
}

private fun stripTrailingZeros(s: String): String {
    if (!s.contains('.')) return s
    var end = s.length
    while (end > 0 && s[end - 1] == '0') end--
    if (end > 0 && s[end - 1] == '.') end--
    return s.substring(0, end)
}

private fun stripTrailingZerosInExp(s: String): String {
    val eIdx = s.indexOfFirst { it == 'e' || it == 'E' }
    if (eIdx < 0) return stripTrailingZeros(s)
    val mant = s.substring(0, eIdx)
    val expPart = s.substring(eIdx)
    val trimmed = stripTrailingZeros(mant)
    return trimmed + expPart
}

/** Bash-style `%q` quoting: backslash-escape each shell-special char so the
 *  result can be re-read by the shell. Empty input renders as `''`. Newlines and
 *  other control chars use the `$'\xHH'` ANSI-C form.
 */
private fun shellQuoteBash(s: String): String {
    if (s.isEmpty()) return "''"
    // If string contains control chars, use the ANSI-C form for the whole string
    if (s.any { it.code < 0x20 || it.code == 0x7F }) {
        val sb = StringBuilder("$'")
        for (c in s) {
            when (c) {
                '\n' -> {
                    sb.append("\\n")
                }

                '\t' -> {
                    sb.append("\\t")
                }

                '\r' -> {
                    sb.append("\\r")
                }

                '\\' -> {
                    sb.append("\\\\")
                }

                '\'' -> {
                    sb.append("\\'")
                }

                else -> {
                    if (c.code < 0x20 || c.code == 0x7F) {
                        // Bash %q emits 3-digit octal for arbitrary control
                        // chars (`\001`), not hex. Matches bash 5.x printf.
                        sb.append("\\").append(c.code.toString(8).padStart(3, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('\'')
        return sb.toString()
    }
    // Fast path: only chars in the always-safe set → no quoting needed
    val safe = s.all { it.isLetterOrDigit() || it in "@%+=:,./_-" }
    if (safe) return s
    // Otherwise backslash-escape each shell-special char
    val sb = StringBuilder()
    for (c in s) {
        if (c.isLetterOrDigit() || c in "@%+=:,./_-") {
            sb.append(c)
        } else {
            sb.append('\\').append(c)
        }
    }
    return sb.toString()
}
