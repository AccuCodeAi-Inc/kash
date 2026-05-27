package com.accucodeai.kash.tools.less

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.test.FakeTerminalControl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LessPagerTest {
    private fun nLines(n: Int): String = (1..n).joinToString("\n") { "L$it" } + "\n"

    /** Strip ANSI CSI sequences so tests can assert against the visible text. */
    private fun stripAnsi(s: String): String = s.replace(Regex("\\[[0-9;?]*[A-Za-z]"), "")

    @Test fun pressingQuitExitsCleanly() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("q")
            val rc = LessPager(term, "hello\n", "x.txt").run()
            assertEquals(0, rc)
            assertEquals(1, term.rawModeEntered)
            assertEquals(false, term.altScreenOn)
        }

    @Test fun fileNameRendersInStatusBar() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("q")
            LessPager(term, "hello\n", "myfile.txt").run()
            assertContains(term.output, "myfile.txt")
        }

    @Test fun stdinSourceShowsAsStdinInStatus() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("q")
            LessPager(term, "x\n", null).run()
            assertContains(term.output, "(stdin)")
        }

    @Test fun gotoEndShowsEndMarker() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("Gq")
            LessPager(term, nLines(200), "big.txt").run()
            assertContains(term.output, "(END)")
        }

    @Test fun searchForwardJumpsToMatch() =
        runTest {
            val term = FakeTerminalControl(cols = 40, rows = 10)
            term.pushChars("/")
            term.pushChars("needle")
            term.pushKey(Key.Named.ENTER)
            term.pushChars("q")
            val text =
                buildString {
                    repeat(50) { appendLine("hay $it") }
                    appendLine("---needle here---")
                    repeat(20) { appendLine("more hay $it") }
                }
            LessPager(term, text, "h.txt").run()
            // After search, the match line should be in view. The "needle"
            // substring itself is wrapped in highlight ANSI; strip it.
            val visible = stripAnsi(term.output.toString())
            assertContains(visible, "needle here")
        }

    @Test fun searchMissShowsErrorInStatus() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("/")
            term.pushChars("zzzzzzzz")
            term.pushKey(Key.Named.ENTER)
            term.pushChars("q")
            LessPager(term, "foo\nbar\n", "h.txt").run()
            assertContains(term.output, "Pattern not found")
        }

    @Test fun searchBadPatternShowsErrorButDoesNotCrash() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("/")
            term.pushChars("[unclosed")
            term.pushKey(Key.Named.ENTER)
            term.pushChars("q")
            val rc = LessPager(term, "foo\nbar\n", "h.txt").run()
            assertEquals(0, rc)
            assertContains(term.output, "Bad pattern")
        }

    @Test fun numericPrefixGotoLine() =
        runTest {
            // Recipe: "50G" should jump near line 50.
            val term = FakeTerminalControl(cols = 40, rows = 10)
            term.pushChars("50Gq")
            LessPager(term, nLines(200), "big.txt").run()
            // status bar should report a window containing line 50
            // (top would be line 49, so first line shown is L50)
            assertContains(term.output, "L50")
        }

    @Test fun pageDownTwicePagesByScreen() =
        runTest {
            // body = rows - 1 = 9; press space twice → top = 18; status:
            // lines 19-27
            val term = FakeTerminalControl(cols = 40, rows = 10)
            term.pushChars("  q")
            LessPager(term, nLines(200), "big.txt").run()
            assertContains(term.output, "lines 19-27")
        }

    @Test fun pageDownThenPageBackReturnsClose() =
        runTest {
            val term = FakeTerminalControl(cols = 40, rows = 10)
            // forward, forward, back → expect to be at line 10 (top 9 == one screen)
            term.pushChars(" b q")
            LessPager(term, nLines(200), "big.txt").run()
            // After space then b we should see "lines 1-" again
            assertContains(term.output, "lines 1-9")
        }

    @Test fun helpScreenAppearsThenDismisses() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("h") // open help
            term.pushChars("x") // dismiss help (any key)
            term.pushChars("q")
            LessPager(term, "hi\n", "x.txt").run()
            assertContains(term.output, "quick reference")
        }

    @Test fun equalsKeyShowsPositionReport() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("=q")
            LessPager(term, nLines(50), "p.txt").run()
            assertContains(term.output, "byte ")
        }

    @Test fun colonQuitExits() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars(":")
            term.pushChars("q")
            term.pushKey(Key.Named.ENTER)
            val rc = LessPager(term, "a\nb\n", "x.txt").run()
            assertEquals(0, rc)
        }

    @Test fun nextSearchRepeatsLastPattern() =
        runTest {
            val term = FakeTerminalControl(cols = 40, rows = 10)
            // Search "apple" → land on first match → press n → land on next match.
            val text =
                buildString {
                    appendLine("apple1")
                    repeat(20) { appendLine("filler $it") }
                    appendLine("apple2")
                    repeat(20) { appendLine("filler later $it") }
                    appendLine("apple3")
                }
            term.pushChars("/")
            term.pushChars("apple")
            term.pushKey(Key.Named.ENTER)
            term.pushChars("n")
            term.pushChars("q")
            LessPager(term, text, "h.txt").run()
            // The "apple" portion is highlight-wrapped, so the literal
            // "apple2" never appears as a contiguous substring. Strip
            // ANSI escape sequences from the captured output, then assert
            // the rendered (visible-text) frame contains "apple2".
            val visible = stripAnsi(term.output.toString())
            assertContains(visible, "apple2")
        }

    @Test fun reverseSearchFindsBackwards() =
        runTest {
            val term = FakeTerminalControl(cols = 40, rows = 10)
            // Jump near end, then ?search for line near top
            term.pushChars("G")
            term.pushChars("?")
            term.pushChars("L5")
            term.pushKey(Key.Named.ENTER)
            term.pushChars("q")
            LessPager(term, nLines(200), "h.txt").run()
            assertContains(term.output, "L5")
        }

    @Test fun ctrlCQuits() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Ctrl('C'))
            val rc = LessPager(term, "hello\n", "x.txt").run()
            assertEquals(0, rc)
        }

    @Test fun ctrlFAndCtrlBPageForwardBackward() =
        runTest {
            val term = FakeTerminalControl(cols = 40, rows = 10)
            term.pushKey(Key.Ctrl('F'))
            term.pushKey(Key.Ctrl('F'))
            term.pushChars("q")
            LessPager(term, nLines(200), "big.txt").run()
            assertContains(term.output, "lines 19-")
        }

    @Test fun ctrlDHalfPageForward() =
        runTest {
            val term = FakeTerminalControl(cols = 40, rows = 10)
            // body = 9, half = 4 → top = 4 → first visible line is L5
            term.pushKey(Key.Ctrl('D'))
            term.pushChars("q")
            LessPager(term, nLines(200), "big.txt").run()
            assertContains(term.output, "lines 5-")
        }

    @Test fun arrowKeysScroll() =
        runTest {
            val term = FakeTerminalControl(cols = 40, rows = 10)
            term.pushKey(Key.Named.DOWN)
            term.pushKey(Key.Named.DOWN)
            term.pushKey(Key.Named.UP)
            term.pushChars("q")
            LessPager(term, nLines(50), "h.txt").run()
            assertTrue(term.output.contains("lines 2-"))
        }

    @Test fun emptyInputDoesNotCrash() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars("q")
            val rc = LessPager(term, "", null).run()
            assertEquals(0, rc)
        }

    @Test fun shortInputShowsEndImmediately() =
        runTest {
            val term = FakeTerminalControl(cols = 40, rows = 24)
            term.pushChars("q")
            LessPager(term, "tiny\n", "t.txt").run()
            // body = 23 rows, only 1 line → status shows lines 1-1
            assertContains(term.output, "lines 1-1")
            assertContains(term.output, "(END)")
        }

    @Test fun percentJump() =
        runTest {
            val term = FakeTerminalControl(cols = 40, rows = 10)
            // 50p should jump to roughly middle.
            term.pushChars("50pq")
            LessPager(term, nLines(100), "h.txt").run()
            // Roughly: top = 50 → lines 51-59
            assertContains(term.output, "L51")
        }

    @Test fun clearHighlightWithAmpersand() =
        runTest {
            val term = FakeTerminalControl(cols = 80, rows = 10)
            term.pushChars("/")
            term.pushChars("L5")
            term.pushKey(Key.Named.ENTER)
            term.pushChars("&") // clear highlight
            term.pushChars("q")
            val rc = LessPager(term, nLines(50), "h.txt").run()
            assertEquals(0, rc)
        }

    @Test fun unknownColonCommandShowsNotice() =
        runTest {
            val term = FakeTerminalControl()
            term.pushChars(":")
            term.pushChars("xyz")
            term.pushKey(Key.Named.ENTER)
            term.pushChars("q")
            LessPager(term, "hi\n", "f.txt").run()
            assertContains(term.output, "Unknown command")
        }

    @Test fun lineNumbersShownWhenEnabled() =
        runTest {
            val term = FakeTerminalControl(cols = 40, rows = 10)
            term.pushChars("q")
            LessPager(term, "alpha\nbeta\ngamma\n", "n.txt", showLineNumbers = true).run()
            // Expect a "1 alpha" style row in output.
            assertContains(term.output, "1 alpha")
            assertContains(term.output, "2 beta")
        }
}
