package com.accucodeai.kash.api

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.LineEditorResult
import com.accucodeai.kash.test.FakeTerminalControl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BasicLineEditorTest {
    // Regression: a colored prompt's ANSI escapes must count as ZERO display
    // width in the editor's wrap/cursor simulation, matching what the grid
    // renders. When they were counted as columns, the editor believed the
    // line wrapped ~13 cols early (the escape byte count of a bold+green+reset
    // prompt), and its redraw scrolled the screen on every keystroke near the
    // right margin (visible in the agent tool, whose prompt is colored).
    @Test
    fun skipNonPrintingSkipsAnsiAndPromptMarkers() {
        val esc = Char(0x1B)
        val soh = Char(0x01)
        val stx = Char(0x02)
        val bel = Char(0x07)

        // CSI SGR runs (ESC[…m) are consumed whole.
        assertEquals(4, skipNonPrinting("$esc[1m", 0)) // ESC [ 1 m
        assertEquals(5, skipNonPrinting("$esc[32m", 0)) // ESC [ 3 2 m
        assertEquals(4, skipNonPrinting("$esc[0mX", 0)) // stops before the X

        // readline \[ \] markers: opening SOH skips through the closing STX.
        assertEquals(7, skipNonPrinting("$soh$esc[31m$stx", 0))

        // A stray closing marker consumes just itself.
        assertEquals(1, skipNonPrinting("${stx}X", 0))

        // OSC … BEL (e.g. a window-title set) is consumed up to and incl. BEL.
        val osc = "$esc]0;t$bel"
        assertEquals(osc.length, skipNonPrinting(osc, 0))

        // An unterminated CSI runs to end-of-text rather than leaking columns.
        assertEquals(2, skipNonPrinting("$esc[", 0))
    }

    private fun newEditor() = BasicLineEditor(FakeTerminalControl()) to FakeTerminalControl()

    private fun runEdit(
        keys: List<Any>, // String or Key
        isComplete: (String) -> Boolean = { true },
    ): Pair<LineEditorResult, FakeTerminalControl> {
        val term = FakeTerminalControl()
        for (k in keys) {
            when (k) {
                is String -> term.pushChars(k)
                is Key -> term.pushKey(k)
                else -> error("bad key: $k")
            }
        }
        val editor = BasicLineEditor(term)
        lateinit var result: LineEditorResult
        runTest { result = editor.readLine("$ ", "> ", isComplete) }
        return result to term
    }

    @Test fun typedTextThenEnterReturnsLine() {
        val (r, _) = runEdit(listOf("hello world", Key.Named.ENTER))
        assertEquals(LineEditorResult.Line("hello world"), r)
    }

    @Test fun backspaceDeletesPreviousChar() {
        // Type "helXX", backspace twice (→ "hel"), append "lo" → "hello".
        val (r, _) = runEdit(listOf("helXX", Key.Named.BACKSPACE, Key.Named.BACKSPACE, "lo", Key.Named.ENTER))
        assertEquals(LineEditorResult.Line("hello"), r)
    }

    @Test fun leftArrowAndInsertInMiddle() {
        // Type "abce", arrow back twice (cursor between 'b' and 'c'),
        // insert 'X' → "abXce".
        val (r, _) = runEdit(listOf("abce", Key.Named.LEFT, Key.Named.LEFT, "X", Key.Named.ENTER))
        assertEquals(LineEditorResult.Line("abXce"), r)
    }

    @Test fun homeAndEnd() {
        val (r, _) =
            runEdit(
                listOf(
                    "world",
                    Key.Named.HOME,
                    "hello ",
                    Key.Named.END,
                    "!",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("hello world!"), r)
    }

    @Test fun ctrlAAndCtrlE() {
        val (r, _) =
            runEdit(
                listOf(
                    "middle",
                    Key.Ctrl('A'),
                    "[",
                    Key.Ctrl('E'),
                    "]",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("[middle]"), r)
    }

    @Test fun ctrlKKillsToEol() {
        val (r, _) =
            runEdit(
                listOf(
                    "keep this drop this",
                    Key.Named.HOME,
                    // Move right past "keep this" (9 chars).
                    *Array(9) { Key.Named.RIGHT },
                    Key.Ctrl('K'),
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("keep this"), r)
    }

    @Test fun ctrlUKillsWholeLine() {
        val (r, _) =
            runEdit(
                listOf(
                    "garbage to discard",
                    Key.Ctrl('U'),
                    "fresh",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("fresh"), r)
    }

    @Test fun ctrlWKillsPrevWord() {
        val (r, _) =
            runEdit(
                listOf(
                    "foo bar baz",
                    Key.Ctrl('W'), // kills "baz" → "foo bar "
                    Key.Ctrl('W'), // kills "bar " → "foo "
                    "qux",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("foo qux"), r)
    }

    @Test fun ctrlDOnEmptyBufferReturnsEof() {
        val (r, _) = runEdit(listOf(Key.Ctrl('D')))
        assertEquals(LineEditorResult.Eof, r)
    }

    @Test fun ctrlDInMiddleForwardDeletes() {
        val (r, _) =
            runEdit(
                listOf(
                    "axb",
                    Key.Named.HOME,
                    Key.Named.RIGHT, // cursor between a and x
                    Key.Ctrl('D'), // forward-delete x
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("ab"), r)
    }

    @Test fun ctrlCReturnsInterrupted() {
        val (r, _) = runEdit(listOf("half typed", Key.Ctrl('C')))
        assertEquals(LineEditorResult.Interrupted, r)
    }

    @Test fun multilineContinuationViaIsComplete() {
        // Simulate: first line incomplete, second line completes.
        // isComplete returns true only when the buffer is "if true\nthen".
        val (r, _) =
            runEdit(
                listOf(
                    "if true",
                    Key.Named.ENTER,
                    "then",
                    Key.Named.ENTER,
                ),
                isComplete = { it == "if true\nthen" },
            )
        assertEquals(LineEditorResult.Line("if true\nthen"), r)
    }

    @Test fun backslashAtEndDoesNotAutoJoin() {
        // Replaces the old backslash-continuation test. The new
        // multi-line model has no special case for trailing `\` —
        // backslash-newline continuation rides through the same
        // validator path as any other "more input needed" cue. When
        // the validator says incomplete (buffer ends with `\`), Enter
        // inserts `\n`. When the validator says complete, submit. The
        // buffer keeps the literal backslash; the shell parser merges
        // it at script-eval time.
        val (r, _) =
            runEdit(
                listOf("echo hello \\", Key.Named.ENTER, "world", Key.Named.ENTER),
                isComplete = { !it.endsWith("\\") },
            )
        assertEquals(LineEditorResult.Line("echo hello \\\nworld"), r)
    }

    @Test fun historyUpArrowRecallsPrior() {
        val term = FakeTerminalControl()
        val editor = BasicLineEditor(term)
        runTest {
            editor.addHistory("ls -la")
            term.pushKey(Key.Named.UP)
            term.pushKey(Key.Named.ENTER)
            val r = editor.readLine("$ ", "> ", { true })
            assertEquals(LineEditorResult.Line("ls -la"), r)
        }
    }

    @Test fun historyDownArrowReturnsToStashedDraft() {
        val term = FakeTerminalControl()
        val editor = BasicLineEditor(term)
        runTest {
            editor.addHistory("from history")
            // Type "draft", press UP (stash "draft", load "from history"),
            // press DOWN (restore "draft"), press ENTER.
            term.pushChars("draft")
            term.pushKey(Key.Named.UP)
            term.pushKey(Key.Named.DOWN)
            term.pushKey(Key.Named.ENTER)
            val r = editor.readLine("$ ", "> ", { true })
            assertEquals(LineEditorResult.Line("draft"), r)
        }
    }

    @Test fun historyDedupesConsecutive() {
        val term = FakeTerminalControl()
        val editor = BasicLineEditor(term)
        runTest {
            editor.addHistory("ls")
            editor.addHistory("ls") // ignored
            editor.addHistory("pwd")
            term.pushKey(Key.Named.UP) // pwd
            term.pushKey(Key.Named.UP) // ls
            term.pushKey(Key.Named.ENTER)
            val r = editor.readLine("$ ", "> ", { true })
            assertEquals(LineEditorResult.Line("ls"), r)
        }
    }

    @Test fun rawModeIsEnteredAndExited() {
        val term = FakeTerminalControl()
        val editor = BasicLineEditor(term)
        runTest {
            term.pushChars("hi")
            term.pushKey(Key.Named.ENTER)
            editor.readLine("$ ", "> ", { true })
            assertEquals(1, term.rawModeEntered, "should enter raw mode exactly once per readLine")
        }
    }

    @Test fun outputContainsPromptAndTypedText() {
        val (_, term) = runEdit(listOf("hi", Key.Named.ENTER))
        val out = term.output.toString()
        assertContains(out, "$ ")
        assertContains(out, "hi")
    }

    @Test fun astralCodepointInserts() {
        // 😀 = U+1F600, requires surrogate pair handling.
        val term = FakeTerminalControl()
        val editor = BasicLineEditor(term)
        runTest {
            term.pushKey(Key.Char(0x1F600))
            term.pushKey(Key.Named.ENTER)
            val r = editor.readLine("$ ", "> ", { true })
            assertIs<LineEditorResult.Line>(r)
            assertEquals("😀", r.text)
        }
    }

    // ---- Multi-line editing -----------------------------------------

    @Test fun enterInsertsNewlineWhenIncomplete() {
        // Validator: only complete when text ends with ";;". First
        // Enter (buffer "foo") finds it incomplete → inserts \n.
        // Second Enter (buffer "foo\nbar;;") finds it complete →
        // submits.
        val (r, _) =
            runEdit(
                listOf("foo", Key.Named.ENTER, "bar;;", Key.Named.ENTER),
                isComplete = { it.endsWith(";;") },
            )
        assertEquals(LineEditorResult.Line("foo\nbar;;"), r)
    }

    @Test fun altEnterAlwaysInsertsNewline() {
        // Validator always says complete, but Alt-Enter is the
        // force-newline override and bypasses it. The final plain
        // Enter then submits.
        val (r, _) =
            runEdit(
                listOf("foo", Key.Named.ALT_ENTER, "bar", Key.Named.ENTER),
                isComplete = { true },
            )
        assertEquals(LineEditorResult.Line("foo\nbar"), r)
    }

    @Test fun leftCrossesNewline() {
        // Buffer "ab\ncd", cursor at end (idx 5). HOME → idx 3
        // (start of "cd"). LEFT → idx 2 (sits at the `\n`). Insert
        // 'X' at idx 2 → "abX\ncd".
        val (r, _) =
            runEdit(
                listOf(
                    "ab",
                    Key.Named.ALT_ENTER,
                    "cd",
                    Key.Named.HOME,
                    Key.Named.LEFT,
                    "X",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("abX\ncd"), r)
    }

    @Test fun rightCrossesNewline() {
        // From the end of logical line "ab" (idx 2), RIGHT steps
        // across the `\n` into the start of "cd" (idx 3). Insert
        // 'X' there → "ab\nXcd".
        val (r, _) =
            runEdit(
                listOf(
                    "ab",
                    Key.Named.ALT_ENTER,
                    "cd",
                    Key.Named.HOME, // idx 3 (start of "cd")
                    Key.Named.LEFT,
                    Key.Named.LEFT,
                    Key.Named.LEFT, // idx 0
                    Key.Named.END, // idx 2 (end of "ab")
                    Key.Named.RIGHT, // idx 3 (crosses the \n)
                    "X",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("ab\nXcd"), r)
    }

    @Test fun homeFindsLogicalLineStart() {
        // After "abc" + Alt-Enter + "def", cursor at idx 7. HOME →
        // start of current logical line ("def") = idx 4. Insert
        // 'X' → "abc\nXdef".
        val (r, _) =
            runEdit(
                listOf(
                    "abc",
                    Key.Named.ALT_ENTER,
                    "def",
                    Key.Named.HOME,
                    "X",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("abc\nXdef"), r)
    }

    @Test fun endFindsLogicalLineEnd() {
        // After "abc" + Alt-Enter + "def", navigate to start of
        // "abc" (idx 0), then END goes to end of the CURRENT logical
        // line, not the buffer — idx 3. Insert 'X' there → "abcX\ndef".
        val (r, _) =
            runEdit(
                listOf(
                    "abc",
                    Key.Named.ALT_ENTER,
                    "def",
                    Key.Named.HOME, // idx 4
                    Key.Named.LEFT,
                    Key.Named.LEFT,
                    Key.Named.LEFT,
                    Key.Named.LEFT, // idx 0
                    Key.Named.END, // lineEnd of "abc" = idx 3
                    "X",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("abcX\ndef"), r)
    }

    @Test fun upMovesCursorWithinBuffer() {
        // Buffer "ab\ncd", cursor at end (idx 5, col 2 of "cd").
        // UP moves up one logical line preserving column. Col 2 of
        // "ab" is exactly its end, so cursor lands at idx 2. Insert
        // 'X' → "abX\ncd".
        val (r, _) =
            runEdit(
                listOf(
                    "ab",
                    Key.Named.ALT_ENTER,
                    "cd",
                    Key.Named.UP,
                    "X",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("abX\ncd"), r)
    }

    @Test fun downMovesCursorWithinBuffer() {
        // Same buffer but start from the top row at col 0 and move
        // DOWN one line preserving column. Col 0 of "cd" = idx 3.
        // Insert 'X' → "ab\nXcd".
        val (r, _) =
            runEdit(
                listOf(
                    "ab",
                    Key.Named.ALT_ENTER,
                    "cd",
                    Key.Named.UP, // idx 2 (end of "ab")
                    Key.Named.HOME, // idx 0
                    Key.Named.DOWN, // col 0 of "cd" = idx 3
                    "X",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("ab\nXcd"), r)
    }

    @Test fun downColumnClampedToShorterLine() {
        // Buffer "abc\nxy". From end of "abc" (col 3), DOWN onto
        // "xy" (len 2): column is clamped to 2 (end of "xy"),
        // cursor lands at idx 6. Insert 'Z' → "abc\nxyZ".
        val (r, _) =
            runEdit(
                listOf(
                    "abc",
                    Key.Named.ALT_ENTER,
                    "xy",
                    Key.Named.UP, // col 2 of "abc" = idx 2
                    Key.Named.END, // idx 3 (end of "abc")
                    Key.Named.DOWN, // col 3 of "xy" clamped to col 2 = idx 6
                    "Z",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("abc\nxyZ"), r)
    }

    @Test fun backspaceJoinsLines() {
        // Cursor at idx 3 (start of "cd"). Backspace deletes the
        // preceding `\n` at idx 2 → buffer "abcd", cursor at idx 2.
        // Insert 'X' → "abXcd".
        val (r, _) =
            runEdit(
                listOf(
                    "ab",
                    Key.Named.ALT_ENTER,
                    "cd",
                    Key.Named.HOME, // idx 3
                    Key.Named.BACKSPACE, // delete \n; cursor → idx 2
                    "X",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("abXcd"), r)
    }

    @Test fun deleteJoinsLines() {
        // Cursor at idx 2 (sitting on the `\n`). Delete removes the
        // `\n` → buffer "abcd", cursor stays at idx 2. Insert 'X'
        // → "abXcd".
        val (r, _) =
            runEdit(
                listOf(
                    "ab",
                    Key.Named.ALT_ENTER,
                    "cd",
                    Key.Named.HOME, // idx 3
                    Key.Named.LEFT, // idx 2
                    Key.Named.DELETE,
                    "X",
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("abXcd"), r)
    }

    @Test fun ctrlKKillsToLogicalLineEnd() {
        // Ctrl-K kills only to end of the current logical line —
        // doesn't eat the `\n` or anything after it. Buffer
        // "abc\ndef", cursor at idx 1 (after 'a'); Ctrl-K drops
        // "bc" → "a\ndef".
        val (r, _) =
            runEdit(
                listOf(
                    "abc",
                    Key.Named.ALT_ENTER,
                    "def",
                    Key.Named.HOME, // idx 4
                    Key.Named.LEFT,
                    Key.Named.LEFT,
                    Key.Named.LEFT,
                    Key.Named.LEFT, // idx 0
                    Key.Named.RIGHT, // idx 1
                    Key.Ctrl('K'), // kill "bc"
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("a\ndef"), r)
    }

    @Test fun ctrlUKillsToLogicalLineStart() {
        // Ctrl-U kills from start of the current logical line to
        // cursor — doesn't cross `\n` into the prior line. Buffer
        // "abc\ndef", cursor at idx 6 (between 'e' and 'f'); Ctrl-U
        // drops "de" → "abc\nf".
        val (r, _) =
            runEdit(
                listOf(
                    "abc",
                    Key.Named.ALT_ENTER,
                    "def",
                    Key.Named.LEFT, // idx 6
                    Key.Ctrl('U'),
                    Key.Named.ENTER,
                ),
            )
        assertEquals(LineEditorResult.Line("abc\nf"), r)
    }

    @Test fun pasteWithEmbeddedNewlines() {
        // Bracketed paste with embedded `\n` lands in the buffer
        // intact and is submittable as one logical input.
        val pasted = "for x in 1 2\ndo echo \$x\ndone"
        val (r, _) =
            runEdit(
                listOf(Key.Paste(pasted), Key.Named.ENTER),
            )
        assertEquals(LineEditorResult.Line(pasted), r)
    }

    @Test fun historyRecallMultilineEntryLandsAtEndOfFirstLine() {
        // Per fish convention: ↑ on a multi-line history entry
        // places the cursor at the end of the first logical line.
        // Verified by typing 'X' immediately after recall — it
        // appears at end of line 0.
        val term = FakeTerminalControl()
        val editor = BasicLineEditor(term)
        runTest {
            editor.addHistory("a\nb\nc")
            term.pushKey(Key.Named.UP)
            term.pushChars("X")
            term.pushKey(Key.Named.ENTER)
            val r = editor.readLine("$ ", "> ", { true })
            assertEquals(LineEditorResult.Line("aX\nb\nc"), r)
        }
    }

    @Test fun upAtTopRowFallsThroughToHistory() {
        // Verify the "history fallback" still works when the user
        // is on the top row of a multi-line buffer with no row
        // above. (At top row with no history, UP is a no-op.)
        val term = FakeTerminalControl()
        val editor = BasicLineEditor(term)
        runTest {
            editor.addHistory("ls -la")
            term.pushChars("draft")
            term.pushKey(Key.Named.UP) // top row → history
            term.pushKey(Key.Named.ENTER)
            val r = editor.readLine("$ ", "> ", { true })
            assertEquals(LineEditorResult.Line("ls -la"), r)
        }
    }
}
