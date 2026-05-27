package com.accucodeai.kash.tools.nl

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class NlRun(
    val exit: Int,
    val out: String,
    val err: String,
)

private fun stdin(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runNl(
    args: List<String> = emptyList(),
    stdin: Buffer = Buffer(),
    fs: FileSystem = NullFs(),
): NlRun {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = "/",
            stdin = stdin.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = NlCommand().run(args, ctx)
    return NlRun(res.exitCode, out.readString(), err.readString())
}

private fun num(
    n: Int,
    width: Int = 6,
) = n.toString().padStart(width, ' ')

class NlRecipeTest {
    // ---------- defaults ----------

    @Test fun `default numbers non-empty lines only with width 6 tab separator`() =
        runTest {
            val r = runNl(stdin = stdin("a\n\nb\n\nc\n"))
            assertEquals(0, r.exit)
            val expected =
                buildString {
                    append(num(1)).append('\t').append("a\n")
                    append(" ".repeat(6) + " ").append("\n")
                    append(num(2)).append('\t').append("b\n")
                    append(" ".repeat(6) + " ").append("\n")
                    append(num(3)).append('\t').append("c\n")
                }
            assertEquals(expected, r.out)
        }

    @Test fun `default right aligned to width 6`() =
        runTest {
            val r = runNl(stdin = stdin("x\n"))
            assertEquals("${num(1)}\tx\n", r.out)
        }

    // ---------- -b TYPE ----------

    @Test fun `dash b a numbers all lines including blanks`() =
        runTest {
            val r = runNl(listOf("-ba"), stdin = stdin("a\n\nb\n"))
            val expected = "${num(1)}\ta\n${num(2)}\t\n${num(3)}\tb\n"
            assertEquals(expected, r.out)
        }

    @Test fun `dash b n suppresses all numbering`() =
        runTest {
            val r = runNl(listOf("-bn"), stdin = stdin("a\nb\nc\n"))
            val blank = " ".repeat(6) + " "
            assertEquals("${blank}a\n${blank}b\n${blank}c\n", r.out)
        }

    @Test fun `dash b pREGEX matches BRE`() =
        runTest {
            // BRE \+ is metachar (one or more); bare + is literal.
            val r = runNl(listOf("-bp^foo"), stdin = stdin("foobar\nbaz\nfoo\n"))
            val blank = " ".repeat(6) + " "
            val expected = "${num(1)}\tfoobar\n${blank}baz\n${num(2)}\tfoo\n"
            assertEquals(expected, r.out)
        }

    @Test fun `dash b pREGEX BRE escaping bare paren is literal`() =
        runTest {
            // In BRE, bare `(` is a literal paren (ERE treats it as group open).
            // Our breToEre must escape it so the pattern still matches a literal "(".
            val r = runNl(listOf("-bp(x)"), stdin = stdin("hello\n(x)foo\nbar\n"))
            val blank = " ".repeat(6) + " "
            val expected = "${blank}hello\n${num(1)}\t(x)foo\n${blank}bar\n"
            assertEquals(expected, r.out)
        }

    // ---------- -n FORMAT ----------

    @Test fun `dash n ln left-aligned no leading zero`() =
        runTest {
            val r = runNl(listOf("-nln"), stdin = stdin("a\n"))
            assertEquals("1     \ta\n", r.out)
        }

    @Test fun `dash n rz right zero-padded`() =
        runTest {
            val r = runNl(listOf("-nrz"), stdin = stdin("a\nb\n"))
            assertEquals("000001\ta\n000002\tb\n", r.out)
        }

    @Test fun `dash n rn is the default`() =
        runTest {
            val r = runNl(listOf("-nrn"), stdin = stdin("a\n"))
            assertEquals("${num(1)}\ta\n", r.out)
        }

    // ---------- -i / -v / -w ----------

    @Test fun `dash i 5 increments by 5`() =
        runTest {
            val r = runNl(listOf("-i5"), stdin = stdin("a\nb\nc\n"))
            assertEquals("${num(1)}\ta\n${num(6)}\tb\n${num(11)}\tc\n", r.out)
        }

    @Test fun `dash v 100 starts numbering at 100`() =
        runTest {
            val r = runNl(listOf("-v100"), stdin = stdin("a\nb\n"))
            assertEquals("${num(100)}\ta\n${num(101)}\tb\n", r.out)
        }

    @Test fun `dash w 3 sets width to 3`() =
        runTest {
            val r = runNl(listOf("-w3"), stdin = stdin("a\n"))
            assertEquals("  1\ta\n", r.out)
        }

    @Test fun `dash w combined with rz pads to that width`() =
        runTest {
            val r = runNl(listOf("-w4", "-nrz"), stdin = stdin("a\n"))
            assertEquals("0001\ta\n", r.out)
        }

    // ---------- -s ----------

    @Test fun `dash s custom separator`() =
        runTest {
            val r = runNl(listOf("-s", " | "), stdin = stdin("a\nb\n"))
            assertEquals("${num(1)} | a\n${num(2)} | b\n", r.out)
        }

    @Test fun `dash s attached form`() =
        runTest {
            val r = runNl(listOf("-s: "), stdin = stdin("a\n"))
            assertEquals("${num(1)}: a\n", r.out)
        }

    // ---------- -l blank join ----------

    @Test fun `dash l 3 with ba groups 3 blanks as one`() =
        runTest {
            // -ba alone numbers every blank; -l3 means only every 3rd blank gets numbered.
            val r = runNl(listOf("-ba", "-l3"), stdin = stdin("a\n\n\n\nb\n"))
            // Lines: "a" (1), blank (run=1, no), blank (run=2, no), blank (run=3, YES =2), "b" (=3)
            val blank = " ".repeat(6) + " "
            val expected = "${num(1)}\ta\n${blank}\n${blank}\n${num(2)}\t\n${num(3)}\tb\n"
            assertEquals(expected, r.out)
        }

    // ---------- file operands ----------

    @Test fun `single file operand`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/in.txt", "hello\nworld\n".encodeToByteArray())
            val r = runNl(listOf("/in.txt"), fs = fs)
            assertEquals("${num(1)}\thello\n${num(2)}\tworld\n", r.out)
        }

    @Test fun `dash operand reads stdin`() =
        runTest {
            val r = runNl(listOf("-"), stdin = stdin("x\ny\n"))
            assertEquals("${num(1)}\tx\n${num(2)}\ty\n", r.out)
        }

    @Test fun `missing file errors and exits 1`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/ok", "a\n".encodeToByteArray())
            val r = runNl(listOf("/nope", "/ok"), fs = fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("nl: /nope: No such file or directory"))
            assertTrue(r.out.contains("a"))
        }

    @Test fun `multiple files continue numbering`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "x\n".encodeToByteArray())
            fs.writeBytes("/b", "y\n".encodeToByteArray())
            val r = runNl(listOf("/a", "/b"), fs = fs)
            assertEquals("${num(1)}\tx\n${num(2)}\ty\n", r.out)
        }

    // ---------- logical pages ----------

    @Test fun `body delimiter resets numbering by default`() =
        runTest {
            // \:\:\: is header → triggers reset.
            val r = runNl(stdin = stdin("a\nb\n\\:\\:\\:\nc\n"))
            // After header delim: lineNum reset to 1; section=HEADER but body type is the default for body section only.
            // Header type defaults to 'n' (none) — so 'c' lands while we're in HEADER (no number).
            // That's the intent: a header-delim with default -h n produces no numbering for header lines.
            val blank = " ".repeat(6) + " "
            val expected = "${num(1)}\ta\n${num(2)}\tb\n\n${blank}c\n"
            assertEquals(expected, r.out)
        }

    @Test fun `body delimiter switches to body section with numbering`() =
        runTest {
            // \:\: is body delim. After it, body section resumes with type t.
            val r = runNl(stdin = stdin("a\n\\:\\:\nb\nc\n"))
            // First "a" is body (default), num=1. After \:\: we are still in body, no reset (only header resets).
            // Numbering continues.
            val expected = "${num(1)}\ta\n\n${num(2)}\tb\n${num(3)}\tc\n"
            assertEquals(expected, r.out)
        }

    @Test fun `header delim resets and dash p preserves numbering`() =
        runTest {
            val r = runNl(listOf("-p"), stdin = stdin("a\nb\n\\:\\:\\:\nc\n\\:\\:\nd\n"))
            // With -p: no reset on header. Section becomes HEADER (default 'n' = no number) for "c",
            // then \:\: → BODY for "d" (numbered, continues).
            val blank = " ".repeat(6) + " "
            val expected = "${num(1)}\ta\n${num(2)}\tb\n\n${blank}c\n\n${num(3)}\td\n"
            assertEquals(expected, r.out)
        }

    @Test fun `dash h a numbers header lines`() =
        runTest {
            val r = runNl(listOf("-ha"), stdin = stdin("\\:\\:\\:\nh1\nh2\n\\:\\:\nb1\n"))
            // Page starts; \:\:\: switches to HEADER and resets lineNum=1.
            // "h1","h2" header-numbered (a). Then \:\: → body, "b1" numbered.
            val expected = "\n${num(1)}\th1\n${num(2)}\th2\n\n${num(3)}\tb1\n"
            assertEquals(expected, r.out)
        }

    @Test fun `dash f a numbers footer lines`() =
        runTest {
            val r = runNl(listOf("-fa"), stdin = stdin("a\n\\:\nf1\n"))
            // body "a"=1, \: → FOOTER, "f1" footer (a) → 2.
            val expected = "${num(1)}\ta\n\n${num(2)}\tf1\n"
            assertEquals(expected, r.out)
        }

    // ---------- recipes: real one-liners ----------

    @Test fun `recipe number a config file ignoring blanks`() =
        runTest {
            // Default behavior mirrors `nl config.txt`.
            val fs = InMemoryFs()
            fs.writeBytes("/c", "key=1\n\nkey2=2\n# comment\n".encodeToByteArray())
            val r = runNl(listOf("/c"), fs = fs)
            val blank = " ".repeat(6) + " "
            val expected = "${num(1)}\tkey=1\n${blank}\n${num(2)}\tkey2=2\n${num(3)}\t# comment\n"
            assertEquals(expected, r.out)
        }

    @Test fun `recipe nl -ba file mimics cat -n behavior`() =
        runTest {
            val r = runNl(listOf("-ba"), stdin = stdin("alpha\n\ngamma\n"))
            assertEquals("${num(1)}\talpha\n${num(2)}\t\n${num(3)}\tgamma\n", r.out)
        }

    @Test fun `recipe pipe with custom format`() =
        runTest {
            // nl -ba -n rz -w 3 -s ': '
            val r = runNl(listOf("-ba", "-nrz", "-w3", "-s", ": "), stdin = stdin("a\nb\n"))
            assertEquals("001: a\n002: b\n", r.out)
        }

    // ---------- errors ----------

    @Test fun `invalid option errors with exit 2`() =
        runTest {
            val r = runNl(listOf("-Z"), stdin = stdin(""))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("invalid option"))
        }

    @Test fun `invalid number format errors with exit 2`() =
        runTest {
            val r = runNl(listOf("-nxx"), stdin = stdin(""))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("invalid number format") || r.err.contains("number format"))
        }

    @Test fun `invalid body type errors`() =
        runTest {
            val r = runNl(listOf("-bz"), stdin = stdin(""))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("body"))
        }

    @Test fun `double dash ends options`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-b", "z\n".encodeToByteArray())
            val r = runNl(listOf("--", "-b"), fs = fs)
            assertEquals("${num(1)}\tz\n", r.out)
        }

    // ---------- -d delimiter ----------

    @Test fun `dash d sets custom delimiter`() =
        runTest {
            // Custom 2-char delim "@@" → header is "@@@@@@", body "@@@@", footer "@@".
            val r = runNl(listOf("-d@@"), stdin = stdin("a\n@@\nb\n"))
            // "@@" alone is footer; "b" is footer with default -f n.
            val blank = " ".repeat(6) + " "
            val expected = "${num(1)}\ta\n\n${blank}b\n"
            assertEquals(expected, r.out)
        }

    @Test fun `dash d single char defaults second to colon`() =
        runTest {
            // -d '#' → delim is "#:"; footer line is "#:".
            val r = runNl(listOf("-d#"), stdin = stdin("a\n#:\nb\n"))
            val blank = " ".repeat(6) + " "
            val expected = "${num(1)}\ta\n\n${blank}b\n"
            assertEquals(expected, r.out)
        }

    @Test fun `empty input produces no output`() =
        runTest {
            val r = runNl(stdin = stdin(""))
            assertEquals("", r.out)
            assertEquals(0, r.exit)
        }
}
