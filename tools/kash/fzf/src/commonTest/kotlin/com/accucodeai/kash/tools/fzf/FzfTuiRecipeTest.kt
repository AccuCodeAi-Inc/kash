package com.accucodeai.kash.tools.fzf

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.test.FakeTerminalControl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FzfTuiRecipeTest {
    private fun opts(
        multi: Boolean = false,
        q: String = "",
        prompt: String = "> ",
        caseSensitive: Boolean? = null,
    ) = FzfOptions(multi = multi, initialQuery = q, prompt = prompt, caseSensitive = caseSensitive)

    @Test fun emptyCandidatesReturnsEmptyOutcome() =
        runTest {
            val term = FakeTerminalControl()
            val tui = FzfTui(term, emptyList(), opts())
            val r = tui.run()
            assertIs<FzfOutcome.Empty>(r)
        }

    @Test fun typeQueryThenEnterPicksMatch() =
        runTest {
            val term = FakeTerminalControl()
            val cands = listOf("apple", "banana", "cherry", "apricot")
            // type "ban" → "banana" filters in, Enter
            term.pushChars("ban")
            term.pushKey(Key.Named.ENTER)
            val r = FzfTui(term, cands, opts()).run()
            assertIs<FzfOutcome.Accepted>(r)
            assertEquals(listOf("banana"), r.lines)
        }

    @Test fun escapeCancels() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.ESC)
            val r = FzfTui(term, listOf("a", "b"), opts()).run()
            assertIs<FzfOutcome.Cancelled>(r)
        }

    @Test fun ctrlCcancels() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Ctrl('C'))
            val r = FzfTui(term, listOf("a"), opts()).run()
            assertIs<FzfOutcome.Cancelled>(r)
        }

    @Test fun ctrlGcancels() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Ctrl('G'))
            val r = FzfTui(term, listOf("a"), opts()).run()
            assertIs<FzfOutcome.Cancelled>(r)
        }

    @Test fun downThenEnterPicksSecond() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.DOWN)
            term.pushKey(Key.Named.ENTER)
            val r = FzfTui(term, listOf("one", "two", "three"), opts()).run()
            assertIs<FzfOutcome.Accepted>(r)
            assertEquals(listOf("two"), r.lines)
        }

    @Test fun ctrlNctrlPmoveCursor() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Ctrl('N'))
            term.pushKey(Key.Ctrl('N'))
            term.pushKey(Key.Ctrl('P'))
            term.pushKey(Key.Named.ENTER)
            val r = FzfTui(term, listOf("a", "b", "c"), opts()).run()
            assertIs<FzfOutcome.Accepted>(r)
            assertEquals(listOf("b"), r.lines)
        }

    @Test fun backspacePopsQuery() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("abz")
            term.pushKey(Key.Named.BACKSPACE)
            // After "ab" + bs: query is "ab"
            // Wait — pushChars sent a,b,z. bs pops z → query=="ab".
            term.pushKey(Key.Named.ENTER)
            val cands = listOf("ab", "ax")
            val r = FzfTui(term, cands, opts()).run()
            assertIs<FzfOutcome.Accepted>(r)
            assertEquals(listOf("ab"), r.lines)
        }

    @Test fun ctrlUClearsQuery() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("zzz")
            term.pushKey(Key.Ctrl('U'))
            term.pushKey(Key.Named.ENTER)
            // After clear, all candidates visible, cursor=0 → "a"
            val r = FzfTui(term, listOf("a", "b"), opts()).run()
            assertIs<FzfOutcome.Accepted>(r)
            assertEquals(listOf("a"), r.lines)
        }

    @Test fun ctrlWdeletesLastWord() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("foo bar")
            term.pushKey(Key.Ctrl('W'))
            // query → "foo"
            term.pushKey(Key.Named.ENTER)
            val r = FzfTui(term, listOf("foozz", "barzz"), opts()).run()
            assertIs<FzfOutcome.Accepted>(r)
            assertEquals(listOf("foozz"), r.lines)
        }

    @Test fun tabTogglesSelectionInMultiMode() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.TAB) // select "a", cursor → "b"
            term.pushKey(Key.Named.TAB) // select "b", cursor → "c"
            term.pushKey(Key.Named.ENTER)
            val r = FzfTui(term, listOf("a", "b", "c"), opts(multi = true)).run()
            assertIs<FzfOutcome.Accepted>(r)
            assertEquals(listOf("a", "b"), r.lines)
        }

    @Test fun tabIgnoredInSingleMode() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.TAB)
            term.pushKey(Key.Named.ENTER)
            val r = FzfTui(term, listOf("a", "b"), opts(multi = false)).run()
            assertIs<FzfOutcome.Accepted>(r)
            assertEquals(listOf("a"), r.lines)
        }

    @Test fun pageDownMovesByListHeight() =
        runTest {
            val term = FakeTerminalControl(rows = 6) // 4 list rows
            // 10 candidates; PgDn moves cursor by ~4
            term.pushKey(Key.Named.PGDN)
            term.pushKey(Key.Named.ENTER)
            val cands = (1..10).map { "item$it" }
            val r = FzfTui(term, cands, opts()).run()
            assertIs<FzfOutcome.Accepted>(r)
            // We just verify cursor moved well past 0.
            val picked = r.lines.first()
            assertTrue(picked != "item1", "expected page down to move past first; got $picked")
        }

    @Test fun rawModeEnteredAndExited() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.ESC)
            FzfTui(term, listOf("a"), opts()).run()
            assertEquals(1, term.rawModeEntered)
            assertEquals(false, term.altScreenOn) // exited
        }

    @Test fun rendererWritesPromptAndStatus() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.ESC)
            FzfTui(term, listOf("apple"), opts(prompt = "PICK> ")).run()
            assertTrue("PICK>" in term.output.toString())
            assertTrue("1/1" in term.output.toString())
        }

    @Test fun initialQueryPrefiltersCandidates() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.ENTER)
            val r = FzfTui(term, listOf("apple", "banana"), opts(q = "ban")).run()
            assertIs<FzfOutcome.Accepted>(r)
            assertEquals(listOf("banana"), r.lines)
        }

    @Test fun enterOnNoMatchesYieldsEmptyAccept() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("zzzz")
            term.pushKey(Key.Named.ENTER)
            val r = FzfTui(term, listOf("a", "b"), opts()).run()
            assertIs<FzfOutcome.Accepted>(r)
            assertEquals(emptyList(), r.lines)
        }
}
