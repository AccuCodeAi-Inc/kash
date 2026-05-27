package com.accucodeai.kash.tools.csplit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatternTest {
    @Test fun `single regex`() {
        val r = parsePatterns(listOf("/foo/"))
        assertEquals(listOf(Pattern.Regex("foo", offset = 0, skip = false)), r)
    }

    @Test fun `skip regex`() {
        val r = parsePatterns(listOf("%bar%"))
        assertEquals(listOf(Pattern.Regex("bar", offset = 0, skip = true)), r)
    }

    @Test fun `regex with positive offset`() {
        val r = parsePatterns(listOf("/foo/+3"))
        assertEquals(Pattern.Regex("foo", offset = 3), r[0])
    }

    @Test fun `regex with negative offset`() {
        val r = parsePatterns(listOf("/foo/-2"))
        assertEquals(Pattern.Regex("foo", offset = -2), r[0])
    }

    @Test fun `line number`() {
        val r = parsePatterns(listOf("10"))
        assertEquals(Pattern.LineNum(10), r[0])
    }

    @Test fun `repetition braces`() {
        val r = parsePatterns(listOf("/x/", "{3}"))
        assertEquals(1, r.size)
        assertEquals(3, r[0].repeat)
    }

    @Test fun `repetition star is infinite`() {
        val r = parsePatterns(listOf("/x/", "{*}"))
        assertEquals(1, r.size)
        assertEquals(REPEAT_INFINITE, r[0].repeat)
    }

    @Test fun `multiple patterns`() {
        val r = parsePatterns(listOf("/A/", "10", "%B%", "/C/+1", "{2}"))
        assertEquals(4, r.size)
        assertTrue(r[3].repeat == 2)
    }

    @Test fun `bad pattern errors`() {
        assertFailsWith<IllegalArgumentException> { parsePatterns(listOf("hello")) }
    }

    @Test fun `unterminated regex errors`() {
        assertFailsWith<IllegalArgumentException> { parsePatterns(listOf("/foo")) }
    }

    @Test fun `brace before any pattern errors`() {
        assertFailsWith<IllegalArgumentException> { parsePatterns(listOf("{3}")) }
    }

    @Test fun `line number zero errors`() {
        assertFailsWith<IllegalArgumentException> { parsePatterns(listOf("0")) }
    }

    @Test fun `escape inside regex preserved`() {
        val r = parsePatterns(listOf("/a\\/b/"))
        assertEquals("a\\/b", (r[0] as Pattern.Regex).pattern)
    }
}
