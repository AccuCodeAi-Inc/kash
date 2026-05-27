package com.accucodeai.kash.tools.expand

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

private suspend fun runExpand(
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
    val res = ExpandCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class ExpandTest {
    @Test fun `empty input produces empty output`() =
        runTest {
            val (rc, out, _) = runExpand("")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun `default tab stops every 8 cols`() =
        runTest {
            val (_, out, _) = runExpand("a\tb\n")
            assertEquals("a       b\n", out)
        }

    @Test fun `tab at column 0 expands to 8 spaces`() =
        runTest {
            val (_, out, _) = runExpand("\tx\n")
            assertEquals("        x\n", out)
        }

    @Test fun `tab at column boundary expands to next full stop`() =
        runTest {
            // 8 chars, then tab → should expand to 8 more spaces (col 8→16)
            val (_, out, _) = runExpand("12345678\tX\n")
            assertEquals("12345678        X\n", out)
        }

    @Test fun `multiple tabs in sequence`() =
        runTest {
            val (_, out, _) = runExpand("\t\tX\n")
            assertEquals("                X\n", out)
        }

    @Test fun `-t 4 uses uniform 4-column stops`() =
        runTest {
            val (_, out, _) = runExpand("a\tb\n", args = arrayOf("-t", "4"))
            assertEquals("a   b\n", out)
        }

    @Test fun `-t with attached value`() =
        runTest {
            val (_, out, _) = runExpand("a\tb\n", args = arrayOf("-t4"))
            assertEquals("a   b\n", out)
        }

    @Test fun `-t list with multiple stops`() =
        runTest {
            // stops 3, 7, 10 — at col 0 tab → col 3 (3 spaces).
            val (_, out, _) = runExpand("\tX\n", args = arrayOf("-t", "3,7,10"))
            assertEquals("   X\n", out)
        }

    @Test fun `-t list past last stop falls back to single space`() =
        runTest {
            // After col 10, a tab is just 1 space.
            val (_, out, _) = runExpand("0123456789AB\tX\n", args = arrayOf("-t", "3,7,10"))
            assertEquals("0123456789AB X\n", out)
        }

    @Test fun `-i only expands leading tabs`() =
        runTest {
            val (_, out, _) = runExpand("\t\tfoo\tbar\n", args = arrayOf("-i"))
            assertEquals("                foo\tbar\n", out)
        }

    @Test fun `-i with non-tab leading char does not affect`() =
        runTest {
            val (_, out, _) = runExpand("x\ty\n", args = arrayOf("-i"))
            // 'x' is non-blank → leading run is empty (we treat tab in middle as non-leading)
            assertEquals("x\ty\n", out)
        }

    @Test fun `-i preserves leading spaces`() =
        runTest {
            // Leading spaces don't count as non-leading; tab still expands.
            val (_, out, _) = runExpand("  \tx\n", args = arrayOf("-i"))
            assertEquals("        x\n", out)
        }

    @Test fun `multiple file args concatenated`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "x\ty\n", "/b" to "p\tq\n"))
            val (rc, out, _) = runExpand(fs = fs, args = arrayOf("/a", "/b"))
            assertEquals(0, rc)
            assertEquals("x       y\np       q\n", out)
        }

    @Test fun `dash means stdin`() =
        runTest {
            val (_, out, _) = runExpand("a\tb\n", args = arrayOf("-"))
            assertEquals("a       b\n", out)
        }

    @Test fun `no file arg reads stdin`() =
        runTest {
            val (_, out, _) = runExpand("a\tb\n")
            assertEquals("a       b\n", out)
        }

    @Test fun `missing file returns exit 1`() =
        runTest {
            val (rc, _, err) = runExpand(args = arrayOf("/nope"))
            assertEquals(1, rc)
            assertTrue(err.contains("No such file"))
        }

    @Test fun `invalid -t value returns exit 2`() =
        runTest {
            val (rc, _, err) = runExpand(args = arrayOf("-t", "abc"))
            assertEquals(2, rc)
            assertTrue(err.isNotEmpty())
        }

    @Test fun `unicode codepoints count as one column`() =
        runTest {
            // 'é' is one codepoint; default stop is 8, so 'é\tX' → 'é' at col 1, tab to col 8.
            val (_, out, _) = runExpand("é\tX\n")
            assertEquals("é       X\n", out)
        }

    @Test fun `preserves trailing-newline-less line`() =
        runTest {
            val (_, out, _) = runExpand("a\tb")
            assertEquals("a       b", out)
        }

    @Test fun `multiple lines processed independently`() =
        runTest {
            val (_, out, _) = runExpand("a\tb\nc\td\n")
            assertEquals("a       b\nc       d\n", out)
        }

    @Test fun `legacy dash-N tab-size syntax`() =
        runTest {
            val (_, out, _) = runExpand("a\tb\n", args = arrayOf("-4"))
            assertEquals("a   b\n", out)
        }

    @Test fun `mixed -i and tabs in middle preserved as tab`() =
        runTest {
            val (_, out, _) = runExpand("  hello\tworld\n", args = arrayOf("-i"))
            // 2 leading spaces, then 'h' (non-blank) ends leading; tab between
            // 'hello' and 'world' should remain literal.
            assertEquals("  hello\tworld\n", out)
        }

    @Test fun `tab list with single value uses uniform`() =
        runTest {
            val (_, out, _) = runExpand("a\tb\n", args = arrayOf("-t", "5"))
            assertEquals("a    b\n", out)
        }

    @Test fun `combined -i and -t`() =
        runTest {
            val (_, out, _) = runExpand("\tfoo\tbar\n", args = arrayOf("-i", "-t", "4"))
            assertEquals("    foo\tbar\n", out)
        }
}
