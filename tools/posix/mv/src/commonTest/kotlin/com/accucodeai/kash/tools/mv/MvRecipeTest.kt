package com.accucodeai.kash.tools.mv

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

private suspend fun runMv(
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
    val rc = MvCommand().run(args.toList(), ctx).exitCode
    return Triple(rc, out.readString(), err.readString())
}

class MvRecipeTest {
    // 1. Rename a single file within the same directory.
    @Test fun renameSingleFileSameDir() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "hello".encodeToByteArray())
            fs.chmod("/tmp/a", 0b110_100_100) // 0644
            val (rc, _, err) = runMv(fs, args = arrayOf("/tmp/a", "/tmp/b"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/a"))
            assertTrue(fs.exists("/tmp/b"))
            assertEquals("hello", fs.readBytes("/tmp/b").decodeToString())
            assertEquals(0b110_100_100, fs.stat("/tmp/b").mode)
        }

    // 2. Move single file into an existing directory.
    @Test fun moveSingleFileIntoDirectory() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "x".encodeToByteArray())
            fs.mkdirs("/tmp/dest")
            val (rc, _, err) = runMv(fs, args = arrayOf("/tmp/a", "/tmp/dest"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/a"))
            assertTrue(fs.exists("/tmp/dest/a"))
        }

    // 3. Multiple sources into a directory.
    @Test fun multipleSourcesIntoDir() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "1".encodeToByteArray())
            fs.writeBytes("/tmp/b", "2".encodeToByteArray())
            fs.writeBytes("/tmp/c", "3".encodeToByteArray())
            fs.mkdirs("/tmp/d")
            val (rc, _, err) = runMv(fs, args = arrayOf("/tmp/a", "/tmp/b", "/tmp/c", "/tmp/d"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/a"))
            assertFalse(fs.exists("/tmp/b"))
            assertFalse(fs.exists("/tmp/c"))
            assertEquals("1", fs.readBytes("/tmp/d/a").decodeToString())
            assertEquals("2", fs.readBytes("/tmp/d/b").decodeToString())
            assertEquals("3", fs.readBytes("/tmp/d/c").decodeToString())
        }

    // 4. Move a directory tree.
    @Test fun moveDirectoryTreeRecursive() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/src/sub")
            fs.writeBytes("/tmp/src/a", "A".encodeToByteArray())
            fs.writeBytes("/tmp/src/sub/b", "B".encodeToByteArray())
            val (rc, _, err) = runMv(fs, args = arrayOf("/tmp/src", "/tmp/dst"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/src"))
            assertFalse(fs.exists("/tmp/src/a"))
            assertFalse(fs.exists("/tmp/src/sub"))
            assertFalse(fs.exists("/tmp/src/sub/b"))
            assertTrue(fs.isDirectory("/tmp/dst"))
            assertTrue(fs.isDirectory("/tmp/dst/sub"))
            assertEquals("A", fs.readBytes("/tmp/dst/a").decodeToString())
            assertEquals("B", fs.readBytes("/tmp/dst/sub/b").decodeToString())
        }

    // 5. -n preserves an existing destination.
    @Test fun noClobberPreservesDest() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "new".encodeToByteArray())
            fs.writeBytes("/tmp/b", "old".encodeToByteArray())
            val (rc, _, _) = runMv(fs, args = arrayOf("-n", "/tmp/a", "/tmp/b"))
            assertEquals(0, rc)
            assertTrue(fs.exists("/tmp/a"), "source preserved when dest exists and -n")
            assertEquals("old", fs.readBytes("/tmp/b").decodeToString())
        }

    // 6. -f overwrites cleanly.
    @Test fun forceOverwrites() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "new".encodeToByteArray())
            fs.writeBytes("/tmp/b", "old".encodeToByteArray())
            val (rc, _, err) = runMv(fs, args = arrayOf("-f", "/tmp/a", "/tmp/b"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/a"))
            assertEquals("new", fs.readBytes("/tmp/b").decodeToString())
        }

    // 7. mv src src errors.
    @Test fun sameFileErrors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "x".encodeToByteArray())
            val (rc, _, err) = runMv(fs, args = arrayOf("/tmp/a", "/tmp/a"))
            assertEquals(1, rc)
            assertTrue("same file" in err, err)
            assertTrue(fs.exists("/tmp/a"))
        }

    // 8. Relative paths that resolve equal still detected.
    @Test fun sameFileViaDifferentRelativePaths() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "x".encodeToByteArray())
            val (rc, _, err) = runMv(fs, cwd = "/tmp", args = arrayOf("a", "./a"))
            assertEquals(1, rc)
            assertTrue("same file" in err, err)
            assertTrue(fs.exists("/tmp/a"))
        }

    // 9. -t targets a directory.
    @Test fun targetDirectoryOption() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "1".encodeToByteArray())
            fs.writeBytes("/tmp/b", "2".encodeToByteArray())
            fs.writeBytes("/tmp/c", "3".encodeToByteArray())
            fs.mkdirs("/tmp/dest")
            val (rc, _, err) = runMv(fs, args = arrayOf("-t", "/tmp/dest", "/tmp/a", "/tmp/b", "/tmp/c"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/a"))
            assertTrue(fs.exists("/tmp/dest/a"))
            assertTrue(fs.exists("/tmp/dest/b"))
            assertTrue(fs.exists("/tmp/dest/c"))
        }

    // 10. -T refuses to move into an existing directory.
    @Test fun bigTRefusesExistingDirAsDest() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "x".encodeToByteArray())
            fs.mkdirs("/tmp/dest")
            val (rc, _, err) = runMv(fs, args = arrayOf("-T", "/tmp/a", "/tmp/dest"))
            assertEquals(1, rc)
            assertTrue("directory" in err, err)
            assertTrue(fs.exists("/tmp/a"), "source preserved")
            assertTrue(fs.isDirectory("/tmp/dest"))
            assertFalse(fs.exists("/tmp/dest/a"))
        }

    // 11. Symlink mv preserves the link target, doesn't follow.
    @Test fun symlinkPreservedNotFollowed() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/target", "real".encodeToByteArray())
            fs.createSymlink("/tmp/link", "target")
            val (rc, _, err) = runMv(fs, args = arrayOf("/tmp/link", "/tmp/link2"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/link"))
            assertTrue(fs.exists("/tmp/link2"))
            // Original target still exists and is unmoved.
            assertTrue(fs.exists("/tmp/target"))
            assertEquals("real", fs.readBytes("/tmp/target").decodeToString())
            // The new link should still be a symlink, target verbatim.
            assertEquals("target", fs.readSymlink("/tmp/link2"))
        }

    // 12. mtime preserved across mv.
    @Test fun mtimePreservedAcrossMv() =
        runTest {
            val fs = InMemoryFs(clock = { 999L })
            fs.writeBytes("/tmp/a", "x".encodeToByteArray())
            fs.setMtime("/tmp/a", 42L)
            val (rc, _, err) = runMv(fs, args = arrayOf("/tmp/a", "/tmp/b"))
            assertEquals(0, rc, err)
            assertEquals(42L, fs.stat("/tmp/b").mtimeEpochSeconds)
        }

    // 13. Relative path operand resolves against non-/ cwd.
    @Test fun relativePathsResolveAgainstCwd() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/foo", "hi".encodeToByteArray())
            val (rc, _, err) = runMv(fs, cwd = "/work", args = arrayOf("foo", "bar"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/work/foo"))
            assertTrue(fs.exists("/work/bar"))
        }

    // 14. Destination parent missing → error, source preserved.
    @Test fun missingDestParentErrors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "x".encodeToByteArray())
            val (rc, _, err) = runMv(fs, args = arrayOf("/tmp/a", "/nope/here/dest"))
            assertEquals(1, rc)
            assertTrue("No such" in err || "cannot move" in err, err)
            assertTrue(fs.exists("/tmp/a"), "source preserved on failure")
        }

    // Extra: missing source.
    @Test fun missingSourceErrors() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runMv(fs, args = arrayOf("/nope", "/tmp/x"))
            assertEquals(1, rc)
            assertTrue("cannot stat" in err, err)
        }

    // Extra: -v prints the rename line.
    @Test fun verboseEmitsRenamedLine() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "x".encodeToByteArray())
            val (rc, out, err) = runMv(fs, args = arrayOf("-v", "/tmp/a", "/tmp/b"))
            assertEquals(0, rc, err)
            assertTrue("renamed" in out && "/tmp/a" in out && "/tmp/b" in out, "got: $out")
        }

    // Extra: -- ends options.
    @Test fun doubleDashEndsOptions() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-f", "x".encodeToByteArray())
            val (rc, _, err) = runMv(fs, args = arrayOf("--", "/-f", "/tmp/dash"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/-f"))
            assertTrue(fs.exists("/tmp/dash"))
        }

    // Extra: multiple sources with non-dir dest errors.
    @Test fun multipleSourcesNonDirDestErrors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "x".encodeToByteArray())
            fs.writeBytes("/tmp/b", "y".encodeToByteArray())
            fs.writeBytes("/tmp/c", "z".encodeToByteArray())
            val (rc, _, err) = runMv(fs, args = arrayOf("/tmp/a", "/tmp/b", "/tmp/c"))
            assertEquals(1, rc)
            assertTrue("not a directory" in err, err)
            assertTrue(fs.exists("/tmp/a"))
            assertTrue(fs.exists("/tmp/b"))
        }

    // Extra: continuing past per-operand errors.
    @Test fun continuesPastPerOperandErrors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", "x".encodeToByteArray())
            fs.writeBytes("/tmp/c", "z".encodeToByteArray())
            fs.mkdirs("/tmp/dest")
            val (rc, _, err) =
                runMv(fs, args = arrayOf("-t", "/tmp/dest", "/tmp/a", "/nope", "/tmp/c"))
            assertEquals(1, rc)
            assertTrue("cannot stat" in err, err)
            assertTrue(fs.exists("/tmp/dest/a"))
            assertTrue(fs.exists("/tmp/dest/c"))
        }

    // Extra: mode preserved on directory mv.
    @Test fun modePreservedOnDirMv() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/src")
            fs.writeBytes("/tmp/src/f", "x".encodeToByteArray())
            fs.chmod("/tmp/src/f", 0b111_111_111) // 0777
            val (rc, _, err) = runMv(fs, args = arrayOf("/tmp/src", "/tmp/dst"))
            assertEquals(0, rc, err)
            assertEquals(0b111_111_111, fs.stat("/tmp/dst/f").mode)
        }
}
