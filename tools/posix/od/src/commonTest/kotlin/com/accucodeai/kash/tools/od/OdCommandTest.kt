package com.accucodeai.kash.tools.od

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OdCommandTest {
    private fun ctx(
        fs: InMemoryFs = InMemoryFs(),
        stdinBytes: ByteArray = ByteArray(0),
    ): Triple<CommandContext, Buffer, Buffer> {
        fs.mkdirs("/work")
        val out = Buffer()
        val err = Buffer()
        val inBuf = Buffer()
        if (stdinBytes.isNotEmpty()) inBuf.write(stdinBytes)
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

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { (b[it] and 0xFF).toByte() }

    // ---------- OdFormats unit tests ----------

    @Test fun `parseTypeSpec single c`() {
        val r = parseTypeSpec("c")
        assertEquals(1, r?.size)
        assertTrue(r!![0] is OdFormat.Char1)
    }

    @Test fun `parseTypeSpec x1 d2 stacks two`() {
        val r = parseTypeSpec("x1d2")
        assertEquals(2, r?.size)
        assertTrue(r!![0] is OdFormat.Hex)
        assertEquals(1, (r[0] as OdFormat.Hex).size)
        assertTrue(r[1] is OdFormat.DecSigned)
        assertEquals(2, (r[1] as OdFormat.DecSigned).size)
    }

    @Test fun `parseTypeSpec invalid size`() {
        assertEquals(null, parseTypeSpec("d3"))
        assertEquals(null, parseTypeSpec("z"))
        assertEquals(null, parseTypeSpec(""))
    }

    @Test fun `octal helper pads`() {
        assertEquals("000123", octal(0x53, 6)) // 0x53 = 83 = 0123 octal
        assertEquals("0", octal(0, 1))
    }

    @Test fun `hex helper pads`() {
        assertEquals("000a", hex(10, 4))
        assertEquals("ff", hex(0xff, 2))
    }

    @Test fun `readSigned 1 byte FF is minus one`() {
        assertEquals(-1L, readSigned(bytes(0xFF), 0, 1))
        assertEquals(127L, readSigned(bytes(0x7F), 0, 1))
        assertEquals(-128L, readSigned(bytes(0x80), 0, 1))
    }

    @Test fun `readUnsigned 2 bytes little-endian`() {
        // bytes 0x34 0x12 → 0x1234 = 4660
        assertEquals(0x1234L, readUnsigned(bytes(0x34, 0x12), 0, 2))
    }

    // ---------- Command tests ----------

    @Test fun `default format octal 2byte on Hello`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = "Hello".encodeToByteArray())
            val r = OdCommand().run(emptyList(), c)
            assertEquals(0, r.exitCode)
            val s = out.readString()
            // First line address 0000000, then three 16-bit octal pairs:
            //   'H'|'e' = 0x6548 = 062510
            //   'l'|'l' = 0x6c6c = 066154
            //   'o'|\0  = 0x006f = 000157
            assertContains(s, "0000000")
            assertContains(s, "062510")
            assertContains(s, "066154")
            assertContains(s, "000157")
            // trailing address line at 0000005
            assertContains(s, "0000005")
        }

    @Test fun `dash c renders printable and escapes`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = "Hi\n".encodeToByteArray())
            OdCommand().run(listOf("-c"), c)
            val s = out.readString()
            assertContains(s, "  H")
            assertContains(s, "  i")
            assertContains(s, " \\n")
        }

    @Test fun `dash A x emits hex addresses`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = ByteArray(20))
            OdCommand().run(listOf("-A", "x", "-t", "x1"), c)
            val s = out.readString()
            // 20 bytes → addresses 0000000 and 0000010 (hex) and trailing 0000014
            assertContains(s, "0000000")
            assertContains(s, "0000010")
            assertContains(s, "0000014")
        }

    @Test fun `dash A n suppresses addresses`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = "ab".encodeToByteArray())
            OdCommand().run(listOf("-A", "n", "-t", "x1"), c)
            val s = out.readString()
            assertFalse(s.contains("0000000"), "expected no address but got: $s")
            assertContains(s, "61")
            assertContains(s, "62")
        }

    @Test fun `attached form -An matches separated -A n`() =
        runTest {
            // Real od accepts `-An` (no space) as well as `-A n`. The previous
            // parser rejected the attached form as an unknown option.
            val (c, out, _) = ctx(stdinBytes = "ab".encodeToByteArray())
            OdCommand().run(listOf("-An", "-t", "x1"), c)
            val s = out.readString()
            assertFalse(s.contains("0000000"), "expected no address but got: $s")
            assertContains(s, "61")
            assertContains(s, "62")
        }

    @Test fun `attached form -Ax produces hex addresses`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = "a".encodeToByteArray())
            OdCommand().run(listOf("-Ax", "-t", "x1"), c)
            val s = out.readString()
            // First-line address renders as a hex zero, end address as hex 1.
            assertContains(s, "000000")
            assertContains(s, "000001")
        }

    @Test fun `dash A d decimal addresses`() =
        runTest {
            // Use distinct bytes so duplicate-line collapse doesn't hide addresses.
            val (c, out, _) = ctx(stdinBytes = ByteArray(32) { it.toByte() })
            OdCommand().run(listOf("-A", "d", "-t", "x1"), c)
            val s = out.readString()
            // 32 bytes at width 16 → addresses 0, 16, 32; padded to 7
            assertContains(s, "      0")
            assertContains(s, "     16")
            assertContains(s, "     32")
        }

    @Test fun `dash j skips bytes`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = "ABCDEFGH".encodeToByteArray())
            OdCommand().run(listOf("-j", "4", "-A", "n", "-t", "c"), c)
            val s = out.readString()
            // After skipping 4 bytes: 'E','F','G','H'
            assertContains(s, "  E")
            assertContains(s, "  F")
            assertContains(s, "  G")
            assertContains(s, "  H")
            assertFalse(" A" in s)
        }

    @Test fun `dash N caps bytes read`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = ByteArray(100) { ('A'.code + (it % 26)).toByte() })
            OdCommand().run(listOf("-N", "8", "-A", "n", "-t", "x1"), c)
            val s = out.readString()
            // 8 bytes → one row 41 42 43 44 45 46 47 48
            assertContains(s, " 41")
            assertContains(s, " 48")
            assertFalse(" 49" in s)
        }

    @Test fun `dash w sets line width`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = ByteArray(16) { it.toByte() })
            OdCommand().run(listOf("-w", "8", "-A", "o", "-t", "x1"), c)
            val s = out.readString()
            val lines = s.lines().filter { it.isNotBlank() }
            // 16 bytes / 8 per line → 2 lines + trailing address; address every 8 bytes
            assertContains(lines[0], "0000000")
            assertContains(lines[1], "0000010") // 8 in octal
            assertContains(lines[2], "0000020") // 16 in octal
        }

    @Test fun `dash w with attached value`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = ByteArray(8))
            OdCommand().run(listOf("-w8", "-A", "n", "-t", "x1"), c)
            val s = out.readString()
            val firstLine = s.lines().first { it.isNotBlank() }
            assertEquals(8, firstLine.count { it == '0' } / 2)
        }

    @Test fun `multiple dash t stacks rows per address`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = bytes(0xAB, 0xCD))
            OdCommand().run(listOf("-A", "n", "-t", "o1", "-t", "x1"), c)
            val s = out.readString()
            assertContains(s, "253") // 0xAB octal
            assertContains(s, "315") // 0xCD octal
            assertContains(s, "ab")
            assertContains(s, "cd")
        }

    @Test fun `duplicate lines collapsed to star by default`() =
        runTest {
            // Two identical 16-byte lines: 32 zeros.
            val (c, out, _) = ctx(stdinBytes = ByteArray(32))
            OdCommand().run(listOf("-t", "x1"), c)
            val lines = out.readString().lines()
            // Should be: first line, '*', trailing address
            assertContains(lines[0], "0000000")
            assertEquals("*", lines[1])
            assertContains(lines[2], "0000040") // 32 octal
        }

    @Test fun `dash v shows duplicate lines`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = ByteArray(32))
            OdCommand().run(listOf("-v", "-t", "x1"), c)
            val text = out.readString()
            assertFalse("*" in text.lines(), "should not collapse with -v: $text")
        }

    @Test fun `final address line shown when input empty`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = ByteArray(0))
            OdCommand().run(listOf("-t", "x1"), c)
            // Empty input → just the trailing address 0000000
            assertEquals("0000000\n", out.readString())
        }

    @Test fun `dash x1 byte hex`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = bytes(0x00, 0x7F, 0xFF))
            OdCommand().run(listOf("-A", "n", "-t", "x1"), c)
            val s = out.readString()
            assertContains(s, " 00")
            assertContains(s, " 7f")
            assertContains(s, " ff")
        }

    @Test fun `dash d1 signed`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = bytes(0xFF, 0x80, 0x7F))
            OdCommand().run(listOf("-A", "n", "-t", "d1"), c)
            val s = out.readString()
            assertContains(s, "  -1")
            assertContains(s, "-128")
            assertContains(s, " 127")
        }

    @Test fun `dash u1 unsigned`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = bytes(0xFF, 0x00))
            OdCommand().run(listOf("-A", "n", "-t", "u1"), c)
            val s = out.readString()
            assertContains(s, "255")
            assertContains(s, "  0")
        }

    @Test fun `short form dash b is octal-1`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = bytes(0xAB))
            OdCommand().run(listOf("-A", "n", "-b"), c)
            val s = out.readString()
            assertContains(s, "253") // 0xAB = 0o253
        }

    @Test fun `short form dash a named char`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = bytes(0x00, 0x07, 0x20, 0x41))
            OdCommand().run(listOf("-A", "n", "-a"), c)
            val s = out.readString()
            assertContains(s, "nul")
            assertContains(s, "bel")
            assertContains(s, " sp")
            assertContains(s, "  A")
        }

    @Test fun `file operand read`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/data.bin", bytes(0x41, 0x42, 0x43))
            val (c, out, _) = ctx(fs)
            OdCommand().run(listOf("-A", "n", "-t", "c", "data.bin"), c)
            val s = out.readString()
            assertContains(s, "  A")
            assertContains(s, "  B")
            assertContains(s, "  C")
        }

    @Test fun `missing file errors`() =
        runTest {
            val (c, _, err) = ctx()
            val r = OdCommand().run(listOf("nope.bin"), c)
            assertEquals(1, r.exitCode)
            assertContains(err.readString(), "No such file")
        }

    @Test fun `concat multiple files`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a", "AB".encodeToByteArray())
            fs.writeBytes("/work/b", "CD".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            OdCommand().run(listOf("-A", "n", "-t", "c", "a", "b"), c)
            val s = out.readString()
            assertContains(s, "  A")
            assertContains(s, "  D")
        }

    @Test fun `dash j with hex size suffix`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = ByteArray(20) { (it + 1).toByte() })
            OdCommand().run(listOf("-j", "0x4", "-A", "n", "-t", "x1"), c)
            val s = out.readString()
            // Skipping 4 bytes — first byte rendered is byte 5 (= 0x05)
            assertContains(s, " 05")
            assertFalse(" 01" in s)
        }

    @Test fun `invalid type spec errors`() =
        runTest {
            val (c, _, err) = ctx(stdinBytes = "x".encodeToByteArray())
            val r = OdCommand().run(listOf("-t", "z"), c)
            assertEquals(1, r.exitCode)
            assertContains(err.readString(), "invalid type string")
        }

    @Test fun `unknown option exit 2`() =
        runTest {
            val (c, _, err) = ctx(stdinBytes = "x".encodeToByteArray())
            val r = OdCommand().run(listOf("-Z"), c)
            assertEquals(2, r.exitCode)
            assertContains(err.readString(), "invalid option")
        }

    @Test fun `help exits zero`() =
        runTest {
            val (c, out, _) = ctx()
            val r = OdCommand().run(listOf("--help"), c)
            assertEquals(0, r.exitCode)
            assertContains(out.readString(), "od")
        }

    @Test fun `recipe full hex dump of binary file`() =
        runTest {
            val fs = InMemoryFs()
            // 'Hello\n' → 48 65 6c 6c 6f 0a
            fs.writeBytes("/work/h.bin", "Hello\n".encodeToByteArray())
            val (c, out, _) = ctx(fs)
            OdCommand().run(listOf("-A", "x", "-t", "x1", "h.bin"), c)
            val s = out.readString()
            assertContains(s, " 48")
            assertContains(s, " 65")
            assertContains(s, " 6c")
            assertContains(s, " 6f")
            assertContains(s, " 0a")
        }

    @Test fun `recipe dash c shows backslash-n for newline`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = "a\nb".encodeToByteArray())
            OdCommand().run(listOf("-A", "n", "-c"), c)
            val s = out.readString()
            assertContains(s, "  a")
            assertContains(s, " \\n")
            assertContains(s, "  b")
        }

    @Test fun `recipe combined -j and -N reads a window`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = ByteArray(100) { (it + 1).toByte() })
            OdCommand().run(listOf("-j", "10", "-N", "4", "-A", "n", "-t", "x1"), c)
            val s = out.readString()
            // bytes 11..14 → 0b 0c 0d 0e
            assertContains(s, " 0b")
            assertContains(s, " 0c")
            assertContains(s, " 0d")
            assertContains(s, " 0e")
            assertFalse(" 0a" in s)
            assertFalse(" 0f" in s)
        }

    @Test fun `recipe multiple -t formats produce parallel rows`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = bytes(0x41, 0x42))
            OdCommand().run(listOf("-A", "n", "-t", "c", "-t", "x1"), c)
            val lines = out.readString().lines().filter { it.isNotBlank() }
            // first row: chars, second row: hex
            assertTrue(lines[0].contains("  A"), "first row should be chars: ${lines[0]}")
            assertTrue(lines[1].contains(" 41"), "second row should be hex: ${lines[1]}")
        }
}
