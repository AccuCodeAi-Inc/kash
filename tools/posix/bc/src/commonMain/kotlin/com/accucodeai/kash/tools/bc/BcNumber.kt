package com.accucodeai.kash.tools.bc

/**
 * Arbitrary-precision fixed-point decimal used by bc.
 *
 * Internal representation: a signed integer [unscaled] (as a base-10 magnitude
 * stored in a digit array) with a non-negative [scale] giving the number of
 * digits after the implied decimal point. So the value is
 *   sign * unscaled * 10^-scale.
 *
 * This is *not* a general BigDecimal — we only implement what bc needs:
 * add, sub, mul, div (with scale parameter), mod, pow (integer exponent),
 * sqrt (Newton's method), comparison, and base-N input/output (ibase/obase).
 *
 * We hand-roll a base-10^9 magnitude rather than pulling in java.math.BigInteger
 * because this module is kotlin-multiplatform commonMain (no JVM-only deps).
 */
public class BcNumber private constructor(
    internal val sign: Int, // -1, 0, or +1
    internal val unscaled: UMag, // magnitude (always >= 0)
    public val scale: Int,
) {
    init {
        require(scale >= 0)
        require(sign in -1..1)
        require(if (unscaled.isZero()) sign == 0 else sign != 0)
    }

    public fun isZero(): Boolean = sign == 0

    public fun negate(): BcNumber = if (isZero()) this else BcNumber(-sign, unscaled, scale)

    public fun withScale(newScale: Int): BcNumber {
        if (newScale == scale) return this
        if (newScale > scale) {
            // pad zeros: multiply by 10^(newScale - scale)
            val m = unscaled.mulPow10(newScale - scale)
            return BcNumber(sign, m, newScale)
        }
        // truncate (bc truncates, not rounds)
        val m = unscaled.divPow10(scale - newScale)
        val s = if (m.isZero()) 0 else sign
        return BcNumber(s, m, newScale)
    }

    public operator fun unaryMinus(): BcNumber = negate()

    public operator fun plus(other: BcNumber): BcNumber {
        val s = maxOf(scale, other.scale)
        val a = this.withScale(s)
        val b = other.withScale(s)
        return if (a.sign == b.sign) {
            // same sign: add magnitudes
            if (a.sign == 0) zero(s) else BcNumber(a.sign, a.unscaled + b.unscaled, s)
        } else if (a.sign == 0) {
            b
        } else if (b.sign == 0) {
            a
        } else {
            val cmp = a.unscaled.compareTo(b.unscaled)
            when {
                cmp == 0 -> zero(s)
                cmp > 0 -> BcNumber(a.sign, a.unscaled - b.unscaled, s)
                else -> BcNumber(b.sign, b.unscaled - a.unscaled, s)
            }
        }
    }

    public operator fun minus(other: BcNumber): BcNumber = this + (-other)

    public operator fun times(other: BcNumber): BcNumber {
        if (this.isZero() || other.isZero()) return zero(minOf(scale + other.scale, maxOf(scale, other.scale)))
        // POSIX bc: scale of a*b is min(a.scale + b.scale, max(scale, a.scale, b.scale)).
        // But we ignore the scale variable in the type level; the interpreter
        // passes in a desired scale via mulWithScale when needed. Simpler:
        // produce full precision scale + truncate later in the interpreter.
        val m = this.unscaled * other.unscaled
        val s = this.sign * other.sign
        return BcNumber(s, m, scale + other.scale)
    }

    /**
     * bc-flavor multiply: result scale is min(a+b, max(scale, a, b)).
     */
    public fun mulBc(
        other: BcNumber,
        scaleVar: Int,
    ): BcNumber {
        val full = this * other
        val target = minOf(this.scale + other.scale, maxOf(scaleVar, this.scale, other.scale))
        return full.withScale(target)
    }

    /**
     * bc division: result scale is exactly [scaleVar]; the numerator is shifted
     * to that scale before the integer divide. POSIX truncation.
     */
    public fun divBc(
        other: BcNumber,
        scaleVar: Int,
    ): BcNumber {
        if (other.isZero()) throw BcRuntimeError("divide by zero")
        if (this.isZero()) return zero(scaleVar)
        // Compute (this * 10^(scaleVar + other.scale)) / other.unscaled, then result has scale scaleVar.
        // numerator unscaled has this.scale digits implicit; we need denominator
        // canceled, so shift this by (scaleVar + other.scale - this.scale) places.
        val shift = scaleVar + other.scale - this.scale
        val numerator =
            if (shift >= 0) {
                this.unscaled.mulPow10(shift)
            } else {
                this.unscaled.divPow10(-shift)
            }
        val (q, _) = numerator.divmod(other.unscaled)
        val sign = if (q.isZero()) 0 else this.sign * other.sign
        return BcNumber(sign, q, scaleVar)
    }

    /**
     * POSIX bc modulo:  a % b  is defined as  a - (a / b) * b  where the division
     * is done at the current scale. The result's scale is max(scale, a.scale - b.scale + scale).
     * In practice we implement it as (a - (a/b)*b) at scale scaleVar.
     */
    public fun modBc(
        other: BcNumber,
        scaleVar: Int,
    ): BcNumber {
        if (other.isZero()) throw BcRuntimeError("divide by zero")
        val q = this.divBc(other, scaleVar)
        val prod = q.mulBc(other, scaleVar)
        return this - prod
    }

    /**
     * Integer-exponent power: x^y. y must be an integer (truncated).
     */
    public fun powBc(
        exp: BcNumber,
        scaleVar: Int,
    ): BcNumber {
        // truncate exp to integer
        val e = exp.withScale(0)
        if (!e.unscaled.fitsInInt()) throw BcRuntimeError("exponent too large")
        val n = e.unscaled.toInt() * (if (e.sign < 0) -1 else 1)
        if (n == 0) return one(0)
        if (n > 0) {
            // exact: scale grows multiplicatively
            var base = this
            var k = n
            var result = one(0)
            while (k > 0) {
                if (k and 1 == 1) {
                    val full = result * base
                    // bc says scale(x^y) = min(scale*|y|, max(scale, scale_of_x)) — approximate by truncation later
                    result = full
                }
                k = k ushr 1
                if (k > 0) base = base * base
            }
            // POSIX: if y > 0, scale of x^y is min(scale*y, max(scale, scale_of_x))
            val target = minOf(this.scale * n, maxOf(scaleVar, this.scale))
            return result.withScale(target)
        } else {
            // x^(-n) = 1 / x^n  at scaleVar
            val pos = this.powBc(fromInt(-n), scaleVar)
            return one(0).divBc(pos, scaleVar)
        }
    }

    /**
     * Compare numerically. Returns -1 / 0 / +1.
     */
    public operator fun compareTo(other: BcNumber): Int {
        val s = maxOf(scale, other.scale)
        val a = this.withScale(s)
        val b = other.withScale(s)
        if (a.sign != b.sign) return a.sign.compareTo(b.sign)
        if (a.sign == 0) return 0
        val mag = a.unscaled.compareTo(b.unscaled)
        return if (a.sign > 0) mag else -mag
    }

    override fun equals(other: Any?): Boolean = other is BcNumber && this.compareTo(other) == 0

    override fun hashCode(): Int {
        // toString of canonical form gives consistent hash
        return formatBase10().hashCode()
    }

    override fun toString(): String = formatBase10()

    /**
     * Format with the current scale, base 10 (used for tests + obase==10).
     */
    public fun formatBase10(): String {
        val digits = unscaled.toBase10String()
        val body =
            if (scale == 0) {
                digits
            } else if (digits.length <= scale) {
                "." + digits.padStart(scale, '0')
            } else {
                val cut = digits.length - scale
                digits.substring(0, cut) + "." + digits.substring(cut)
            }
        return if (sign < 0 && !isZero()) "-$body" else body
    }

    /**
     * Format in arbitrary output base (POSIX: integer part uses positional
     * digits separated by spaces when base > 16; 0-9 then A-F for ≤16;
     * we support base ≥ 2). Fractional part is converted digit-by-digit
     * using repeated multiplication.
     */
    public fun formatBase(
        obase: Int,
        scaleVar: Int,
    ): String {
        require(obase >= 2)
        if (obase == 10) return formatBase10()
        // split into integer + fractional
        val intPart = this.withScale(0).unscaled // truncate toward zero
        val fracDigits = scale
        // fractional value: unscaled % 10^scale
        val fractional =
            if (scale == 0) {
                UMag.ZERO
            } else {
                val pow = UMag.pow10(fracDigits)
                unscaled.divmod(pow).second
            }
        val intStr = formatIntegerInBase(intPart, obase)
        val sb = StringBuilder()
        if (sign < 0 && !isZero()) sb.append('-')
        sb.append(intStr)
        if (fractional.isZero()) return sb.toString()
        sb.append('.')
        // fractional digits: each step multiplies the fraction by obase, takes
        // floor as digit. Continue for `scaleVar` (or `scale`, whichever the
        // caller picks) — for simplicity we emit `scale` digits.
        val pow = UMag.pow10(fracDigits)
        var rem = fractional
        val outDigits = if (scaleVar > 0) scaleVar else fracDigits
        repeat(outDigits) {
            val mul = rem * UMag.fromInt(obase)
            val (d, r) = mul.divmod(pow)
            val digit = if (d.isZero()) 0 else d.toInt()
            sb.append(formatDigitInBase(digit, obase))
            rem = r
            if (rem.isZero()) return@repeat
        }
        return sb.toString().trimEnd('0').let { if (it.endsWith('.')) "${it}0" else it }
    }

    public companion object {
        public val ZERO: BcNumber = BcNumber(0, UMag.ZERO, 0)
        public val ONE: BcNumber = BcNumber(1, UMag.ONE, 0)

        public fun zero(scale: Int = 0): BcNumber = if (scale == 0) ZERO else BcNumber(0, UMag.ZERO, scale)

        public fun one(scale: Int = 0): BcNumber = if (scale == 0) ONE else BcNumber(1, UMag.ONE, 0).withScale(scale)

        public fun fromInt(n: Int): BcNumber {
            if (n == 0) return ZERO
            return BcNumber(if (n > 0) 1 else -1, UMag.fromLong(kotlin.math.abs(n.toLong())), 0)
        }

        public fun fromLong(n: Long): BcNumber {
            if (n == 0L) return ZERO
            return BcNumber(
                if (n >
                    0
                ) {
                    1
                } else {
                    -1
                },
                UMag.fromLong(if (n == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(n)),
                0,
            ).let { if (n == Long.MIN_VALUE) it - ONE else it }
        }

        /**
         * Parse a literal in [ibase]. Allows optional leading '-', digits 0-9
         * (lowercase a-f when ibase > 10 for hex-like), optional '.', then more
         * digits. Newlines inside the literal are skipped (bc behavior:
         * `123\\<newline>456` is the number 123456).
         */
        public fun parse(
            literal: String,
            ibase: Int = 10,
        ): BcNumber {
            var s = literal.replace("\\\n", "")
            if (s.isEmpty()) throw BcRuntimeError("empty number")
            val negative = s.startsWith('-')
            if (negative) s = s.substring(1)
            val dot = s.indexOf('.')
            val intStr = if (dot < 0) s else s.substring(0, dot)
            val fracStr = if (dot < 0) "" else s.substring(dot + 1)
            // base-N parse: each digit contributes digitVal * base^position
            val intVal = parseDigits(intStr, ibase)
            val fracVal = parseDigits(fracStr, ibase)
            // value = intVal + fracVal / base^fracStr.length
            // Result scale = fracStr.length (in base 10 of fractional digits)
            // Map to decimal: intVal * 10^scale + fracVal-scaled.
            // For bc, fractional digit conversion from ibase to decimal is approximate
            // when ibase != 10. We use a simple approach: when ibase == 10, exact.
            // Otherwise, fracStr.length digits in ibase ≈ fracStr.length digits in base 10.
            val scale = fracStr.length
            val combined =
                if (ibase == 10 || fracStr.isEmpty()) {
                    intVal.mulPow10(scale) + fracVal
                } else {
                    // convert fractional value into decimal at given scale
                    val basePow = UMag.fromInt(ibase).pow(scale)
                    val tenPow = UMag.pow10(scale)
                    // fracDecimal = fracVal * 10^scale / basePow  (truncated)
                    val (fd, _) = (fracVal * tenPow).divmod(basePow)
                    intVal.mulPow10(scale) + fd
                }
            val sign =
                when {
                    combined.isZero() -> 0
                    negative -> -1
                    else -> 1
                }
            return BcNumber(sign, combined, scale)
        }

        private fun parseDigits(
            s: String,
            base: Int,
        ): UMag {
            if (s.isEmpty()) return UMag.ZERO
            var v = UMag.ZERO
            val b = UMag.fromInt(base)
            for (ch in s) {
                val d =
                    when {
                        ch in '0'..'9' -> ch - '0'
                        ch in 'A'..'Z' -> ch - 'A' + 10
                        ch in 'a'..'z' -> ch - 'a' + 10
                        ch == '_' -> continue
                        else -> throw BcRuntimeError("bad digit '$ch' in numeric literal")
                    }
                if (d < 0) throw BcRuntimeError("bad digit '$ch'")
                // Note: POSIX bc allows digits ≥ base in input (interprets them
                // as the largest single-digit-equivalent value, but for now we
                // tolerate up to 35).
                if (d >= 36) throw BcRuntimeError("digit '$ch' out of range")
                v = v * b + UMag.fromInt(d)
            }
            return v
        }

        private fun formatIntegerInBase(
            magnitude: UMag,
            obase: Int,
        ): String {
            if (magnitude.isZero()) return "0"
            if (obase <= 16) {
                val sb = StringBuilder()
                var m = magnitude
                val b = UMag.fromInt(obase)
                while (!m.isZero()) {
                    val (q, r) = m.divmod(b)
                    sb.append(formatDigitInBase(r.toInt(), obase))
                    m = q
                }
                return sb.reverse().toString()
            } else {
                // base > 16: emit each "digit" as decimal, space-separated
                val parts = mutableListOf<String>()
                var m = magnitude
                val b = UMag.fromInt(obase)
                while (!m.isZero()) {
                    val (q, r) = m.divmod(b)
                    parts.add(0, r.toInt().toString())
                    m = q
                }
                return parts.joinToString(" ")
            }
        }

        private fun formatDigitInBase(
            d: Int,
            base: Int,
        ): Char = if (d < 10) ('0' + d) else ('A' + (d - 10))

        /**
         * sqrt at scaleVar (Newton's method). Negative input → error.
         */
        public fun sqrt(
            x: BcNumber,
            scaleVar: Int,
        ): BcNumber {
            if (x.sign < 0) throw BcRuntimeError("sqrt of negative")
            if (x.isZero()) return zero(scaleVar)
            // Use scale large enough for stable iteration
            val s = maxOf(scaleVar, x.scale)
            // initial guess: shift digits roughly in half
            var guess = x.withScale(s)
            // crude initial: take an approximate root by scaling
            val tenPowS = UMag.pow10(s)
            val approx = guess.unscaled * tenPowS
            // sqrt of (m * 10^s) at scale s ~ sqrt(m) * 10^(s/2) — approximate via Newton
            var cur = BcNumber(1, intSqrt(approx), s)
            // iterate: cur = (cur + x/cur) / 2
            val two = fromInt(2)
            var prev: BcNumber? = null
            repeat(200) {
                val next = (cur + x.divBc(cur, s)).divBc(two, s)
                if (prev != null && next == prev) return@repeat
                prev = cur
                cur = next
                if (next == prev) return@repeat
            }
            return cur.withScale(scaleVar)
        }

        private fun intSqrt(m: UMag): UMag {
            if (m.isZero()) return UMag.ZERO
            // Newton on integer
            var lo = UMag.ONE
            var hi = m
            while (lo < hi) {
                val mid = (lo + hi + UMag.ONE).halve()
                val sq = mid * mid
                if (sq.compareTo(m) <= 0) lo = mid else hi = mid - UMag.ONE
            }
            return lo
        }
    }
}

/**
 * Unsigned magnitude — base-10^9 little-endian digit array. Supports the
 * arithmetic primitives BcNumber needs.
 */
internal class UMag private constructor(
    private val limbs: IntArray,
) {
    init {
        // canonical form: no leading zero limb (except for the single zero limb)
        check(limbs.isNotEmpty())
        check(limbs.size == 1 || limbs.last() != 0)
        for (l in limbs) check(l in 0 until BASE)
    }

    fun isZero(): Boolean = limbs.size == 1 && limbs[0] == 0

    fun fitsInInt(): Boolean {
        if (limbs.size == 1) return true // single limb < BASE = 10^9 < Int.MAX_VALUE
        if (limbs.size == 2) {
            // value = limbs[1] * BASE + limbs[0]; check it fits Int.MAX_VALUE.
            val v = limbs[1].toLong() * BASE + limbs[0]
            return v <= Int.MAX_VALUE.toLong()
        }
        return false
    }

    fun toInt(): Int {
        if (limbs.size == 1) return limbs[0]
        if (limbs.size == 2) {
            val v = limbs[1].toLong() * BASE + limbs[0]
            if (v > Int.MAX_VALUE) throw BcRuntimeError("number too large for Int")
            return v.toInt()
        }
        throw BcRuntimeError("number too large for Int")
    }

    operator fun plus(other: UMag): UMag {
        val n = maxOf(limbs.size, other.limbs.size)
        val out = IntArray(n + 1)
        var carry = 0
        for (i in 0 until n) {
            val a = if (i < limbs.size) limbs[i] else 0
            val b = if (i < other.limbs.size) other.limbs[i] else 0
            val s = a + b + carry
            out[i] = s % BASE
            carry = s / BASE
        }
        out[n] = carry
        return of(out)
    }

    operator fun minus(other: UMag): UMag {
        require(this.compareTo(other) >= 0) { "UMag subtraction would go negative" }
        val out = IntArray(limbs.size)
        var borrow = 0
        for (i in out.indices) {
            val a = limbs[i]
            val b = if (i < other.limbs.size) other.limbs[i] else 0
            var d = a - b - borrow
            if (d < 0) {
                d += BASE
                borrow = 1
            } else {
                borrow = 0
            }
            out[i] = d
        }
        return of(out)
    }

    operator fun times(other: UMag): UMag {
        if (isZero() || other.isZero()) return ZERO
        val out = IntArray(limbs.size + other.limbs.size)
        for (i in limbs.indices) {
            var carry = 0L
            val a = limbs[i].toLong()
            for (j in other.limbs.indices) {
                val cur = out[i + j].toLong() + a * other.limbs[j] + carry
                out[i + j] = (cur % BASE).toInt()
                carry = cur / BASE
            }
            var k = i + other.limbs.size
            while (carry > 0) {
                val cur = out[k].toLong() + carry
                out[k] = (cur % BASE).toInt()
                carry = cur / BASE
                k++
            }
        }
        return of(out)
    }

    fun divmod(divisor: UMag): Pair<UMag, UMag> {
        require(!divisor.isZero()) { "divide by zero" }
        if (this.compareTo(divisor) < 0) return ZERO to this
        if (divisor.limbs.size == 1) {
            // fast path: short divide
            val d = divisor.limbs[0].toLong()
            val out = IntArray(limbs.size)
            var rem = 0L
            for (i in limbs.indices.reversed()) {
                val cur = rem * BASE + limbs[i]
                out[i] = (cur / d).toInt()
                rem = cur % d
            }
            return of(out) to fromLong(rem)
        }
        // long division: schoolbook (base-10^9)
        var rem = ZERO
        val qDigits = IntArray(limbs.size)
        for (i in limbs.indices.reversed()) {
            rem = rem.shiftLimbsUp(1) + fromInt(limbs[i])
            // binary-search the digit
            var lo = 0
            var hi = BASE - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                val prod = divisor * fromInt(mid)
                if (prod.compareTo(rem) <= 0) lo = mid else hi = mid - 1
            }
            qDigits[i] = lo
            rem -= divisor * fromInt(lo)
        }
        return of(qDigits) to rem
    }

    private fun shiftLimbsUp(n: Int): UMag {
        if (isZero()) return this
        val out = IntArray(limbs.size + n)
        for (i in limbs.indices) out[i + n] = limbs[i]
        return of(out)
    }

    fun mulPow10(n: Int): UMag {
        if (n == 0 || isZero()) return this
        // multiply by 10^n. Could optimize by shifting whole limbs, but keep simple.
        return this * pow10(n)
    }

    fun divPow10(n: Int): UMag {
        if (n == 0 || isZero()) return this
        val (q, _) = this.divmod(pow10(n))
        return q
    }

    fun halve(): UMag {
        if (isZero()) return this
        val (q, _) = this.divmod(fromInt(2))
        return q
    }

    fun pow(n: Int): UMag {
        if (n == 0) return ONE
        var result = ONE
        var base = this
        var k = n
        while (k > 0) {
            if (k and 1 == 1) result *= base
            k = k ushr 1
            if (k > 0) base *= base
        }
        return result
    }

    operator fun compareTo(other: UMag): Int {
        if (limbs.size != other.limbs.size) return limbs.size.compareTo(other.limbs.size)
        for (i in limbs.indices.reversed()) {
            val c = limbs[i].compareTo(other.limbs[i])
            if (c != 0) return c
        }
        return 0
    }

    fun toBase10String(): String {
        if (isZero()) return "0"
        val sb = StringBuilder()
        sb.append(limbs.last())
        for (i in limbs.size - 2 downTo 0) {
            val s = limbs[i].toString()
            sb.append("0".repeat(LIMB_DIGITS - s.length))
            sb.append(s)
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = other is UMag && this.compareTo(other) == 0

    override fun hashCode(): Int = limbs.contentHashCode()

    companion object {
        const val BASE: Int = 1_000_000_000
        const val LIMB_DIGITS: Int = 9
        val ZERO: UMag = UMag(intArrayOf(0))
        val ONE: UMag = UMag(intArrayOf(1))

        fun of(raw: IntArray): UMag {
            var end = raw.size
            while (end > 1 && raw[end - 1] == 0) end--
            return UMag(raw.copyOfRange(0, end))
        }

        fun fromInt(v: Int): UMag {
            require(v >= 0)
            if (v == 0) return ZERO
            if (v == 1) return ONE
            return if (v < BASE) UMag(intArrayOf(v)) else fromLong(v.toLong())
        }

        fun fromLong(v: Long): UMag {
            require(v >= 0)
            if (v == 0L) return ZERO
            val lo = (v % BASE).toInt()
            val hi = (v / BASE).toInt()
            return if (hi == 0) UMag(intArrayOf(lo)) else UMag(intArrayOf(lo, hi))
        }

        fun pow10(n: Int): UMag {
            require(n >= 0)
            if (n == 0) return ONE
            // Build by repeated multiplication by 10. Could limb-shift but keep simple.
            var v = ONE
            val ten = fromInt(10)
            repeat(n) { v *= ten }
            return v
        }
    }
}

/**
 * Number of significant decimal digits (POSIX `length(x)`).
 *
 * For a non-zero number this is the number of digits in the unscaled magnitude
 * (when scale==0) or the number of digits in the integer part (when there is
 * one) plus the scale, OR just scale when the value is purely fractional. POSIX
 * specifies: total number of significant decimal digits.
 */
public fun BcNumber.lengthDigits(): Int {
    if (isZero()) return 1
    val digits = unscaled.toBase10String().length
    return digits
}

public class BcRuntimeError(
    message: String,
) : RuntimeException(message)

public class BcParseError(
    message: String,
    public val line: Int = -1,
) : RuntimeException(message)
