package com.accucodeai.kash.tools.seq

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.io.writeUtf8
import kotlin.math.abs
import kotlin.math.max

/**
 * `seq` — print a sequence of numbers.
 *
 * Forms:
 * - `seq LAST`                  → 1, 2, ..., LAST
 * - `seq FIRST LAST`            → FIRST, FIRST+1, ..., LAST  (step inferred ±1)
 * - `seq FIRST INCR LAST`       → FIRST, FIRST+INCR, ..., (last not exceeding LAST)
 *
 * Flags:
 * - `-s SEP` — separator between numbers (default `"\n"`). A trailing newline is
 *   always emitted (matching coreutils behavior).
 * - `-w`     — pad numbers with leading zeros to equal width.
 *
 * Numbers may be integers or decimals. Negative step is permitted (in which
 * case `FIRST` must be ≥ `LAST`). A step of 0 is an error.
 */
public class SeqCommand :
    Command,
    CommandSpec {
    override val name: String = "seq"
    override val kind: CommandKind = CommandKind.TOOL
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val parsed = parseArgs(args, ctx) ?: return CommandResult(exitCode = 2)
        if (parsed.exitCode != null) return CommandResult(exitCode = parsed.exitCode)

        val positional = parsed.positional
        if (positional.isEmpty() || positional.size > 3) {
            ctx.stderr.writeUtf8("seq: usage: seq [-w] [-s SEP] [FIRST [INCR]] LAST\n")
            return CommandResult(exitCode = 2)
        }

        val (firstStr, incrStr, lastStr) = expandOperands(positional)

        val first = parseNum(firstStr) ?: return invalid(ctx, firstStr)
        val incr = parseNum(incrStr) ?: return invalid(ctx, incrStr)
        val last = parseNum(lastStr) ?: return invalid(ctx, lastStr)

        if (incr == 0.0) {
            ctx.stderr.writeUtf8("seq: zero increment\n")
            return CommandResult(exitCode = 1)
        }

        // Decimal precision is the max fractional-digit count among inputs.
        val precision = max(max(fracDigits(firstStr), fracDigits(incrStr)), fracDigits(lastStr))
        val pad: Int = if (parsed.equalWidth) computePadWidth(first, incr, last, precision) else 0

        val ascending = incr > 0
        if ((ascending && first > last) || (!ascending && first < last)) {
            // Empty sequence — no output, exit 0 (matches coreutils).
            return CommandResult()
        }

        var firstOut = true
        var step = 0L
        while (true) {
            val cur = first + step * incr
            val tolerance = 1e-12 * max(1.0, abs(last))
            if (ascending && cur > last + tolerance) break
            if (!ascending && cur < last - tolerance) break
            val s = format(cur, precision, pad)
            if (!firstOut) ctx.stdout.writeUtf8(parsed.sep)
            ctx.stdout.writeUtf8(s)
            firstOut = false
            step++
        }
        if (!firstOut) ctx.stdout.writeUtf8("\n")
        return CommandResult()
    }

    private data class ParsedArgs(
        val sep: String,
        val equalWidth: Boolean,
        val positional: List<String>,
        val exitCode: Int? = null,
    )

    private suspend fun parseArgs(
        args: List<String>,
        ctx: CommandContext,
    ): ParsedArgs? {
        var sep = "\n"
        var equalWidth = false
        val positional = mutableListOf<String>()
        var i = 0
        var endOfOpts = false
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                positional += a
            } else if (a == "--") {
                endOfOpts = true
            } else if (a == "-w" || a == "--equal-width") {
                equalWidth = true
            } else if (a == "-s" || a == "--separator") {
                if (i + 1 >= args.size) {
                    ctx.stderr.writeUtf8("seq: option requires an argument -- '${a.trimStart('-')}'\n")
                    return ParsedArgs(sep, equalWidth, positional, exitCode = 2)
                }
                sep = args[++i]
            } else if (a.startsWith("-s") && a.length > 2) {
                sep = a.substring(2)
            } else if (a == "-" || looksLikeNumber(a)) {
                // Bare "-" or a numeric-looking value (incl. negatives) is positional.
                positional += a
            } else if (a.startsWith("--")) {
                ctx.stderr.writeUtf8("seq: unrecognized option: $a\n")
                return ParsedArgs(sep, equalWidth, positional, exitCode = 2)
            } else if (a.startsWith("-") && a.length > 1) {
                ctx.stderr.writeUtf8("seq: invalid option -- '${a.substring(1)}'\n")
                return ParsedArgs(sep, equalWidth, positional, exitCode = 2)
            } else {
                positional += a
            }
            i++
        }
        return ParsedArgs(sep, equalWidth, positional)
    }

    private fun expandOperands(positional: List<String>): Triple<String, String, String> =
        when (positional.size) {
            1 -> Triple("1", "1", positional[0])
            2 -> Triple(positional[0], "1", positional[1])
            else -> Triple(positional[0], positional[1], positional[2])
        }

    private suspend fun invalid(
        ctx: CommandContext,
        s: String,
    ): CommandResult {
        ctx.stderr.writeUtf8("seq: invalid floating point argument: '$s'\n")
        return CommandResult(exitCode = 1)
    }

    private fun looksLikeNumber(s: String): Boolean {
        if (s.isEmpty()) return false
        val body = if (s.startsWith("-") || s.startsWith("+")) s.substring(1) else s
        if (body.isEmpty()) return false
        var sawDigit = false
        var sawDot = false
        var sawE = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c in '0'..'9') {
                sawDigit = true
            } else if (c == '.' && !sawDot && !sawE) {
                sawDot = true
            } else if ((c == 'e' || c == 'E') && sawDigit && !sawE) {
                sawE = true
                if (i + 1 < body.length && (body[i + 1] == '+' || body[i + 1] == '-')) i++
            } else {
                return false
            }
            i++
        }
        return sawDigit
    }

    private fun parseNum(s: String): Double? = if (looksLikeNumber(s)) s.toDoubleOrNull() else null

    private fun fracDigits(s: String): Int {
        val dot = s.indexOf('.')
        if (dot < 0) return 0
        var end = s.length
        val eIdx = s.indexOfAny(charArrayOf('e', 'E'), startIndex = dot)
        if (eIdx >= 0) end = eIdx
        return end - dot - 1
    }

    private fun format(
        v: Double,
        precision: Int,
        pad: Int,
    ): String {
        val raw = formatFixed(v, precision)
        if (pad <= 0 || raw.length >= pad) return raw
        // Pad zeros after the optional leading '-'.
        return if (raw.startsWith("-")) {
            "-" + raw.substring(1).padStart(pad - 1, '0')
        } else {
            raw.padStart(pad, '0')
        }
    }

    private fun formatFixed(
        v: Double,
        precision: Int,
    ): String {
        if (precision == 0) {
            val rounded = if (v >= 0) (v + 0.5).toLong() else -((-v + 0.5).toLong())
            return rounded.toString()
        }
        val negative = v < 0
        val absV = abs(v)
        var scale = 1.0
        repeat(precision) { scale *= 10.0 }
        val scaled = (absV * scale + 0.5).toLong()
        val scaleLong = scale.toLong()
        val intPart = scaled / scaleLong
        val fracPart = scaled % scaleLong
        val fracStr = fracPart.toString().padStart(precision, '0')
        val body = "$intPart.$fracStr"
        return if (negative && scaled != 0L) "-$body" else body
    }

    private fun computePadWidth(
        first: Double,
        incr: Double,
        last: Double,
        precision: Int,
    ): Int {
        val a = formatFixed(first, precision).length
        val b = formatFixed(last, precision).length
        val ascending = incr > 0
        if ((ascending && first > last) || (!ascending && first < last)) return max(a, b)
        val n = ((last - first) / incr).toLong()
        val reachable = first + n * incr
        val c = formatFixed(reachable, precision).length
        return max(max(a, b), c)
    }
}
