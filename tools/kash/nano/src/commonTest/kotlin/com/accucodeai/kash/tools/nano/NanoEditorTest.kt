package com.accucodeai.kash.tools.nano

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.FakeTerminalControl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NanoEditorTest {
    private fun mkFs(): InMemoryFs {
        val fs = InMemoryFs()
        runCatching { fs.mkdirs("/home") }
        return fs
    }

    @Test fun emptyBufferAndImmediateCtrlXExits() =
        runTest {
            val term = FakeTerminalControl()
            term.pushKey(Key.Ctrl('X'))
            val ed = NanoEditor(term, mkFs(), "/home", null)
            assertEquals(0, ed.run())
            // Editor must enter raw + alt screen; teardown must exit alt screen.
            assertEquals(1, term.rawModeEntered)
            assertEquals(false, term.altScreenOn)
        }

    @Test fun typingAndCtrlSWritesFile() =
        runTest {
            val fs = mkFs()
            val term = FakeTerminalControl()
            term.pushChars("hi")
            term.pushKey(Key.Ctrl('S'))
            // ^S without prompt — uses the existing filename (we passed "f.txt")
            term.pushKey(Key.Ctrl('X'))
            val ed = NanoEditor(term, fs, "/home", "f.txt")
            assertEquals(0, ed.run())
            assertEquals("hi\n", fs.readBytes("/home/f.txt").decodeToString())
        }

    @Test fun ctrlXOnDirtyBufferPromptsAndYWritesThenExits() =
        runTest {
            val fs = mkFs()
            val term = FakeTerminalControl()
            term.pushChars("ab")
            term.pushKey(Key.Ctrl('X'))
            // confirm: y → save → since filename is known, no further prompt needed
            term.pushKey(Key.Char('y'.code))
            val ed = NanoEditor(term, fs, "/home", "saved.txt")
            assertEquals(0, ed.run())
            assertEquals("ab\n", fs.readBytes("/home/saved.txt").decodeToString())
        }

    @Test fun ctrlXOnDirtyBufferNDiscardsAndExits() =
        runTest {
            val fs = mkFs()
            val term = FakeTerminalControl()
            term.pushChars("x")
            term.pushKey(Key.Ctrl('X'))
            term.pushKey(Key.Char('n'.code)) // discard
            val ed = NanoEditor(term, fs, "/home", "discard.txt")
            assertEquals(0, ed.run())
            assertTrue(!fs.exists("/home/discard.txt"))
        }

    @Test fun openExistingFileReadsContents() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/g.txt", "alpha\nbeta\n".encodeToByteArray())
            val term = FakeTerminalControl()
            term.pushKey(Key.Named.DOWN)
            term.pushKey(Key.Ctrl('X')) // not dirty — exits immediately
            val ed = NanoEditor(term, fs, "/home", "g.txt")
            assertEquals(0, ed.run())
            // Title bar should mention the filename in the rendered output.
            assertContains(term.output, "g.txt")
        }

    @Test fun arrowsMoveCursorAndAffectInsertionPoint() =
        runTest {
            val fs = mkFs()
            val term = FakeTerminalControl()
            term.pushChars("abcdef")
            term.pushKey(Key.Named.LEFT)
            term.pushKey(Key.Named.LEFT)
            // cursor between 'd' and 'e'
            term.pushChars("X")
            term.pushKey(Key.Ctrl('S'))
            term.pushKey(Key.Ctrl('X'))
            val ed = NanoEditor(term, fs, "/home", "mv.txt")
            assertEquals(0, ed.run())
            assertEquals("abcdXef\n", fs.readBytes("/home/mv.txt").decodeToString())
        }

    @Test fun searchMovesCursorToMatch() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/s.txt", "alpha\nbeta\ngamma\n".encodeToByteArray())
            val term = FakeTerminalControl()
            term.pushKey(Key.Ctrl('W'))
            term.pushChars("gamma")
            term.pushKey(Key.Named.ENTER)
            // After search, cursor should be on row 2 col 0; type "!" there.
            term.pushChars("!")
            term.pushKey(Key.Ctrl('S'))
            term.pushKey(Key.Ctrl('X'))
            val ed = NanoEditor(term, fs, "/home", "s.txt")
            assertEquals(0, ed.run())
            assertEquals("alpha\nbeta\n!gamma\n", fs.readBytes("/home/s.txt").decodeToString())
        }

    @Test fun cutAndPasteLine() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/k.txt", "a\nb\nc\n".encodeToByteArray())
            val term = FakeTerminalControl()
            // start on row 0 ("a") — cut twice would cut a and b; we cut once
            // then move down and paste, producing a result with line order shifted.
            term.pushKey(Key.Ctrl('K')) // cut "a" → buffer is b,c
            term.pushKey(Key.Named.DOWN) // now on row 1 ("c")
            term.pushKey(Key.Ctrl('U')) // paste "a" above → b, a, c with cursor on "a"
            term.pushKey(Key.Ctrl('S'))
            term.pushKey(Key.Ctrl('X'))
            val ed = NanoEditor(term, fs, "/home", "k.txt")
            assertEquals(0, ed.run())
            assertEquals("b\na\nc\n", fs.readBytes("/home/k.txt").decodeToString())
        }
}
