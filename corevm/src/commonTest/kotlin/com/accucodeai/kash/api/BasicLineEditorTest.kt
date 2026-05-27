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

    @Test fun backslashContinuationJoinsLines() {
        // Backslash at EOL joins, bypassing isComplete entirely on that read.
        val (r, _) =
            runEdit(
                listOf(
                    "echo hello \\",
                    Key.Named.ENTER,
                    "world",
                    Key.Named.ENTER,
                ),
            )
        // The \ is stripped, lines joined with `\n` per readOnePhysicalLine.
        assertEquals(LineEditorResult.Line("echo hello \nworld"), r)
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
}
