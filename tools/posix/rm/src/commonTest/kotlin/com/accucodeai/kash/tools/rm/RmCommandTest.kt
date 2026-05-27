package com.accucodeai.kash.tools.rm

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.FsLabel
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.fs.Mount
import com.accucodeai.kash.fs.MountedFileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun runRm(
    fs: FileSystem,
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
    val res = RmCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class RmCommandTest {
    @Test fun removesSingleFile() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/f", "hi".encodeToByteArray())
            val (rc, _, err) = runRm(fs, args = arrayOf("/tmp/f"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/f"))
        }

    @Test fun missingFile_noForce_errors() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runRm(fs, args = arrayOf("/nope"))
            assertEquals(1, rc)
            assertTrue("No such file" in err, err)
        }

    @Test fun missingFile_withForce_ok() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runRm(fs, args = arrayOf("-f", "/nope"))
            assertEquals(0, rc, err)
            assertEquals("", err)
        }

    @Test fun refusesDirectoryWithoutRecursive() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/d")
            val (rc, _, err) = runRm(fs, args = arrayOf("/tmp/d"))
            assertEquals(1, rc)
            assertTrue("Is a directory" in err, err)
            assertTrue(fs.isDirectory("/tmp/d"))
        }

    @Test fun dashDRemovesEmptyDir() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/e")
            val (rc, _, err) = runRm(fs, args = arrayOf("-d", "/tmp/e"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/e"))
        }

    @Test fun dashDRefusesNonEmptyDir() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/e")
            fs.writeBytes("/tmp/e/x", byteArrayOf(1))
            val (rc, _, err) = runRm(fs, args = arrayOf("-d", "/tmp/e"))
            assertEquals(1, rc)
            assertTrue("not empty" in err, err)
        }

    @Test fun recursiveRemovesDirectoryTree() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/tree/a/b")
            fs.writeBytes("/tmp/tree/a/file1", "x".encodeToByteArray())
            fs.writeBytes("/tmp/tree/a/b/file2", "y".encodeToByteArray())
            fs.writeBytes("/tmp/tree/top", "z".encodeToByteArray())
            val (rc, _, err) = runRm(fs, args = arrayOf("-r", "/tmp/tree"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/tree"))
            assertFalse(fs.exists("/tmp/tree/a"))
            assertFalse(fs.exists("/tmp/tree/a/b"))
            assertFalse(fs.exists("/tmp/tree/a/file1"))
            assertFalse(fs.exists("/tmp/tree/a/b/file2"))
            assertFalse(fs.exists("/tmp/tree/top"))
        }

    @Test fun bigRRecursiveAlsoWorks() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/X")
            fs.writeBytes("/tmp/X/y", byteArrayOf(0))
            val (rc, _, _) = runRm(fs, args = arrayOf("-R", "/tmp/X"))
            assertEquals(0, rc)
            assertFalse(fs.exists("/tmp/X"))
        }

    @Test fun forceSuppressesMissingOperands() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runRm(fs, args = arrayOf("-f"))
            assertEquals(0, rc)
            assertEquals("", err)
        }

    @Test fun missingOperand_noForce_fails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runRm(fs)
            assertEquals(1, rc)
            assertTrue("missing operand" in err)
        }

    @Test fun refusesRootByDefault() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runRm(fs, args = arrayOf("-rf", "/"))
            assertEquals(1, rc)
            assertTrue("dangerous" in err, err)
            // Root and contents must still exist.
            assertTrue(fs.isDirectory("/"))
            assertTrue(fs.isDirectory("/tmp"))
        }

    @Test fun noPreserveRootOverride() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runRm(fs, args = arrayOf("-rf", "--no-preserve-root", "/"))
            // Should succeed (or partially) — at minimum /tmp gets nuked.
            assertEquals(0, rc)
            assertFalse(fs.exists("/tmp"))
        }

    @Test fun interactiveFlagAcceptedAsStub() =
        runTest {
            // No TTY model — -i is accepted, behaves like default (no prompt, proceed).
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/x", byteArrayOf(0))
            val (rc, _, err) = runRm(fs, args = arrayOf("-i", "/tmp/x"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/x"))
        }

    @Test fun forceOverridesInteractiveLater() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, _) = runRm(fs, args = arrayOf("-if", "/nope"))
            // -f comes after -i, so force wins; missing file is silently ignored.
            assertEquals(0, rc)
        }

    @Test fun continuesAfterPerOperandError() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/a", byteArrayOf(1))
            fs.writeBytes("/tmp/c", byteArrayOf(2))
            val (rc, _, err) = runRm(fs, args = arrayOf("/tmp/a", "/nope", "/tmp/c"))
            assertEquals(1, rc)
            assertTrue("No such file" in err)
            assertFalse(fs.exists("/tmp/a"))
            assertFalse(fs.exists("/tmp/c"))
        }

    @Test fun doubleDashTerminatesOptions() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-f", byteArrayOf(1))
            val (rc, _, err) = runRm(fs, args = arrayOf("--", "/-f"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/-f"))
        }

    @Test fun resolvesRelativeToCwd() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/rel", byteArrayOf(1))
            val (rc, _, err) = runRm(fs, cwd = "/tmp", args = arrayOf("rel"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/rel"))
        }

    @Test fun unknownOptionFails() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runRm(fs, args = arrayOf("-Z", "/x"))
            assertEquals(2, rc)
            assertTrue("invalid option" in err)
        }

    @Test fun recursiveEmptyDirAlone() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/empty1")
            val (rc, _, _) = runRm(fs, args = arrayOf("-r", "/tmp/empty1"))
            assertEquals(0, rc)
            assertFalse(fs.exists("/tmp/empty1"))
        }

    // --- Symlinks are leaves: rm unlinks the link, never recurses through
    // it (the lstat contract). Regression for the proc-cwd loop where
    // following /proc/<pid>/cwd -> / re-entered /proc forever, blowing up
    // into proc/2/cwd/proc/2/cwd/... ---

    @Test fun recursiveUnlinksSymlinkWithoutTouchingTarget() =
        runTest {
            val fs = InMemoryFs()
            // A directory tree whose only child is a symlink to an
            // out-of-tree directory with real contents.
            fs.mkdirs("/victim")
            fs.writeBytes("/victim/precious", "keep me".encodeToByteArray())
            fs.mkdirs("/tmp/d")
            fs.createSymlink("/tmp/d/link", "/victim")

            val (rc, _, err) = runRm(fs, args = arrayOf("-r", "/tmp/d"))

            assertEquals(0, rc, err)
            assertFalse(fs.exists("/tmp/d"), "tree should be removed")
            // The symlink target and its contents must survive — rm must
            // have unlinked the link, not descended into /victim.
            assertTrue(fs.isDirectory("/victim"), "symlink target deleted!")
            assertTrue(fs.exists("/victim/precious"), "target contents deleted!")
        }

    @Test fun recursiveDoesNotFollowSelfReferentialSymlinkLoop() =
        runTest {
            // Mirror /proc/<pid>/cwd -> a directory that contains the link:
            // a read-only mount with `self` pointing back at its own parent.
            // Pre-fix, rm followed the link and recursed forever, producing
            // paths like .../self/self/self/... and a read-only error per
            // level until SYMLOOP_MAX tripped.
            val ro = InMemoryFs()
            ro.mkdirs("/d")
            ro.createSymlink("/d/self", "/ro/d")
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/ro", ro, FsLabel.USER, readOnly = true),
                    ),
                )

            val (rc, _, err) = runRm(mfs, args = arrayOf("-rf", "/ro/d"))

            // Read-only mount: removal can't succeed, but the walk must be
            // bounded — it treats `self` as a leaf and never builds the
            // pathological repeated path.
            assertEquals(1, rc)
            assertFalse("self/self" in err, "rm recursed through the symlink loop:\n$err")
            assertTrue("self" in err, "expected an error about the symlink leaf:\n$err")
        }

    @Test fun symlinkOperandRemovedNotFollowed() =
        runTest {
            // `rm <symlink-to-dir>` (no -r) removes the link itself — it is
            // not "Is a directory", because lstat sees a symlink.
            val fs = InMemoryFs()
            fs.mkdirs("/realdir")
            fs.createSymlink("/link", "/realdir")
            val (rc, _, err) = runRm(fs, args = arrayOf("/link"))
            assertEquals(0, rc, err)
            assertFalse(fs.exists("/link"), "symlink should be unlinked")
            assertTrue(fs.isDirectory("/realdir"), "target must survive")
        }
}
