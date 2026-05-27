package com.accucodeai.kash.tools.touch

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun Buffer.contains(s: String): Boolean = peek().readString().contains(s)

private suspend fun runTouch(
    fs: InMemoryFs,
    cwd: String = "/",
    vararg args: String,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = cwd,
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = TouchCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class TouchCommandTest {
    @Test fun dashT_setsMtimeFromPosixStamp() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/f", ByteArray(0))
            // 2026-05-15 14:30:45 UTC → epoch 1779__see toEpochSeconds (computed)
            val (rc, _, err) = runTouch(fs, args = arrayOf("-t", "202605151430.45", "/tmp/f"))
            assertEquals(0, rc, err)
            assertEquals(toEpochSeconds(2026, 5, 15, 14, 30, 45), fs.stat("/tmp/f").mtimeEpochSeconds)
        }

    @Test fun dashR_copiesMtimeFromReference() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/ref", ByteArray(0))
            fs.setMtime("/ref", 12345L)
            fs.writeBytes("/target", ByteArray(0))
            val (rc, _, err) = runTouch(fs, args = arrayOf("-r", "/ref", "/target"))
            assertEquals(0, rc, err)
            assertEquals(12345L, fs.stat("/target").mtimeEpochSeconds)
        }

    @Test fun dashD_acceptsEpochAtForm() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/f", ByteArray(0))
            val (rc, _, err) = runTouch(fs, args = arrayOf("-d", "@99999", "/f"))
            assertEquals(0, rc, err)
            assertEquals(99999L, fs.stat("/f").mtimeEpochSeconds)
        }

    @Test fun dashD_acceptsIso8601() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/f", ByteArray(0))
            val (rc, _, err) = runTouch(fs, args = arrayOf("-d", "2026-05-15T14:30:45Z", "/f"))
            assertEquals(0, rc, err)
            assertEquals(toEpochSeconds(2026, 5, 15, 14, 30, 45), fs.stat("/f").mtimeEpochSeconds)
        }

    @Test fun dashT_invalidStamp_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/f", ByteArray(0))
            val (rc, _, err) = runTouch(fs, args = arrayOf("-t", "garbage", "/f"))
            assertEquals(1, rc)
            assertTrue(err.contains("invalid date format"))
        }

    @Test fun setMtime_unsupported_emitsReadOnlyError() =
        runTest {
            // Wrap InMemoryFs but make setMtime throw to simulate a RO mount.
            val backing = InMemoryFs()
            backing.writeBytes("/f", ByteArray(0))
            val ro =
                object : com.accucodeai.kash.fs.FileSystem by backing {
                    override fun setMtime(
                        path: String,
                        epochSeconds: Long,
                    ) = throw UnsupportedOperationException("ro mount")
                }
            val out = Buffer()
            val err = Buffer()
            val ctx =
                bareCommandContext(
                    fs = ro,
                    env = mutableMapOf(),
                    cwd = "/",
                    stdin = Buffer().asSuspendSource(),
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                )
            val rc = TouchCommand().run(listOf("-t", "202605151430.45", "/f"), ctx)
            assertEquals(1, rc.exitCode)
            assertTrue(err.readString().contains("Read-only file system"))
        }

    @Test fun createsEmptyFile() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runTouch(fs, args = arrayOf("/tmp/new.txt"))
            assertEquals(0, rc, err)
            assertTrue(fs.exists("/tmp/new.txt"))
            assertFalse(fs.isDirectory("/tmp/new.txt"))
            assertEquals(0, fs.readBytes("/tmp/new.txt").size)
        }

    @Test fun existingFile_isNoOp_contentsPreserved() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/exists", "data".encodeToByteArray())
            val (rc, _, err) = runTouch(fs, args = arrayOf("/tmp/exists"))
            assertEquals(0, rc, err)
            assertEquals("data", fs.readBytes("/tmp/exists").decodeToString())
        }

    @Test fun cFlag_doesNotCreate() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runTouch(fs, args = arrayOf("-c", "/tmp/absent"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/absent"))
        }

    @Test fun cFlag_existingFile_stillNoOp() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/p", "x".encodeToByteArray())
            val (rc, _, err) = runTouch(fs, args = arrayOf("-c", "/tmp/p"))
            assertEquals(0, rc, err)
            assertEquals("x", fs.readBytes("/tmp/p").decodeToString())
        }

    @Test fun longNoCreate() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runTouch(fs, args = arrayOf("--no-create", "/tmp/absent2"))
            assertEquals(0, rc)
            assertFalse(fs.exists("/tmp/absent2"))
        }

    @Test fun multipleFiles() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runTouch(fs, args = arrayOf("/tmp/a", "/tmp/b", "/tmp/c"))
            assertEquals(0, rc)
            assertTrue(fs.exists("/tmp/a"))
            assertTrue(fs.exists("/tmp/b"))
            assertTrue(fs.exists("/tmp/c"))
        }

    @Test fun missingParentFails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runTouch(fs, args = arrayOf("/no/where/file"))
            assertEquals(1, rc)
            assertTrue("No such file or directory" in err, err)
            assertFalse(fs.exists("/no/where/file"))
        }

    @Test fun parentIsAFile_fails() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/notadir", byteArrayOf(0))
            val (rc, _, err) = runTouch(fs, args = arrayOf("/tmp/notadir/child"))
            assertEquals(1, rc)
            assertTrue("Not a directory" in err, err)
        }

    @Test fun continuesAfterPerOperandError() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runTouch(fs, args = arrayOf("/tmp/ok1", "/no/where/x", "/tmp/ok2"))
            assertEquals(1, rc)
            assertTrue("No such file" in err)
            assertTrue(fs.exists("/tmp/ok1"))
            assertTrue(fs.exists("/tmp/ok2"))
        }

    @Test fun acceptsAndIgnoresTimeOptions() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runTouch(fs, args = arrayOf("-a", "-m", "/tmp/x1"))
            assertEquals(0, rc, err)
            assertTrue(fs.exists("/tmp/x1"))
        }

    @Test fun acceptsAndIgnoresRefArgWithValue() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/ref", byteArrayOf(0))
            val (rc, _, err) = runTouch(fs, args = arrayOf("-r", "/tmp/ref", "/tmp/x2"))
            assertEquals(0, rc, err)
            assertTrue(fs.exists("/tmp/x2"))
        }

    @Test fun acceptsAndIgnoresTimestamp() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runTouch(fs, args = arrayOf("-t", "202401010000", "/tmp/x3"))
            assertEquals(0, rc)
            assertTrue(fs.exists("/tmp/x3"))
        }

    @Test fun acceptsLongDateArg() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runTouch(fs, args = arrayOf("--date=2024-01-01", "/tmp/x4"))
            assertEquals(0, rc)
            assertTrue(fs.exists("/tmp/x4"))
        }

    @Test fun missingOperand_fails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runTouch(fs)
            assertEquals(1, rc)
            assertTrue("missing operand" in err)
        }

    @Test fun resolvesRelativeToCwd() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runTouch(fs, cwd = "/tmp", args = arrayOf("rel"))
            assertEquals(0, rc, err)
            assertTrue(fs.exists("/tmp/rel"))
        }

    @Test fun doubleDash() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runTouch(fs, args = arrayOf("--", "-weird"))
            assertEquals(0, rc)
            assertTrue(fs.exists("/-weird"))
        }

    @Test fun unknownOptionFails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runTouch(fs, args = arrayOf("-Z", "/tmp/x"))
            assertEquals(2, rc)
            assertTrue("invalid option" in err)
        }

    @Test fun rWithoutValue_fails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runTouch(fs, args = arrayOf("-r"))
            assertEquals(2, rc)
            assertTrue("requires an argument" in err)
        }
}
