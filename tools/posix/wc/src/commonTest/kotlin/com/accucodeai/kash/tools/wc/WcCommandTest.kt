package com.accucodeai.kash.tools.wc

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

private data class WcRun(
    val exit: Int,
    val out: String,
    val err: String,
)

private fun stdin(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runWc(
    args: List<String> = emptyList(),
    stdin: Buffer = Buffer(),
    fs: FileSystem = NullFs(),
): WcRun {
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
    val res = WcCommand().run(args, ctx)
    return WcRun(res.exitCode, out.readString(), err.readString())
}

private fun pad(v: Long): String = v.toString().padStart(7, ' ')

class WcCommandTest {
    @Test fun `default mode on stdin emits lines words bytes no filename`() =
        runTest {
            val r = runWc(stdin = stdin("hello world\nfoo bar baz\n"))
            assertEquals(0, r.exit)
            // 2 lines, 5 words, 24 bytes
            assertEquals("${pad(2)} ${pad(5)} ${pad(24)}\n", r.out)
        }

    @Test fun `dash l only counts lines`() =
        runTest {
            val r = runWc(listOf("-l"), stdin = stdin("a\nb\nc\n"))
            assertEquals("${pad(3)}\n", r.out)
        }

    @Test fun `dash w only counts words`() =
        runTest {
            val r = runWc(listOf("-w"), stdin = stdin("one two  three\nfour\n"))
            assertEquals("${pad(4)}\n", r.out)
        }

    @Test fun `dash c only counts bytes`() =
        runTest {
            val r = runWc(listOf("-c"), stdin = stdin("abcde"))
            assertEquals("${pad(5)}\n", r.out)
        }

    @Test fun `dash m counts UTF-8 codepoints not bytes`() =
        runTest {
            // "héllo" = 5 codepoints but 6 bytes (é is 0xC3 0xA9)
            val r = runWc(listOf("-m"), stdin = stdin("héllo"))
            assertEquals("${pad(5)}\n", r.out)
        }

    @Test fun `dash c and dash m diverge on multibyte input`() =
        runTest {
            val r = runWc(listOf("-mc"), stdin = stdin("héllo"))
            // order: chars then bytes
            assertEquals("${pad(5)} ${pad(6)}\n", r.out)
        }

    @Test fun `combined short flags lwc`() =
        runTest {
            val r = runWc(listOf("-lwc"), stdin = stdin("a b\nc\n"))
            assertEquals("${pad(2)} ${pad(3)} ${pad(6)}\n", r.out)
        }

    @Test fun `double dash ends options`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-l", "hi\n".encodeToByteArray())
            val r = runWc(listOf("--", "-l"), fs = fs)
            // file "-l" — default mode lines/words/bytes
            assertEquals("${pad(1)} ${pad(1)} ${pad(3)} -l\n", r.out)
        }

    @Test fun `no operand reads stdin`() =
        runTest {
            val r = runWc(stdin = stdin("alpha\n"))
            assertEquals("${pad(1)} ${pad(1)} ${pad(6)}\n", r.out)
        }

    @Test fun `single file shows filename`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a.txt", "one two\nthree\n".encodeToByteArray())
            val r = runWc(listOf("/a.txt"), fs = fs)
            assertEquals("${pad(2)} ${pad(3)} ${pad(14)} /a.txt\n", r.out)
        }

    @Test fun `multi file emits total row`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "hi\n".encodeToByteArray()) // 1l 1w 3b
            fs.writeBytes("/b", "yo there\n".encodeToByteArray()) // 1l 2w 9b
            val r = runWc(listOf("/a", "/b"), fs = fs)
            val lines = r.out.trimEnd('\n').split('\n')
            assertEquals(3, lines.size)
            assertEquals("${pad(1)} ${pad(1)} ${pad(3)} /a", lines[0])
            assertEquals("${pad(1)} ${pad(2)} ${pad(9)} /b", lines[1])
            assertEquals("${pad(2)} ${pad(3)} ${pad(12)} total", lines[2])
        }

    @Test fun `missing file errors on stderr and exits 1 but processes others`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/exists", "x\n".encodeToByteArray())
            val r = runWc(listOf("/nope", "/exists"), fs = fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("wc: /nope: No such file or directory"))
            // /exists still emitted, plus total (multi-file mode)
            assertTrue(r.out.contains("/exists"))
            assertTrue(r.out.contains("total"))
        }

    @Test fun `dash operand reads stdin`() =
        runTest {
            val r = runWc(listOf("-l", "-"), stdin = stdin("a\nb\n"))
            assertEquals("${pad(2)} -\n", r.out)
        }

    @Test fun `dash L reports max line length`() =
        runTest {
            val r = runWc(listOf("-L"), stdin = stdin("short\nmuch longer line\nmid\n"))
            assertEquals("${pad(16)}\n", r.out)
        }

    @Test fun `dash L counts codepoints not bytes`() =
        runTest {
            // "héllo" = 5 codepoints
            val r = runWc(listOf("-L"), stdin = stdin("héllo\n"))
            assertEquals("${pad(5)}\n", r.out)
        }

    @Test fun `empty input gives zeros`() =
        runTest {
            val r = runWc(stdin = stdin(""))
            assertEquals("${pad(0)} ${pad(0)} ${pad(0)}\n", r.out)
        }

    @Test fun `trailing line without newline still counts words and bytes`() =
        runTest {
            val r = runWc(stdin = stdin("a b c"))
            // 0 newlines → 0 lines per POSIX, 3 words, 5 bytes
            assertEquals("${pad(0)} ${pad(3)} ${pad(5)}\n", r.out)
        }

    @Test fun `invalid option errors with exit 2`() =
        runTest {
            val r = runWc(listOf("-Z"), stdin = stdin(""))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("invalid option"))
        }

    @Test fun `multiple whitespace types separate words`() =
        runTest {
            val r = runWc(listOf("-w"), stdin = stdin("a\tb  c\r\ndef\n"))
            assertEquals("${pad(6)}\n", r.out)
        }

    @Test fun `column order is lines words chars bytes maxline`() =
        runTest {
            // request all five flags; verify column order is fixed
            val r = runWc(listOf("-Lcmwl"), stdin = stdin("hi there\n"))
            // 1 line, 2 words, 9 chars, 9 bytes, max line 8
            assertEquals(
                "${pad(1)} ${pad(2)} ${pad(9)} ${pad(9)} ${pad(8)}\n",
                r.out,
            )
        }
}
