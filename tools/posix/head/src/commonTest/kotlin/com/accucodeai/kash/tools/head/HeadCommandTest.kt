package com.accucodeai.kash.tools.head

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadCommandTest {
    private fun ctx(
        fs: InMemoryFs = InMemoryFs(),
        stdin: String = "",
    ): Triple<CommandContext, Buffer, Buffer> {
        val out = Buffer()
        val err = Buffer()
        val inBuf = Buffer()
        if (stdin.isNotEmpty()) inBuf.writeString(stdin)
        return Triple(
            bareCommandContext(
                fs,
                mutableMapOf(),
                "/work",
                inBuf.asSuspendSource(),
                out.asSuspendSink(),
                err.asSuspendSink(),
            ),
            out,
            err,
        )
    }

    private fun lines(
        n: Int,
        prefix: String = "line",
    ): String = (1..n).joinToString("\n") { "$prefix$it" } + "\n"

    @Test fun `default prints first 10 lines`() =
        runTest {
            val (c, out, _) = ctx(stdin = lines(20))
            val r = HeadCommand().run(emptyList(), c)
            assertEquals(0, r.exitCode)
            assertEquals(lines(10), out.readString())
        }

    @Test fun `dash n 3 prints three lines`() =
        runTest {
            val (c, out, _) = ctx(stdin = lines(20))
            HeadCommand().run(listOf("-n", "3"), c)
            assertEquals("line1\nline2\nline3\n", out.readString())
        }

    @Test fun `dash n 0 prints nothing`() =
        runTest {
            val (c, out, _) = ctx(stdin = lines(5))
            val r = HeadCommand().run(listOf("-n", "0"), c)
            assertEquals(0, r.exitCode)
            assertEquals("", out.readString())
        }

    @Test fun `dash n negative drops last n lines`() =
        runTest {
            val (c, out, _) = ctx(stdin = lines(5))
            HeadCommand().run(listOf("-n", "-2"), c)
            assertEquals("line1\nline2\nline3\n", out.readString())
        }

    @Test fun `dash c 5 prints first five bytes`() =
        runTest {
            val (c, out, _) = ctx(stdin = "abcdefghij")
            HeadCommand().run(listOf("-c", "5"), c)
            assertEquals("abcde", out.readString())
        }

    @Test fun `dash c negative drops last n bytes`() =
        runTest {
            val (c, out, _) = ctx(stdin = "abcdefghij")
            HeadCommand().run(listOf("-c", "-3"), c)
            assertEquals("abcdefg", out.readString())
        }

    @Test fun `dash q suppresses headers with multiple files`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "A1\nA2\n".encodeToByteArray())
            fs.writeBytes("/work/b.txt", "B1\nB2\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            HeadCommand().run(listOf("-q", "/work/a.txt", "/work/b.txt"), c)
            assertEquals("A1\nA2\nB1\nB2\n", out.readString())
        }

    @Test fun `dash v forces header for single file`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/only.txt", "hi\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            HeadCommand().run(listOf("-v", "/work/only.txt"), c)
            assertEquals("==> /work/only.txt <==\nhi\n", out.readString())
        }

    @Test fun `multiple files emit header blocks with blank separator`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "A1\n".encodeToByteArray())
            fs.writeBytes("/work/b.txt", "B1\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            HeadCommand().run(listOf("/work/a.txt", "/work/b.txt"), c)
            assertEquals(
                "==> /work/a.txt <==\nA1\n\n==> /work/b.txt <==\nB1\n",
                out.readString(),
            )
        }

    @Test fun `missing file errors but other files still processed`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/good.txt", "ok\n".encodeToByteArray())
            val (c, out, err) = ctx(fs)
            val r = HeadCommand().run(listOf("/work/missing.txt", "/work/good.txt"), c)
            assertEquals(1, r.exitCode)
            val errStr = err.readString()
            assertTrue("cannot open '/work/missing.txt'" in errStr, "got: $errStr")
            val outStr = out.readString()
            assertTrue("ok" in outStr, "expected good file processed, got: $outStr")
        }

    @Test fun `dash operand reads stdin`() =
        runTest {
            val (c, out, _) = ctx(stdin = lines(3))
            HeadCommand().run(listOf("-n", "2", "-"), c)
            assertEquals("line1\nline2\n", out.readString())
        }

    @Test fun `obsolete dash N form`() =
        runTest {
            val (c, out, _) = ctx(stdin = lines(20))
            HeadCommand().run(listOf("-5"), c)
            assertEquals("line1\nline2\nline3\nline4\nline5\n", out.readString())
        }

    @Test fun `double dash ends options`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/-n", "weird\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            val r = HeadCommand().run(listOf("--", "/work/-n"), c)
            assertEquals(0, r.exitCode)
            assertEquals("weird\n", out.readString())
        }

    @Test fun `combined dash n3 form`() =
        runTest {
            val (c, out, _) = ctx(stdin = lines(10))
            HeadCommand().run(listOf("-n3"), c)
            assertEquals("line1\nline2\nline3\n", out.readString())
        }

    @Test fun `long option lines equals`() =
        runTest {
            val (c, out, _) = ctx(stdin = lines(10))
            HeadCommand().run(listOf("--lines=4"), c)
            assertEquals("line1\nline2\nline3\nline4\n", out.readString())
        }

    @Test fun `empty input produces empty output`() =
        runTest {
            val (c, out, _) = ctx(stdin = "")
            val r = HeadCommand().run(emptyList(), c)
            assertEquals(0, r.exitCode)
            assertEquals("", out.readString())
        }

    @Test fun `exactly N lines input`() =
        runTest {
            val (c, out, _) = ctx(stdin = lines(10))
            HeadCommand().run(emptyList(), c)
            assertEquals(lines(10), out.readString())
        }

    @Test fun `last flag wins between n and c`() =
        runTest {
            // -n 5 then -c 3 → byte mode wins
            val (c, out, _) = ctx(stdin = "abcdefghij")
            HeadCommand().run(listOf("-n", "5", "-c", "3"), c)
            assertEquals("abc", out.readString())
        }

    @Test fun `combined dash c form`() =
        runTest {
            val (c, out, _) = ctx(stdin = "abcdefghij")
            HeadCommand().run(listOf("-c4"), c)
            assertEquals("abcd", out.readString())
        }

    @Test fun `bytes negative larger than input gives empty output`() =
        runTest {
            val (c, out, _) = ctx(stdin = "abc")
            HeadCommand().run(listOf("-c", "-10"), c)
            assertEquals("", out.readString())
        }

    @Test fun `lines negative larger than input gives empty output`() =
        runTest {
            val (c, out, _) = ctx(stdin = lines(3))
            HeadCommand().run(listOf("-n", "-10"), c)
            assertEquals("", out.readString())
        }

    @Test fun `single file no header by default`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/only.txt", "hi\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            HeadCommand().run(listOf("/work/only.txt"), c)
            assertEquals("hi\n", out.readString())
        }

    @Test fun `invalid number errors with exit one`() =
        runTest {
            val (c, _, err) = ctx(stdin = "x")
            val r = HeadCommand().run(listOf("-n", "abc"), c)
            assertEquals(1, r.exitCode)
            assertTrue("invalid number of lines" in err.readString())
        }
}
