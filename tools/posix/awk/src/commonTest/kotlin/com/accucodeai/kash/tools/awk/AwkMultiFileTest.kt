package com.accucodeai.kash.tools.awk

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals

/** Collect a Flow into a single concatenated string. Suspending so
 *  tests can call it from a `runTest { ... }` block. */
private suspend fun Flow<String>.joinAll(): String {
    val sb = StringBuilder()
    collect { sb.append(it) }
    return sb.toString()
}

/**
 * Multi-file input semantics — FNR resets per file, FILENAME tracks the
 * current file, `nextfile` skips the rest of the current file, and bare
 * `getline` advances across file boundaries. Exercised both at the
 * engine level (`AwkProgramHandle.runFiles`) and end-to-end through
 * `AwkCommand`.
 */
class AwkMultiFileTest {
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

    // -- Engine-level (runFiles) --------------------------------------------

    @Test fun `FNR resets at each file while NR keeps counting`() =
        runTest {
            val files =
                sequenceOf(
                    AwkInputFile("a", sequenceOf("a1", "a2")),
                    AwkInputFile("b", sequenceOf("b1", "b2", "b3")),
                )
            val out =
                Awk
                    .compile("{ print NR, FNR, FILENAME, \$0 }")
                    .runFiles(files)
                    .joinAll()
            assertEquals(
                "1 1 a a1\n2 2 a a2\n3 1 b b1\n4 2 b b2\n5 3 b b3\n",
                out,
            )
        }

    @Test fun `nextfile skips the rest of the current file`() =
        runTest {
            val files =
                sequenceOf(
                    AwkInputFile("a", sequenceOf("a1", "a2", "a3")),
                    AwkInputFile("b", sequenceOf("b1", "b2")),
                )
            // Trigger nextfile only on file `a` — file `b` should run to
            // completion. `a1` prints (FNR=1), `a2` hits nextfile and isn't
            // printed, `a3` is skipped entirely, then both records of `b` print.
            val src = "FILENAME == \"a\" && FNR == 2 { nextfile } { print FILENAME, FNR, \$0 }"
            val out = Awk.compile(src).runFiles(files).joinAll()
            assertEquals("a 1 a1\nb 1 b1\nb 2 b2\n", out)
        }

    @Test fun `bare getline crosses file boundaries and updates FILENAME`() =
        runTest {
            // Two-file input. The BEGIN rule pulls every record via bare
            // `getline` and reports FILENAME/FNR for each — confirming the
            // cursor advances transparently and updates state.
            val files =
                sequenceOf(
                    AwkInputFile("first", sequenceOf("x", "y")),
                    AwkInputFile("second", sequenceOf("z")),
                )
            val src =
                """
                BEGIN {
                    while ((getline line) > 0) {
                        print FILENAME, FNR, line
                    }
                }
                """.trimIndent()
            val out = Awk.compile(src).runFiles(files).joinAll()
            assertEquals("first 1 x\nfirst 2 y\nsecond 1 z\n", out)
        }

    @Test fun `single-stream run keeps FNR equal to NR and FILENAME empty`() =
        runTest {
            val out =
                Awk
                    .compile("{ print NR, FNR, \"<\" FILENAME \">\", \$0 }")
                    .run(sequenceOf("one", "two"))
                    .joinAll()
            assertEquals("1 1 <> one\n2 2 <> two\n", out)
        }

    // -- End-to-end through AwkCommand --------------------------------------

    @Test fun `AwkCommand sets FILENAME and resets FNR per file`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/one.txt", "a\nb\n".encodeToByteArray())
            fs.writeBytes("/work/two.txt", "x\ny\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            val r =
                AwkCommand().run(
                    listOf("{ print FILENAME, FNR, NR, \$0 }", "one.txt", "two.txt"),
                    c,
                )
            assertEquals(0, r.exitCode)
            assertEquals(
                "one.txt 1 1 a\none.txt 2 2 b\ntwo.txt 1 3 x\ntwo.txt 2 4 y\n",
                out.readString(),
            )
        }

    @Test fun `AwkCommand nextfile advances to next input file`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "1\n2\n3\n".encodeToByteArray())
            fs.writeBytes("/work/b.txt", "10\n20\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            val r =
                AwkCommand().run(
                    listOf("\$0 == \"2\" { nextfile } { print FILENAME, \$0 }", "a.txt", "b.txt"),
                    c,
                )
            assertEquals(0, r.exitCode)
            // a.txt prints "1", hits nextfile on "2" (not printed), skips
            // "3", advances to b.txt which prints both records.
            assertEquals("a.txt 1\nb.txt 10\nb.txt 20\n", out.readString())
        }

    @Test fun `AwkCommand with no file args reports empty FILENAME`() =
        runTest {
            val fs = InMemoryFs()
            val (c, out, _) = ctx(fs, stdin = "hi\n")
            AwkCommand().run(listOf("{ print \"<\" FILENAME \">\", \$0 }"), c)
            assertEquals("<> hi\n", out.readString())
        }

    @Test fun `AwkCommand with dash for stdin reports FILENAME as dash`() =
        runTest {
            val fs = InMemoryFs()
            val (c, out, _) = ctx(fs, stdin = "via-stdin\n")
            AwkCommand().run(listOf("{ print FILENAME, \$0 }", "-"), c)
            assertEquals("- via-stdin\n", out.readString())
        }

    @Test fun `ENVIRON exposes process env vars`() =
        runTest {
            val out = Buffer()
            val err = Buffer()
            val c =
                bareCommandContext(
                    InMemoryFs(),
                    mutableMapOf("FOO" to "bar", "BAZ" to "qux"),
                    "/work",
                    Buffer().asSuspendSource(),
                    out.asSuspendSink(),
                    err.asSuspendSink(),
                )
            val src =
                """
                BEGIN { print ENVIRON["FOO"]; print ENVIRON["BAZ"]; print ("MISSING" in ENVIRON) }
                """.trimIndent()
            AwkCommand().run(listOf(src), c)
            assertEquals("bar\nqux\n0\n", out.readString())
        }
}
