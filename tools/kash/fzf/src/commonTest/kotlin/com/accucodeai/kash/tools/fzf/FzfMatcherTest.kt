package com.accucodeai.kash.tools.fzf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FzfMatcherTest {
    // --- subsequence basics ---

    @Test fun emptyQueryMatchesAllWithZeroScore() {
        val m = FzfMatcher.match("", "anything")!!
        assertEquals(0, m.score)
        assertEquals(emptyList(), m.positions)
    }

    @Test fun simpleSubsequenceMatch() {
        val m = FzfMatcher.match("abc", "axbxc")
        assertNotNull(m)
        assertEquals(listOf(0, 2, 4), m.positions)
    }

    @Test fun nonSubsequenceReturnsNull() {
        assertNull(FzfMatcher.match("abc", "axc"))
    }

    @Test fun longerQueryThanCandidateFails() {
        assertNull(FzfMatcher.match("hellothere", "hi"))
    }

    @Test fun exactMatchProducesContiguousPositions() {
        val m = FzfMatcher.match("foo", "foo")!!
        assertEquals(listOf(0, 1, 2), m.positions)
    }

    @Test fun matchAtEnd() {
        val m = FzfMatcher.match("end", "/path/to/end")
        assertNotNull(m)
        assertEquals(listOf(9, 10, 11), m.positions)
    }

    @Test fun matchAtStart() {
        val m = FzfMatcher.match("foo", "foobar")!!
        assertEquals(listOf(0, 1, 2), m.positions)
    }

    // --- scoring ---

    @Test fun contiguousScoresHigherThanScattered() {
        val contig = FzfMatcher.match("abc", "abc")!!
        val scattered = FzfMatcher.match("abc", "axbxxc")!!
        assertTrue(contig.score > scattered.score, "contig ${contig.score} vs scattered ${scattered.score}")
    }

    @Test fun startOfStringBonusApplies() {
        val s = FzfMatcher.match("a", "axxx")!!
        val mid = FzfMatcher.match("a", "xxxa")!!
        assertTrue(s.score > mid.score)
    }

    @Test fun wordBoundaryBonusOnSlash() {
        val boundary = FzfMatcher.match("b", "a/b")!!
        val inside = FzfMatcher.match("b", "aab")!!
        assertTrue(boundary.score > inside.score, "boundary ${boundary.score} inside ${inside.score}")
    }

    @Test fun wordBoundaryBonusOnUnderscore() {
        val a = FzfMatcher.match("b", "a_b")!!
        val b = FzfMatcher.match("b", "aab")!!
        assertTrue(a.score > b.score)
    }

    @Test fun wordBoundaryBonusOnDash() {
        val a = FzfMatcher.match("b", "a-b")!!
        val b = FzfMatcher.match("b", "aab")!!
        assertTrue(a.score > b.score)
    }

    @Test fun wordBoundaryBonusOnDot() {
        val a = FzfMatcher.match("j", "main.java")!!
        val b = FzfMatcher.match("j", "majava")!!
        assertTrue(a.score > b.score)
    }

    @Test fun wordBoundaryBonusOnSpace() {
        val a = FzfMatcher.match("w", "hello world")!!
        val b = FzfMatcher.match("w", "helloworld")!!
        assertTrue(a.score > b.score)
    }

    @Test fun camelCaseBonus() {
        val camel = FzfMatcher.match("F", "myFile", caseSensitive = true)!!
        val flat = FzfMatcher.match("F", "myXXFile", caseSensitive = true)!!
        // both upper-F but camel has lower→upper transition right before
        assertTrue(camel.score > flat.score)
    }

    @Test fun gapPenaltyMakesShorterDistanceWin() {
        val close = FzfMatcher.match("ab", "ab")!!
        val far = FzfMatcher.match("ab", "axxxxxxxb")!!
        assertTrue(close.score > far.score)
    }

    @Test fun moreMatchedCharsScoresHigherPerChar() {
        val one = FzfMatcher.match("a", "a")!!
        val two = FzfMatcher.match("ab", "ab")!!
        assertTrue(two.score > one.score)
    }

    // --- smart-case ---

    @Test fun smartCaseLowercaseIsInsensitive() {
        val m = FzfMatcher.match("foo", "FOO")
        assertNotNull(m)
    }

    @Test fun smartCaseMixedCaseIsSensitive() {
        // "Foo" has uppercase → caseSensitive=true → "foo" candidate should NOT match
        assertNull(FzfMatcher.match("Foo", "foo"))
    }

    @Test fun smartCaseMixedCaseMatchesSameCase() {
        val m = FzfMatcher.match("Foo", "FooBar")
        assertNotNull(m)
    }

    @Test fun forcedCaseInsensitive() {
        val m = FzfMatcher.match("FOO", "foo", caseSensitive = false)
        assertNotNull(m)
    }

    @Test fun forcedCaseSensitiveReject() {
        assertNull(FzfMatcher.match("foo", "FOO", caseSensitive = true))
    }

    // --- ranking ---

    @Test fun rankSortsByScoreDescending() {
        val r = FzfMatcher.rank("ab", listOf("a_xxxx_b", "ab"))
        assertEquals("ab", r[0].text)
    }

    @Test fun rankEmptyQueryPreservesOrder() {
        val cands = listOf("c", "a", "b")
        val r = FzfMatcher.rank("", cands)
        assertEquals(cands, r.map { it.text })
    }

    @Test fun rankFiltersOutNonMatches() {
        val r = FzfMatcher.rank("abc", listOf("abc", "xyz", "axbxc"))
        assertEquals(2, r.size)
    }

    @Test fun rankTieBreakByShorterLength() {
        // Both score the same — shorter wins.
        val r = FzfMatcher.rank("a", listOf("aaa", "a"))
        assertEquals("a", r[0].text)
    }

    @Test fun rankTieBreakByOriginalIndexWhenSameLength() {
        val r = FzfMatcher.rank("x", listOf("x", "x"))
        assertEquals(0, r[0].index)
        assertEquals(1, r[1].index)
    }

    @Test fun rankStablePreservesIndices() {
        val cands = listOf("apple", "banana", "cherry")
        val r = FzfMatcher.rank("", cands)
        assertEquals(listOf(0, 1, 2), r.map { it.index })
    }

    // --- positions accuracy ---

    @Test fun positionsAlignToFirstMatchingOffset() {
        val m = FzfMatcher.match("ab", "xabyab")!!
        assertEquals(listOf(1, 2), m.positions)
    }

    @Test fun positionsForRepeatedQueryChar() {
        val m = FzfMatcher.match("aa", "aXaXa")!!
        assertEquals(listOf(0, 2), m.positions)
    }

    @Test fun positionsCountEqualsQueryLength() {
        val m = FzfMatcher.match("hello", "hxexlxlxoy")!!
        assertEquals(5, m.positions.size)
    }

    // --- edge cases ---

    @Test fun singleCharMatch() {
        val m = FzfMatcher.match("x", "x")!!
        assertTrue(m.score > 0)
    }

    @Test fun matchInPathString() {
        val m = FzfMatcher.match("rcm", "src/main/Command.kt")
        assertNotNull(m)
    }

    @Test fun rankPathLikeAllMatch() {
        val r = FzfMatcher.rank("mainkt", listOf("src/main.kt", "main_kt", "xmainkty"))
        assertEquals(3, r.size)
    }

    @Test fun emptyCandidateNonEmptyQueryFails() {
        assertNull(FzfMatcher.match("a", ""))
    }

    @Test fun emptyCandidateEmptyQueryMatches() {
        val m = FzfMatcher.match("", "")
        assertNotNull(m)
    }

    @Test fun queryLongerByOneCharFails() {
        assertNull(FzfMatcher.match("ab", "a"))
    }

    @Test fun matchUnicodeBmp() {
        val m = FzfMatcher.match("aé", "café")
        assertNotNull(m)
    }

    @Test fun rankAllNonMatchesYieldsEmpty() {
        val r = FzfMatcher.rank("zzz", listOf("abc", "def"))
        assertTrue(r.isEmpty())
    }

    @Test fun rankCandidateIndicesPreservedAfterFilter() {
        val r = FzfMatcher.rank("a", listOf("zz", "a", "qq", "aa"))
        // Surviving entries are at indices 1 and 3.
        val idx = r.map { it.index }.toSet()
        assertEquals(setOf(1, 3), idx)
    }

    @Test fun greedyMatchAdvancesPastConsumed() {
        // For "aa" against "aXa", first 'a' takes pos 0, second 'a' takes pos 2.
        val m = FzfMatcher.match("aa", "aXa")!!
        assertEquals(listOf(0, 2), m.positions)
    }

    @Test fun consecutiveMatchedCharsHaveNoGapPenalty() {
        val twoGap = FzfMatcher.match("ab", "axxb")!!
        val zeroGap = FzfMatcher.match("ab", "ab")!!
        assertTrue(zeroGap.score > twoGap.score)
    }

    @Test fun mixedScoringScenario() {
        // "find . -name '*.kt' | fzf -q FzfM" should rank FzfMatcher.kt first.
        val cands = listOf("FzfMatcher.kt", "src/Other.kt", "FzfState.kt")
        val r = FzfMatcher.rank("FzfM", cands)
        assertEquals("FzfMatcher.kt", r[0].text)
    }
}
