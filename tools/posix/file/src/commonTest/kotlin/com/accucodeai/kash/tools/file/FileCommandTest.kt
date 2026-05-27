package com.accucodeai.kash.tools.file

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

private suspend fun runFile(
    args: List<String>,
    stdin: ByteArray = ByteArray(0),
    fs: InMemoryFs = InMemoryFs(),
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
    val res = FileCommand().run(args, ctx)
    return Run(res.exitCode, outB.readString(), errB.readString())
}

private fun hex(h: String): ByteArray {
    val t = h.trim().split(Regex("\\s+"))
    return ByteArray(t.size) { t[it].toInt(16).toByte() }
}

class FileCommandTest {
    private suspend fun fsWith(vararg entries: Pair<String, ByteArray>): InMemoryFs {
        val fs = InMemoryFs()
        fs.mkdirs("/work", 0b111_101_101)
        for ((name, content) in entries) fs.writeBytes("/work/$name", content)
        return fs
    }

    @Test
    fun detectsPngFromFile() =
        runTest {
            val fs = fsWith("logo.png" to hex("89 50 4E 47 0D 0A 1A 0A 00 00 00 0D"))
            val r = runFile(listOf("logo.png"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("logo.png: PNG image data\n", r.out)
        }

    @Test
    fun briefMode() =
        runTest {
            val fs = fsWith("logo.png" to hex("89 50 4E 47 0D 0A 1A 0A"))
            val r = runFile(listOf("-b", "logo.png"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("PNG image data\n", r.out)
        }

    @Test
    fun mimeMode() =
        runTest {
            val fs = fsWith("logo.png" to hex("89 50 4E 47 0D 0A 1A 0A"))
            val r = runFile(listOf("-i", "logo.png"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("logo.png: image/png\n", r.out)
        }

    @Test
    fun stackedBriefMime() =
        runTest {
            val fs = fsWith("a.gz" to hex("1F 8B 08 00"))
            val r = runFile(listOf("-bi", "a.gz"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("application/gzip\n", r.out)
        }

    @Test
    fun emptyFile() =
        runTest {
            val fs = fsWith("nothing" to ByteArray(0))
            val r = runFile(listOf("nothing"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("nothing: empty\n", r.out)
        }

    @Test
    fun asciiText() =
        runTest {
            val fs = fsWith("readme" to "hello world\n".encodeToByteArray())
            val r = runFile(listOf("readme"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("readme: ASCII text\n", r.out)
        }

    @Test
    fun directory() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work/sub", 0b111_101_101)
            val r = runFile(listOf("sub"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("sub: directory\n", r.out)
        }

    @Test
    fun missingFileErrorsAndExits1() =
        runTest {
            val r = runFile(listOf("nope"), fs = InMemoryFs())
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("cannot open `nope'"), r.err)
        }

    @Test
    fun columnPaddingMultipleFiles() =
        runTest {
            val fs =
                fsWith(
                    "a.png" to hex("89 50 4E 47 0D 0A 1A 0A"),
                    "longername.txt" to "text\n".encodeToByteArray(),
                )
            val r = runFile(listOf("a.png", "longername.txt"), fs = fs)
            assertEquals(0, r.exit)
            // Labels pad so descriptions align in a column.
            assertEquals(
                "a.png:          PNG image data\n" +
                    "longername.txt: ASCII text\n",
                r.out,
            )
        }

    @Test
    fun stdinWhenNoOperands() =
        runTest {
            val r = runFile(emptyList(), stdin = hex("25 50 44 46 2D 31 2E 35"))
            assertEquals(0, r.exit)
            assertEquals("/dev/stdin: PDF document, version 1.5\n", r.out)
        }

    @Test
    fun dashIsStdin() =
        runTest {
            val r = runFile(listOf("-"), stdin = "plain text\n".encodeToByteArray())
            assertEquals(0, r.exit)
            assertEquals("/dev/stdin: ASCII text\n", r.out)
        }

    @Test
    fun help() =
        runTest {
            val r = runFile(listOf("--help"))
            assertEquals(0, r.exit)
            assertTrue(r.out.startsWith("Usage: file"))
        }

    @Test
    fun unknownOption() =
        runTest {
            val r = runFile(listOf("-Z"))
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("invalid option"))
        }

    @Test
    fun elfFromFile() =
        runTest {
            val fs =
                fsWith(
                    "prog" to hex("7F 45 4C 46 02 01 01 00 00 00 00 00 00 00 00 00 02 00 3E 00"),
                )
            val r = runFile(listOf("prog"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("prog: ELF 64-bit LSB executable\n", r.out)
        }

    @Test
    fun shebangScript() =
        runTest {
            val fs = fsWith("run.sh" to "#!/bin/bash\necho hi\n".encodeToByteArray())
            val r = runFile(listOf("run.sh"), fs = fs)
            assertEquals(0, r.exit)
            assertEquals("run.sh: Bourne-Again shell script, ASCII text executable\n", r.out)
        }
}
