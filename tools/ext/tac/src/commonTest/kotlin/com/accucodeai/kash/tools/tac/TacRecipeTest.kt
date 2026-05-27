package com.accucodeai.kash.tools.tac

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

private data class TacRun(
    val exit: Int,
    val out: String,
    val err: String,
)

private fun stdinOf(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runTac(
    args: List<String> = emptyList(),
    stdin: Buffer = Buffer(),
    fs: FileSystem = NullFs(),
): TacRun {
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
    val res = TacCommand().run(args, ctx)
    return TacRun(res.exitCode, out.readString(), err.readString())
}

class TacRecipeTest {
    @Test fun `basic 3 line file reversed`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a.txt", "a\nb\nc\n".encodeToByteArray())
            val r = runTac(listOf("/a.txt"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("c\nb\na\n", r.out)
        }

    @Test fun `stdin reverse default`() =
        runTest {
            val r = runTac(stdin = stdinOf("one\ntwo\nthree\n"))
            assertEquals(0, r.exit)
            assertEquals("three\ntwo\none\n", r.out)
        }

    @Test fun `two files concatenated then reversed as unit`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/x", "1\n2\n".encodeToByteArray())
            fs.writeBytes("/y", "3\n4\n".encodeToByteArray())
            val r = runTac(listOf("/x", "/y"), fs = fs)
            assertEquals(0, r.exit)
            // concat = "1\n2\n3\n4\n", reversed = "4\n3\n2\n1\n"
            assertEquals("4\n3\n2\n1\n", r.out)
        }

    @Test fun `input without trailing newline last partial line emits first`() =
        runTest {
            val r = runTac(stdin = stdinOf("a\nb\nc"))
            assertEquals(0, r.exit)
            // records=[a,b,c], seps=[\n,\n] -> tokens [a\n, b\n, c] -> reverse "cb\na\n"
            assertEquals("cb\na\n", r.out)
        }

    @Test fun `custom separator -s colon`() =
        runTest {
            val r = runTac(listOf("-s", ":"), stdin = stdinOf("a:b:c"))
            assertEquals(0, r.exit)
            // records=[a,b,c], seps=[:,:] -> tokens [a:, b:, c] -> reverse "cb:a:"
            assertEquals("cb:a:", r.out)
        }

    @Test fun `before mode -b attaches separator to front`() =
        runTest {
            val r = runTac(listOf("-b"), stdin = stdinOf("a\nb\nc\n"))
            assertEquals(0, r.exit)
            // records=[a,b,c,""], seps=[\n,\n,\n]
            // before: [a, \nb, \nc, \n] -> reverse "\n\nc\nba"
            assertEquals("\n\nc\nba", r.out)
        }

    @Test fun `regex separator -r matches variable length digits`() =
        runTest {
            val r = runTac(listOf("-r", "-s", "[0-9]+"), stdin = stdinOf("foo1bar22baz"))
            assertEquals(0, r.exit)
            // records=[foo, bar, baz], seps=[1, 22]
            // tokens [foo1, bar22, baz] -> reverse "bazbar22foo1"
            assertEquals("bazbar22foo1", r.out)
        }

    @Test fun `dash operand reads stdin`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/f", "X\nY\n".encodeToByteArray())
            val r = runTac(listOf("/f", "-"), stdin = stdinOf("Z\n"), fs = fs)
            assertEquals(0, r.exit)
            // concat = "X\nY\nZ\n" -> reverse = "Z\nY\nX\n"
            assertEquals("Z\nY\nX\n", r.out)
        }

    @Test fun `empty input emits nothing`() =
        runTest {
            val r = runTac(stdin = stdinOf(""))
            assertEquals(0, r.exit)
            assertEquals("", r.out)
        }

    @Test fun `long form --before and --separator`() =
        runTest {
            val r = runTac(listOf("--before", "--separator=|"), stdin = stdinOf("a|b|c"))
            assertEquals(0, r.exit)
            // records=[a,b,c], seps=[|,|]
            // before: [a, |b, |c] -> reverse "|c|ba"
            assertEquals("|c|ba", r.out)
        }

    @Test fun `missing file is reported and other operands still process`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/ok", "1\n2\n".encodeToByteArray())
            val r = runTac(listOf("/nope", "/ok"), fs = fs)
            assertEquals(1, r.exit)
            assertEquals("2\n1\n", r.out)
            assertEquals("tac: /nope: No such file or directory\n", r.err)
        }

    @Test fun `double dash ends option parsing`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-b", "1\n2\n".encodeToByteArray())
            val r = runTac(listOf("--", "-b"), fs = fs)
            assertEquals(0, r.exit)
            // treat "-b" as filename, not flag
            assertEquals("2\n1\n", r.out)
        }

    @Test fun `single line with newline reverses to itself`() =
        runTest {
            val r = runTac(stdin = stdinOf("only\n"))
            assertEquals(0, r.exit)
            assertEquals("only\n", r.out)
        }

    @Test fun `single line without newline emits as-is`() =
        runTest {
            val r = runTac(stdin = stdinOf("only"))
            assertEquals(0, r.exit)
            assertEquals("only", r.out)
        }

    @Test fun `multichar separator`() =
        runTest {
            val r = runTac(listOf("-s", "::"), stdin = stdinOf("a::b::c::"))
            assertEquals(0, r.exit)
            // records=[a,b,c,""], seps=[::,::,::]
            // after: [a::, b::, c::, ""] -> reverse "c::b::a::"
            assertEquals("c::b::a::", r.out)
        }

    @Test fun `empty separator is an error`() =
        runTest {
            val r = runTac(listOf("-s", ""), stdin = stdinOf("abc"))
            assertEquals(1, r.exit)
        }

    @Test fun `unknown option errors with exit 2`() =
        runTest {
            val r = runTac(listOf("-Q"), stdin = stdinOf("a\n"))
            assertEquals(2, r.exit)
        }
}
