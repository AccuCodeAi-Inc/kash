package com.accucodeai.kash.tools.tail

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

private fun stdinOf(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runTail(
    args: List<String>,
    stdin: String = "",
    fs: FileSystem = NullFs(),
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = "/",
            stdin = stdinOf(stdin).asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = TailCommand().run(args, ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

private fun linesUpTo(n: Int): String = (1..n).joinToString("\n", postfix = "\n") { "line$it" }

class TailCommandTest {
    @Test fun `default prints last 10 lines`() =
        runTest {
            val (rc, out, _) = runTail(emptyList(), stdin = linesUpTo(20))
            assertEquals(0, rc)
            val expected = (11..20).joinToString("\n", postfix = "\n") { "line$it" }
            assertEquals(expected, out)
        }

    @Test fun `n flag with space`() =
        runTest {
            val (_, out, _) = runTail(listOf("-n", "3"), stdin = linesUpTo(10))
            assertEquals("line8\nline9\nline10\n", out)
        }

    @Test fun `n zero prints nothing`() =
        runTest {
            val (rc, out, _) = runTail(listOf("-n", "0"), stdin = linesUpTo(5))
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun `n plus2 starts at line 2`() =
        runTest {
            val (_, out, _) = runTail(listOf("-n", "+2"), stdin = linesUpTo(4))
            assertEquals("line2\nline3\nline4\n", out)
        }

    @Test fun `c last 5 bytes`() =
        runTest {
            val (_, out, _) = runTail(listOf("-c", "5"), stdin = "abcdefghij")
            assertEquals("fghij", out)
        }

    @Test fun `c plus 3 from byte 3`() =
        runTest {
            val (_, out, _) = runTail(listOf("-c", "+3"), stdin = "abcdefghij")
            assertEquals("cdefghij", out)
        }

    @Test fun `quiet suppresses headers with multiple files`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "A\n".encodeToByteArray())
            fs.writeBytes("/b", "B\n".encodeToByteArray())
            val (rc, out, _) = runTail(listOf("-q", "/a", "/b"), fs = fs)
            assertEquals(0, rc)
            assertEquals("A\nB\n", out)
        }

    @Test fun `verbose forces header for single file`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "hello\n".encodeToByteArray())
            val (_, out, _) = runTail(listOf("-v", "/a"), fs = fs)
            assertEquals("==> /a <==\nhello\n", out)
        }

    @Test fun `multi-file headers with blank between`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "A1\nA2\n".encodeToByteArray())
            fs.writeBytes("/b", "B1\n".encodeToByteArray())
            val (rc, out, _) = runTail(listOf("/a", "/b"), fs = fs)
            assertEquals(0, rc)
            assertEquals("==> /a <==\nA1\nA2\n\n==> /b <==\nB1\n", out)
        }

    @Test fun `missing file emits error and exits 1 but others succeed`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "A\n".encodeToByteArray())
            val (rc, out, err) = runTail(listOf("/nope", "/a"), fs = fs)
            assertEquals(1, rc)
            assertTrue(err.contains("cannot open '/nope' for reading: No such file or directory"))
            // Header for /a still printed (≥2 operands → auto headers).
            assertTrue(out.contains("==> /a <=="))
            assertTrue(out.contains("A\n"))
        }

    @Test fun `dash reads stdin`() =
        runTest {
            val (_, out, _) = runTail(listOf("-n", "2", "-"), stdin = linesUpTo(5))
            assertEquals("line4\nline5\n", out)
        }

    @Test fun `obsolete dash5 form`() =
        runTest {
            val (_, out, _) = runTail(listOf("-5"), stdin = linesUpTo(20))
            val expected = (16..20).joinToString("\n", postfix = "\n") { "line$it" }
            assertEquals(expected, out)
        }

    @Test fun `obsolete plus5 form`() =
        runTest {
            val (_, out, _) = runTail(listOf("+5"), stdin = linesUpTo(6))
            assertEquals("line5\nline6\n", out)
        }

    @Test fun `long lines flag`() =
        runTest {
            val (_, out, _) = runTail(listOf("--lines=4"), stdin = linesUpTo(10))
            assertEquals("line7\nline8\nline9\nline10\n", out)
        }

    @Test fun `double dash ends options`() =
        runTest {
            val fs = InMemoryFs()
            // File literally named "-v" — must be treated as a path, not the -v flag.
            fs.writeBytes("/-v", "x\n".encodeToByteArray())
            val (rc, out, _) = runTail(listOf("--", "/-v"), fs = fs)
            assertEquals(0, rc)
            assertEquals("x\n", out)
        }

    @Test fun `empty input produces empty output`() =
        runTest {
            val (rc, out, _) = runTail(emptyList(), stdin = "")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun `exactly N lines emits all of them`() =
        runTest {
            val (_, out, _) = runTail(listOf("-n", "5"), stdin = linesUpTo(5))
            assertEquals(linesUpTo(5), out)
        }

    @Test fun `attached -nN form`() =
        runTest {
            val (_, out, _) = runTail(listOf("-n3"), stdin = linesUpTo(10))
            assertEquals("line8\nline9\nline10\n", out)
        }

    @Test fun `attached n plus2 form`() =
        runTest {
            val (_, out, _) = runTail(listOf("-n+2"), stdin = linesUpTo(4))
            assertEquals("line2\nline3\nline4\n", out)
        }

    @Test fun `bytes long flag`() =
        runTest {
            val (_, out, _) = runTail(listOf("--bytes=4"), stdin = "abcdefg")
            assertEquals("defg", out)
        }

    @Test fun `single file no header by default`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "hello\n".encodeToByteArray())
            val (_, out, _) = runTail(listOf("/a"), fs = fs)
            assertEquals("hello\n", out)
        }
}
