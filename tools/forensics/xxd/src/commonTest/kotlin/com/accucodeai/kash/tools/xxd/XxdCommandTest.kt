package com.accucodeai.kash.tools.xxd

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Run(
    val exit: Int,
    val out: ByteArray,
    val err: String,
)

private suspend fun runXxd(
    args: List<String>,
    stdin: String = "",
): Run {
    val outB = Buffer()
    val errB = Buffer()
    val inB = Buffer().apply { writeString(stdin) }
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            cwd = "/work",
            env = mutableMapOf(),
            stdin = inB.asSuspendSource(),
            stdout = outB.asSuspendSink(),
            stderr = errB.asSuspendSink(),
        )
    val res = XxdCommand().run(args, ctx)
    return Run(res.exitCode, outB.readByteArray(), errB.readString())
}

class XxdCommandTest {
    @Test
    fun defaultDump() =
        runTest {
            val r = runXxd(emptyList(), "Hello, world!")
            assertEquals(0, r.exit)
            assertEquals(
                "00000000: 4865 6c6c 6f2c 2077 6f72 6c64 21         Hello, world!\n",
                r.out.decodeToString(),
            )
        }

    @Test
    fun plainHex() =
        runTest {
            val r = runXxd(listOf("-p"), "Hello")
            assertEquals(0, r.exit)
            assertEquals("48656c6c6f\n", r.out.decodeToString())
        }

    @Test
    fun reversePlain() =
        runTest {
            val r = runXxd(listOf("-r", "-p"), "48656c6c6f0a")
            assertEquals(0, r.exit)
            assertEquals("Hello\n", r.out.decodeToString())
        }

    @Test
    fun reversePlainWithWhitespace() =
        runTest {
            val r = runXxd(listOf("-r", "-p"), "48 65 6c\n6c 6f")
            assertEquals(0, r.exit)
            assertEquals("Hello", r.out.decodeToString())
        }

    @Test
    fun reverseFullDump() =
        runTest {
            val dump = "00000000: 4865 6c6c 6f2c 2077 6f72 6c64 21         Hello, world!\n"
            val r = runXxd(listOf("-r"), dump)
            assertEquals(0, r.exit)
            assertEquals("Hello, world!", r.out.decodeToString())
        }

    @Test
    fun upperHex() =
        runTest {
            val r = runXxd(listOf("-p", "-u"), "Hi")
            assertEquals(0, r.exit)
            assertEquals("4869\n", r.out.decodeToString())
        }

    @Test
    fun roundTripPlain() =
        runTest {
            val original = "The quick brown fox\n"
            val encoded = runXxd(listOf("-p"), original)
            assertEquals(0, encoded.exit)
            val decoded = runXxd(listOf("-r", "-p"), encoded.out.decodeToString())
            assertEquals(0, decoded.exit)
            assertEquals(original, decoded.out.decodeToString())
        }

    @Test
    fun roundTripDefault() =
        runTest {
            val original = "abc ÿDEF"
            val encoded = runXxd(emptyList(), original)
            assertEquals(0, encoded.exit)
            val decoded = runXxd(listOf("-r"), encoded.out.decodeToString())
            assertEquals(0, decoded.exit)
            assertEquals(original, decoded.out.decodeToString())
        }

    @Test
    fun rejectsOddHex() =
        runTest {
            val r = runXxd(listOf("-r", "-p"), "abc")
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("odd number"), "stderr was: ${r.err}")
        }

    @Test
    fun unknownOption() =
        runTest {
            val r = runXxd(listOf("-z"), "x")
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("unknown option"))
        }

    @Test
    fun helpFlag() =
        runTest {
            val r = runXxd(listOf("-h"), "")
            assertEquals(0, r.exit)
            val text = r.out.decodeToString()
            assertTrue("Usage: xxd" in text)
            assertTrue("-autoskip" in text)
            assertTrue("-revert" in text)
        }

    @Test
    fun versionFlag() =
        runTest {
            val r = runXxd(listOf("-v"), "")
            assertEquals(0, r.exit)
            assertTrue(r.out.decodeToString().startsWith("kash-xxd"))
        }

    @Test
    fun longFormOptions() =
        runTest {
            val r = runXxd(listOf("-cols", "4", "-groupsize", "1"), "ABCDE")
            assertEquals(0, r.exit)
            assertEquals(
                "00000000: 41 42 43 44  ABCD\n00000004: 45           E\n",
                r.out.decodeToString(),
            )
        }

    @Test
    fun decimalOffset() =
        runTest {
            val r = runXxd(listOf("-d", "-c", "8"), "0123456789abcdef")
            assertEquals(0, r.exit)
            // Second line offset should be 00000008 in decimal padding.
            val lines = r.out.decodeToString().lines()
            assertEquals("00000000", lines[0].substringBefore(':'))
            assertEquals("00000008", lines[1].substringBefore(':'))
        }

    @Test
    fun autoskipReplacesNullLines() =
        runTest {
            // 16 NULs then "x" — the 16-byte run should collapse to "*\n".
            val stdin = "\u0000".repeat(16) + "x"
            val r = runXxd(listOf("-a"), stdin)
            assertEquals(0, r.exit)
            val text = r.out.decodeToString()
            assertTrue("*\n" in text, "expected '*' line, got: $text")
            assertTrue("00000010: 78" in text)
        }

    @Test
    fun skipAndLen() =
        runTest {
            val r = runXxd(listOf("-s", "2", "-l", "3", "-p"), "abcdef")
            assertEquals(0, r.exit)
            assertEquals("636465\n", r.out.decodeToString())
        }

    @Test
    fun skipFromEnd() =
        runTest {
            // -s -2 should take the last 2 bytes.
            val r = runXxd(listOf("-s", "-2", "-p"), "abcdef")
            assertEquals(0, r.exit)
            assertEquals("6566\n", r.out.decodeToString())
        }

    @Test
    fun hexNumberArgs() =
        runTest {
            // 0x4 columns, 0x2 group; bytes are A..H (8 chars), 2 lines of 4.
            val r = runXxd(listOf("-c", "0x4"), "ABCDEFGH")
            assertEquals(0, r.exit)
            val lines = r.out.decodeToString().lines()
            assertTrue(lines[0].startsWith("00000000: 4142 4344"))
            assertTrue(lines[1].startsWith("00000004: 4546 4748"))
        }

    @Test
    fun offsetBias() =
        runTest {
            val r = runXxd(listOf("-o", "0x100", "-c", "4"), "ABCD")
            assertEquals(0, r.exit)
            assertTrue(r.out.decodeToString().startsWith("00000100:"))
        }

    @Test
    fun chainBase64RevHexBytes() =
        runTest {
            // Simulate the recipe: from-base64 → reverse → from-hex.
            val hex = "666c6167"
            val reversedHex = hex.reversed()
            val unreversedHex = reversedHex.reversed()
            val r = runXxd(listOf("-r", "-p"), unreversedHex)
            assertEquals(0, r.exit)
            assertEquals("flag", r.out.decodeToString())
        }

    @Test
    fun deferredOptionsErrorCleanly() =
        runTest {
            for (opt in listOf("-b", "-i", "-E", "-C")) {
                val r = runXxd(listOf(opt), "x")
                assertEquals(1, r.exit, "expected exit 1 for $opt")
                assertTrue("not supported" in r.err, "expected 'not supported' for $opt, got: ${r.err}")
            }
        }
}
