package com.accucodeai.kash.tools.join

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

private data class Run(
    val exit: Int,
    val out: String,
    val err: String,
)

private fun stdin(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runJoin(
    args: List<String>,
    fs: FileSystem = InMemoryFs(),
    stdin: Buffer = Buffer(),
): Run {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            cwd = "/",
            stdin = stdin.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = JoinCommand().run(args, ctx)
    return Run(res.exitCode, out.readString(), err.readString())
}

private suspend fun fsWith(vararg pairs: Pair<String, String>): InMemoryFs {
    val fs = InMemoryFs()
    for ((p, c) in pairs) fs.writeBytes(p, c.encodeToByteArray())
    return fs
}

class JoinCommandTest {
    @Test fun `basic two-file join on field 1`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "1 alpha\n2 bravo\n3 charlie\n",
                    "/b" to "1 x\n2 y\n4 z\n",
                )
            val r = runJoin(listOf("/a", "/b"), fs)
            assertEquals(0, r.exit)
            assertEquals("1 alpha x\n2 bravo y\n", r.out)
        }

    @Test fun `join with -t comma for csv`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "1,alpha\n2,bravo\n",
                    "/b" to "1,red\n2,blue\n",
                )
            val r = runJoin(listOf("-t", ",", "/a", "/b"), fs)
            assertEquals("1,alpha,red\n2,bravo,blue\n", r.out)
        }

    @Test fun `dash 1 dash 2 different fields per file`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "alpha 1\nbravo 2\n",
                    "/b" to "2 x\n1 y\n", // sorted by field 1 of b
                )
            // join on field 2 of A, field 1 of B. Need sort-compatible: rearrange.
            // a sorted by field 2 -> already in order ("1","2")
            // b sorted by field 1 -> need: "1 y", "2 x"
            val fs2 =
                fsWith(
                    "/a" to "alpha 1\nbravo 2\n",
                    "/b" to "1 y\n2 x\n",
                )
            val r = runJoin(listOf("-1", "2", "-2", "1", "/a", "/b"), fs2)
            // key=1, then a-rest "alpha", then b-rest "y"
            assertEquals("1 alpha y\n2 bravo x\n", r.out)
        }

    @Test fun `-a 1 includes unpairable from file 1`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "1 a\n2 b\n3 c\n",
                    "/b" to "2 y\n",
                )
            val r = runJoin(listOf("-a", "1", "/a", "/b"), fs)
            assertEquals("1 a\n2 b y\n3 c\n", r.out)
        }

    @Test fun `-a 2 includes unpairable from file 2`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "2 b\n",
                    "/b" to "1 x\n2 y\n3 z\n",
                )
            val r = runJoin(listOf("-a", "2", "/a", "/b"), fs)
            assertEquals("1 x\n2 b y\n3 z\n", r.out)
        }

    @Test fun `-v 1 prints only unpairable from file 1`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "1 a\n2 b\n3 c\n",
                    "/b" to "2 y\n",
                )
            val r = runJoin(listOf("-v", "1", "/a", "/b"), fs)
            assertEquals("1 a\n3 c\n", r.out)
        }

    @Test fun `-v 2 prints only unpairable from file 2`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "2 b\n",
                    "/b" to "1 x\n2 y\n3 z\n",
                )
            val r = runJoin(listOf("-v", "2", "/a", "/b"), fs)
            assertEquals("1 x\n3 z\n", r.out)
        }

    @Test fun `-o custom order`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "1 alpha\n2 bravo\n",
                    "/b" to "1 red\n2 blue\n",
                )
            val r = runJoin(listOf("-o", "1.1,2.2,0", "/a", "/b"), fs)
            // 1.1=key field of file1 ("1"), 2.2=field2 of b ("red"), 0=key
            assertEquals("1 red 1\n2 blue 2\n", r.out)
        }

    @Test fun `-e fills empty with default output spec doesnt apply to default`() =
        runTest {
            // -e applies when -o or when an unpairable field is referenced.
            val fs =
                fsWith(
                    "/a" to "1 alpha\n2 bravo\n",
                    "/b" to "1 red\n",
                )
            val r = runJoin(listOf("-a", "1", "-e", "MISSING", "-o", "0,1.2,2.2", "/a", "/b"), fs)
            assertEquals("1 alpha red\n2 bravo MISSING\n", r.out)
        }

    @Test fun `-i case-insensitive join`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "Apple x\nBanana y\n",
                    "/b" to "apple red\nbanana yellow\n",
                )
            val r = runJoin(listOf("-i", "/a", "/b"), fs)
            assertEquals("Apple x red\nBanana y yellow\n", r.out)
        }

    @Test fun `--header passes through first line`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "id name\n1 alpha\n2 bravo\n",
                    "/b" to "id val\n1 x\n2 y\n",
                )
            val r = runJoin(listOf("--header", "/a", "/b"), fs)
            // header is joined: "id name val"
            assertEquals("id name val\n1 alpha x\n2 bravo y\n", r.out)
        }

    @Test fun `unsorted input emits warning`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "3 c\n1 a\n",
                    "/b" to "1 x\n",
                )
            val r = runJoin(listOf("/a", "/b"), fs)
            assertTrue(r.err.contains("not in sorted order"))
        }

    @Test fun `--nocheck-order silences warning`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "3 c\n1 a\n",
                    "/b" to "1 x\n",
                )
            val r = runJoin(listOf("--nocheck-order", "/a", "/b"), fs)
            assertEquals("", r.err)
        }

    @Test fun `stdin via dash`() =
        runTest {
            val fs = fsWith("/b" to "1 x\n2 y\n")
            val r = runJoin(listOf("-", "/b"), fs, stdin = stdin("1 alpha\n2 bravo\n"))
            assertEquals("1 alpha x\n2 bravo y\n", r.out)
        }

    @Test fun `both stdin is error`() =
        runTest {
            val r = runJoin(listOf("-", "-"))
            assertEquals(2, r.exit)
        }

    @Test fun `empty file produces empty output`() =
        runTest {
            val fs = fsWith("/a" to "", "/b" to "1 x\n2 y\n")
            val r = runJoin(listOf("/a", "/b"), fs)
            assertEquals("", r.out)
        }

    @Test fun `duplicate keys yield cross product`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "1 a1\n1 a2\n2 b\n",
                    "/b" to "1 x\n1 y\n",
                )
            val r = runJoin(listOf("/a", "/b"), fs)
            // 4 combinations for key 1
            assertEquals("1 a1 x\n1 a1 y\n1 a2 x\n1 a2 y\n", r.out)
        }

    @Test fun `mismatched field counts dont error`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "1 alpha\n2 bravo extra\n",
                    "/b" to "1 x\n2 y\n",
                )
            val r = runJoin(listOf("/a", "/b"), fs)
            assertEquals(0, r.exit)
            assertEquals("1 alpha x\n2 bravo extra y\n", r.out)
        }

    @Test fun `-j2 joins on field 2 of both`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "alpha 1\nbravo 2\n",
                    "/b" to "x 1\ny 2\n",
                )
            val r = runJoin(listOf("-j", "2", "/a", "/b"), fs)
            assertEquals("1 alpha x\n2 bravo y\n", r.out)
        }

    @Test fun `missing file errors exit 1`() =
        runTest {
            val fs = fsWith("/a" to "1 x\n")
            val r = runJoin(listOf("/a", "/nope"), fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("No such file"))
        }

    @Test fun `wrong arg count exits 2`() =
        runTest {
            val r = runJoin(listOf("/only"))
            assertEquals(2, r.exit)
        }

    @Test fun `whitespace runs collapse without -t`() =
        runTest {
            // multiple spaces between fields, plus leading whitespace
            val fs =
                fsWith(
                    "/a" to "  1    alpha\n  2    bravo\n",
                    "/b" to "1 red\n2 blue\n",
                )
            val r = runJoin(listOf("/a", "/b"), fs)
            assertEquals("1 alpha red\n2 bravo blue\n", r.out)
        }

    @Test fun `tab separator -t tab`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "1\talpha\n2\tbravo\n",
                    "/b" to "1\tx\n2\ty\n",
                )
            val r = runJoin(listOf("-t", "\t", "/a", "/b"), fs)
            assertEquals("1\talpha\tx\n2\tbravo\ty\n", r.out)
        }

    @Test fun `-o with -v 1 emits placeholder for file2 fields`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "1 a\n3 c\n",
                    "/b" to "2 y\n",
                )
            val r = runJoin(listOf("-v", "1", "-e", "X", "-o", "0,1.2,2.2", "/a", "/b"), fs)
            assertEquals("1 a X\n3 c X\n", r.out)
        }

    @Test fun `-e fills both directions in cross-spec`() =
        runTest {
            val fs =
                fsWith(
                    "/a" to "1 a\n",
                    "/b" to "2 y\n",
                )
            val r = runJoin(listOf("-a", "1", "-a", "2", "-e", "?", "-o", "0,1.2,2.2", "/a", "/b"), fs)
            // 1 unpairable from file 1; 2 unpairable from file 2.
            // order: cmp 1<2 so emit 1 first, then 2.
            assertEquals("1 a ?\n2 ? y\n", r.out)
        }

    @Test fun `invalid -o spec exits 2`() =
        runTest {
            val fs = fsWith("/a" to "1 x\n", "/b" to "1 y\n")
            val r = runJoin(listOf("-o", "3.1", "/a", "/b"), fs)
            assertEquals(2, r.exit)
        }

    @Test fun `dash dash ends options`() =
        runTest {
            val fs = fsWith("/-a" to "1 x\n", "/b" to "1 y\n")
            val r = runJoin(listOf("--", "/-a", "/b"), fs)
            assertEquals("1 x y\n", r.out)
        }
}
