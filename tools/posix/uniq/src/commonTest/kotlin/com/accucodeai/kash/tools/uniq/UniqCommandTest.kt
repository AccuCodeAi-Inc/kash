package com.accucodeai.kash.tools.uniq

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
import kotlin.test.assertTrue

private fun bufferOf(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runUniq(
    args: List<String>,
    stdin: String = "",
    fs: FileSystem = InMemoryFs(),
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = "/",
            stdin = bufferOf(stdin).asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = UniqCommand().run(args, ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class UniqCommandTest {
    @Test fun `basic adjacent dedupe collapses runs`() =
        runTest {
            val (rc, out, _) = runUniq(emptyList(), stdin = "a\na\nb\nb\nb\nc\na\n")
            assertEquals(0, rc)
            assertEquals("a\nb\nc\na\n", out)
        }

    @Test fun `count flag prefixes width-7 count`() =
        runTest {
            val (_, out, _) = runUniq(listOf("-c"), stdin = "a\na\nb\n")
            assertEquals("      2 a\n      1 b\n", out)
        }

    @Test fun `repeated flag only prints runs of two or more`() =
        runTest {
            val (_, out, _) = runUniq(listOf("-d"), stdin = "a\na\nb\nc\nc\nc\nd\n")
            assertEquals("a\nc\n", out)
        }

    @Test fun `unique flag only prints singletons`() =
        runTest {
            val (_, out, _) = runUniq(listOf("-u"), stdin = "a\na\nb\nc\nc\nd\n")
            assertEquals("b\nd\n", out)
        }

    @Test fun `ignore-case folds comparisons`() =
        runTest {
            val (_, out, _) = runUniq(listOf("-i"), stdin = "Apple\nAPPLE\napple\nBanana\n")
            assertEquals("Apple\nBanana\n", out)
        }

    @Test fun `skip-fields skips first field for comparison`() =
        runTest {
            val input = "1 foo\n2 foo\n3 bar\n"
            val (_, out, _) = runUniq(listOf("-f", "1"), stdin = input)
            assertEquals("1 foo\n3 bar\n", out)
        }

    @Test fun `skip-chars skips first N chars`() =
        runTest {
            // After skipping 2 chars: "xx_same" → "_same" for all three first lines
            val input = "aa_same\nbb_same\ncc_same\nzz_other\n"
            val (_, out, _) = runUniq(listOf("-s", "2"), stdin = input)
            assertEquals("aa_same\nzz_other\n", out)
        }

    @Test fun `check-chars compares only first N chars`() =
        runTest {
            // first 3 chars: "abc" twice then "abx"
            val input = "abcDEF\nabcGHI\nabxJKL\n"
            val (_, out, _) = runUniq(listOf("-w", "3"), stdin = input)
            assertEquals("abcDEF\nabxJKL\n", out)
        }

    @Test fun `zero-terminated mode uses NUL delimiter`() =
        runTest {
            val nul = 0.toChar()
            val input = "a${nul}a${nul}b$nul"
            val (_, out, _) = runUniq(listOf("-z"), stdin = input)
            assertEquals("a${nul}b$nul", out)
        }

    @Test fun `repeated plus unique together emits nothing`() =
        runTest {
            val (rc, out, _) = runUniq(listOf("-d", "-u"), stdin = "a\na\nb\nc\nc\n")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun `two-operand form writes to file via fs sink`() =
        runTest {
            val fs = InMemoryFs()
            // pre-create input file
            fs.writeBytes("/tmp/in.txt", "a\na\nb\nb\nc\n".encodeToByteArray())
            val (rc, out, err) = runUniq(listOf("/tmp/in.txt", "/tmp/out.txt"), fs = fs)
            assertEquals(0, rc)
            assertEquals("", out)
            assertEquals("", err)
            val written = fs.readBytes("/tmp/out.txt").decodeToString()
            assertEquals("a\nb\nc\n", written)
        }

    @Test fun `single dash means stdin`() =
        runTest {
            val (_, out, _) = runUniq(listOf("-"), stdin = "x\nx\ny\n")
            assertEquals("x\ny\n", out)
        }

    @Test fun `double-dash ends options`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/-c", "a\na\nb\n".encodeToByteArray())
            // With --, "-c" is interpreted as a filename, not the count flag.
            val (rc, out, _) = runUniq(listOf("--", "/tmp/-c"), fs = fs)
            assertEquals(0, rc)
            assertEquals("a\nb\n", out)
        }

    @Test fun `empty input yields empty output`() =
        runTest {
            val (rc, out, _) = runUniq(emptyList(), stdin = "")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun `single line passes through`() =
        runTest {
            val (_, out, _) = runUniq(emptyList(), stdin = "only\n")
            assertEquals("only\n", out)
        }

    @Test fun `all-same lines collapse to one`() =
        runTest {
            val (_, out, _) = runUniq(listOf("-c"), stdin = "x\nx\nx\nx\n")
            assertEquals("      4 x\n", out)
        }

    @Test fun `all-different lines pass through unchanged`() =
        runTest {
            val (_, out, _) = runUniq(emptyList(), stdin = "a\nb\nc\nd\n")
            assertEquals("a\nb\nc\nd\n", out)
        }

    @Test fun `combined short flags ci`() =
        runTest {
            val (_, out, _) = runUniq(listOf("-ci"), stdin = "Foo\nFOO\nfoo\nBar\n")
            assertEquals("      3 Foo\n      1 Bar\n", out)
        }

    @Test fun `skip-fields beyond available fields compares empty strings`() =
        runTest {
            // -f 3 on lines with 1 field each → comparison key is "" for all → collapse to one
            val (_, out, _) = runUniq(listOf("-f", "3"), stdin = "a\nb\nc\n")
            assertEquals("a\n", out)
        }

    @Test fun `unknown long option errors`() =
        runTest {
            val (rc, _, err) = runUniq(listOf("--nope"), stdin = "")
            assertEquals(2, rc)
            assertTrue(err.contains("unrecognized option"))
        }

    @Test fun `attached short numeric flag form`() =
        runTest {
            // -f1 = -f 1
            val (_, out, _) = runUniq(listOf("-f1"), stdin = "1 foo\n2 foo\n3 bar\n")
            assertEquals("1 foo\n3 bar\n", out)
        }
}
