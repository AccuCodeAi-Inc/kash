package com.accucodeai.kash.tools.paste

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

private data class PasteRun(
    val exit: Int,
    val out: String,
    val err: String,
)

private suspend fun runPaste(
    args: List<String> = emptyList(),
    input: String = "",
    fs: FileSystem = MemFs(),
): PasteRun {
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
    val r = PasteCommand().run(args, ctx)
    return PasteRun(r.exitCode, out.readString(), err.readString())
}

class PasteRecipeTest {
    @Test fun `two files parallel tab default`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "a1\na2\na3\n", "/b" to "b1\nb2\nb3\n"))
            val r = runPaste(listOf("/a", "/b"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("a1\tb1\na2\tb2\na3\tb3\n", r.out)
        }

    @Test fun `custom comma delim`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "1\n2\n", "/b" to "x\ny\n"))
            val r = runPaste(listOf("-d", ",", "/a", "/b"), fs = fs)
            assertEquals("1,x\n2,y\n", r.out)
        }

    @Test fun `cycled delim list three files`() =
        runTest {
            // -d '\t,' cycles tab then comma between successive column boundaries.
            // 3 files → 2 boundaries per row: first tab, then comma.
            val fs = MemFs(mapOf("/a" to "1\n", "/b" to "2\n", "/c" to "3\n"))
            val r = runPaste(listOf("-d", "\\t,", "/a", "/b", "/c"), fs = fs)
            assertEquals("1\t2,3\n", r.out)
        }

    @Test fun `space delim`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "hello\n", "/b" to "world\n"))
            val r = runPaste(listOf("-d", " ", "/a", "/b"), fs = fs)
            assertEquals("hello world\n", r.out)
        }

    @Test fun `unequal length pads with empty`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "a1\na2\na3\n", "/b" to "b1\n"))
            val r = runPaste(listOf("/a", "/b"), fs = fs)
            assertEquals("a1\tb1\na2\t\na3\t\n", r.out)
        }

    @Test fun `unequal length first shorter`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "a1\n", "/b" to "b1\nb2\nb3\n"))
            val r = runPaste(listOf("/a", "/b"), fs = fs)
            assertEquals("a1\tb1\n\tb2\n\tb3\n", r.out)
        }

    @Test fun `serial mode single file default delim`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "x\ny\nz\n"))
            val r = runPaste(listOf("-s", "/a"), fs = fs)
            assertEquals("x\ty\tz\n", r.out)
        }

    @Test fun `serial mode with comma delim`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "x\ny\nz\n"))
            val r = runPaste(listOf("-s", "-d", ",", "/a"), fs = fs)
            assertEquals("x,y,z\n", r.out)
        }

    @Test fun `serial mode two files emits two rows`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "1\n2\n", "/b" to "x\ny\nz\n"))
            val r = runPaste(listOf("-s", "-d", ",", "/a", "/b"), fs = fs)
            assertEquals("1,2\nx,y,z\n", r.out)
        }

    @Test fun `serial mode cycled delim list`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "1\n2\n3\n4\n5\n"))
            // Delim list ":-", boundaries: 1:2-3:4-5
            val r = runPaste(listOf("-s", "-d", ":-", "/a"), fs = fs)
            assertEquals("1:2-3:4-5\n", r.out)
        }

    @Test fun `stdin via dash`() =
        runTest {
            val r = runPaste(listOf("-"), input = "a\nb\nc\n")
            assertEquals("a\nb\nc\n", r.out)
        }

    @Test fun `paste dash dash reads stdin twice in parallel`() =
        runTest {
            // Each `-` shares stdin. Lines consumed in turn: col0 gets line1,
            // col1 gets line2, then col0 gets line3, col1 gets line4.
            val r = runPaste(listOf("-", "-"), input = "1\n2\n3\n4\n")
            assertEquals("1\t2\n3\t4\n", r.out)
        }

    @Test fun `paste dash dash odd stdin lines pads`() =
        runTest {
            val r = runPaste(listOf("-", "-"), input = "1\n2\n3\n")
            assertEquals("1\t2\n3\t\n", r.out)
        }

    @Test fun `three way parallel paste`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "a\n", "/b" to "b\n", "/c" to "c\n"))
            val r = runPaste(listOf("/a", "/b", "/c"), fs = fs)
            assertEquals("a\tb\tc\n", r.out)
        }

    @Test fun `no operands reads stdin in parallel single column`() =
        runTest {
            val r = runPaste(input = "hello\nworld\n")
            assertEquals("hello\nworld\n", r.out)
        }

    @Test fun `escape n in delim spec`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "1\n", "/b" to "2\n"))
            val r = runPaste(listOf("-d", "\\n", "/a", "/b"), fs = fs)
            assertEquals("1\n2\n", r.out)
        }

    @Test fun `escape 0 in delim spec means no separator`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "ab\n", "/b" to "cd\n"))
            val r = runPaste(listOf("-d", "\\0", "/a", "/b"), fs = fs)
            assertEquals("abcd\n", r.out)
        }

    @Test fun `double dash ends options`() =
        runTest {
            val fs = MemFs(mapOf("/-s" to "x\ny\n"))
            // Without --, "-s" would be the serial flag. With --, treat as filename.
            val r = runPaste(listOf("--", "-s"), fs = fs)
            assertEquals("x\ny\n", r.out)
        }

    @Test fun `missing file errors with exit 1`() =
        runTest {
            val r = runPaste(listOf("/nope"), fs = MemFs())
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("paste: /nope: No such file or directory"))
        }

    @Test fun `unknown option exits 2`() =
        runTest {
            val r = runPaste(listOf("-Z"))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("invalid option"))
        }

    @Test fun `dash d missing argument exits 2`() =
        runTest {
            val r = runPaste(listOf("-d"))
            assertEquals(2, r.exit)
        }

    @Test fun `dash d attached form`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "1\n", "/b" to "2\n"))
            val r = runPaste(listOf("-d,", "/a", "/b"), fs = fs)
            assertEquals("1,2\n", r.out)
        }

    @Test fun `tab escape literal in delim`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "1\n", "/b" to "2\n"))
            val r = runPaste(listOf("-d", "\\t", "/a", "/b"), fs = fs)
            assertEquals("1\t2\n", r.out)
        }

    @Test fun `empty files in parallel produce nothing`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "", "/b" to ""))
            val r = runPaste(listOf("/a", "/b"), fs = fs)
            assertEquals("", r.out)
        }

    @Test fun `serial empty file emits one empty line`() =
        runTest {
            val fs = MemFs(mapOf("/a" to ""))
            val r = runPaste(listOf("-s", "/a"), fs = fs)
            assertEquals("\n", r.out)
        }

    @Test fun `cycled delim 4 column row`() =
        runTest {
            // delim list ":-" with 4 files: boundaries cycle :-: (b1=':' b2='-' b3=':')
            val fs =
                MemFs(
                    mapOf(
                        "/a" to "1\n",
                        "/b" to "2\n",
                        "/c" to "3\n",
                        "/d" to "4\n",
                    ),
                )
            val r = runPaste(listOf("-d", ":-", "/a", "/b", "/c", "/d"), fs = fs)
            assertEquals("1:2-3:4\n", r.out)
        }
}
