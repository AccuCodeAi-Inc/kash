package com.accucodeai.kash.tools.binwalk

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

private data class Run(
    val exit: Int,
    val out: String,
    val err: String,
)

private suspend fun runBinwalk(
    args: List<String>,
    stdin: ByteArray = ByteArray(0),
    fs: InMemoryFs = InMemoryFs(),
    chunkSize: Int? = null,
): Run {
    val outB = Buffer()
    val errB = Buffer()
    val inB = Buffer().apply { write(stdin) }
    val ctx =
        bareCommandContext(
            fs = fs,
            cwd = "/work",
            env = mutableMapOf(),
            stdin = inB.asSuspendSource(),
            stdout = outB.asSuspendSink(),
            stderr = errB.asSuspendSink(),
        )
    val cmd = BinwalkCommand()
    if (chunkSize != null) cmd.chunkSize = chunkSize
    val res = cmd.run(args, ctx)
    return Run(res.exitCode, outB.readString(), errB.readString())
}

private fun hex(h: String): ByteArray {
    val t = h.trim().split(Regex("\\s+"))
    return ByteArray(t.size) { t[it].toInt(16).toByte() }
}

class BinwalkCommandTest {
    /** firmware-like blob: PNG at 8, gzip at 40. */
    private fun blob(): ByteArray {
        val b = ByteArray(64)
        hex("89 50 4E 47 0D 0A 1A 0A").copyInto(b, 8)
        hex("1F 8B 08").copyInto(b, 40)
        return b
    }

    private suspend fun fsWith(
        name: String,
        content: ByteArray,
    ): InMemoryFs {
        val fs = InMemoryFs()
        fs.mkdirs("/work", 0b111_101_101)
        fs.writeBytes("/work/$name", content)
        return fs
    }

    @Test
    fun signatureTableFromStdin() =
        runTest {
            val r = runBinwalk(emptyList(), stdin = blob())
            assertEquals(0, r.exit)
            assertTrue("DECIMAL" in r.out && "HEXADECIMAL" in r.out && "DESCRIPTION" in r.out, r.out)
            assertTrue("8             0x8             PNG image data" in r.out, r.out)
            assertTrue("40            0x28            gzip compressed data" in r.out, r.out)
        }

    @Test
    fun scansFile() =
        runTest {
            val fs = fsWith("fw.bin", blob())
            val r = runBinwalk(listOf("fw.bin"), fs = fs)
            assertEquals(0, r.exit)
            assertTrue(r.out.startsWith("fw.bin\n"), r.out)
            assertTrue("PNG image data" in r.out)
        }

    @Test
    fun includeFilter() =
        runTest {
            val r = runBinwalk(listOf("-y", "png"), stdin = blob())
            assertEquals(0, r.exit)
            assertTrue("PNG image data" in r.out)
            assertTrue("gzip" !in r.out, r.out)
        }

    @Test
    fun excludeFilter() =
        runTest {
            val r = runBinwalk(listOf("-x", "gzip"), stdin = blob())
            assertEquals(0, r.exit)
            assertTrue("PNG image data" in r.out)
            assertTrue("gzip" !in r.out, r.out)
        }

    @Test
    fun textSuppressedByDefault() =
        runTest {
            // An XML declaration embedded in an otherwise-binary blob.
            val b = ByteArray(32)
            hex("3C 3F 78 6D 6C").copyInto(b, 4) // "<?xml"
            hex("1F 8B 08 00").copyInto(b, 20) // gzip (deflate, no flags)
            val def = runBinwalk(emptyList(), stdin = b)
            assertTrue("XML" !in def.out, def.out)
            assertTrue("gzip" in def.out)
            val withText = runBinwalk(listOf("--include-text"), stdin = b)
            assertTrue("XML" in withText.out, withText.out)
        }

    @Test
    fun extractCarvesSegments() =
        runTest {
            val fs = fsWith("fw.bin", blob())
            val r = runBinwalk(listOf("-e", "fw.bin"), fs = fs)
            assertEquals(0, r.exit)
            assertTrue("extracted" in r.out, r.out)
            // PNG segment: offset 8 → next hit at 40, so 32 bytes; named by hex offset + .png
            val png = fs.readBytes("/work/fw.bin.extracted/00000008.png")
            assertEquals(32, png.size)
            assertEquals(0x89.toByte(), png[0])
            // gzip segment: offset 40 → EOF (64), 24 bytes
            val gz = fs.readBytes("/work/fw.bin.extracted/00000028.gz")
            assertEquals(24, gz.size)
            assertEquals(0x1F.toByte(), gz[0])
        }

    @Test
    fun missingFileExits1() =
        runTest {
            val r = runBinwalk(listOf("nope"), fs = InMemoryFs())
            assertEquals(1, r.exit)
            assertTrue("cannot open" in r.err, r.err)
        }

    @Test
    fun emptyInputNoHits() =
        runTest {
            val r = runBinwalk(emptyList(), stdin = ByteArray(0))
            assertEquals(0, r.exit)
            assertTrue("DECIMAL" in r.out)
        }

    @Test
    fun help() =
        runTest {
            val r = runBinwalk(listOf("--help"))
            assertEquals(0, r.exit)
            assertTrue(r.out.startsWith("Usage: binwalk"))
        }

    @Test
    fun unknownOption() =
        runTest {
            val r = runBinwalk(listOf("-Z"))
            assertEquals(1, r.exit)
            assertTrue("unknown option" in r.err)
        }

    @Test
    fun firmwareSignatures() =
        runTest {
            val b = ByteArray(64)
            hex("68 73 71 73").copyInto(b, 0) // squashfs LE "hsqs"
            hex("D0 0D FE ED").copyInto(b, 16) // device tree blob
            hex("27 05 19 56").copyInto(b, 32) // u-boot uImage
            val r = runBinwalk(emptyList(), stdin = b)
            assertEquals(0, r.exit)
            assertTrue("Squashfs filesystem, little endian" in r.out, r.out)
            assertTrue("Flattened device tree blob (DTB)" in r.out, r.out)
            assertTrue("u-boot legacy uImage" in r.out, r.out)
        }

    @Test
    fun streamingFindsSignaturesAcrossChunkBoundaries() =
        runTest {
            // Build a blob larger than a tiny chunk, with signatures placed so
            // some straddle window boundaries; assert streaming == in-memory.
            val b = ByteArray(500)
            hex("89 50 4E 47 0D 0A 1A 0A").copyInto(b, 7) // PNG straddling chunk edges
            hex("1F 8B 08").copyInto(b, 200) // gzip
            "ustar".encodeToByteArray().copyInto(b, 257) // tar magic → item starts at 0
            hex("D0 0D FE ED").copyInto(b, 400) // DTB
            val r = runBinwalk(emptyList(), stdin = b, chunkSize = 16)
            assertEquals(0, r.exit)
            assertTrue("PNG image data" in r.out, r.out)
            assertTrue("gzip compressed data" in r.out, r.out)
            assertTrue("POSIX tar archive" in r.out, r.out)
            assertTrue("Flattened device tree blob (DTB)" in r.out, r.out)
            // tar item back-computes to offset 0 even though its magic is at 257.
            assertTrue("\n0             0x0 " in r.out, r.out)
        }

    @Test
    fun entropyMode() =
        runTest {
            // First 1024 bytes all zero (entropy 0), next 1024 a uniform 0..255
            // sweep (high entropy).
            val b = ByteArray(2048)
            for (i in 0 until 1024) b[1024 + i] = (i and 0xff).toByte()
            val r = runBinwalk(listOf("-E"), stdin = b)
            assertEquals(0, r.exit)
            assertTrue("ENTROPY" in r.out, r.out)
            val lines = r.out.lines()
            // block @0 is all-zero → entropy 0.000000
            assertTrue(lines.any { it.startsWith("0 ") && "0.000000" in it }, r.out)
            // block @1024 is a full byte sweep → high entropy flagged
            assertTrue(lines.any { it.startsWith("1024 ") && "high" in it }, r.out)
        }

    @Test
    fun extractUsesExactPngLength() =
        runTest {
            // A complete tiny PNG followed by trailing junk; -e must carve only
            // the PNG (exact IEND length), not run to EOF.
            val png =
                hex("89 50 4E 47 0D 0A 1A 0A") +
                    hex("00 00 00 0D") + "IHDR".encodeToByteArray() + ByteArray(13) + hex("00 00 00 00") +
                    hex("00 00 00 00") + "IEND".encodeToByteArray() + hex("AE 42 60 82")
            val blob = png + ByteArray(40)
            val fs = fsWith("pic.bin", blob)
            val r = runBinwalk(listOf("-e", "pic.bin"), fs = fs)
            assertEquals(0, r.exit)
            val carved = fs.readBytes("/work/pic.bin.extracted/00000000.png")
            assertEquals(png.size, carved.size)
        }

    @Test
    fun streamingMatchesInMemoryForFile() =
        runTest {
            val b = ByteArray(300)
            hex("89 50 4E 47 0D 0A 1A 0A").copyInto(b, 9)
            hex("28 B5 2F FD").copyInto(b, 130) // zstd
            val fs = fsWith("big.bin", b)
            val streamed = runBinwalk(listOf("big.bin"), fs = fs, chunkSize = 8)
            val whole = runBinwalk(listOf("big.bin"), fs = fs)
            assertEquals(whole.out, streamed.out)
            assertTrue("PNG image data" in streamed.out)
            assertTrue("Zstandard compressed data" in streamed.out)
        }
}
