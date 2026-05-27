package com.accucodeai.kash.tools.comm

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

private data class CommRun(
    val exit: Int,
    val out: String,
    val err: String,
)

private fun stdin(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runComm(
    args: List<String>,
    fs: FileSystem = InMemoryFs(),
    stdin: Buffer = Buffer(),
): CommRun {
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
    val res = CommCommand().run(args, ctx)
    return CommRun(res.exitCode, out.readString(), err.readString())
}

private suspend fun fsWith(vararg pairs: Pair<String, String>): InMemoryFs {
    val fs = InMemoryFs()
    for ((p, c) in pairs) fs.writeBytes(p, c.encodeToByteArray())
    return fs
}

class CommRecipeTest {
    @Test fun `sorted no overlap emits col1 and col2`() =
        runTest {
            val fs = fsWith("/a" to "apple\ncarrot\n", "/b" to "banana\ndate\n")
            val r = runComm(listOf("/a", "/b"), fs)
            assertEquals(0, r.exit)
            // a < b: apple, banana, carrot, date interleaved by sort order
            assertEquals(
                "apple\n\tbanana\ncarrot\n\tdate\n",
                r.out,
            )
        }

    @Test fun `overlap produces col3`() =
        runTest {
            val fs = fsWith("/a" to "alpha\nbeta\ngamma\n", "/b" to "beta\ndelta\ngamma\n")
            val r = runComm(listOf("/a", "/b"), fs)
            assertEquals(
                "alpha\n\t\tbeta\n\tdelta\n\t\tgamma\n",
                r.out,
            )
        }

    @Test fun `dash 1 dash 2 emits only intersection`() =
        runTest {
            val fs = fsWith("/a" to "alpha\nbeta\ngamma\n", "/b" to "beta\ndelta\ngamma\n")
            val r = runComm(listOf("-12", "/a", "/b"), fs)
            // col3 only: no leading tabs since col1 and col2 suppressed
            assertEquals("beta\ngamma\n", r.out)
        }

    @Test fun `dash 2 dash 3 emits only col1 (in A not B)`() =
        runTest {
            val fs = fsWith("/a" to "alpha\nbeta\ngamma\n", "/b" to "beta\ndelta\ngamma\n")
            val r = runComm(listOf("-23", "/a", "/b"), fs)
            assertEquals("alpha\n", r.out)
        }

    @Test fun `dash 1 dash 3 emits only col2 (in B not A)`() =
        runTest {
            val fs = fsWith("/a" to "alpha\nbeta\ngamma\n", "/b" to "beta\ndelta\ngamma\n")
            val r = runComm(listOf("-13", "/a", "/b"), fs)
            // col2 only; col1 suppressed so no leading tab
            assertEquals("delta\n", r.out)
        }

    @Test fun `duplicates in both inputs emit per duplicate`() =
        runTest {
            // each pair of equal lines is consumed together; extra dups become col1 or col2.
            val fs = fsWith("/a" to "x\nx\nx\ny\n", "/b" to "x\nx\nz\n")
            val r = runComm(listOf("/a", "/b"), fs)
            // matches: x/x, x/x. Then a has x, then y; b has z.
            // Walk: cmp x==x col3; cmp x==x col3; cmp x<z col1 x; cmp y<z col1 y; remaining b: z col2.
            assertEquals(
                "\t\tx\n\t\tx\nx\ny\n\tz\n",
                r.out,
            )
        }

    @Test fun `one file empty emits other entirely in col2 or col1`() =
        runTest {
            val fs1 = fsWith("/a" to "", "/b" to "x\ny\n")
            val r1 = runComm(listOf("/a", "/b"), fs1)
            assertEquals("\tx\n\ty\n", r1.out)

            val fs2 = fsWith("/a" to "x\ny\n", "/b" to "")
            val r2 = runComm(listOf("/a", "/b"), fs2)
            assertEquals("x\ny\n", r2.out)
        }

    @Test fun `stdin via dash for one operand`() =
        runTest {
            val fs = fsWith("/b" to "banana\ndate\n")
            val r = runComm(listOf("-", "/b"), fs, stdin = stdin("apple\ndate\n"))
            // apple<banana col1; banana col2; date==date col3
            assertEquals("apple\n\tbanana\n\t\tdate\n", r.out)
        }

    @Test fun `dash 1 only - col2 has no leading tab, col3 has one`() =
        runTest {
            val fs = fsWith("/a" to "a\nb\n", "/b" to "b\nc\n")
            val r = runComm(listOf("-1", "/a", "/b"), fs)
            // a in col1 suppressed; b in both col3 with 1 tab; c in col2 no tab
            assertEquals("\tb\nc\n", r.out)
        }

    @Test fun `dash 2 only - col1 unaffected, col3 has one tab`() =
        runTest {
            val fs = fsWith("/a" to "a\nb\n", "/b" to "b\nc\n")
            val r = runComm(listOf("-2", "/a", "/b"), fs)
            // a col1; b col3 with 1 tab (col2 suppressed); c col2 suppressed
            assertEquals("a\n\tb\n", r.out)
        }

    @Test fun `dash 3 only - col1 and col2 untouched, col3 hidden`() =
        runTest {
            val fs = fsWith("/a" to "a\nb\n", "/b" to "b\nc\n")
            val r = runComm(listOf("-3", "/a", "/b"), fs)
            // a col1; b suppressed; c col2 with 1 tab
            assertEquals("a\n\tc\n", r.out)
        }

    @Test fun `default three columns have correct tab counts`() =
        runTest {
            val fs = fsWith("/a" to "a\nb\n", "/b" to "b\nc\n")
            val r = runComm(listOf("/a", "/b"), fs)
            // a col1: no tab; b col3: 2 tabs; c col2: 1 tab
            assertEquals("a\n\t\tb\n\tc\n", r.out)
        }

    @Test fun `check-order flags are accepted as no-ops`() =
        runTest {
            val fs = fsWith("/a" to "x\n", "/b" to "x\n")
            val r1 = runComm(listOf("--check-order", "/a", "/b"), fs)
            assertEquals(0, r1.exit)
            assertEquals("\t\tx\n", r1.out)
            val r2 = runComm(listOf("--nocheck-order", "/a", "/b"), fs)
            assertEquals(0, r2.exit)
            assertEquals("\t\tx\n", r2.out)
        }

    @Test fun `double dash ends options`() =
        runTest {
            val fs = fsWith("/-1" to "x\n", "/b" to "y\n")
            val r = runComm(listOf("--", "/-1", "/b"), fs)
            assertEquals(0, r.exit)
            // file "/-1" has "x", file "/b" has "y": x<y col1; y col2
            assertEquals("x\n\ty\n", r.out)
        }

    @Test fun `wrong arg count exits 2`() =
        runTest {
            val r = runComm(listOf("/only"), InMemoryFs())
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("expected exactly two"))
        }

    @Test fun `missing file errors and exits 1`() =
        runTest {
            val fs = fsWith("/a" to "x\n")
            val r = runComm(listOf("/a", "/missing"), fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("No such file"))
        }

    @Test fun `invalid option exits 2`() =
        runTest {
            val r = runComm(listOf("-X", "/a", "/b"))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("invalid option"))
        }

    @Test fun `unknown long option exits 2`() =
        runTest {
            val r = runComm(listOf("--bogus", "/a", "/b"))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("unrecognized option"))
        }

    @Test fun `identical files produce only col3`() =
        runTest {
            val fs = fsWith("/a" to "x\ny\nz\n", "/b" to "x\ny\nz\n")
            val r = runComm(listOf("/a", "/b"), fs)
            assertEquals("\t\tx\n\t\ty\n\t\tz\n", r.out)
        }

    @Test fun `both empty produces no output`() =
        runTest {
            val fs = fsWith("/a" to "", "/b" to "")
            val r = runComm(listOf("/a", "/b"), fs)
            assertEquals(0, r.exit)
            assertEquals("", r.out)
        }

    @Test fun `suppress all three columns produces no output`() =
        runTest {
            val fs = fsWith("/a" to "a\nb\n", "/b" to "b\nc\n")
            val r = runComm(listOf("-123", "/a", "/b"), fs)
            assertEquals("", r.out)
        }
}
