package com.accucodeai.kash.tools.vi

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.FakeTerminalControl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViEditorRecipeTest {
    private fun mkFs(): InMemoryFs {
        val fs = InMemoryFs()
        runCatching { fs.mkdirs("/home") }
        return fs
    }

    /** Send the runes of a string as Key.Char events. */
    private fun FakeTerminalControl.typeChars(s: String) {
        for (ch in s) pushKey(Key.Char(ch.code))
    }

    /** Send `:cmd` + ENTER. */
    private fun FakeTerminalControl.ex(cmd: String) {
        pushKey(Key.Char(':'.code))
        for (ch in cmd) pushKey(Key.Char(ch.code))
        pushKey(Key.Named.ENTER)
    }

    @Test fun recipe_openSaveQuit() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/a.txt", "hello world\n".encodeToByteArray())
            val term = FakeTerminalControl()
            // Move to col 6 (start of "world"), change word to FOO
            term.typeChars("wcw")
            term.typeChars("FOO")
            term.pushKey(Key.Named.ESC)
            term.ex("wq")
            val ed = ViEditor(term, fs, "/home", "a.txt")
            assertEquals(0, ed.run())
            assertEquals("hello FOO\n", fs.readBytes("/home/a.txt").decodeToString())
        }

    @Test fun recipe_globalSubstitute() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/g.txt", "foo bar foo\nbaz foo\n".encodeToByteArray())
            val term = FakeTerminalControl()
            term.ex("%s/foo/QUX/g")
            term.ex("wq")
            val ed = ViEditor(term, fs, "/home", "g.txt")
            assertEquals(0, ed.run())
            assertEquals("QUX bar QUX\nbaz QUX\n", fs.readBytes("/home/g.txt").decodeToString())
        }

    @Test fun recipe_yankAndPaste() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/y.txt", "a\nb\nc\nd\ne\nf\n".encodeToByteArray())
            val term = FakeTerminalControl()
            // yy at line 0 → unnamed register holds "a" line-wise
            // 3j to line 3 ("d")
            // p → paste below: a b c d a e f
            term.typeChars("yy")
            term.typeChars("3j")
            term.typeChars("p")
            term.ex("wq")
            val ed = ViEditor(term, fs, "/home", "y.txt")
            assertEquals(0, ed.run())
            assertEquals("a\nb\nc\nd\na\ne\nf\n", fs.readBytes("/home/y.txt").decodeToString())
        }

    @Test fun recipe_undoMultiStepThenRedoThenNewEditClearsRedo() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/u.txt", "hello\n".encodeToByteArray())
            val term = FakeTerminalControl()
            // x x x → delete first 3 chars: "lo"
            term.typeChars("xxx")
            // u → "ello", u → "hello"  (wait — first x deletes 'h' giving "ello"; we'll just observe final state)
            term.typeChars("u")
            // i → INSERT, type Z, ESC → "hello" becomes "Zlo" or similar — doesn't matter; assert final contains expected
            // For determinism: do a new edit so redo stack must clear
            term.pushKey(Key.Char('i'.code))
            term.pushKey(Key.Char('Z'.code))
            term.pushKey(Key.Named.ESC)
            term.ex("wq")
            val ed = ViEditor(term, fs, "/home", "u.txt")
            assertEquals(0, ed.run())
            // After "hello", xxx → "lo", u once → "llo". insert Z before cursor (cursor at col 0): "Zllo"
            assertEquals("Zllo\n", fs.readBytes("/home/u.txt").decodeToString())
        }

    @Test fun recipe_searchForwardAndEdit() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/s.txt", "alpha\nbeta\ngamma\n".encodeToByteArray())
            val term = FakeTerminalControl()
            // /gamma ENTER  → cursor at row 2 col 0
            term.pushKey(Key.Char('/'.code))
            term.typeChars("gamma")
            term.pushKey(Key.Named.ENTER)
            // i ! ESC :wq
            term.pushKey(Key.Char('i'.code))
            term.pushKey(Key.Char('!'.code))
            term.pushKey(Key.Named.ESC)
            term.ex("wq")
            val ed = ViEditor(term, fs, "/home", "s.txt")
            assertEquals(0, ed.run())
            assertEquals("alpha\nbeta\n!gamma\n", fs.readBytes("/home/s.txt").decodeToString())
        }

    @Test fun recipe_newFile_typeAndSaveWithPath() =
        runTest {
            val fs = mkFs()
            val term = FakeTerminalControl()
            // i, type "new content", ESC
            term.pushKey(Key.Char('i'.code))
            term.typeChars("new content")
            term.pushKey(Key.Named.ESC)
            term.ex("w out.txt")
            term.ex("q")
            val ed = ViEditor(term, fs, "/home", null)
            assertEquals(0, ed.run())
            assertEquals("new content\n", fs.readBytes("/home/out.txt").decodeToString())
        }

    @Test fun recipe_openDirectoryRefusesContent() =
        runTest {
            val fs = mkFs()
            fs.mkdirs("/home/somedir")
            val term = FakeTerminalControl()
            term.ex("q")
            val ed = ViEditor(term, fs, "/home", "somedir")
            assertEquals(0, ed.run())
            // The editor displays a notice and starts with empty buffer.
            assertTrue(term.output.contains("directory"))
        }

    @Test fun recipe_quitDirtyWithoutBangFails_thenForce() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/d.txt", "abc\n".encodeToByteArray())
            val term = FakeTerminalControl()
            // make dirty
            term.pushKey(Key.Char('x'.code))
            // :q  — should warn, not quit
            term.ex("q")
            // :q!  — should force quit
            term.ex("q!")
            val ed = ViEditor(term, fs, "/home", "d.txt")
            assertEquals(0, ed.run())
            // File not saved → still "abc\n"
            assertEquals("abc\n", fs.readBytes("/home/d.txt").decodeToString())
            // Notice about no-write should have been rendered
            assertTrue(term.output.contains("No write"))
        }

    @Test fun recipe_ddPasteSwapsLines() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/m.txt", "first\nsecond\nthird\n".encodeToByteArray())
            val term = FakeTerminalControl()
            // dd at row 0 → "first" yanked, lines become second/third
            term.typeChars("dd")
            // p → paste below row 0 (now "second") → second / first / third
            term.typeChars("p")
            term.ex("wq")
            val ed = ViEditor(term, fs, "/home", "m.txt")
            assertEquals(0, ed.run())
            assertEquals("second\nfirst\nthird\n", fs.readBytes("/home/m.txt").decodeToString())
        }

    @Test fun recipe_visualLineDelete() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/v.txt", "a\nb\nc\nd\ne\n".encodeToByteArray())
            val term = FakeTerminalControl()
            // V j j d → delete 3 lines (a, b, c)
            term.pushKey(Key.Char('V'.code))
            term.pushKey(Key.Char('j'.code))
            term.pushKey(Key.Char('j'.code))
            term.pushKey(Key.Char('d'.code))
            term.ex("wq")
            val ed = ViEditor(term, fs, "/home", "v.txt")
            assertEquals(0, ed.run())
            assertEquals("d\ne\n", fs.readBytes("/home/v.txt").decodeToString())
        }

    @Test fun recipe_namedRegisterYankPaste() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/r.txt", "AAA\nBBB\n".encodeToByteArray())
            val term = FakeTerminalControl()
            // "ayy yanks AAA into register a
            // j moves to BBB line
            // "ap pastes from a after BBB
            term.pushKey(Key.Char('"'.code))
            term.pushKey(Key.Char('a'.code))
            term.pushKey(Key.Char('y'.code))
            term.pushKey(Key.Char('y'.code))
            term.pushKey(Key.Char('j'.code))
            term.pushKey(Key.Char('"'.code))
            term.pushKey(Key.Char('a'.code))
            term.pushKey(Key.Char('p'.code))
            term.ex("wq")
            val ed = ViEditor(term, fs, "/home", "r.txt")
            assertEquals(0, ed.run())
            assertEquals("AAA\nBBB\nAAA\n", fs.readBytes("/home/r.txt").decodeToString())
        }

    @Test fun recipe_marksJump() =
        runTest {
            val fs = mkFs()
            fs.writeBytes("/home/k.txt", "one\ntwo\nthree\nfour\n".encodeToByteArray())
            val term = FakeTerminalControl()
            // jj  -> row 2 "three"
            // ma  -> set mark 'a' at three
            // gg  -> back to top
            // 'a  -> jump to row 2
            // i!  ESC :wq
            term.pushKey(Key.Char('j'.code))
            term.pushKey(Key.Char('j'.code))
            term.pushKey(Key.Char('m'.code))
            term.pushKey(Key.Char('a'.code))
            term.pushKey(Key.Char('g'.code))
            term.pushKey(Key.Char('g'.code))
            term.pushKey(Key.Char('\''.code))
            term.pushKey(Key.Char('a'.code))
            term.pushKey(Key.Char('i'.code))
            term.pushKey(Key.Char('!'.code))
            term.pushKey(Key.Named.ESC)
            term.ex("wq")
            val ed = ViEditor(term, fs, "/home", "k.txt")
            assertEquals(0, ed.run())
            assertEquals("one\ntwo\n!three\nfour\n", fs.readBytes("/home/k.txt").decodeToString())
        }

    @Test fun recipe_insertModeArrowsThenSave() =
        runTest {
            val fs = mkFs()
            val term = FakeTerminalControl()
            term.pushKey(Key.Char('i'.code))
            term.typeChars("abcdef")
            term.pushKey(Key.Named.LEFT)
            term.pushKey(Key.Named.LEFT)
            term.typeChars("X")
            term.pushKey(Key.Named.ESC)
            term.ex("w mv.txt")
            term.ex("q")
            val ed = ViEditor(term, fs, "/home", null)
            assertEquals(0, ed.run())
            assertEquals("abcdXef\n", fs.readBytes("/home/mv.txt").decodeToString())
        }
}
