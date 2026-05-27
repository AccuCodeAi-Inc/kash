package com.accucodeai.kash.tools.cut

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
import kotlin.test.assertTrue

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

private suspend fun runCut(
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
    val res = CutCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class CutCommandTest {
    @Test fun `c 1-3 selects first three chars`() =
        runTest {
            val (rc, out, _) = runCut("abcdef\n", args = arrayOf("-c", "1-3"))
            assertEquals(0, rc)
            assertEquals("abc\n", out)
        }

    @Test fun `c with comma list`() =
        runTest {
            val (_, out, _) = runCut("abcdef\n", args = arrayOf("-c", "1,3,5"))
            assertEquals("ace\n", out)
        }

    @Test fun `c open ended N dash`() =
        runTest {
            val (_, out, _) = runCut("abcdef\n", args = arrayOf("-c", "5-"))
            assertEquals("ef\n", out)
        }

    @Test fun `c open ended dash M`() =
        runTest {
            val (_, out, _) = runCut("abcdef\n", args = arrayOf("-c", "-3"))
            assertEquals("abc\n", out)
        }

    @Test fun `f default delim is TAB`() =
        runTest {
            val (_, out, _) = runCut("a\tb\tc\n", args = arrayOf("-f", "2"))
            assertEquals("b\n", out)
        }

    @Test fun `f with custom delim`() =
        runTest {
            val (_, out, _) = runCut("a:b:c\n", args = arrayOf("-f", "2", "-d", ":"))
            assertEquals("b\n", out)
        }

    @Test fun `f range`() =
        runTest {
            val (_, out, _) = runCut("a:b:c:d:e\n", args = arrayOf("-f", "2-4", "-d:"))
            assertEquals("b:c:d\n", out)
        }

    @Test fun `f comma list`() =
        runTest {
            val (_, out, _) = runCut("a:b:c:d\n", args = arrayOf("-f", "1,3", "-d:"))
            assertEquals("a:c\n", out)
        }

    @Test fun `s suppresses lines without delimiter`() =
        runTest {
            val (_, out, _) = runCut("nodelim\nfoo:bar\n", args = arrayOf("-f", "1", "-d:", "-s"))
            assertEquals("foo\n", out)
        }

    @Test fun `line without delimiter prints whole line by default`() =
        runTest {
            val (_, out, _) = runCut("nodelim\nfoo:bar\n", args = arrayOf("-f", "1", "-d:"))
            assertEquals("nodelim\nfoo\n", out)
        }

    @Test fun `complement with c range`() =
        runTest {
            val (_, out, _) = runCut("abcdef\n", args = arrayOf("--complement", "-c", "2-4"))
            assertEquals("aef\n", out)
        }

    @Test fun `output delimiter for f`() =
        runTest {
            val (_, out, _) =
                runCut("a:b:c\n", args = arrayOf("-f", "1,3", "-d:", "--output-delimiter=,"))
            assertEquals("a,c\n", out)
        }

    @Test fun `multi-file concatenation`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "abc\ndef\n", "/b" to "ghi\n"))
            val (rc, out, _) = runCut(fs = fs, args = arrayOf("-c", "1-2", "/a", "/b"))
            assertEquals(0, rc)
            assertEquals("ab\nde\ngh\n", out)
        }

    @Test fun `dash reads stdin`() =
        runTest {
            val (_, out, _) = runCut("xyz\n", args = arrayOf("-c", "1", "-"))
            assertEquals("x\n", out)
        }

    @Test fun `missing mode flag exits 2`() =
        runTest {
            val (rc, _, err) = runCut("abc\n", args = arrayOf())
            assertEquals(2, rc)
            assertTrue(err.contains("must specify"))
        }

    @Test fun `invalid range zero exits 1`() =
        runTest {
            val (rc, _, err) = runCut("abc\n", args = arrayOf("-c", "0"))
            assertEquals(1, rc)
            assertTrue(err.contains("invalid range"))
        }

    @Test fun `invalid range nonnumeric exits 1`() =
        runTest {
            val (rc, _, err) = runCut("abc\n", args = arrayOf("-c", "abc"))
            assertEquals(1, rc)
            assertTrue(err.contains("invalid range"))
        }

    @Test fun `double dash ends options`() =
        runTest {
            val fs = MemFs(mapOf("/-c" to "hello\n"))
            val (rc, out, _) = runCut(fs = fs, args = arrayOf("-c", "1-3", "--", "/-c"))
            assertEquals(0, rc)
            assertEquals("hel\n", out)
        }

    @Test fun `empty input produces no output`() =
        runTest {
            val (rc, out, _) = runCut("", args = arrayOf("-c", "1-3"))
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun `mixed comma and range list`() =
        runTest {
            val (_, out, _) = runCut("abcdefgh\n", args = arrayOf("-c", "1,3-5,7"))
            assertEquals("acdeg\n", out)
        }

    @Test fun `n flag accepted and ignored`() =
        runTest {
            val (rc, out, _) = runCut("abcdef\n", args = arrayOf("-n", "-c", "1-3"))
            assertEquals(0, rc)
            assertEquals("abc\n", out)
        }

    @Test fun `b treated like c for ASCII`() =
        runTest {
            val (rc, out, _) = runCut("hello\n", args = arrayOf("-b", "2-4"))
            assertEquals(0, rc)
            assertEquals("ell\n", out)
        }
}
