package com.accucodeai.kash.tools.cksum

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

private suspend fun runCksum(
    args: List<String> = emptyList(),
    stdin: Buffer = Buffer(),
    fs: FileSystem = NullFs(),
): Run {
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
    val res = CksumCommand().run(args, ctx)
    return Run(res.exitCode, out.readString(), err.readString())
}

class CksumCommandTest {
    @Test fun emptyStdin() =
        runTest {
            val r = runCksum(stdin = stdin(""))
            assertEquals(0, r.exit)
            assertEquals("4294967295 0\n", r.out)
        }

    @Test fun stdinSingleChar() =
        runTest {
            val r = runCksum(stdin = stdin("a"))
            assertEquals("1220704766 1\n", r.out)
        }

    @Test fun stdinAbc() =
        runTest {
            val r = runCksum(stdin = stdin("abc"))
            assertEquals("1219131554 3\n", r.out)
        }

    @Test fun stdinAWithNewline() =
        runTest {
            val r = runCksum(stdin = stdin("a\n"))
            assertEquals("2418082923 2\n", r.out)
        }

    @Test fun singleFileShowsFilename() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/x", "abc".encodeToByteArray())
            val r = runCksum(listOf("/x"), fs = fs)
            assertEquals("1219131554 3 /x\n", r.out)
        }

    @Test fun multipleFiles() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "a".encodeToByteArray())
            fs.writeBytes("/b", "abc".encodeToByteArray())
            val r = runCksum(listOf("/a", "/b"), fs = fs)
            val lines = r.out.trimEnd('\n').split('\n')
            assertEquals(2, lines.size)
            assertEquals("1220704766 1 /a", lines[0])
            assertEquals("1219131554 3 /b", lines[1])
        }

    @Test fun missingFile() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/exists", "abc".encodeToByteArray())
            val r = runCksum(listOf("/nope", "/exists"), fs = fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("cksum: /nope"))
            assertTrue(r.out.contains("/exists"))
        }

    @Test fun dashIsStdin() =
        runTest {
            val r = runCksum(listOf("-"), stdin = stdin("abc"))
            assertEquals("1219131554 3 -\n", r.out)
        }

    @Test fun doubleDashTerminator() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-x", "a".encodeToByteArray())
            val r = runCksum(listOf("--", "/-x"), fs = fs)
            assertEquals("1220704766 1 /-x\n", r.out)
        }

    @Test fun unknownOption() =
        runTest {
            val r = runCksum(listOf("-Z"))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("invalid option"))
        }

    @Test fun largeBinaryFileIsStable() =
        runTest {
            val fs = InMemoryFs()
            val payload = ByteArray(2048) { (it and 0xFF).toByte() }
            fs.writeBytes("/p", payload)
            // Compute via helper to cross-check command output uses same engine.
            val (crc, size) = Crc32Cksum.of(payload)
            val r = runCksum(listOf("/p"), fs = fs)
            assertEquals("$crc $size /p\n", r.out)
        }

    @Test fun digitsFixture() =
        runTest {
            val r = runCksum(stdin = stdin("123456789"))
            assertEquals("930766865 9\n", r.out)
        }

    @Test fun threeFilesAllShow() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "".encodeToByteArray())
            fs.writeBytes("/b", "a".encodeToByteArray())
            fs.writeBytes("/c", "abc".encodeToByteArray())
            val r = runCksum(listOf("/a", "/b", "/c"), fs = fs)
            val lines = r.out.trimEnd('\n').split('\n')
            assertEquals(3, lines.size)
            assertEquals("4294967295 0 /a", lines[0])
            assertEquals("1220704766 1 /b", lines[1])
            assertEquals("1219131554 3 /c", lines[2])
        }

    @Test fun mixedStdinAndFile() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/f", "a".encodeToByteArray())
            val r = runCksum(listOf("-", "/f"), stdin = stdin("abc"), fs = fs)
            val lines = r.out.trimEnd('\n').split('\n')
            assertEquals("1219131554 3 -", lines[0])
            assertEquals("1220704766 1 /f", lines[1])
        }

    @Test fun newlineByteIsCountedAsByte() =
        runTest {
            // 5 newlines → byte count 5, CRC includes the bytes.
            val r = runCksum(stdin = stdin("\n\n\n\n\n"))
            val parts = r.out.trim().split(' ')
            assertEquals(2, parts.size)
            assertEquals("5", parts[1])
        }
}
