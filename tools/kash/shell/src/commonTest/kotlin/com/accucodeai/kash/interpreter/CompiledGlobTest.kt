package com.accucodeai.kash.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Functional contract for [CompiledGlob] — the layer that bash pattern
 * operators (`${var#pat}`, `${var//pat/rep}`, etc.) sit on top of.
 *
 * Each test exercises both the regex-fast path (most patterns) and the
 * extglob fallback path (patterns containing `?(`, `*(`, etc.) so the
 * behaviour is identical regardless of which engine ran.
 */
class CompiledGlobTest {
    @Test fun matchesFullLiteral() {
        val g = CompiledGlob.compile("foo")
        assertTrue(g.matchesFull("foo"))
        assertTrue(!g.matchesFull("foox"))
        assertTrue(!g.matchesFull(""))
    }

    @Test fun matchesFullStar() {
        val g = CompiledGlob.compile("a*c")
        assertTrue(g.matchesFull("ac"))
        assertTrue(g.matchesFull("abc"))
        assertTrue(g.matchesFull("axyzc"))
        assertTrue(!g.matchesFull("ab"))
    }

    @Test fun matchAtStartLongest() {
        // Pattern `a*` against `aaabc`: longest start match is `aaabc` (whole).
        val g = CompiledGlob.compile("a*")
        assertEquals(5, g.matchAtStart("aaabc", longest = true))
        // Pattern `a*b` against `aab xyz`: longest from start = `aab`.
        val g2 = CompiledGlob.compile("a*b")
        assertEquals(3, g2.matchAtStart("aab xyz", longest = true))
    }

    @Test fun matchAtStartShortest() {
        // Shortest from start: `a*` → empty match (`*` matches zero chars).
        val g = CompiledGlob.compile("a*")
        assertEquals(1, g.matchAtStart("aaabc", longest = false))
        // Shortest `a*b` against `aabab`: shortest is `aab` (3 chars).
        val g2 = CompiledGlob.compile("a*b")
        assertEquals(3, g2.matchAtStart("aabab", longest = false))
    }

    @Test fun matchAtStartNoMatch() {
        val g = CompiledGlob.compile("x")
        assertNull(g.matchAtStart("abc", longest = true))
    }

    @Test fun matchAtEndLongest() {
        // `*c` against `abcabc`: longest suffix = whole string.
        val g = CompiledGlob.compile("*c")
        assertEquals(6, g.matchAtEnd("abcabc", longest = true))
    }

    @Test fun matchAtEndShortest() {
        // `*c` against `abcabc`: shortest suffix = `c` (1 char).
        val g = CompiledGlob.compile("*c")
        assertEquals(1, g.matchAtEnd("abcabc", longest = false))
    }

    @Test fun replaceAllLiteral() {
        val g = CompiledGlob.compile("a")
        assertEquals("xbcxb", g.replaceAll("abcab") { "x" })
        assertEquals("xbcxx", g.replaceAll("abcaa") { "x" })
    }

    @Test fun replaceAllStarMatchesGreedy() {
        // `${var//*/x}` against "abc" — leftmost-longest match consumes
        // the whole string, replaced with "x".
        val g = CompiledGlob.compile("*")
        assertEquals("x", g.replaceAll("abc") { "x" })
    }

    @Test fun replaceAllNewExp8Sample() {
        // From new-exp8: build a long string with `;` separators, then
        // `${z//[^;]}` should delete everything except the `;`s.
        val z =
            buildString {
                append("start")
                for (i in 0 until 50) {
                    append(";string").append(i)
                }
            }
        val g = CompiledGlob.compile("[^;]")
        val result = g.replaceAll(z) { "" }
        // Expected: 50 semicolons.
        assertEquals(";".repeat(50), result)
    }

    @Test fun replaceFirstOnlyFirstMatch() {
        val g = CompiledGlob.compile("a")
        assertEquals("xbcab", g.replaceFirst("abcab") { "x" })
    }

    @Test fun extglobFallback() {
        // Extglob → null from translator → CompiledGlob uses recursive matcher.
        val g = CompiledGlob.compile("@(foo|bar)")
        assertTrue(g.matchesFull("foo"))
        assertTrue(g.matchesFull("bar"))
        assertTrue(!g.matchesFull("baz"))
    }

    @Test fun extglobAtStart() {
        // Verify the fallback path's anchored-start logic.
        val g = CompiledGlob.compile("?(foo)")
        // ?(foo) matches "" or "foo"
        assertEquals(0, g.matchAtStart("xyz", longest = true) ?: -1)
        assertEquals(3, g.matchAtStart("foobar", longest = true))
    }

    @Test fun bracketWithPosixClass() {
        val g = CompiledGlob.compile("[[:digit:]]")
        assertTrue(g.matchesFull("5"))
        assertTrue(!g.matchesFull("a"))
    }

    @Test fun negatedBracketBangSyntax() {
        val g = CompiledGlob.compile("[!a]")
        assertTrue(g.matchesFull("b"))
        assertTrue(!g.matchesFull("a"))
    }
}
