package com.accucodeai.kash.tools.fzf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FzfStateTest {
    private fun initial(
        cands: List<String> = listOf("alpha", "beta", "gamma", "delta"),
        q: String = "",
    ): FzfState = FzfState.initial(cands, q, caseSensitive = null)

    @Test fun initialAllFilteredOnEmptyQuery() {
        val s = initial()
        assertEquals(4, s.filteredCount)
        assertEquals(0, s.cursor)
    }

    @Test fun withQueryRefiltersAndResetsCursor() {
        val s = initial().moveCursor(+2).withQuery("a")
        assertEquals(0, s.cursor)
        assertTrue(s.filtered.all { "a" in it.text })
    }

    @Test fun appendCodepointAdvancesQuery() {
        val s = initial().appendCodepoint('a'.code)
        assertEquals("a", s.query)
    }

    @Test fun deleteLastCodepointPopsChar() {
        val s = initial().appendCodepoint('a'.code).appendCodepoint('b'.code).deleteLastCodepoint()
        assertEquals("a", s.query)
    }

    @Test fun deleteLastCodepointOnEmptyIsNoop() {
        val s = initial().deleteLastCodepoint()
        assertEquals("", s.query)
    }

    @Test fun clearQueryRestoresAllCandidates() {
        val s = initial().appendCodepoint('a'.code).clearQuery()
        assertEquals("", s.query)
        assertEquals(4, s.filteredCount)
    }

    @Test fun deleteLastWordRemovesWordAndPrecedingSpace() {
        val s = initial().withQuery("foo bar").deleteLastWord()
        assertEquals("foo", s.query)
    }

    @Test fun deleteLastWordOnSingleWordClears() {
        val s = initial().withQuery("foo").deleteLastWord()
        assertEquals("", s.query)
    }

    @Test fun moveCursorDown() {
        val s = initial().moveCursor(+1)
        assertEquals(1, s.cursor)
    }

    @Test fun moveCursorUpClampsAtZero() {
        val s = initial().moveCursor(-5)
        assertEquals(0, s.cursor)
    }

    @Test fun moveCursorDownClampsAtMax() {
        val s = initial().moveCursor(+100)
        assertEquals(3, s.cursor)
    }

    @Test fun moveCursorOnEmptyFilteredStaysZero() {
        val s = initial().withQuery("ZZZ")
        assertEquals(0, s.filteredCount)
        val s2 = s.moveCursor(+5)
        assertEquals(0, s2.cursor)
    }

    @Test fun toggleSelectionAddsIndex() {
        val s = initial().toggleSelectionAtCursor()
        assertEquals(setOf(0), s.selected)
    }

    @Test fun toggleSelectionRemovesIfPresent() {
        val s = initial().toggleSelectionAtCursor().toggleSelectionAtCursor()
        assertTrue(s.selected.isEmpty())
    }

    @Test fun acceptMultiReturnsSelectedInInputOrder() {
        val s =
            initial()
                .moveCursor(+2)
                .toggleSelectionAtCursor() // gamma
                .moveCursor(-2)
                .toggleSelectionAtCursor() // alpha
        val out = s.accept(multi = true)
        assertEquals(listOf("alpha", "gamma"), out)
    }

    @Test fun acceptSingleReturnsHighlighted() {
        val s = initial().moveCursor(+1)
        assertEquals(listOf("beta"), s.accept(multi = false))
    }

    @Test fun acceptOnEmptyFilteredReturnsEmpty() {
        val s = initial().withQuery("ZZZZ")
        assertTrue(s.accept(multi = false).isEmpty())
    }

    @Test fun acceptMultiNoSelectionsFallsBackToCursor() {
        val s = initial().moveCursor(+1)
        assertEquals(listOf("beta"), s.accept(multi = true))
    }

    @Test fun cursorCandidateIndexTracksFilteredOrdering() {
        val s = initial(listOf("zzz", "aaa", "bbb")).withQuery("a")
        // "aaa" is the only match, cursor=0 → original index 1
        assertEquals(1, s.cursorCandidateIndex)
    }

    @Test fun smartCaseRespected() {
        val s = FzfState.initial(listOf("FooBar", "foobar"), "Foo", caseSensitive = null)
        // 'F' uppercase → case-sensitive; only "FooBar" matches
        assertEquals(1, s.filteredCount)
    }

    @Test fun forcedInsensitiveMatchesBoth() {
        val s = FzfState.initial(listOf("FooBar", "foobar"), "Foo", caseSensitive = false)
        assertEquals(2, s.filteredCount)
    }

    @Test fun selectedSurvivesQueryChange() {
        val s = initial().toggleSelectionAtCursor().withQuery("xxxxx")
        assertEquals(setOf(0), s.selected)
        assertEquals(0, s.filteredCount)
    }

    @Test fun selectedSurvivesQueryRestore() {
        val s = initial().toggleSelectionAtCursor().withQuery("xxxxx").withQuery("")
        // Accept under multi should now still output the originally-toggled item.
        assertEquals(listOf("alpha"), s.accept(multi = true))
    }

    @Test fun multipleAppendsRefilterIncrementally() {
        val s = initial().appendCodepoint('a'.code)
        assertTrue(s.filteredCount in 1..4)
    }

    @Test fun queryDirty() {
        val s = initial().appendCodepoint('b'.code)
        assertEquals("b", s.query)
        assertFalse(s.filtered.isEmpty())
    }
}
