package com.accucodeai.kash.tools.rev

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

private data class RevRun(
    val exit: Int,
    val out: String,
    val err: String,
)

private fun stdin(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runRev(
    args: List<String> = emptyList(),
    stdin: Buffer = Buffer(),
    fs: FileSystem = NullFs(),
): RevRun {
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
    val res = RevCommand().run(args, ctx)
    return RevRun(res.exitCode, out.readString(), err.readString())
}

class RevRecipeTest {
    @Test fun singleLine() =
        runTest {
            val r = runRev(stdin = stdin("hello\n"))
            assertEquals(0, r.exit)
            assertEquals("olleh\n", r.out)
        }

    @Test fun multipleLinesReversedIndependently() =
        runTest {
            val r = runRev(stdin = stdin("abc\n123\nxyz\n"))
            assertEquals("cba\n321\nzyx\n", r.out)
        }

    @Test fun emptyLineStaysEmpty() =
        runTest {
            val r = runRev(stdin = stdin("foo\n\nbar\n"))
            assertEquals("oof\n\nrab\n", r.out)
        }

    @Test fun completelyEmptyInput() =
        runTest {
            val r = runRev(stdin = stdin(""))
            assertEquals(0, r.exit)
            assertEquals("", r.out)
        }

    @Test fun stdinViaDash() =
        runTest {
            val r = runRev(listOf("-"), stdin = stdin("hello\n"))
            assertEquals("olleh\n", r.out)
        }

    @Test fun multiByteUtf8PreservesCodepoints() =
        runTest {
            // "héllo" → "olléh"; é is U+00E9, must survive as one codepoint.
            val r = runRev(stdin = stdin("héllo\n"))
            assertEquals("olléh\n", r.out)
        }

    @Test fun nonBmpCodepointNotSplit() =
        runTest {
            // U+1F600 grinning face emoji is a surrogate pair in UTF-16.
            // "a😀b" reversed should be "b😀a", not
            // "b\uDE00\uD83Da" (which would split the surrogate pair).
            val emoji = "😀"
            val r = runRev(stdin = stdin("a${emoji}b\n"))
            assertEquals("b${emoji}a\n", r.out)
        }

    @Test fun nonBmpAlone() =
        runTest {
            // Single astral codepoint on its own line — reversal is identity.
            val cjk = "𩸽" // U+29E3D
            val r = runRev(stdin = stdin("$cjk\n"))
            assertEquals("$cjk\n", r.out)
        }

    @Test fun trailingLineWithoutNewline() =
        runTest {
            val r = runRev(stdin = stdin("hello"))
            // No trailing newline added.
            assertEquals("olleh", r.out)
        }

    @Test fun mixedLastLineNoNewline() =
        runTest {
            val r = runRev(stdin = stdin("abc\ndef"))
            assertEquals("cba\nfed", r.out)
        }

    @Test fun twoFileOperands() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "hello\n".encodeToByteArray())
            fs.writeBytes("/b", "world\n".encodeToByteArray())
            val r = runRev(listOf("/a", "/b"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("olleh\ndlrow\n", r.out)
        }

    @Test fun missingFileExitsOneButContinues() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/exists", "abc\n".encodeToByteArray())
            val r = runRev(listOf("/nope", "/exists"), fs = fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("rev: /nope: No such file or directory"))
            assertEquals("cba\n", r.out)
        }

    @Test fun doubleDashEndsOptions() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-x", "hi\n".encodeToByteArray())
            val r = runRev(listOf("--", "-x"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("ih\n", r.out)
        }

    @Test fun helpFlagPrintsUsageAndExitsZero() =
        runTest {
            val r = runRev(listOf("--help"))
            assertEquals(0, r.exit)
            assertTrue(r.out.contains("Usage: rev"))
        }

    @Test fun unknownLongOptionRejected() =
        runTest {
            val r = runRev(listOf("--bogus"))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("invalid option"))
        }

    @Test fun singleDashAloneIsStdinNotOption() =
        runTest {
            val r = runRev(listOf("-"), stdin = stdin("xy\n"))
            assertEquals(0, r.exit)
            assertEquals("yx\n", r.out)
        }
}
