package com.accucodeai.kash.tools.awk

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

/**
 * End-to-end tests for `getline < file` (and friends) running through
 * the real `AwkCommand` against an in-memory virtual filesystem. The
 * command's file opener bridges `ctx.process.fs` to the engine's
 * [AwkLineReader] interface — these tests exercise that wiring.
 */
class AwkCommandFileIoTest {
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

    @Test fun `getline lt file reads the file line by line into dollar zero`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/data.txt", "alpha\nbeta\ngamma\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            // BEGIN-only program (no stdin records) — pulls every line of
            // data.txt and prints it back.
            val src = "BEGIN { while ((getline line < \"data.txt\") > 0) print line }"
            val r = AwkCommand().run(listOf(src), c)
            assertEquals(0, r.exitCode)
            assertEquals("alpha\nbeta\ngamma\n", out.readString())
        }

    @Test fun `getline lt file populates fields when target is omitted`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/cols.txt", "first second third\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            val src = "BEGIN { getline < \"cols.txt\"; print \$2 }"
            AwkCommand().run(listOf(src), c)
            assertEquals("second\n", out.readString())
        }

    @Test fun `getline lt file returns zero at EOF and minus one for missing file`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/one.txt", "only\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            val src =
                """
                BEGIN {
                    r1 = (getline x < "one.txt"); print "r1", r1, x
                    r2 = (getline y < "one.txt"); print "r2", r2
                    r3 = (getline z < "missing.txt"); print "r3", r3
                }
                """.trimIndent()
            AwkCommand().run(listOf(src), c)
            assertEquals("r1 1 only\nr2 0\nr3 -1\n", out.readString())
        }

    @Test fun `getline lt file is sequential across calls on the same path`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/seq.txt", "1\n2\n3\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            // POSIX: repeated `getline < same-file` reads successive
            // records, not the first record over and over. The opener
            // must keep the reader open across calls.
            val src =
                """
                BEGIN {
                    getline a < "seq.txt"
                    getline b < "seq.txt"
                    getline c < "seq.txt"
                    print a, b, c
                }
                """.trimIndent()
            AwkCommand().run(listOf(src), c)
            assertEquals("1 2 3\n", out.readString())
        }

    @Test fun `print gt file writes the file truncating any existing content`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/out.txt", "OLD\n".encodeToByteArray())
            val (c, _, _) = ctx(fs)
            val src = "BEGIN { print \"hello\" > \"out.txt\"; print \"world\" > \"out.txt\" }"
            AwkCommand().run(listOf(src), c)
            // Same target across statements stays open — both lines
            // land in the same file, and the old content is replaced.
            assertEquals("hello\nworld\n", fs.readBytes("/work/out.txt").decodeToString())
        }

    @Test fun `print gtgt file appends to an existing file`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/log.txt", "previous\n".encodeToByteArray())
            val (c, _, _) = ctx(fs)
            val src = "BEGIN { print \"new line\" >> \"log.txt\" }"
            AwkCommand().run(listOf(src), c)
            assertEquals("previous\nnew line\n", fs.readBytes("/work/log.txt").decodeToString())
        }

    @Test fun `print gt with computed path interpolates the destination`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "1\n2\n3\n4\n")
            // Split records into two files based on parity.
            val src =
                """
                { if (${'$'}1 % 2 == 1) print > "odd"; else print > "even" }
                """.trimIndent()
            AwkCommand().run(listOf(src), c)
            assertEquals("1\n3\n", fs.readBytes("/work/odd").decodeToString())
            assertEquals("2\n4\n", fs.readBytes("/work/even").decodeToString())
        }

    @Test fun `close gt file lets the next print gt the same path truncate again`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs)
            // Without close(), the second `>` would keep appending to
            // the same open writer. With close(), the next write
            // re-opens in truncate mode and replaces the file.
            val src =
                """
                BEGIN {
                    print "first"  > "f"
                    close("f")
                    print "second" > "f"
                }
                """.trimIndent()
            AwkCommand().run(listOf(src), c)
            assertEquals("second\n", fs.readBytes("/work/f").decodeToString())
        }

    @Test fun `print pipe to command surfaces a runtime error`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, err) = ctx(fs)
            // Pipe-to-cmd needs process spawn which the shell doesn't
            // expose yet — should fail loudly rather than silently
            // dropping the writes.
            val src = "BEGIN { print \"x\" | \"cat\" }"
            val r = AwkCommand().run(listOf(src), c)
            assertEquals(2, r.exitCode)
            assertTrue("awk:" in err.readString())
        }
}
