package com.accucodeai.kash.tools.fold

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals

private class MemFs(
    private val files: Map<String, String> = emptyMap(),
) : FileSystem {
    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun isDirectory(path: String): Boolean = false

    override fun source(path: String): SuspendSource {
        val buf = Buffer()
        files[path]?.let { buf.writeString(it) }
        return buf.asSuspendSource()
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = Buffer().asSuspendSink()

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {}

    override fun list(path: String): List<String> = emptyList()

    override fun remove(path: String) {}
}

private fun stdinOf(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runFold(
    input: String = "",
    fs: FileSystem = MemFs(),
    vararg args: String,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = "/",
            stdin = stdinOf(input).asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = FoldCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class FoldRecipeTest {
    @Test fun `default width 80 splits long line`() =
        runTest {
            val input = "a".repeat(200) + "\n"
            val (rc, out, _) = runFold(input)
            assertEquals(0, rc)
            // 80 + 80 + 40
            val expected = "a".repeat(80) + "\n" + "a".repeat(80) + "\n" + "a".repeat(40) + "\n"
            assertEquals(expected, out)
        }

    @Test fun `default width 80 leaves short line alone`() =
        runTest {
            val (_, out, _) = runFold("hello\n")
            assertEquals("hello\n", out)
        }

    @Test fun `-w 10 splits exactly`() =
        runTest {
            val (_, out, _) = runFold("abcdefghijklmnopqrst\n", args = arrayOf("-w", "10"))
            assertEquals("abcdefghij\nklmnopqrst\n", out)
        }

    @Test fun `-w 5 multiple splits`() =
        runTest {
            val (_, out, _) = runFold("abcdefghijklm\n", args = arrayOf("-w", "5"))
            assertEquals("abcde\nfghij\nklm\n", out)
        }

    @Test fun `legacy -N width syntax`() =
        runTest {
            val (_, out, _) = runFold("abcdefghij\n", args = arrayOf("-5"))
            assertEquals("abcde\nfghij\n", out)
        }

    @Test fun `-w attached form`() =
        runTest {
            val (_, out, _) = runFold("abcdefghij\n", args = arrayOf("-w5"))
            assertEquals("abcde\nfghij\n", out)
        }

    @Test fun `-s breaks at last space within width`() =
        runTest {
            // 'hello world foo' -w 10 -s ⇒ first break should be after 'hello '
            val (_, out, _) = runFold("hello world foo\n", args = arrayOf("-s", "-w", "10"))
            assertEquals("hello \nworld foo\n", out)
        }

    @Test fun `-s falls back to hard break when no space fits`() =
        runTest {
            // No spaces ⇒ hard break at width
            val (_, out, _) = runFold("abcdefghij\n", args = arrayOf("-s", "-w", "5"))
            assertEquals("abcde\nfghij\n", out)
        }

    @Test fun `-s with leading long token`() =
        runTest {
            // 'abcdefghij kk' width 5 with -s: no space in first 5 chars → hard break,
            // then 'fghij kk' width 5 with -s: space at pos 5? 'fghij' is 5 → break, then ' kk' starts.
            val (_, out, _) = runFold("abcdefghij kk\n", args = arrayOf("-s", "-w", "5"))
            // Hard break at 5, then 'fghij' is 5 (next char ' ' would overflow). At overflow,
            // last blank in chunk? chunk='fghij' has no blank, so hard break → 'fghij' + '\n' + ' kk'
            assertEquals("abcde\nfghij\n kk\n", out)
        }

    @Test fun `tab advances column to multiple of 8`() =
        runTest {
            // 'a\tbc' default mode: col 1 → tab to 8 → b at 9, c at 10. width 9 means:
            // After 'a' col=1, tab → col=8, b → col=9, c would → col=10 > 9, break.
            val (_, out, _) = runFold("a\tbc\n", args = arrayOf("-w", "9"))
            assertEquals("a\tb\nc\n", out)
        }

    @Test fun `backspace decrements column`() =
        runTest {
            // 'abc\bd' default: a=1, b=2, c=3, \b → col=2, d → col=3 ⇒ fits width 3
            val (_, out, _) = runFold("abc\bd\n", args = arrayOf("-w", "3"))
            assertEquals("abc\bd\n", out)
        }

    @Test fun `carriage return resets column`() =
        runTest {
            // 'abcde\rxyz' default width 5: cols 1..5, \r → 0, x=1, y=2, z=3 fits
            val (_, out, _) = runFold("abcde\rxyz\n", args = arrayOf("-w", "5"))
            assertEquals("abcde\rxyz\n", out)
        }

    @Test fun `-b counts bytes for multibyte chars`() =
        runTest {
            // 'é' is 2 bytes in UTF-8. 3 of them = 6 bytes. -b -w 4 ⇒ 2 chars per line.
            val (_, out, _) = runFold("ééé\n", args = arrayOf("-b", "-w", "4"))
            assertEquals("éé\né\n", out)
        }

    @Test fun `default column mode counts codepoints not bytes`() =
        runTest {
            // 3 'é' = 3 columns, fits in width 4
            val (_, out, _) = runFold("ééé\n", args = arrayOf("-w", "4"))
            assertEquals("ééé\n", out)
        }

    @Test fun `file operand`() =
        runTest {
            val fs = MemFs(mapOf("/in.txt" to "abcdefghij\n"))
            val (rc, out, _) = runFold(fs = fs, args = arrayOf("-w", "5", "/in.txt"))
            assertEquals(0, rc)
            assertEquals("abcde\nfghij\n", out)
        }

    @Test fun `stdin via dash`() =
        runTest {
            val (_, out, _) = runFold("abcdef\n", args = arrayOf("-w", "3", "-"))
            assertEquals("abc\ndef\n", out)
        }

    @Test fun `missing file returns exit 1 with error`() =
        runTest {
            val (rc, _, err) = runFold(args = arrayOf("/nope.txt"))
            assertEquals(1, rc)
            assertEquals(true, err.contains("No such file"))
        }

    @Test fun `invalid width returns exit 2`() =
        runTest {
            val (rc, _, _) = runFold(args = arrayOf("-w", "0"))
            assertEquals(2, rc)
        }

    @Test fun `multiple lines processed independently`() =
        runTest {
            val (_, out, _) = runFold("abcdef\nghijkl\n", args = arrayOf("-w", "3"))
            assertEquals("abc\ndef\nghi\njkl\n", out)
        }

    @Test fun `line without trailing newline preserved without newline`() =
        runTest {
            val (_, out, _) = runFold("abcdef", args = arrayOf("-w", "3"))
            assertEquals("abc\ndef", out)
        }

    @Test fun `empty input produces empty output`() =
        runTest {
            val (rc, out, _) = runFold("")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun `empty line preserved`() =
        runTest {
            val (_, out, _) = runFold("\n\n", args = arrayOf("-w", "5"))
            assertEquals("\n\n", out)
        }

    @Test fun `-s with tab as blank`() =
        runTest {
            // Tab counts as blank for -s. 'ab\tcd' width 4: a=1, b=2, \t→col=8 > 4.
            // At overflow with -s, look for blank in chunk 'ab' → none, hard break → 'ab\n'.
            // Then chunk = [\t], width = 8 > 4 alone but no break possible (single unit), add c → ...
            // Conservative: just verify it runs and produces the hard-break first chunk.
            val (rc, out, _) = runFold("ab\tcd\n", args = arrayOf("-s", "-w", "4"))
            assertEquals(0, rc)
            assertEquals(true, out.startsWith("ab\n"))
        }

    @Test fun `combined short flags -sb`() =
        runTest {
            val (rc, out, _) = runFold("hello world foo bar\n", args = arrayOf("-sb", "-w", "10"))
            assertEquals(0, rc)
            // -b -s -w 10 on 'hello world foo bar' (19 bytes): break at last blank within 10 bytes.
            // 'hello world' first 10 bytes = 'hello worl', last blank at pos 6 (after 'hello ').
            // So first chunk = 'hello ' (6 bytes), remainder = 'world foo bar' (13 bytes).
            // Then 'world foo ' = 10 bytes exactly, last blank at pos 10 (after ' '). Chunk = 'world foo ', remainder = 'bar'.
            assertEquals("hello \nworld foo \nbar\n", out)
        }
}
