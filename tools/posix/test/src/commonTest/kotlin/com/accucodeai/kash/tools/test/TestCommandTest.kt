package com.accucodeai.kash.tools.test

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

private suspend fun runTestCmd(
    fs: InMemoryFs = InMemoryFs(),
    vararg args: String,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = TestCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class TestCommandTest {
    @Test fun eqOnNonNumericErrors() =
        runTest {
            val (rc, _, err) = runTestCmd(args = arrayOf("abc", "-eq", "1"))
            assertEquals(2, rc)
            assertTrue("integer expression expected" in err, err)
        }

    @Test fun eqOnNumericMatches() =
        runTest {
            val (rc, _, _) = runTestCmd(args = arrayOf("1", "-eq", "1"))
            assertEquals(0, rc)
        }

    @Test fun setuidBitDetected() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/u", byteArrayOf())
            fs.chmod("/tmp/u", 0b100_000_000_000 or 0b110_100_100)
            val (rc, _, _) = runTestCmd(fs, args = arrayOf("-u", "/tmp/u"))
            assertEquals(0, rc)
        }

    @Test fun setuidBitAbsentIsFalse() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/u2", byteArrayOf())
            val (rc, _, _) = runTestCmd(fs, args = arrayOf("-u", "/tmp/u2"))
            assertEquals(1, rc)
        }

    @Test fun setgidBitDetected() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/g", byteArrayOf())
            fs.chmod("/tmp/g", 0b010_000_000_000 or 0b110_100_100)
            val (rc, _, _) = runTestCmd(fs, args = arrayOf("-g", "/tmp/g"))
            assertEquals(0, rc)
        }

    @Test fun stickyBitDetected() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/sticky", 0b001_111_111_111)
            val (rc, _, _) = runTestCmd(fs, args = arrayOf("-k", "/tmp/sticky"))
            assertEquals(0, rc)
        }

    @Test fun blockCharFifoSocketOnRegularFalse() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/r", byteArrayOf(1))
            assertEquals(1, runTestCmd(fs, args = arrayOf("-b", "/tmp/r")).first)
            assertEquals(1, runTestCmd(fs, args = arrayOf("-c", "/tmp/r")).first)
            assertEquals(1, runTestCmd(fs, args = arrayOf("-p", "/tmp/r")).first)
            assertEquals(1, runTestCmd(fs, args = arrayOf("-S", "/tmp/r")).first)
        }

    @Test fun blockCharFifoSocketOnMissingFalse() =
        runTest {
            assertEquals(1, runTestCmd(args = arrayOf("-b", "/nope")).first)
            assertEquals(1, runTestCmd(args = arrayOf("-S", "/nope")).first)
        }
}
