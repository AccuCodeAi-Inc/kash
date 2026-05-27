package com.accucodeai.kash.tools.forensics.strings

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringsCommandTest {
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

    private fun ascii(s: String): ByteArray = s.encodeToByteArray()

    // ---------- unit tests ----------

    @Test fun `formatOffset hex dec oct`() {
        val c = StringsCommand()
        assertEquals("     ff", c.formatOffset(255, StringsCommand.Radix.HEX))
        assertEquals("    255", c.formatOffset(255, StringsCommand.Radix.DEC))
        assertEquals("    377", c.formatOffset(255, StringsCommand.Radix.OCT))
    }

    // ---------- semantic / recipe tests ----------

    @Test fun `ascii runs of at least 4 from mixed bytes`() =
        runTest {
            val data = bytes(0x00, 0x01) + ascii("hello") + bytes(0x00) + ascii("ab") + bytes(0xFF) + ascii("world!")
            val (c, out, _) = ctx(stdinBytes = data)
            val rc = StringsCommand().run(emptyList(), c)
            assertEquals(0, rc.exitCode)
            // "ab" is too short (2 < 4); "hello" and "world!" qualify.
            assertEquals("hello\nworld!\n", out.readString())
        }

    @Test fun `tab counts as printable`() =
        runTest {
            val data = bytes(0x00) + ascii("a\tbc") + bytes(0x00)
            val (c, out, _) = ctx(stdinBytes = data)
            StringsCommand().run(emptyList(), c)
            assertEquals("a\tbc\n", out.readString())
        }

    @Test fun `-n 8 threshold drops shorter runs`() =
        runTest {
            val data = ascii("short") + bytes(0x00) + ascii("longenough")
            val (c, out, _) = ctx(stdinBytes = data)
            StringsCommand().run(listOf("-n", "8"), c)
            assertEquals("longenough\n", out.readString())
        }

    @Test fun `legacy dash 6 sets min length`() =
        runTest {
            val data = ascii("12345") + bytes(0x00) + ascii("1234567")
            val (c, out, _) = ctx(stdinBytes = data)
            StringsCommand().run(listOf("-6"), c)
            assertEquals("1234567\n", out.readString())
        }

    @Test fun `-t x prints hex offset`() =
        runTest {
            // 17 leading zero bytes (0x11) then "hello" starting at offset 17 = 0x11.
            val data = ByteArray(17) + ascii("hello")
            val (c, out, _) = ctx(stdinBytes = data)
            StringsCommand().run(listOf("-t", "x"), c)
            assertEquals("     11 hello\n", out.readString())
        }

    @Test fun `-t d prints decimal offset`() =
        runTest {
            val data = ByteArray(10) + ascii("hello")
            val (c, out, _) = ctx(stdinBytes = data)
            StringsCommand().run(listOf("-t", "d"), c)
            assertEquals("     10 hello\n", out.readString())
        }

    @Test fun `-t o prints octal offset`() =
        runTest {
            val data = ByteArray(8) + ascii("hello")
            val (c, out, _) = ctx(stdinBytes = data)
            StringsCommand().run(listOf("-t", "o"), c)
            assertEquals("     10 hello\n", out.readString()) // 8 decimal = 10 octal
        }

    @Test fun `-f prefixes file name`() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/bin.dat", ascii("hello") + bytes(0x00))
            val (c, out, _) = ctx(fs)
            StringsCommand().run(listOf("-f", "bin.dat"), c)
            assertEquals("bin.dat: hello\n", out.readString())
        }

    @Test fun `-e S keeps high-bit run that -e s splits`() =
        runTest {
            // "ab" + 0xC3 (high bit) + "cd": under -s the 0xC3 breaks the run, so
            // "abcd" never forms (ab and cd are each < 4). Under -S, 0xC3 is printable
            // (>= 0xA0) so the whole 5-char run survives.
            val data = ascii("ab") + bytes(0xC3) + ascii("cd")
            val (c1, out1, _) = ctx(stdinBytes = data)
            StringsCommand().run(listOf("-e", "s"), c1)
            assertEquals("", out1.readString()) // both fragments too short

            val (c2, out2, _) = ctx(stdinBytes = data)
            StringsCommand().run(listOf("-e", "S"), c2)
            assertEquals("abÃcd\n", out2.readString())
        }

    @Test fun `-e l extracts 16-bit little-endian run`() =
        runTest {
            // "H\0i\0!\0!\0" -> printable wide units H i ! ! (low byte ascii, high zero).
            val data = bytes('H'.code, 0x00, 'i'.code, 0x00, '!'.code, 0x00, '!'.code, 0x00)
            val (c, out, _) = ctx(stdinBytes = data)
            StringsCommand().run(listOf("-e", "l"), c)
            assertEquals("Hi!!\n", out.readString())
        }

    @Test fun `-e b extracts 16-bit big-endian run`() =
        runTest {
            val data = bytes(0x00, 'H'.code, 0x00, 'i'.code, 0x00, '!'.code, 0x00, '!'.code)
            val (c, out, _) = ctx(stdinBytes = data)
            StringsCommand().run(listOf("-e", "b"), c)
            assertEquals("Hi!!\n", out.readString())
        }

    @Test fun `run spanning a chunk boundary is still found`() =
        runTest {
            val data = ascii("abcdefghij") // 10 chars, all one run
            val (c, out, _) = ctx(stdinBytes = data)
            val cmd = StringsCommand()
            cmd.chunkSize = 3L // force the run to span multiple chunks
            cmd.run(emptyList(), c)
            assertEquals("abcdefghij\n", out.readString())
        }

    @Test fun `wide run spanning a chunk boundary with odd carry`() =
        runTest {
            val data = bytes('H'.code, 0x00, 'e'.code, 0x00, 'l'.code, 0x00, 'l'.code, 0x00, 'o'.code, 0x00)
            val (c, out, _) = ctx(stdinBytes = data)
            val cmd = StringsCommand()
            cmd.chunkSize = 3L // odd chunk size leaves a carried half-unit each read
            cmd.run(listOf("-e", "l"), c)
            assertEquals("Hello\n", out.readString())
        }

    @Test fun `multiple files are scanned in order`() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/a.bin", ascii("aaaa") + bytes(0x00))
            fs.writeBytes("/work/b.bin", ascii("bbbb") + bytes(0x00))
            val (c, out, _) = ctx(fs)
            StringsCommand().run(listOf("a.bin", "b.bin"), c)
            assertEquals("aaaa\nbbbb\n", out.readString())
        }

    @Test fun `dash reads stdin`() =
        runTest {
            val (c, out, _) = ctx(stdinBytes = ascii("fromstdin"))
            StringsCommand().run(listOf("-"), c)
            assertEquals("fromstdin\n", out.readString())
        }

    @Test fun `missing file yields exit 1`() =
        runTest {
            val (c, _, err) = ctx()
            val rc = StringsCommand().run(listOf("nope.bin"), c)
            assertEquals(1, rc.exitCode)
            assertTrue(err.readString().contains("No such file"))
        }

    @Test fun `help prints usage and exits 0`() =
        runTest {
            val (c, out, _) = ctx()
            val rc = StringsCommand().run(listOf("--help"), c)
            assertEquals(0, rc.exitCode)
            assertTrue(out.readString().contains("Usage: strings"))
        }

    @Test fun `empty input produces no output and exit 0`() =
        runTest {
            val (c, out, _) = ctx()
            val rc = StringsCommand().run(emptyList(), c)
            assertEquals(0, rc.exitCode)
            assertEquals("", out.readString())
        }

    @Test fun `bytes long form sets min length`() =
        runTest {
            val data = ascii("abc") + bytes(0x00) + ascii("abcdef")
            val (c, out, _) = ctx(stdinBytes = data)
            StringsCommand().run(listOf("--bytes=5"), c)
            assertEquals("abcdef\n", out.readString())
        }
}
