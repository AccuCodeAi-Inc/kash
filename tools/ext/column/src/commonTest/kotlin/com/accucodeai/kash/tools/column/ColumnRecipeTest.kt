package com.accucodeai.kash.tools.column

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

private suspend fun runColumn(
    args: List<String> = emptyList(),
    stdin: Buffer = Buffer(),
    fs: FileSystem = NullFs(),
    env: MutableMap<String, String> = mutableMapOf(),
): Run {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = env,
            cwd = "/",
            stdin = stdin.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = ColumnCommand().run(args, ctx)
    return Run(res.exitCode, out.readString(), err.readString())
}

class ColumnRecipeTest {
    @Test fun `table mode aligns whitespace-split columns`() =
        runTest {
            val input = "apple red 1\nbananasplit yellow 2\nfig purple 33\n"
            val r = runColumn(listOf("-t"), stdin = stdin(input))
            assertEquals(0, r.exit)
            val expected =
                "apple        red     1\n" +
                    "bananasplit  yellow  2\n" +
                    "fig          purple  33\n"
            assertEquals(expected, r.out)
        }

    @Test fun `table mode with comma separator`() =
        runTest {
            val input = "a,bb,ccc\nxxx,y,z\n"
            val r = runColumn(listOf("-t", "-s", ","), stdin = stdin(input))
            assertEquals(0, r.exit)
            val expected =
                "a    bb  ccc\n" +
                    "xxx  y   z\n"
            assertEquals(expected, r.out)
        }

    @Test fun `table mode with custom output separator`() =
        runTest {
            val input = "one two three\nfour five six\n"
            val r = runColumn(listOf("-t", "-o", " | "), stdin = stdin(input))
            assertEquals(0, r.exit)
            val expected =
                "one  | two  | three\n" +
                    "four | five | six\n"
            assertEquals(expected, r.out)
        }

    @Test fun `table mode prepends header row from dash N`() =
        runTest {
            val input = "1 2 3\n4 5 6\n"
            val r = runColumn(listOf("-t", "-N", "A,B,C"), stdin = stdin(input))
            assertEquals(0, r.exit)
            val expected =
                "A  B  C\n" +
                    "1  2  3\n" +
                    "4  5  6\n"
            assertEquals(expected, r.out)
        }

    @Test fun `default grid mode column-first layout`() =
        runTest {
            // 6 single-char items in 80-col window: lots of columns; verify
            // column-first ordering by using a width that forces few columns.
            val r = runColumn(stdin = stdin("a b c d e f\n"), env = mutableMapOf("COLUMNS" to "5"))
            assertEquals(0, r.exit)
            // With width=5 and item-len=1, sep=2 → cols = (5+2)/(1+2) = 2
            // rows = ceil(6/2) = 3. Column-first: col0={a,b,c} col1={d,e,f}
            val expected =
                "a  d\n" +
                    "b  e\n" +
                    "c  f\n"
            assertEquals(expected, r.out)
        }

    @Test fun `default grid mode in 80 cols packs many items per row`() =
        runTest {
            // 4 short items easily fit on one row in 80 cols
            val r = runColumn(stdin = stdin("one two three four\n"))
            assertEquals(0, r.exit)
            // 4 items, all fit → 4 cols, 1 row
            assertEquals("one  two  three  four\n", r.out)
        }

    @Test fun `dash x fills rows first`() =
        runTest {
            // Same 6 items, width=5 → 2 cols. Row-first: row0={a,b} row1={c,d} row2={e,f}
            val r = runColumn(listOf("-x"), stdin = stdin("a b c d e f\n"), env = mutableMapOf("COLUMNS" to "5"))
            assertEquals(0, r.exit)
            val expected =
                "a  b\n" +
                    "c  d\n" +
                    "e  f\n"
            assertEquals(expected, r.out)
        }

    @Test fun `file operand reads from filesystem`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/data", "x y\np q\n".encodeToByteArray())
            val r = runColumn(listOf("-t", "/data"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("x  y\np  q\n", r.out)
        }

    @Test fun `dash operand reads stdin`() =
        runTest {
            val r = runColumn(listOf("-t", "-"), stdin = stdin("a b\nc d\n"))
            assertEquals(0, r.exit)
            assertEquals("a  b\nc  d\n", r.out)
        }

    @Test fun `empty input produces empty output`() =
        runTest {
            val r = runColumn(listOf("-t"), stdin = stdin(""))
            assertEquals(0, r.exit)
            assertEquals("", r.out)
        }

    @Test fun `empty input grid mode also empty`() =
        runTest {
            val r = runColumn(stdin = stdin(""))
            assertEquals(0, r.exit)
            assertEquals("", r.out)
        }

    @Test fun `missing file errors and exits 1`() =
        runTest {
            val fs = InMemoryFs()
            val r = runColumn(listOf("-t", "/nope"), fs = fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("column: /nope: No such file or directory"))
        }

    @Test fun `unknown option exits 2`() =
        runTest {
            val r = runColumn(listOf("-Z"), stdin = stdin(""))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("invalid option") || r.err.contains("unrecognized"))
        }

    @Test fun `long options work`() =
        runTest {
            val input = "a:b:c\nx:y:z\n"
            val r = runColumn(listOf("--table", "--separator=:", "--output-separator=,"), stdin = stdin(input))
            assertEquals(0, r.exit)
            assertEquals("a,b,c\nx,y,z\n", r.out)
        }

    @Test fun `table-columns long option`() =
        runTest {
            val r = runColumn(listOf("-t", "--table-columns=H1,H2"), stdin = stdin("1 2\n3 4\n"))
            assertEquals(0, r.exit)
            assertEquals("H1  H2\n1   2\n3   4\n", r.out)
        }

    @Test fun `table mode preserves empty fields with explicit separator`() =
        runTest {
            val r = runColumn(listOf("-t", "-s", ","), stdin = stdin("a,,b\n,x,y\n"))
            assertEquals(0, r.exit)
            // Empty cells are kept; widths set accordingly
            val expected =
                "a     b\n" +
                    "   x  y\n"
            assertEquals(expected, r.out)
        }

    @Test fun `table mode collapses whitespace runs in default sep`() =
        runTest {
            val r = runColumn(listOf("-t"), stdin = stdin("a    b\nc  d\n"))
            assertEquals(0, r.exit)
            assertEquals("a  b\nc  d\n", r.out)
        }

    @Test fun `grid mode honors COLUMNS env var`() =
        runTest {
            // Three 3-char items. With COLUMNS=20 → cols = (20+2)/(3+2) = 4 → all fit on one row.
            val r = runColumn(stdin = stdin("aaa bbb ccc\n"), env = mutableMapOf("COLUMNS" to "20"))
            assertEquals(0, r.exit)
            assertEquals("aaa  bbb  ccc\n", r.out)
        }

    @Test fun `double dash ends options so file named -t is read`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-t", "x y\n".encodeToByteArray())
            val r = runColumn(listOf("--", "-t"), fs = fs)
            assertEquals(0, r.exit)
            // No -t flag, so this is grid mode on tokens "x" "y"
            assertEquals("x  y\n", r.out)
        }
}
