package com.accucodeai.kash.tools.bc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BcNumberTest {
    @Test fun parseInt() {
        assertEquals("123", BcNumber.parse("123").formatBase10())
    }

    @Test fun parseDecimal() {
        assertEquals("123.456", BcNumber.parse("123.456").formatBase10())
    }

    @Test fun parseLeadingDot() {
        assertEquals(".5", BcNumber.parse(".5").formatBase10())
    }

    @Test fun parseNegative() {
        assertEquals("-7.25", BcNumber.parse("-7.25").formatBase10())
    }

    @Test fun parseZero() {
        assertTrue(BcNumber.parse("0").isZero())
        assertTrue(BcNumber.parse("0.0000").isZero())
    }

    @Test fun addIntegers() {
        assertEquals("5", (BcNumber.parse("2") + BcNumber.parse("3")).formatBase10())
    }

    @Test fun addFractions() {
        // 0.1 + 0.2 in base-10 fixed-point is exact: 0.3
        // POSIX bc emits leading-dot fractions without a leading zero.
        assertEquals(".3", (BcNumber.parse("0.1") + BcNumber.parse("0.2")).formatBase10())
    }

    @Test fun subtract() {
        assertEquals("-3", (BcNumber.parse("5") - BcNumber.parse("8")).formatBase10())
    }

    @Test fun multiplyExact() {
        val r = BcNumber.parse("0.5") * BcNumber.parse("0.4")
        // exact product scale=2: 0.20
        assertEquals(".20", r.formatBase10())
    }

    @Test fun divisionScale10() {
        // 22 / 7 at scale 10 → 3.1428571428
        val r = BcNumber.parse("22").divBc(BcNumber.parse("7"), 10)
        assertEquals("3.1428571428", r.formatBase10())
    }

    @Test fun divisionScale0() {
        val r = BcNumber.parse("10").divBc(BcNumber.parse("3"), 0)
        assertEquals("3", r.formatBase10())
    }

    @Test fun divisionByZeroError() {
        assertFailsWith<BcRuntimeError> { BcNumber.parse("1").divBc(BcNumber.ZERO, 5) }
    }

    @Test fun moduloPositive() {
        val r = BcNumber.parse("10").modBc(BcNumber.parse("3"), 0)
        assertEquals("1", r.formatBase10())
    }

    @Test fun powerInteger() {
        val r = BcNumber.parse("2").powBc(BcNumber.parse("10"), 0)
        assertEquals("1024", r.formatBase10())
    }

    @Test fun powerNegativeExponent() {
        // 2 ^ -2 at scale 5 = 0.25 → "0.25" or padded to "0.25000"
        val r = BcNumber.parse("2").powBc(BcNumber.parse("-2"), 5)
        assertEquals(BcNumber.parse("0.25"), r)
    }

    @Test fun obase16Of255() {
        val r = BcNumber.parse("255").formatBase(16, 0)
        assertEquals("FF", r)
    }

    @Test fun obase2Of10() {
        assertEquals("1010", BcNumber.parse("10").formatBase(2, 0))
    }

    @Test fun ibase16OfFF() {
        assertEquals("255", BcNumber.parse("FF", ibase = 16).formatBase10())
    }

    @Test fun sqrt2AtScale10() {
        val r = BcNumber.sqrt(BcNumber.parse("2"), 10)
        // bc reference: 1.4142135623
        assertEquals("1.4142135623", r.formatBase10())
    }

    @Test fun sqrtOfZero() {
        assertTrue(BcNumber.sqrt(BcNumber.ZERO, 5).isZero())
    }

    @Test fun sqrtNegativeThrows() {
        assertFailsWith<BcRuntimeError> { BcNumber.sqrt(BcNumber.parse("-1"), 5) }
    }

    @Test fun comparisonChain() {
        assertTrue(BcNumber.parse("1.5").compareTo(BcNumber.parse("1.50")) == 0)
        assertTrue(BcNumber.parse("2") > BcNumber.parse("1.999"))
        assertTrue(BcNumber.parse("-3") < BcNumber.parse("-2"))
    }

    @Test fun lengthDigits() {
        assertEquals(3, BcNumber.parse("123").lengthDigits())
        assertEquals(6, BcNumber.parse("123.456").lengthDigits())
        assertEquals(1, BcNumber.ZERO.lengthDigits())
    }

    @Test fun negationIdempotent() {
        val x = BcNumber.parse("3.14")
        assertEquals(x, -(-x))
    }

    @Test fun bigMultiplication() {
        // 10^9 * 10^9 = 10^18 — exercises the limb-carry path
        val a = BcNumber.parse("1000000000")
        val r = a * a
        assertEquals("1000000000000000000", r.formatBase10())
    }
}
