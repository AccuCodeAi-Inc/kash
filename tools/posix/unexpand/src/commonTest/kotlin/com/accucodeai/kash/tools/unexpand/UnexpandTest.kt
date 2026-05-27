package com.accucodeai.kash.tools.unexpand

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

private suspend fun runUnexpand(
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
    val res = UnexpandCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class UnexpandTest {
    @Test fun `empty input produces empty output`() =
        runTest {
            val (rc, out, _) = runUnexpand("")
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun `default 8 leading spaces become one tab`() =
        runTest {
            val (_, out, _) = runUnexpand("        x\n")
            assertEquals("\tx\n", out)
        }

    @Test fun `7 leading spaces stay as spaces`() =
        runTest {
            val (_, out, _) = runUnexpand("       x\n")
            assertEquals("       x\n", out)
        }

    @Test fun `16 leading spaces become 2 tabs`() =
        runTest {
            val (_, out, _) = runUnexpand("                x\n")
            assertEquals("\t\tx\n", out)
        }

    @Test fun `10 leading spaces become tab plus 2 spaces`() =
        runTest {
            val (_, out, _) = runUnexpand("          x\n")
            assertEquals("\t  x\n", out)
        }

    @Test fun `default does not touch interior whitespace`() =
        runTest {
            val (_, out, _) = runUnexpand("hi        there\n")
            // leading-only mode → spaces between 'hi' and 'there' preserved.
            assertEquals("hi        there\n", out)
        }

    @Test fun `-a converts all whitespace runs`() =
        runTest {
            // 'hi' at col 0..1, then 6 spaces moves to col 8 → 1 tab. Then 'there'.
            val (_, out, _) = runUnexpand("hi      there\n", args = arrayOf("-a"))
            assertEquals("hi\tthere\n", out)
        }

    @Test fun `-a with non-stop-crossing spaces leaves them`() =
        runTest {
            // 'h' at col 0, then 3 spaces at cols 1..3 — no tab stop crossed (next stop is 8).
            val (_, out, _) = runUnexpand("h   x\n", args = arrayOf("-a"))
            assertEquals("h   x\n", out)
        }

    @Test fun `-t 4 with -a`() =
        runTest {
            // -t implies -a. 'hi' col 0..1, 2 spaces → col 4 (1 tab). 'there'.
            val (_, out, _) = runUnexpand("hi  there\n", args = arrayOf("-t", "4"))
            assertEquals("hi\tthere\n", out)
        }

    @Test fun `-t list with multiple stops`() =
        runTest {
            // stops 3, 7. Leading 3 spaces → tab (crosses col 3). Then 4 spaces → tab (crosses col 7).
            val (_, out, _) = runUnexpand("       X\n", args = arrayOf("-t", "3,7"))
            assertEquals("\t\tX\n", out)
        }

    @Test fun `existing tabs preserved when aligned`() =
        runTest {
            // Input has a tab already (col 0 → 8). Output should keep it.
            val (_, out, _) = runUnexpand("\tx\n")
            assertEquals("\tx\n", out)
        }

    @Test fun `multiple file args concatenated`() =
        runTest {
            val fs = MemFs(mapOf("/a" to "        a\n", "/b" to "        b\n"))
            val (rc, out, _) = runUnexpand(fs = fs, args = arrayOf("/a", "/b"))
            assertEquals(0, rc)
            assertEquals("\ta\n\tb\n", out)
        }

    @Test fun `dash means stdin`() =
        runTest {
            val (_, out, _) = runUnexpand("        x\n", args = arrayOf("-"))
            assertEquals("\tx\n", out)
        }

    @Test fun `no file arg reads stdin`() =
        runTest {
            val (_, out, _) = runUnexpand("        y\n")
            assertEquals("\ty\n", out)
        }

    @Test fun `missing file returns exit 1`() =
        runTest {
            val (rc, _, err) = runUnexpand(args = arrayOf("/nope"))
            assertEquals(1, rc)
            assertTrue(err.contains("No such file"))
        }

    @Test fun `invalid -t value returns exit 2`() =
        runTest {
            val (rc, _, _) = runUnexpand(args = arrayOf("-t", "abc"))
            assertEquals(2, rc)
        }

    @Test fun `unicode codepoints count as one column`() =
        runTest {
            // 'é' at col 0, then 7 spaces puts us at col 8 (one tab stop).
            val (_, out, _) = runUnexpand("é       x\n", args = arrayOf("-a"))
            assertEquals("é\tx\n", out)
        }

    @Test fun `preserves trailing-newline-less line`() =
        runTest {
            val (_, out, _) = runUnexpand("        a")
            assertEquals("\ta", out)
        }

    @Test fun `multiple lines processed independently`() =
        runTest {
            val (_, out, _) = runUnexpand("        a\n        b\n")
            assertEquals("\ta\n\tb\n", out)
        }

    @Test fun `leading tab plus spaces`() =
        runTest {
            // tab then 3 spaces: col 0 → 8 (tab) → 9,10,11. No second stop crossed.
            val (_, out, _) = runUnexpand("\t   x\n")
            assertEquals("\t   x\n", out)
        }

    @Test fun `recipe round-trip with expand for uniform stops`() =
        runTest {
            // unexpand(expand(input,-t8),-t8) should be a fixed point for typical
            // indented text. Smoke test with 3-line file.
            val (_, out, _) = runUnexpand("\thello\n\t\tworld\n\t\t\tfoo\n")
            assertEquals("\thello\n\t\tworld\n\t\t\tfoo\n", out)
        }

    @Test fun `leading-only mode does not touch internal aligned spaces`() =
        runTest {
            val (_, out, _) = runUnexpand("        a        b\n")
            // First 8 spaces become tab; the 8 spaces between 'a' and 'b' stay
            // because we're in leading-only mode.
            assertEquals("\ta        b\n", out)
        }
}
