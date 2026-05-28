package com.accucodeai.kash.tools.ed

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EdInterpreterTest {
    private suspend fun runEd(
        script: String,
        fs: InMemoryFs = InMemoryFs(),
        args: List<String> = listOf("-s"),
        cwd: String = "/",
    ): EdRunResult {
        val stdinBuf = Buffer().apply { writeUtf8Local(script) }
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                cwd = cwd,
                stdin = stdinBuf.asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val rc = EdCommand().run(args, ctx).exitCode
        return EdRunResult(rc, out.readString(), err.readString(), fs)
    }

    private fun Buffer.writeUtf8Local(s: String) {
        this.writeString(s)
    }

    private data class EdRunResult(
        val rc: Int,
        val stdout: String,
        val stderr: String,
        val fs: InMemoryFs,
    )

    private suspend fun writeFile(
        fs: InMemoryFs,
        path: String,
        text: String,
    ) {
        fs.writeBytes(path, text.encodeToByteArray())
    }

    private suspend fun readFile(
        fs: InMemoryFs,
        path: String,
    ): String = fs.readBytes(path).decodeToString()

    @Test fun appendAndWrite() =
        runTest {
            val fs = InMemoryFs()
            val r = runEd("a\nhello\nworld\n.\nw /out\nq\n", fs)
            assertEquals(0, r.rc)
            assertEquals("hello\nworld\n", readFile(fs, "/out"))
        }

    @Test fun deleteRangeAndWrite() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\nc\nd\ne\n")
            val r = runEd("1,3d\nw /out\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals(0, r.rc)
            assertEquals("d\ne\n", readFile(fs, "/out"))
        }

    @Test fun substituteGlobalAcrossLines() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "foo bar foo\nfoo only\nno match\n")
            val r = runEd("1,\$s/foo/BAR/g\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals(0, r.rc)
            assertEquals("BAR bar BAR\nBAR only\nno match\n", readFile(fs, "/in"))
        }

    @Test fun globalDelete() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "keep\ndrop me\nkeep\ndrop x\n")
            val r = runEd("g/drop/d\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("keep\nkeep\n", readFile(fs, "/in"))
        }

    @Test fun globalInvertedKeepsMatching() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "keep\ndrop\nkeep\n")
            val r = runEd("v/keep/d\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("keep\nkeep\n", readFile(fs, "/in"))
        }

    @Test fun printLinesOutputsThem() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "alpha\nbeta\ngamma\n")
            val r = runEd("1,3p\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("alpha\nbeta\ngamma\n", r.stdout)
        }

    @Test fun numberedPrint() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "x\ny\n")
            val r = runEd("1,2n\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("1\tx\n2\ty\n", r.stdout)
        }

    @Test fun equalsPrintsLastLine() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\nc\n")
            val r = runEd("=\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("3\n", r.stdout)
        }

    @Test fun equalsWithAddressPrintsIt() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\nc\n")
            val r = runEd("2=\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("2\n", r.stdout)
        }

    @Test fun substituteWithBackrefs() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "hello world\n")
            val r = runEd("s/\\(hello\\) \\(world\\)/\\2 \\1/\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("world hello\n", readFile(fs, "/in"))
        }

    @Test fun substituteAmp() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "abc\n")
            val r = runEd("s/b/[&]/\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("a[b]c\n", readFile(fs, "/in"))
        }

    @Test fun substituteOnlyFirstByDefault() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a a a\n")
            val r = runEd("s/a/X/\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("X a a\n", readFile(fs, "/in"))
        }

    @Test fun substituteWithG() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a a a\n")
            val r = runEd("s/a/X/g\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("X X X\n", readFile(fs, "/in"))
        }

    @Test fun undoRevertsLastEdit() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "abc\n")
            val r = runEd("s/abc/XXX/\nu\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("abc\n", readFile(fs, "/in"))
        }

    @Test fun undoUndoTogglesBack() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "abc\n")
            val r = runEd("s/abc/XXX/\nu\nu\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("XXX\n", readFile(fs, "/in"))
        }

    @Test fun searchForward() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "alpha\nbeta\ngamma\n")
            val r = runEd("/gamma/p\nq\n", fs, args = listOf("-s", "/in"))
            assertContains(r.stdout, "gamma")
        }

    @Test fun searchBackward() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "alpha\nbeta\ngamma\n")
            val r = runEd("?alpha?p\nq\n", fs, args = listOf("-s", "/in"))
            assertContains(r.stdout, "alpha")
        }

    @Test fun emptyCommandAdvancesAndPrints() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\nc\n")
            // Open file -> dot=3. Empty command should fail (no next line).
            // Use a 1<enter> sequence: 1 sets dot, then empty advances and prints line 2.
            val r = runEd("1\n\nq\n", fs, args = listOf("-s", "/in"))
            assertContains(r.stdout, "b")
        }

    @Test fun quitOnDirtyFirstTimePrintsQ() =
        runTest {
            val fs = InMemoryFs()
            val r = runEd("a\nfoo\n.\nq\nq\n", fs)
            // First q prints ?; second q quits.
            assertContains(r.stdout, "?")
        }

    @Test fun forceQuitDirty() =
        runTest {
            val fs = InMemoryFs()
            val r = runEd("a\nfoo\n.\nQ\n", fs)
            assertEquals(0, r.rc)
        }

    @Test fun readFileIntoBuffer() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "from-in\n")
            val r = runEd("a\nstart\n.\nr /in\n%p\nq\n", fs)
            assertContains(r.stdout, "start")
            assertContains(r.stdout, "from-in")
        }

    @Test fun writeAppend() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/out", "old\n")
            val r = runEd("a\nnew\n.\nW /out\nq\n", fs)
            assertEquals("old\nnew\n", readFile(fs, "/out"))
        }

    @Test fun moveCommand() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\nc\nd\n")
            val r = runEd("1m3\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("b\nc\na\nd\n", readFile(fs, "/in"))
        }

    @Test fun transferCommand() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\n")
            val r = runEd("1t2\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("a\nb\na\n", readFile(fs, "/in"))
        }

    @Test fun joinCommand() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\nc\n")
            val r = runEd("1,2j\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("ab\nc\n", readFile(fs, "/in"))
        }

    @Test fun markAndGoto() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\nc\n")
            val r = runEd("2ka\n'a=\nq\n", fs, args = listOf("-s", "/in"))
            assertContains(r.stdout, "2")
        }

    @Test fun helpToggleStorsLastErr() =
        runTest {
            val fs = InMemoryFs()
            val r = runEd("zz\nh\nq\n", fs)
            // First command is unknown so '?' is printed, then h prints the
            // remembered explanation.
            assertContains(r.stdout, "?")
            // Some explanation text should follow.
        }

    @Test fun changeReplacesRange() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\nc\n")
            val r = runEd("2c\nX\nY\n.\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("a\nX\nY\nc\n", readFile(fs, "/in"))
        }

    @Test fun insertBefore() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\n")
            val r = runEd("2i\nX\n.\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("a\nX\nb\n", readFile(fs, "/in"))
        }

    @Test fun appendAfterZero() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\n")
            val r = runEd("0a\nX\n.\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("X\na\nb\n", readFile(fs, "/in"))
        }

    @Test fun fileCommandSetsFilename() =
        runTest {
            val fs = InMemoryFs()
            val r = runEd("a\nfoo\n.\nf /new\nw\nq\n", fs)
            assertEquals("foo\n", readFile(fs, "/new"))
        }

    @Test fun fileCommandPrintsFilename() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "x\n")
            val r = runEd("f\nq\n", fs, args = listOf("-s", "/in"))
            assertContains(r.stdout, "/in")
        }

    @Test fun substituteNthFlag() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a a a a\n")
            val r = runEd("s/a/X/2\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("a X a a\n", readFile(fs, "/in"))
        }

    @Test fun substituteWithPrint() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "abc\n")
            val r = runEd("s/abc/XXX/p\nq\n", fs, args = listOf("-s", "/in"))
            assertContains(r.stdout, "XXX")
        }

    @Test fun globalSubstitute() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a foo\nb foo\nc no\n")
            val r = runEd("g/foo/s//bar/\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("a bar\nb bar\nc no\n", readFile(fs, "/in"))
        }

    @Test fun nestedGlobalRejected() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\n")
            val r = runEd("g/a/g/b/d\nq\n", fs, args = listOf("-s", "/in"))
            assertContains(r.stdout, "?")
        }

    @Test fun batchModeNoByteCount() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "abc\n")
            // -s suppresses byte counts on r/w/e
            val r = runEd("w\nq\n", fs, args = listOf("-s", "/in"))
            assertFalse(r.stdout.contains("4"))
        }

    @Test fun nonSuppressedShowsByteCountOnWrite() =
        runTest {
            val fs = InMemoryFs()
            val r = runEd("a\nabc\n.\nw /out\nq\n", fs, args = emptyList())
            // 4 bytes ("abc\n")
            assertContains(r.stdout, "4")
        }

    @Test fun unknownCommandPrintsQuestion() =
        runTest {
            val fs = InMemoryFs()
            val r = runEd("xx\nq\n", fs)
            assertContains(r.stdout, "?")
        }

    @Test fun substituteWithNewline() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "ab\n")
            val r = runEd("s/b/X\\nY/\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("aX\nY\n", readFile(fs, "/in"))
        }

    @Test fun percentIsWholeBuffer() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\nc\n")
            val r = runEd("%d\nw\nq\n", fs, args = listOf("-s", "/in"))
            assertEquals("", readFile(fs, "/in"))
        }

    @Test fun commaIsWholeBuffer() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "a\nb\n")
            val r = runEd(",p\nq\n", fs, args = listOf("-s", "/in"))
            assertContains(r.stdout, "a")
            assertContains(r.stdout, "b")
        }

    @Test fun rangeExtraction() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "head\nSTART\nbody1\nbody2\nEND\ntail\n")
            val r = runEd("/START/,/END/p\nq\n", fs, args = listOf("-s", "/in"))
            assertContains(r.stdout, "START")
            assertContains(r.stdout, "body1")
            assertContains(r.stdout, "END")
            assertFalse(r.stdout.contains("head"))
        }

    @Test fun lastSearchReused() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "foo\nbar\nfoo\n")
            // First /foo/ sets lastSearch; subsequent // reuses.
            val r = runEd("/foo/=\n//=\nq\n", fs, args = listOf("-s", "/in"))
            // Cursor wraps from line 1 to next foo at line 3.
            assertContains(r.stdout, "1")
            assertContains(r.stdout, "3")
        }

    @Test fun versionFlag() =
        runTest {
            val fs = InMemoryFs()
            val r = runEd("", fs, args = listOf("-V"))
            assertEquals(0, r.rc)
            assertContains(r.stdout, "ed")
        }

    @Test fun usageExit2() =
        runTest {
            val fs = InMemoryFs()
            val r = runEd("", fs, args = listOf("--no-such"))
            assertEquals(2, r.rc)
        }

    @Test fun promptFlag() =
        runTest {
            val fs = InMemoryFs()
            val r = runEd("q\n", fs, args = listOf("-s", "-p", "> "))
            // Prompt should appear before q is consumed.
            assertContains(r.stdout, "> ")
        }

    @Test fun nonExistentFileLoad() =
        runTest {
            val fs = InMemoryFs()
            // Load missing file -> POSIX behavior: print 0 (or in -s mode,
            // nothing) and start with empty buffer.
            val r = runEd("a\nx\n.\nw\nq\n", fs, args = listOf("-s", "/missing"))
            assertEquals(0, r.rc)
            assertEquals("x\n", readFile(fs, "/missing"))
        }

    @Test fun ePostsRefusesDirty() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/a", "A\n")
            writeFile(fs, "/b", "B\n")
            val r = runEd("a\nfoo\n.\ne /b\nq\n", fs)
            // First e attempts to discard dirty buffer => ?
            assertContains(r.stdout, "?")
        }

    @Test fun forceEditDiscards() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/a", "A\n")
            writeFile(fs, "/b", "B\n")
            val r = runEd("a\nfoo\n.\nE /b\n,p\nq\n", fs)
            assertContains(r.stdout, "B")
            assertFalse(r.stdout.contains("foo"))
        }

    @Test fun multiCommandsInARow() =
        runTest {
            val fs = InMemoryFs()
            writeFile(fs, "/in", "aaa\nbbb\nccc\n")
            val r =
                runEd(
                    "1s/aaa/AAA/\n2s/bbb/BBB/\n3s/ccc/CCC/\nw\nq\n",
                    fs,
                    args = listOf("-s", "/in"),
                )
            assertEquals("AAA\nBBB\nCCC\n", readFile(fs, "/in"))
        }
}
