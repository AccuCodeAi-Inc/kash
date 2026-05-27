package com.accucodeai.kash.tools.sum

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
import kotlin.test.assertNotEquals
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

private suspend fun runSum(
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
    val res = SumCommand().run(args, ctx)
    return Run(res.exitCode, out.readString(), err.readString())
}

class SumCommandTest {
    // -------- algorithm unit tests --------

    @Test fun bsdEmpty() {
        assertEquals(0, SumCommand.bsd(ByteArray(0)))
    }

    @Test fun bsdSingleA() {
        // rotR(0) = 0; 0 + 97 = 97.
        assertEquals(97, SumCommand.bsd("a".encodeToByteArray()))
    }

    @Test fun bsdAbc() {
        // 'a'=97: rot=0, 0+97=97
        // 'b'=98: rotR(97) = (97>>1)|((97&1)<<15) = 48|32768 = 32816; +98 = 32914
        // 'c'=99: rotR(32914) = (32914>>1)|((32914&1)<<15) = 16457|0 = 16457; +99 = 16556
        assertEquals(16556, SumCommand.bsd("abc".encodeToByteArray()))
    }

    @Test fun sysvEmpty() {
        assertEquals(0, SumCommand.sysv(ByteArray(0)))
    }

    @Test fun sysvAbc() {
        // 97+98+99 = 294 → folds to 294.
        assertEquals(294, SumCommand.sysv("abc".encodeToByteArray()))
    }

    @Test fun sysvFoldsLargeSums() {
        // 70000 bytes of 0xFF: raw sum = 70000*255 = 17_850_000 (0x110_1E50).
        val buf = ByteArray(70000) { 0xFF.toByte() }
        val raw = 70000L * 255L
        val once = (raw and 0xFFFFL) + (raw ushr 16)
        val twice = ((once and 0xFFFFL) + (once ushr 16)).toInt() and 0xFFFF
        assertEquals(twice, SumCommand.sysv(buf))
    }

    // -------- CLI behavior --------

    @Test fun stdinDefaultBsd() =
        runTest {
            val r = runSum(stdin = stdin("a"))
            assertEquals(0, r.exit)
            assertEquals("00097 1\n", r.out)
        }

    @Test fun stdinSysv() =
        runTest {
            val r = runSum(listOf("-s"), stdin = stdin("a"))
            assertEquals("97 1\n", r.out)
        }

    @Test fun blockCountZeroBytes() =
        runTest {
            val r = runSum(stdin = stdin(""))
            assertEquals("00000 0\n", r.out)
        }

    @Test fun blockCountExactly512() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/p", ByteArray(512))
            val r = runSum(listOf("/p"), fs = fs)
            // blocks = 1
            assertTrue(r.out.endsWith(" 1 /p\n"), "got: ${r.out}")
        }

    @Test fun blockCountJustOver512() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/p", ByteArray(513))
            val r = runSum(listOf("/p"), fs = fs)
            assertTrue(r.out.endsWith(" 2 /p\n"), "got: ${r.out}")
        }

    @Test fun blockCount1023() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/p", ByteArray(1023))
            val r = runSum(listOf("/p"), fs = fs)
            assertTrue(r.out.endsWith(" 2 /p\n"), "got: ${r.out}")
        }

    @Test fun bsdAndSysvDiffer() =
        runTest {
            val bsd = runSum(stdin = stdin("hello world\n"))
            val sysv = runSum(listOf("-s"), stdin = stdin("hello world\n"))
            assertNotEquals(bsd.out, sysv.out)
        }

    @Test fun singleFileShowsName() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/x", "abc".encodeToByteArray())
            val r = runSum(listOf("/x"), fs = fs)
            assertEquals("16556 1 /x\n", r.out)
        }

    @Test fun multipleFiles() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "a".encodeToByteArray())
            fs.writeBytes("/b", "abc".encodeToByteArray())
            val r = runSum(listOf("/a", "/b"), fs = fs)
            val lines = r.out.trimEnd('\n').split('\n')
            assertEquals(2, lines.size)
            assertEquals("00097 1 /a", lines[0])
            assertEquals("16556 1 /b", lines[1])
        }

    @Test fun missingFile() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/exists", "a".encodeToByteArray())
            val r = runSum(listOf("/nope", "/exists"), fs = fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("sum: /nope"))
            assertTrue(r.out.contains("/exists"))
        }

    @Test fun dashOperandReadsStdin() =
        runTest {
            val r = runSum(listOf("-"), stdin = stdin("a"))
            // single operand with stdin name; the name is omitted for sole stdin.
            assertEquals("00097 1\n", r.out)
        }

    @Test fun unknownOption() =
        runTest {
            val r = runSum(listOf("-Z"))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("invalid option"))
        }

    @Test fun rOverridesS() =
        runTest {
            val r = runSum(listOf("-s", "-r"), stdin = stdin("a"))
            // -r last → BSD format
            assertEquals("00097 1\n", r.out)
        }

    @Test fun doubleDashTerminator() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-r", "a".encodeToByteArray())
            val r = runSum(listOf("--", "/-r"), fs = fs)
            // Treated as filename, not BSD switch
            assertEquals("00097 1 /-r\n", r.out)
        }
}
