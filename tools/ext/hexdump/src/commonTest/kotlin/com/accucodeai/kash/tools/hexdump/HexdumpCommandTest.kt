package com.accucodeai.kash.tools.hexdump

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

private data class Run(
    val exit: Int,
    val out: String,
    val err: String,
)

private suspend fun runHexdump(
    args: List<String>,
    stdin: String = "",
    fs: com.accucodeai.kash.fs.FileSystem = InMemoryFs(),
): Run {
    val outB = Buffer()
    val errB = Buffer()
    val inB = Buffer().apply { writeString(stdin) }
    val ctx =
        bareCommandContext(
            fs = fs,
            cwd = "/work",
            env = mutableMapOf(),
            stdin = inB.asSuspendSource(),
            stdout = outB.asSuspendSink(),
            stderr = errB.asSuspendSink(),
        )
    val res = HexdumpCommand().run(args, ctx)
    return Run(res.exitCode, outB.readString(), errB.readString())
}

/**
 * Vectors captured byte-for-byte from BSD hexdump on macOS so we stay
 * compatible with the most common host implementation.
 */
class HexdumpCommandTest {
    private val hello = "Hello, world!\n"

    @Test
    fun defaultIsTwoByteHex() =
        runTest {
            val r = runHexdump(emptyList(), hello)
            assertEquals(0, r.exit)
            assertEquals(
                "0000000    6548    6c6c    2c6f    7720    726f    646c    0a21        \n" +
                    "000000e\n",
                r.out,
            )
        }

    @Test
    fun canonical() =
        runTest {
            val r = runHexdump(listOf("-C"), hello)
            assertEquals(0, r.exit)
            assertEquals(
                "00000000  48 65 6c 6c 6f 2c 20 77  6f 72 6c 64 21 0a        |Hello, world!.|\n" +
                    "0000000e\n",
                r.out,
            )
        }

    @Test
    fun oneByteOctal() =
        runTest {
            val r = runHexdump(listOf("-b"), hello)
            assertEquals(0, r.exit)
            assertEquals(
                "0000000 110 145 154 154 157 054 040 167 157 162 154 144 041 012        \n" +
                    "000000e\n",
                r.out,
            )
        }

    @Test
    fun oneByteChar() =
        runTest {
            val r = runHexdump(listOf("-c"), hello)
            assertEquals(0, r.exit)
            assertEquals(
                "0000000   H   e   l   l   o   ,       w   o   r   l   d   !  \\n        \n" +
                    "000000e\n",
                r.out,
            )
        }

    @Test
    fun twoByteDecimal() =
        runTest {
            val r = runHexdump(listOf("-d"), hello)
            assertEquals(0, r.exit)
            // 'He' LE = 0x6548 = 25928, etc. 14 bytes = exactly 7 words; the
            // last word is '!\\n' = 0x0a21 = 2593.
            assertEquals(
                "0000000   25928   27756   11375   30496   29295   25708   02593        \n" +
                    "000000e\n",
                r.out,
            )
        }

    @Test
    fun twoByteOctal() =
        runTest {
            val r = runHexdump(listOf("-o"), hello)
            assertEquals(0, r.exit)
            assertEquals(
                "0000000  062510  066154  026157  073440  071157  062154  005041        \n" +
                    "000000e\n",
                r.out,
            )
        }

    @Test
    fun emptyInputProducesNothing() =
        runTest {
            val r = runHexdump(listOf("-C"))
            assertEquals(0, r.exit)
            assertEquals("", r.out)
        }

    @Test
    fun fullSixteenByteLine() =
        runTest {
            val r = runHexdump(listOf("-C"), "ABCDEFGHIJKLMNOP")
            assertEquals(0, r.exit)
            assertEquals(
                "00000000  41 42 43 44 45 46 47 48  49 4a 4b 4c 4d 4e 4f 50  |ABCDEFGHIJKLMNOP|\n" +
                    "00000010\n",
                r.out,
            )
        }

    @Test
    fun autoSkipCollapsesRepeats() =
        runTest {
            // 16 A's + 16 A's + 'B' — middle line should be replaced with '*'.
            val r = runHexdump(listOf("-C"), "A".repeat(32) + "B")
            assertEquals(0, r.exit)
            assertEquals(
                "00000000  41 41 41 41 41 41 41 41  41 41 41 41 41 41 41 41  |AAAAAAAAAAAAAAAA|\n" +
                    "*\n" +
                    "00000020  42                                                |B|\n" +
                    "00000021\n",
                r.out,
            )
        }

    @Test
    fun verboseDisablesCollapse() =
        runTest {
            val r = runHexdump(listOf("-C", "-v"), "A".repeat(32))
            assertEquals(0, r.exit)
            // Both identical lines should appear; no '*'.
            assertEquals(
                "00000000  41 41 41 41 41 41 41 41  41 41 41 41 41 41 41 41  |AAAAAAAAAAAAAAAA|\n" +
                    "00000010  41 41 41 41 41 41 41 41  41 41 41 41 41 41 41 41  |AAAAAAAAAAAAAAAA|\n" +
                    "00000020\n",
                r.out,
            )
        }

    @Test
    fun skipAndLen() =
        runTest {
            // Skip first 4 bytes, take 8: "EFGHIJKL" of "ABCDEFGHIJKLMNOP".
            val r = runHexdump(listOf("-C", "-s", "4", "-n", "8"), "ABCDEFGHIJKLMNOP")
            assertEquals(0, r.exit)
            assertEquals(
                "00000004  45 46 47 48 49 4a 4b 4c                           |EFGHIJKL|\n" +
                    "0000000c\n",
                r.out,
            )
        }

    @Test
    fun skipHexOffset() =
        runTest {
            val r = runHexdump(listOf("-C", "-s", "0x4", "-n", "4"), "ABCDEFGHIJKLMNOP")
            assertEquals(0, r.exit)
            assertEquals(
                "00000004  45 46 47 48                                       |EFGH|\n" +
                    "00000008\n",
                r.out,
            )
        }

    @Test
    fun skipBlockSuffix() =
        runTest {
            // 'b' = 512-byte block. 0b means 0 bytes skipped.
            val r = runHexdump(listOf("-C", "-s", "0b", "-n", "4"), "ABCD")
            assertEquals(0, r.exit)
            assertEquals(
                "00000000  41 42 43 44                                       |ABCD|\n" +
                    "00000004\n",
                r.out,
            )
        }

    @Test
    fun stackedFlagsCv() =
        runTest {
            // -Cv = canonical + verbose
            val r = runHexdump(listOf("-Cv"), "A".repeat(32))
            assertEquals(0, r.exit)
            assertTrue("00000010  41 41" in r.out)
            assertTrue("*\n" !in r.out)
        }

    @Test
    fun fileInput() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/in.bin", "hello".encodeToByteArray())
            val r = runHexdump(listOf("-C", "in.bin"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals(
                "00000000  68 65 6c 6c 6f                                    |hello|\n" +
                    "00000005\n",
                r.out,
            )
        }

    @Test
    fun missingFileFails() =
        runTest {
            val r = runHexdump(listOf("nope"))
            assertEquals(1, r.exit)
            assertTrue("No such file" in r.err)
        }

    @Test
    fun unknownOptionFails() =
        runTest {
            val r = runHexdump(listOf("-Z"))
            assertEquals(1, r.exit)
            assertTrue("unknown option" in r.err)
        }

    @Test
    fun helpFlag() =
        runTest {
            val r = runHexdump(listOf("-h"))
            assertEquals(0, r.exit)
            assertTrue(r.out.startsWith("Usage: hexdump"))
            assertTrue("-C" in r.out)
        }

    @Test
    fun formatStringsRejected() =
        runTest {
            for (opt in listOf("-e", "-f")) {
                val r = runHexdump(listOf(opt, "fmt"))
                assertEquals(1, r.exit, "expected exit 1 for $opt")
                assertTrue("not supported" in r.err)
            }
        }

    @Test
    fun charDisplayEscapesControlBytes() =
        runTest {
            // Test the escape map for low bytes that have named escapes.
            val r = runHexdump(listOf("-c"), "\u0000\u0007\u0008\t\n\u000b\u000c\r")
            assertEquals(0, r.exit)
            // \0 \a \b \t \n \v \f \r — 8 bytes
            assertEquals(
                "0000000  \\0  \\a  \\b  \\t  \\n  \\v  \\f  \\r                                \n" +
                    "0000008\n",
                r.out,
            )
        }
}
