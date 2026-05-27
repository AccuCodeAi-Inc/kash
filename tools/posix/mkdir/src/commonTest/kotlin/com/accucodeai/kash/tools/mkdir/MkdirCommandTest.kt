package com.accucodeai.kash.tools.mkdir

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

class MkdirModeApplyTest {
    @Test fun dashM_octalModeIsAppliedViaChmod() =
        runTest {
            val fs = InMemoryFs()
            val out = Buffer()
            val err = Buffer()
            val ctx =
                bareCommandContext(
                    fs = fs,
                    env = mutableMapOf(),
                    cwd = "/",
                    stdin = Buffer().asSuspendSource(),
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                )
            val rc = MkdirCommand().run(listOf("-m", "700", "/tmp/secret"), ctx)
            assertEquals(0, rc.exitCode, err.readString())
            assertEquals(0b111_000_000, fs.stat("/tmp/secret").mode)
        }
}

private suspend fun runMkdir(
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
    val res = MkdirCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class MkdirCommandTest {
    @Test fun createsSingleDirectory() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runMkdir(fs, args = arrayOf("/tmp/new"))
            assertEquals(0, rc, err)
            assertTrue(fs.isDirectory("/tmp/new"))
        }

    @Test fun createsMultipleDirectories() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runMkdir(fs, args = arrayOf("/tmp/a", "/tmp/b", "/tmp/c"))
            assertEquals(0, rc, err)
            assertTrue(fs.isDirectory("/tmp/a"))
            assertTrue(fs.isDirectory("/tmp/b"))
            assertTrue(fs.isDirectory("/tmp/c"))
        }

    @Test fun missingParent_withoutP_fails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runMkdir(fs, args = arrayOf("/tmp/missing/deep"))
            assertEquals(1, rc)
            assertTrue("No such file or directory" in err, err)
            assertFalse(fs.exists("/tmp/missing/deep"))
        }

    @Test fun pCreatesParents() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runMkdir(fs, args = arrayOf("-p", "/tmp/a/b/c"))
            assertEquals(0, rc)
            assertTrue(fs.isDirectory("/tmp/a"))
            assertTrue(fs.isDirectory("/tmp/a/b"))
            assertTrue(fs.isDirectory("/tmp/a/b/c"))
        }

    @Test fun pDoesNotErrorOnExistingDir() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/exists")
            val (rc, _, err) = runMkdir(fs, args = arrayOf("-p", "/tmp/exists"))
            assertEquals(0, rc, err)
            assertTrue(fs.isDirectory("/tmp/exists"))
        }

    @Test fun withoutP_errorsOnExistingDir() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/exists")
            val (rc, _, err) = runMkdir(fs, args = arrayOf("/tmp/exists"))
            assertEquals(1, rc)
            assertTrue("File exists" in err, err)
        }

    @Test fun longOptionParents() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runMkdir(fs, args = arrayOf("--parents", "/tmp/x/y"))
            assertEquals(0, rc)
            assertTrue(fs.isDirectory("/tmp/x/y"))
        }

    @Test fun modeFlagAcceptedAndIgnored() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runMkdir(fs, args = arrayOf("-m", "755", "/tmp/d"))
            assertEquals(0, rc, err)
            assertTrue(fs.isDirectory("/tmp/d"))
        }

    @Test fun modeFlagLongForm() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runMkdir(fs, args = arrayOf("--mode=0700", "/tmp/d2"))
            assertEquals(0, rc)
            assertTrue(fs.isDirectory("/tmp/d2"))
        }

    @Test fun invalidModeFails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runMkdir(fs, args = arrayOf("-m", "notamode!", "/tmp/d3"))
            assertEquals(1, rc)
            assertTrue("invalid mode" in err, err)
        }

    @Test fun continuesAfterPerOperandError() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/already")
            val (rc, _, err) =
                runMkdir(fs, args = arrayOf("/tmp/already", "/tmp/fresh"))
            assertEquals(1, rc)
            assertTrue("File exists" in err)
            assertTrue(fs.isDirectory("/tmp/fresh"))
        }

    @Test fun resolvesRelativeToCwd() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runMkdir(fs, cwd = "/tmp", args = arrayOf("rel"))
            assertEquals(0, rc)
            assertTrue(fs.isDirectory("/tmp/rel"))
        }

    @Test fun missingOperand_fails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runMkdir(fs)
            assertEquals(1, rc)
            assertTrue("missing operand" in err, err)
        }

    @Test fun fileBlocksDirectory_p() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/afile", byteArrayOf(1, 2, 3))
            val (rc, _, err) = runMkdir(fs, args = arrayOf("-p", "/tmp/afile"))
            assertEquals(1, rc)
            assertTrue("File exists" in err, err)
        }

    @Test fun doubleDashTerminatesOptions() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runMkdir(fs, args = arrayOf("--", "-weirddir"))
            assertEquals(0, rc)
            assertTrue(fs.isDirectory("/-weirddir"))
        }

    @Test fun unknownOptionFails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runMkdir(fs, args = arrayOf("-Z", "/tmp/x"))
            assertEquals(2, rc)
            assertTrue("invalid option" in err, err)
        }
}
