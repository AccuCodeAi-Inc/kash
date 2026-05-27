package com.accucodeai.kash.tools.csplit

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private data class Run(
    val exit: Int,
    val out: String,
    val err: String,
    val fs: FileSystem,
)

private fun stdinOf(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runCsplit(
    args: List<String>,
    fs: InMemoryFs = InMemoryFs(),
    stdin: Buffer = Buffer(),
): Run {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            cwd = "/",
            stdin = stdin.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = CsplitCommand().run(args, ctx)
    return Run(res.exitCode, out.readString(), err.readString(), fs)
}

private suspend fun writeFile(
    fs: InMemoryFs,
    path: String,
    content: String,
) {
    fs.writeBytes(path, content.encodeToByteArray())
}

private suspend fun readFile(
    fs: FileSystem,
    path: String,
): String = fs.readBytes(path).decodeToString()

class CsplitCommandTest {
    @Test fun `single regex splits into two pieces`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nb\nMARK\nc\nd\n") }
            val r = runCsplit(listOf("/in", "/MARK/"), fs)
            assertEquals(0, r.exit)
            assertEquals("a\nb\n", readFile(r.fs, "/xx00"))
            assertEquals("MARK\nc\nd\n", readFile(r.fs, "/xx01"))
            // byte counts printed: 4 (a\nb\n) and 9 (MARK\nc\nd\n)
            assertEquals("4\n9\n", r.out)
        }

    @Test fun `multiple regexes`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "h1\nA\n1\n2\nB\n3\nC\n4\n") }
            val r = runCsplit(listOf("/in", "/A/", "/B/", "/C/"), fs)
            assertEquals(0, r.exit)
            assertEquals("h1\n", readFile(r.fs, "/xx00"))
            assertEquals("A\n1\n2\n", readFile(r.fs, "/xx01"))
            assertEquals("B\n3\n", readFile(r.fs, "/xx02"))
            assertEquals("C\n4\n", readFile(r.fs, "/xx03"))
        }

    @Test fun `linenum split`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "1\n2\n3\n4\n5\n") }
            val r = runCsplit(listOf("/in", "3"), fs)
            assertEquals(0, r.exit)
            // POSIX: split at line 3 means piece 0 = lines 1..2, piece 1 = lines 3..end
            assertEquals("1\n2\n", readFile(r.fs, "/xx00"))
            assertEquals("3\n4\n5\n", readFile(r.fs, "/xx01"))
        }

    @Test fun `repetition braces`() =
        runTest {
            val fs =
                InMemoryFs().also {
                    writeFile(it, "/in", "SEC\n1\nSEC\n2\nSEC\n3\nSEC\n4\n")
                }
            val r = runCsplit(listOf("/in", "/SEC/", "{3}"), fs)
            assertEquals(0, r.exit)
            // 4 splits = 5 pieces (xx00..xx04). xx00 is empty (file starts at SEC).
            assertEquals("", readFile(r.fs, "/xx00"))
            assertEquals("SEC\n1\n", readFile(r.fs, "/xx01"))
            assertEquals("SEC\n2\n", readFile(r.fs, "/xx02"))
            assertEquals("SEC\n3\n", readFile(r.fs, "/xx03"))
            assertEquals("SEC\n4\n", readFile(r.fs, "/xx04"))
        }

    @Test fun `infinite star`() =
        runTest {
            val fs =
                InMemoryFs().also { writeFile(it, "/in", "M\nx\nM\ny\nM\nz\n") }
            val r = runCsplit(listOf("/in", "/M/", "{*}"), fs)
            assertEquals(0, r.exit)
            // splits at every M => pieces: "", M+x, M+y, M+z = 4 pieces
            assertEquals("", readFile(r.fs, "/xx00"))
            assertEquals("M\nx\n", readFile(r.fs, "/xx01"))
            assertEquals("M\ny\n", readFile(r.fs, "/xx02"))
            assertEquals("M\nz\n", readFile(r.fs, "/xx03"))
        }

    @Test fun `prefix and digits`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nb\nM\nc\n") }
            val r = runCsplit(listOf("-f", "PRE", "-n", "1", "/in", "/M/"), fs)
            assertEquals(0, r.exit)
            assertEquals("a\nb\n", readFile(r.fs, "/PRE0"))
            assertEquals("M\nc\n", readFile(r.fs, "/PRE1"))
        }

    @Test fun `custom suffix format`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nb\nM\nc\n") }
            val r = runCsplit(listOf("-b", "%03d.txt", "/in", "/M/"), fs)
            assertEquals(0, r.exit)
            assertEquals("a\nb\n", readFile(r.fs, "/xx000.txt"))
            assertEquals("M\nc\n", readFile(r.fs, "/xx001.txt"))
        }

    @Test fun `silent suppresses counts`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nM\nb\n") }
            val r = runCsplit(listOf("-s", "/in", "/M/"), fs)
            assertEquals(0, r.exit)
            assertEquals("", r.out)
        }

    @Test fun `elide empty files`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "M\na\nM\nb\n") }
            // First piece is empty (file starts with M).
            val r = runCsplit(listOf("-z", "/in", "/M/", "/M/"), fs)
            assertEquals(0, r.exit)
            // With -z, xx00 (empty) is elided; xx00 holds "M\na\n", xx01 holds "M\nb\n"
            assertEquals("M\na\n", readFile(r.fs, "/xx00"))
            assertEquals("M\nb\n", readFile(r.fs, "/xx01"))
            assertFalse(r.fs.exists("/xx02"))
        }

    @Test fun `regex with positive offset includes lines after match`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nb\nX\nc\nd\ne\n") }
            val r = runCsplit(listOf("/in", "/X/+2"), fs)
            assertEquals(0, r.exit)
            // X is at index 2; +2 => split at index 4. Piece 0 = "a\nb\nX\nc\n", piece 1 = "d\ne\n"
            assertEquals("a\nb\nX\nc\n", readFile(r.fs, "/xx00"))
            assertEquals("d\ne\n", readFile(r.fs, "/xx01"))
        }

    @Test fun `regex with negative offset`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nb\nc\nX\nd\n") }
            val r = runCsplit(listOf("/in", "/X/-1"), fs)
            assertEquals(0, r.exit)
            // X at idx 3; -1 => split at idx 2. piece 0 = "a\nb\n", piece 1 = "c\nX\nd\n"
            assertEquals("a\nb\n", readFile(r.fs, "/xx00"))
            assertEquals("c\nX\nd\n", readFile(r.fs, "/xx01"))
        }

    @Test fun `suppress-matched drops match line`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nM\nb\nc\n") }
            val r = runCsplit(listOf("--suppress-matched", "/in", "/M/"), fs)
            assertEquals(0, r.exit)
            assertEquals("a\n", readFile(r.fs, "/xx00"))
            assertEquals("b\nc\n", readFile(r.fs, "/xx01"))
        }

    @Test fun `pattern not found errors and removes pieces`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nb\nc\n") }
            val r = runCsplit(listOf("/in", "/NOMATCH/"), fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("not found"))
            // No pieces remain.
            assertFalse(r.fs.exists("/xx00"))
        }

    @Test fun `keep flag preserves pieces on error`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nb\nM\nc\n") }
            // first pattern succeeds, second fails
            val r = runCsplit(listOf("-k", "/in", "/M/", "/NOPE/"), fs)
            assertEquals(1, r.exit)
            assertTrue(r.fs.exists("/xx00"))
        }

    @Test fun `stdin via dash`() =
        runTest {
            val r = runCsplit(listOf("-", "/M/"), InMemoryFs(), stdinOf("a\nM\nb\n"))
            assertEquals(0, r.exit)
            assertEquals("a\n", readFile(r.fs, "/xx00"))
            assertEquals("M\nb\n", readFile(r.fs, "/xx01"))
        }

    @Test fun `linenum at line 1 is empty first piece`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nb\nc\n") }
            val r = runCsplit(listOf("/in", "1"), fs)
            assertEquals(0, r.exit)
            assertEquals("", readFile(r.fs, "/xx00"))
            assertEquals("a\nb\nc\n", readFile(r.fs, "/xx01"))
        }

    @Test fun `missing file errors exit 1`() =
        runTest {
            val r = runCsplit(listOf("/nope", "/X/"))
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("No such file"))
        }

    @Test fun `too few operands errors`() =
        runTest {
            val r = runCsplit(listOf("/in"))
            assertEquals(2, r.exit)
        }

    @Test fun `byte counts match pieces`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "abc\nM\ndef\n") }
            val r = runCsplit(listOf("/in", "/M/"), fs)
            assertEquals(0, r.exit)
            // piece 0: "abc\n" = 4 bytes; piece 1: "M\ndef\n" = 6 bytes
            assertEquals("4\n6\n", r.out)
        }

    @Test fun `unknown long option errors`() =
        runTest {
            val r = runCsplit(listOf("--bogus", "/in", "/X/"))
            assertEquals(2, r.exit)
        }

    @Test fun `dash dash ends options`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/-in", "a\nM\nb\n") }
            val r = runCsplit(listOf("--", "/-in", "/M/"), fs)
            assertEquals(0, r.exit)
            assertEquals("a\n", readFile(r.fs, "/xx00"))
        }

    @Test fun `multiple line numbers`() =
        runTest {
            val fs =
                InMemoryFs().also { writeFile(it, "/in", "1\n2\n3\n4\n5\n6\n") }
            val r = runCsplit(listOf("/in", "3", "5"), fs)
            assertEquals(0, r.exit)
            assertEquals("1\n2\n", readFile(r.fs, "/xx00"))
            assertEquals("3\n4\n", readFile(r.fs, "/xx01"))
            assertEquals("5\n6\n", readFile(r.fs, "/xx02"))
        }

    @Test fun `silent and successful run prints nothing on stdout`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nM\nb\n") }
            val r = runCsplit(listOf("--silent", "/in", "/M/"), fs)
            assertEquals(0, r.exit)
            assertEquals("", r.out)
        }

    @Test fun `printf format with no flag is plain decimal`() =
        runTest {
            val fs = InMemoryFs().also { writeFile(it, "/in", "a\nM\nb\n") }
            val r = runCsplit(listOf("-b", "%d.out", "/in", "/M/"), fs)
            assertEquals(0, r.exit)
            assertTrue(r.fs.exists("/xx0.out"))
            assertTrue(r.fs.exists("/xx1.out"))
        }
}
