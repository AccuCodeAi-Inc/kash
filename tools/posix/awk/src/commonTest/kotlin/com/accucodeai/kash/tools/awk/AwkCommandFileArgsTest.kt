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
 * Tests for positional file arguments and `-f` program-source files on
 * `awk`. File args read through `ctx.process.fs`; `-` is stdin.
 */
class AwkCommandFileArgsTest {
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

    @Test fun `single file argument is read as input`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/in.txt", "alpha\nbeta\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            val r = AwkCommand().run(listOf("{ print NR, \$0 }", "in.txt"), c)
            assertEquals(0, r.exitCode)
            assertEquals("1 alpha\n2 beta\n", out.readString())
        }

    @Test fun `multiple file arguments are concatenated`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "a1\na2\n".encodeToByteArray())
            fs.writeBytes("/work/b.txt", "b1\nb2\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            val r = AwkCommand().run(listOf("{ print NR, \$0 }", "a.txt", "b.txt"), c)
            assertEquals(0, r.exitCode)
            assertEquals("1 a1\n2 a2\n3 b1\n4 b2\n", out.readString())
        }

    @Test fun `bare dash reads from stdin alongside file args`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "file-line\n".encodeToByteArray())
            val (c, out, _) = ctx(fs, stdin = "stdin-line\n")
            val r = AwkCommand().run(listOf("{ print }", "a.txt", "-"), c)
            assertEquals(0, r.exitCode)
            assertEquals("file-line\nstdin-line\n", out.readString())
        }

    @Test fun `missing file argument errors with a clear message`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, err) = ctx(fs)
            val r = AwkCommand().run(listOf("{ print }", "nope.txt"), c)
            assertEquals(2, r.exitCode)
            assertTrue("No such file or directory" in err.readString())
        }

    @Test fun `dash f reads program from file`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/prog.awk", "{ print toupper(\$0) }".encodeToByteArray())
            val (c, out, _) = ctx(fs, stdin = "hello\n")
            val r = AwkCommand().run(listOf("-f", "prog.awk"), c)
            assertEquals(0, r.exitCode)
            assertEquals("HELLO\n", out.readString())
        }

    @Test fun `dash f program plus file argument is treated as input`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/prog.awk", "{ print NR \":\" \$0 }".encodeToByteArray())
            fs.writeBytes("/work/data.txt", "one\ntwo\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            val r = AwkCommand().run(listOf("-f", "prog.awk", "data.txt"), c)
            assertEquals(0, r.exitCode)
            assertEquals("1:one\n2:two\n", out.readString())
        }

    @Test fun `multiple dash f scripts are concatenated`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.awk", "BEGIN { x = 1 }".encodeToByteArray())
            fs.writeBytes("/work/b.awk", "BEGIN { print x + 41 }".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            val r = AwkCommand().run(listOf("-f", "a.awk", "-f", "b.awk"), c)
            assertEquals(0, r.exitCode)
            assertEquals("42\n", out.readString())
        }

    @Test fun `missing dash f source file errors`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, err) = ctx(fs)
            val r = AwkCommand().run(listOf("-f", "nope.awk"), c)
            assertEquals(2, r.exitCode)
            assertTrue("No such file or directory" in err.readString())
        }
}
