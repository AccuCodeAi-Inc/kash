package com.accucodeai.kash.tools.cp

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun runCp(
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
    val res = CpCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class CpCommandTest {
    @Test fun missingOperand_errors() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runCp(fs)
            assertEquals(1, rc)
            assertTrue("missing file operand" in err, err)
        }

    @Test fun missingDestOperand_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "x".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("/a"))
            assertEquals(1, rc)
            assertTrue("missing destination" in err, err)
        }

    @Test fun copySingleFileToNewPath() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "hello".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("/a", "/b"))
            assertEquals(0, rc, err)
            assertEquals("hello", fs.readBytes("/b").decodeToString())
        }

    @Test fun copyOverwritesExistingFile() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "new".encodeToByteArray())
            fs.writeBytes("/b", "old".encodeToByteArray())
            val (rc, _, _) = runCp(fs, args = arrayOf("/a", "/b"))
            assertEquals(0, rc)
            assertEquals("new", fs.readBytes("/b").decodeToString())
        }

    @Test fun copyIntoExistingDirectoryLandsAtBasename() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "hi".encodeToByteArray())
            fs.mkdirs("/d")
            val (rc, _, err) = runCp(fs, args = arrayOf("/a", "/d"))
            assertEquals(0, rc, err)
            assertEquals("hi", fs.readBytes("/d/a").decodeToString())
        }

    @Test fun multiSourceIntoDirectory() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "A".encodeToByteArray())
            fs.writeBytes("/b", "B".encodeToByteArray())
            fs.writeBytes("/c", "C".encodeToByteArray())
            fs.mkdirs("/dest")
            val (rc, _, err) = runCp(fs, args = arrayOf("/a", "/b", "/c", "/dest"))
            assertEquals(0, rc, err)
            assertEquals("A", fs.readBytes("/dest/a").decodeToString())
            assertEquals("B", fs.readBytes("/dest/b").decodeToString())
            assertEquals("C", fs.readBytes("/dest/c").decodeToString())
        }

    @Test fun multiSourceWhereDestNotDir_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "A".encodeToByteArray())
            fs.writeBytes("/b", "B".encodeToByteArray())
            fs.writeBytes("/dest", "x".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("/a", "/b", "/dest"))
            assertEquals(1, rc)
            assertTrue("not a directory" in err, err)
        }

    @Test fun copyDirectoryWithoutRecursive_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/d1")
            fs.writeBytes("/d1/f", "x".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("/d1", "/d2"))
            assertEquals(1, rc)
            assertTrue("omitting directory" in err, err)
            assertFalse(fs.exists("/d2"))
        }

    @Test fun recursiveCopyToNewDest_dirCreated() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/d1/sub")
            fs.writeBytes("/d1/top", "T".encodeToByteArray())
            fs.writeBytes("/d1/sub/inner", "I".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("-r", "/d1", "/d2"))
            assertEquals(0, rc, err)
            assertTrue(fs.isDirectory("/d2"))
            assertTrue(fs.isDirectory("/d2/sub"))
            assertEquals("T", fs.readBytes("/d2/top").decodeToString())
            assertEquals("I", fs.readBytes("/d2/sub/inner").decodeToString())
        }

    @Test fun recursiveCopyWhereDestExists_landsAsSubdir() =
        runTest {
            // POSIX: cp -r d1 d2 where d2 exists -> creates d2/d1.
            val fs = InMemoryFs()
            fs.mkdirs("/d1")
            fs.writeBytes("/d1/x", "X".encodeToByteArray())
            fs.mkdirs("/d2")
            val (rc, _, err) = runCp(fs, args = arrayOf("-r", "/d1", "/d2"))
            assertEquals(0, rc, err)
            assertTrue(fs.isDirectory("/d2/d1"))
            assertEquals("X", fs.readBytes("/d2/d1/x").decodeToString())
        }

    @Test fun noClobberSkipsExisting() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "new".encodeToByteArray())
            fs.writeBytes("/b", "old".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("-n", "/a", "/b"))
            assertEquals(0, rc, err)
            assertEquals("old", fs.readBytes("/b").decodeToString())
        }

    @Test fun preservePreservesModeAndMtime() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "x".encodeToByteArray())
            fs.chmod("/a", 0b111_000_000) // 0700
            fs.setMtime("/a", 123456L)
            val (rc, _, err) = runCp(fs, args = arrayOf("-p", "/a", "/b"))
            assertEquals(0, rc, err)
            val s = fs.stat("/b")
            assertEquals(0b111_000_000, s.mode and 0b111_111_111)
            assertEquals(123456L, s.mtimeEpochSeconds)
        }

    @Test fun bigPDoesntFollowSymlink_recreatesLink() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/target", "T".encodeToByteArray())
            fs.createSymlink("/link", "/target")
            val (rc, _, err) = runCp(fs, args = arrayOf("-P", "/link", "/copy"))
            assertEquals(0, rc, err)
            assertEquals(FileType.SYMLINK, fs.statLink("/copy").type)
            assertEquals("/target", fs.readSymlink("/copy"))
        }

    @Test fun bigLFollowsSymlinkByDefault() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/target", "T".encodeToByteArray())
            fs.createSymlink("/link", "/target")
            val (rc, _, err) = runCp(fs, args = arrayOf("/link", "/copy"))
            assertEquals(0, rc, err)
            // Should be a regular file with the target's content.
            assertEquals(FileType.REGULAR, fs.statLink("/copy").type)
            assertEquals("T", fs.readBytes("/copy").decodeToString())
        }

    @Test fun sameFile_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "x".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("/a", "/a"))
            assertEquals(1, rc)
            assertTrue("same file" in err, err)
        }

    @Test fun targetDirectoryFlag() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "A".encodeToByteArray())
            fs.writeBytes("/b", "B".encodeToByteArray())
            fs.writeBytes("/c", "C".encodeToByteArray())
            fs.mkdirs("/dest")
            val (rc, _, err) = runCp(fs, args = arrayOf("-t", "/dest", "/a", "/b", "/c"))
            assertEquals(0, rc, err)
            assertEquals("A", fs.readBytes("/dest/a").decodeToString())
            assertEquals("B", fs.readBytes("/dest/b").decodeToString())
            assertEquals("C", fs.readBytes("/dest/c").decodeToString())
        }

    @Test fun relativePathResolvesAgainstCwd() =
        runTest {
            // Regression guard for the path-resolution bug-class.
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/src", "x".encodeToByteArray())
            val (rc, _, err) = runCp(fs, cwd = "/work", args = arrayOf("src", "dst"))
            assertEquals(0, rc, err)
            assertEquals("x", fs.readBytes("/work/dst").decodeToString())
        }

    @Test fun recursiveCopyPreservesDeepStructure() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/src/a/b/c")
            fs.writeBytes("/src/top", "0".encodeToByteArray())
            fs.writeBytes("/src/a/one", "1".encodeToByteArray())
            fs.writeBytes("/src/a/b/two", "2".encodeToByteArray())
            fs.writeBytes("/src/a/b/c/three", "3".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("-r", "/src", "/dst"))
            assertEquals(0, rc, err)
            assertEquals("0", fs.readBytes("/dst/top").decodeToString())
            assertEquals("1", fs.readBytes("/dst/a/one").decodeToString())
            assertEquals("2", fs.readBytes("/dst/a/b/two").decodeToString())
            assertEquals("3", fs.readBytes("/dst/a/b/c/three").decodeToString())
        }

    @Test fun verboseEmitsArrowLines() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "x".encodeToByteArray())
            val (rc, out, err) = runCp(fs, args = arrayOf("-v", "/a", "/b"))
            assertEquals(0, rc, err)
            assertTrue("'/a' -> '/b'" in out, out)
        }

    @Test fun forceOverwritesAfterRemove() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "new".encodeToByteArray())
            fs.writeBytes("/b", "old".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("-f", "/a", "/b"))
            assertEquals(0, rc, err)
            assertEquals("new", fs.readBytes("/b").decodeToString())
        }

    @Test fun bigTRefusesToCopyIntoExistingDir() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "x".encodeToByteArray())
            fs.mkdirs("/d")
            val (rc, _, err) = runCp(fs, args = arrayOf("-T", "/a", "/d"))
            assertEquals(1, rc)
            assertTrue("cannot overwrite directory" in err, err)
        }

    @Test fun targetDirThatDoesntExist_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "x".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("-t", "/nope", "/a"))
            assertEquals(1, rc)
            assertTrue("does not exist" in err, err)
        }

    @Test fun continuesAfterPerOperandError() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "A".encodeToByteArray())
            fs.writeBytes("/c", "C".encodeToByteArray())
            fs.mkdirs("/dest")
            val (rc, _, err) = runCp(fs, args = arrayOf("/a", "/nope", "/c", "/dest"))
            assertEquals(1, rc)
            assertTrue("No such file" in err, err)
            assertEquals("A", fs.readBytes("/dest/a").decodeToString())
            assertEquals("C", fs.readBytes("/dest/c").decodeToString())
        }

    @Test fun missingSource_errors() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runCp(fs, args = arrayOf("/nope", "/dst"))
            assertEquals(1, rc)
            assertTrue("No such file" in err, err)
            assertFalse(fs.exists("/dst"))
        }

    @Test fun doubleDashEndsOptions() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-r", "x".encodeToByteArray())
            val (rc, _, err) = runCp(fs, args = arrayOf("--", "/-r", "/dst"))
            assertEquals(0, rc, err)
            assertEquals("x", fs.readBytes("/dst").decodeToString())
        }

    @Test fun unknownFlag_errors() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runCp(fs, args = arrayOf("-Z", "/a", "/b"))
            assertEquals(2, rc)
            assertTrue("invalid option" in err, err)
        }
}
