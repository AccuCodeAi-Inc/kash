package com.accucodeai.kash.tools.split

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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplitCommandTest {
    private fun ctx(
        fs: InMemoryFs = InMemoryFs(),
        stdin: String = "",
        cwd: String = "/work",
    ): Triple<CommandContext, Buffer, Buffer> {
        fs.mkdirs(cwd)
        val out = Buffer()
        val err = Buffer()
        val inBuf = Buffer()
        if (stdin.isNotEmpty()) inBuf.writeString(stdin)
        return Triple(
            bareCommandContext(
                fs,
                mutableMapOf(),
                cwd,
                inBuf.asSuspendSource(),
                out.asSuspendSink(),
                err.asSuspendSink(),
            ),
            out,
            err,
        )
    }

    private suspend fun readFile(
        fs: InMemoryFs,
        path: String,
    ): String = fs.readBytes(path).decodeToString()

    @Test fun `default lines splits stdin into one piece when small`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "a\nb\nc\n")
            val r = SplitCommand().run(emptyList(), c)
            assertEquals(0, r.exitCode)
            assertEquals("a\nb\nc\n", readFile(fs, "/work/xaa"))
            assertFalse(fs.exists("/work/xab"))
        }

    @Test fun `dash l 2 with 5 lines produces three pieces`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "1\n2\n3\n4\n5\n")
            val r = SplitCommand().run(listOf("-l", "2"), c)
            assertEquals(0, r.exitCode)
            assertEquals("1\n2\n", readFile(fs, "/work/xaa"))
            assertEquals("3\n4\n", readFile(fs, "/work/xab"))
            assertEquals("5\n", readFile(fs, "/work/xac"))
        }

    @Test fun `dash b 10 splits 25 bytes into 10+10+5`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "0123456789abcdefghijklmno")
            SplitCommand().run(listOf("-b", "10"), c)
            assertEquals("0123456789", readFile(fs, "/work/xaa"))
            assertEquals("abcdefghij", readFile(fs, "/work/xab"))
            assertEquals("klmno", readFile(fs, "/work/xac"))
        }

    @Test fun `prefix operand is used`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/in", "a\nb\nc\n".encodeToByteArray())
            val (c, _, _) = ctx(fs)
            SplitCommand().run(listOf("-l", "1", "in", "PREFIX_"), c)
            assertEquals("a\n", readFile(fs, "/work/PREFIX_aa"))
            assertEquals("b\n", readFile(fs, "/work/PREFIX_ab"))
            assertEquals("c\n", readFile(fs, "/work/PREFIX_ac"))
        }

    @Test fun `dash a 1 single-char suffix`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "1\n2\n3\n4\n")
            SplitCommand().run(listOf("-a", "1", "-l", "1"), c)
            assertEquals("1\n", readFile(fs, "/work/xa"))
            assertEquals("2\n", readFile(fs, "/work/xb"))
            assertEquals("3\n", readFile(fs, "/work/xc"))
            assertEquals("4\n", readFile(fs, "/work/xd"))
        }

    @Test fun `dash d numeric suffix`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "1\n2\n3\n")
            SplitCommand().run(listOf("-d", "-l", "1"), c)
            assertEquals("1\n", readFile(fs, "/work/x00"))
            assertEquals("2\n", readFile(fs, "/work/x01"))
            assertEquals("3\n", readFile(fs, "/work/x02"))
        }

    @Test fun `dash d dash a 3 three-digit numeric`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "a\nb\n")
            SplitCommand().run(listOf("-d", "-a", "3", "-l", "1"), c)
            assertEquals("a\n", readFile(fs, "/work/x000"))
            assertEquals("b\n", readFile(fs, "/work/x001"))
        }

    @Test fun `numeric-suffixes with start`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "a\nb\n")
            SplitCommand().run(listOf("--numeric-suffixes=10", "-l", "1"), c)
            assertEquals("a\n", readFile(fs, "/work/x10"))
            assertEquals("b\n", readFile(fs, "/work/x11"))
        }

    @Test fun `dash x hex suffix`() =
        runTest {
            val fs = InMemoryFs()
            // 16 pieces forces hex 'f' to appear; here 11 (=b) is enough to verify hex letters.
            val data = (1..11).joinToString("") { "$it\n" }
            val (c, _, _) = ctx(fs, stdin = data)
            SplitCommand().run(listOf("-x", "-l", "1"), c)
            assertTrue(fs.exists("/work/x00"))
            assertTrue(fs.exists("/work/x0a"))
            assertEquals("11\n", readFile(fs, "/work/x0a"))
        }

    @Test fun `dash n 3 splits roughly equal`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "0123456789") // 10 bytes / 3 = 4+3+3
            SplitCommand().run(listOf("-n", "3"), c)
            assertEquals("0123", readFile(fs, "/work/xaa"))
            assertEquals("456", readFile(fs, "/work/xab"))
            assertEquals("789", readFile(fs, "/work/xac"))
        }

    @Test fun `dash n l3 splits on line boundaries`() =
        runTest {
            val fs = InMemoryFs()
            // 6 lines, want 3 chunks → roughly 2 lines each
            val (c, _, _) = ctx(fs, stdin = "aaa\nbbb\nccc\nddd\neee\nfff\n")
            SplitCommand().run(listOf("-n", "l/3"), c)
            // Each piece should end on a newline
            for (sfx in listOf("aa", "ab", "ac")) {
                val s = readFile(fs, "/work/x$sfx")
                assertTrue(s.endsWith("\n"), "piece x$sfx did not end with newline: '$s'")
            }
        }

    @Test fun `dash C 50 line-bytes splits on newline`() =
        runTest {
            val fs = InMemoryFs()
            // 7 lines x 10 bytes = 70 bytes; -C 50 → first piece ≤ 50, on newline
            val data = (1..7).joinToString("") { "xxxxxxxxx\n" } // each = 10 bytes
            val (c, _, _) = ctx(fs, stdin = data)
            SplitCommand().run(listOf("-C", "50"), c)
            // First piece should be 50 bytes (5 lines)
            assertEquals("xxxxxxxxx\n".repeat(5), readFile(fs, "/work/xaa"))
            // Second piece the remaining 2 lines
            assertEquals("xxxxxxxxx\n".repeat(2), readFile(fs, "/work/xab"))
        }

    @Test fun `additional-suffix appends to each piece`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "1\n2\n")
            SplitCommand().run(listOf("--additional-suffix=.txt", "-l", "1"), c)
            assertTrue(fs.exists("/work/xaa.txt"))
            assertTrue(fs.exists("/work/xab.txt"))
            assertEquals("1\n", readFile(fs, "/work/xaa.txt"))
        }

    @Test fun `empty input produces no pieces`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "")
            val r = SplitCommand().run(listOf("-l", "1"), c)
            assertEquals(0, r.exitCode)
            assertFalse(fs.exists("/work/xaa"))
        }

    @Test fun `verbose prints piece names to stderr`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, err) = ctx(fs, stdin = "a\nb\n")
            SplitCommand().run(listOf("--verbose", "-l", "1"), c)
            val s = err.readString()
            assertContains(s, "creating file 'xaa'")
            assertContains(s, "creating file 'xab'")
        }

    @Test fun `suffix exhaustion errors`() =
        runTest {
            val fs = InMemoryFs()
            // -a 1 → only 26 suffixes; 30-line input would exhaust them.
            val data = (1..30).joinToString("") { "$it\n" }
            val (c, _, err) = ctx(fs, stdin = data)
            val r = SplitCommand().run(listOf("-a", "1", "-l", "1"), c)
            assertEquals(1, r.exitCode)
            assertContains(err.readString(), "suffixes exhausted")
        }

    @Test fun `dash b with K suffix multiplies by 1024`() =
        runTest {
            val fs = InMemoryFs()
            val data = "x".repeat(3000)
            val (c, _, _) = ctx(fs, stdin = data)
            SplitCommand().run(listOf("-b", "1K"), c)
            assertEquals(1024, readFile(fs, "/work/xaa").length)
            assertEquals(1024, readFile(fs, "/work/xab").length)
            assertEquals(3000 - 2048, readFile(fs, "/work/xac").length)
        }

    @Test fun `combined dash l5 form`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "1\n2\n3\n4\n5\n6\n")
            SplitCommand().run(listOf("-l5"), c)
            assertEquals("1\n2\n3\n4\n5\n", readFile(fs, "/work/xaa"))
            assertEquals("6\n", readFile(fs, "/work/xab"))
        }

    @Test fun `missing file errors`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, err) = ctx(fs)
            val r = SplitCommand().run(listOf("nope.txt"), c)
            assertEquals(1, r.exitCode)
            assertContains(err.readString(), "cannot open 'nope.txt'")
        }

    @Test fun `dash operand reads stdin explicitly`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "hello\n")
            SplitCommand().run(listOf("-", "p"), c)
            assertEquals("hello\n", readFile(fs, "/work/paa"))
        }

    @Test fun `dash n 3 with default suffix grows to fit chunks`() =
        runTest {
            // 100 chunks would need 2 digits with -d, but with default alpha 2 is fine.
            // For -d -n 3 with no -a, suffix length should default sufficient (>=1, but default is 2).
            // Just verify -d -n 3 produces "x00", "x01", "x02" (suffix len 2).
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "012345")
            SplitCommand().run(listOf("-d", "-n", "3"), c)
            assertEquals("01", readFile(fs, "/work/x00"))
            assertEquals("23", readFile(fs, "/work/x01"))
            assertEquals("45", readFile(fs, "/work/x02"))
        }

    @Test fun `dash e elides empty pieces in n mode`() =
        runTest {
            val fs = InMemoryFs()
            // 2 bytes into 5 chunks: 3 empty chunks should be elided.
            val (c, _, _) = ctx(fs, stdin = "ab")
            SplitCommand().run(listOf("-e", "-n", "5"), c)
            assertEquals("a", readFile(fs, "/work/xaa"))
            assertEquals("b", readFile(fs, "/work/xab"))
            assertFalse(fs.exists("/work/xac"))
        }

    @Test fun `n mode without dash e creates empty pieces`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "ab")
            SplitCommand().run(listOf("-n", "5"), c)
            assertTrue(fs.exists("/work/xaa"))
            assertTrue(fs.exists("/work/xab"))
            assertTrue(fs.exists("/work/xac"))
            assertTrue(fs.exists("/work/xad"))
            assertTrue(fs.exists("/work/xae"))
            assertEquals("", readFile(fs, "/work/xac"))
        }

    @Test fun `help exits zero`() =
        runTest {
            val fs = InMemoryFs()
            val (c, out, _) = ctx(fs)
            val r = SplitCommand().run(listOf("--help"), c)
            assertEquals(0, r.exitCode)
            assertContains(out.readString(), "split")
        }

    @Test fun `invalid bytes spec errors`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, err) = ctx(fs, stdin = "xx")
            val r = SplitCommand().run(listOf("-b", "abc"), c)
            assertEquals(2, r.exitCode)
            assertContains(err.readString(), "invalid number of bytes")
        }

    @Test fun `recipe split CSV into chunks of 100 lines each`() =
        runTest {
            val fs = InMemoryFs()
            // 250-line CSV → expect 3 pieces of 100/100/50.
            val csv = buildString { for (i in 1..250) append("row$i\n") }
            fs.writeBytes("/work/data.csv", csv.encodeToByteArray())
            val (c, _, _) = ctx(fs)
            SplitCommand().run(listOf("-l", "100", "data.csv", "chunk_"), c)
            assertEquals(100, readFile(fs, "/work/chunk_aa").count { it == '\n' })
            assertEquals(100, readFile(fs, "/work/chunk_ab").count { it == '\n' })
            assertEquals(50, readFile(fs, "/work/chunk_ac").count { it == '\n' })
            assertFalse(fs.exists("/work/chunk_ad"))
        }

    @Test fun `recipe split numeric prefix with additional-suffix and start`() =
        runTest {
            val fs = InMemoryFs()
            val (c, _, _) = ctx(fs, stdin = "A\nB\nC\n")
            SplitCommand().run(
                listOf("--numeric-suffixes=100", "--additional-suffix=.txt", "-l", "1", "-a", "3"),
                c,
            )
            assertEquals("A\n", readFile(fs, "/work/x100.txt"))
            assertEquals("B\n", readFile(fs, "/work/x101.txt"))
            assertEquals("C\n", readFile(fs, "/work/x102.txt"))
        }

    @Test fun `recipe split bytes into 1MB chunks computes right count`() =
        runTest {
            val fs = InMemoryFs()
            // 3.5 MB of zeros → 1M + 1M + 1M + 0.5M
            val data = ByteArray(3_500_000)
            fs.writeBytes("/work/big.bin", data)
            val (c, _, _) = ctx(fs)
            SplitCommand().run(listOf("-b", "1M", "big.bin"), c)
            assertEquals(1_048_576, fs.readBytes("/work/xaa").size)
            assertEquals(1_048_576, fs.readBytes("/work/xab").size)
            assertEquals(1_048_576, fs.readBytes("/work/xac").size)
            assertEquals(3_500_000 - 3 * 1_048_576, fs.readBytes("/work/xad").size)
        }

    @Test fun `recipe partial last line preserved as final piece`() =
        runTest {
            val fs = InMemoryFs()
            // Final line has no newline
            val (c, _, _) = ctx(fs, stdin = "a\nb\nc")
            SplitCommand().run(listOf("-l", "1"), c)
            assertEquals("a\n", readFile(fs, "/work/xaa"))
            assertEquals("b\n", readFile(fs, "/work/xab"))
            assertEquals("c", readFile(fs, "/work/xac"))
        }
}
