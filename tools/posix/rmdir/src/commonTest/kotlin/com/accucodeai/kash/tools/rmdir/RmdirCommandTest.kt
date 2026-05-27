package com.accucodeai.kash.tools.rmdir

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

private suspend fun runRmdir(
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
    val res = RmdirCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class RmdirCommandTest {
    @Test fun removesEmptyDirectory() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/empty")
            val (rc, _, err) = runRmdir(fs, args = arrayOf("/tmp/empty"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/empty"))
        }

    @Test fun refusesNonEmptyDirectory() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/d")
            fs.writeBytes("/tmp/d/file", "x".encodeToByteArray())
            val (rc, _, err) = runRmdir(fs, args = arrayOf("/tmp/d"))
            assertEquals(1, rc)
            assertTrue("not empty" in err, err)
            assertTrue(fs.isDirectory("/tmp/d"))
        }

    @Test fun missingDirectoryFails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runRmdir(fs, args = arrayOf("/nope"))
            assertEquals(1, rc)
            assertTrue("No such file" in err, err)
        }

    @Test fun refusesRegularFile() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/afile", byteArrayOf(1, 2))
            val (rc, _, err) = runRmdir(fs, args = arrayOf("/tmp/afile"))
            assertEquals(1, rc)
            assertTrue("Not a directory" in err, err)
            assertTrue(fs.exists("/tmp/afile"))
        }

    @Test fun pRemovesEmptyParents() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/a/b/c")
            val (rc, _, err) = runRmdir(fs, args = arrayOf("-p", "/tmp/a/b/c"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/a/b/c"))
            assertFalse(fs.exists("/tmp/a/b"))
            assertFalse(fs.exists("/tmp/a"))
            // /tmp is preseeded — should still exist (it became empty but we stop at root-adjacent).
            // /tmp is empty after removal, so it gets removed too. That's expected.
        }

    @Test fun pStopsAtNonEmptyParent() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/a/b/c")
            fs.writeBytes("/tmp/a/sibling", byteArrayOf(1))
            val (rc, _, err) = runRmdir(fs, args = arrayOf("-p", "/tmp/a/b/c"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/a/b/c"))
            assertFalse(fs.exists("/tmp/a/b"))
            // /tmp/a has the sibling file, so it must remain.
            assertTrue(fs.isDirectory("/tmp/a"))
        }

    @Test fun continuesAfterPerOperandError() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/keep")
            fs.writeBytes("/tmp/keep/x", byteArrayOf(0))
            fs.mkdirs("/tmp/gone")
            val (rc, _, err) = runRmdir(fs, args = arrayOf("/tmp/keep", "/tmp/gone"))
            assertEquals(1, rc)
            assertTrue("not empty" in err, err)
            assertTrue(fs.isDirectory("/tmp/keep"))
            assertFalse(fs.exists("/tmp/gone"))
        }

    @Test fun missingOperand_fails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runRmdir(fs)
            assertEquals(1, rc)
            assertTrue("missing operand" in err)
        }

    @Test fun resolvesRelativeToCwd() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/rel")
            val (rc, _, err) = runRmdir(fs, cwd = "/tmp", args = arrayOf("rel"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/rel"))
        }

    @Test fun unknownOptionFails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runRmdir(fs, args = arrayOf("-Z", "/tmp"))
            assertEquals(2, rc)
            assertTrue("invalid option" in err)
        }

    @Test fun doubleDash() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/-weirddir")
            val (rc, _, err) = runRmdir(fs, args = arrayOf("--", "/-weirddir"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/-weirddir"))
        }
}
