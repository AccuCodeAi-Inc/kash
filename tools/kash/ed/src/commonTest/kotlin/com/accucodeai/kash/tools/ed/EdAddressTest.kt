package com.accucodeai.kash.tools.ed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EdAddressTest {
    private fun parse(input: String): EdRange {
        val cur = EdLineCursor(input)
        return EdAddressParser.parseRange(cur)
    }

    private fun resolve(
        addr: EdAddress,
        size: Int,
        dot: Int = size,
    ): Int {
        val lines = (1..size).map { "line $it" }
        val buf = EdBuffer(lines = lines, dot = dot)
        return EdAddressResolver.resolve(addr, buf)
    }

    @Test fun parsesNumber() {
        val r = parse("42p")
        assertEquals(EdAddress.Primary.Number(42), r.first?.primary)
    }

    @Test fun parsesDot() {
        val r = parse(".p")
        assertEquals(EdAddress.Primary.Dot, r.first?.primary)
    }

    @Test fun parsesLast() {
        val r = parse("\$p")
        assertEquals(EdAddress.Primary.Last, r.first?.primary)
    }

    @Test fun parsesPercent() {
        val r = parse("%p")
        assertEquals(EdAddress.Primary.Number(1), r.first?.primary)
        assertEquals(EdAddress.Primary.Last, r.second?.primary)
    }

    @Test fun parsesComma() {
        val r = parse(",p")
        assertEquals(EdAddress.Primary.Number(1), r.first?.primary)
        assertEquals(EdAddress.Primary.Last, r.second?.primary)
    }

    @Test fun parsesSemicolon() {
        val r = parse(";p")
        assertEquals(EdAddress.Primary.Dot, r.first?.primary)
        assertEquals(EdAddress.Primary.Last, r.second?.primary)
        assertTrue(r.semicolon)
    }

    @Test fun parsesRange() {
        val r = parse("1,5p")
        assertEquals(EdAddress.Primary.Number(1), r.first?.primary)
        assertEquals(EdAddress.Primary.Number(5), r.second?.primary)
    }

    @Test fun parsesSemicolonRange() {
        val r = parse("1;5p")
        assertTrue(r.semicolon)
    }

    @Test fun parsesPositiveOffset() {
        val r = parse(".+3p")
        assertEquals(listOf(3), r.first?.offsets)
    }

    @Test fun parsesNegativeOffset() {
        val r = parse(".-2p")
        assertEquals(listOf(-2), r.first?.offsets)
    }

    @Test fun parsesBareOffset() {
        val r = parse("+p")
        // bare + with no digits => +1
        assertEquals(listOf(1), r.first?.offsets)
    }

    @Test fun parsesBareDoublePlus() {
        val r = parse("++p")
        assertEquals(listOf(1, 1), r.first?.offsets)
    }

    @Test fun parsesMark() {
        val r = parse("'ap")
        assertEquals(EdAddress.Primary.Mark('a'), r.first?.primary)
    }

    @Test fun parsesForwardSearch() {
        val r = parse("/foo/p")
        assertEquals(EdAddress.Primary.SearchForward("foo"), r.first?.primary)
    }

    @Test fun parsesBackwardSearch() {
        val r = parse("?foo?p")
        assertEquals(EdAddress.Primary.SearchBackward("foo"), r.first?.primary)
    }

    @Test fun parsesEmptySearch() {
        val r = parse("//p")
        assertEquals(EdAddress.Primary.SearchForward(null), r.first?.primary)
    }

    @Test fun parsesEscapedDelimInSearch() {
        val r = parse("/a\\/b/p")
        assertEquals(EdAddress.Primary.SearchForward("a/b"), r.first?.primary)
    }

    @Test fun parsesNoAddress() {
        val r = parse("p")
        assertEquals(null, r.first)
        assertEquals(null, r.second)
    }

    @Test fun parsesRangeWithOffsets() {
        val r = parse("1,.+3p")
        assertNotNull(r.first)
        assertNotNull(r.second)
        assertEquals(listOf(3), r.second.offsets)
    }

    @Test fun resolvesNumber() {
        assertEquals(3, resolve(EdAddress(EdAddress.Primary.Number(3)), 5))
    }

    @Test fun resolvesDot() {
        assertEquals(5, resolve(EdAddress.DOT, 10, dot = 5))
    }

    @Test fun resolvesLast() {
        assertEquals(10, resolve(EdAddress.LAST, 10))
    }

    @Test fun resolvesOffsetForward() {
        assertEquals(5, resolve(EdAddress(EdAddress.Primary.Dot, listOf(2)), 10, dot = 3))
    }

    @Test fun resolvesOffsetBackward() {
        assertEquals(2, resolve(EdAddress(EdAddress.Primary.Dot, listOf(-3)), 10, dot = 5))
    }

    @Test fun resolvesMultipleOffsets() {
        assertEquals(5, resolve(EdAddress(EdAddress.Primary.Number(3), listOf(1, 1)), 10))
    }

    @Test fun outOfRangeThrows() {
        assertFailsWith<EdError> { resolve(EdAddress(EdAddress.Primary.Number(99)), 5) }
    }

    @Test fun negativeOutOfRangeThrows() {
        assertFailsWith<EdError> { resolve(EdAddress(EdAddress.Primary.Dot, listOf(-99)), 5, dot = 1) }
    }

    @Test fun resolvesForwardSearch() {
        val lines = listOf("alpha", "beta", "gamma", "delta")
        val buf = EdBuffer(lines = lines, dot = 1)
        val n = EdAddressResolver.resolve(EdAddress(EdAddress.Primary.SearchForward("gam")), buf)
        assertEquals(3, n)
    }

    @Test fun resolvesBackwardSearch() {
        val lines = listOf("alpha", "beta", "gamma", "delta")
        val buf = EdBuffer(lines = lines, dot = 4)
        val n = EdAddressResolver.resolve(EdAddress(EdAddress.Primary.SearchBackward("bet")), buf)
        assertEquals(2, n)
    }

    @Test fun resolvesForwardSearchWrapsAround() {
        val lines = listOf("foo", "bar", "baz")
        val buf = EdBuffer(lines = lines, dot = 3)
        val n = EdAddressResolver.resolve(EdAddress(EdAddress.Primary.SearchForward("foo")), buf)
        assertEquals(1, n)
    }

    @Test fun resolvesMarkSet() {
        val buf = EdBuffer(lines = listOf("a", "b", "c"), dot = 1, marks = mapOf('x' to 2))
        val n = EdAddressResolver.resolve(EdAddress(EdAddress.Primary.Mark('x')), buf)
        assertEquals(2, n)
    }

    @Test fun resolvesMarkUnsetThrows() {
        val buf = EdBuffer(lines = listOf("a"), dot = 1)
        assertFailsWith<EdError> { EdAddressResolver.resolve(EdAddress(EdAddress.Primary.Mark('z')), buf) }
    }

    @Test fun resolvesEmptyPrimaryDefaultsToFrom() {
        // bare offsets used after first address — primary Empty + offsets
        val buf = EdBuffer(lines = listOf("a", "b", "c"), dot = 1)
        val n = EdAddressResolver.resolve(EdAddress(EdAddress.Primary.Empty, listOf(2)), buf, from = 1)
        assertEquals(3, n)
    }

    @Test fun parsesDoubleAddrSemicolon() {
        val r = parse("1;5p")
        assertEquals(EdAddress.Primary.Number(1), r.first?.primary)
        assertEquals(EdAddress.Primary.Number(5), r.second?.primary)
        assertTrue(r.semicolon)
    }

    @Test fun parsesAddressWithSpaces() {
        val r = parse(" 1 , 5 p")
        assertEquals(EdAddress.Primary.Number(1), r.first?.primary)
        assertEquals(EdAddress.Primary.Number(5), r.second?.primary)
    }

    @Test fun searchSetsLastSearchOnResolve() {
        // Just verifies search succeeds; lastSearch update happens in interpreter.
        val lines = listOf("foo", "bar")
        val buf = EdBuffer(lines = lines, dot = 1, lastSearch = "bar")
        val n = EdAddressResolver.resolve(EdAddress(EdAddress.Primary.SearchForward(null)), buf)
        assertEquals(2, n)
    }
}
