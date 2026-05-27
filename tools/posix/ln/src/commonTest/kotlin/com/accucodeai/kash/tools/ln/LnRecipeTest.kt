package com.accucodeai.kash.tools.ln

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

private suspend fun runLn(
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
    val res = LnCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class LnRecipeTest {
    @Test fun hardLink_twoFilesShareInode() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "hi".encodeToByteArray())
            val (rc, _, err) = runLn(fs, args = arrayOf("/a", "/b"))
            assertEquals(0, rc, err)
            // Mutate through /a, observe through /b.
            fs.writeBytes("/a", "bye".encodeToByteArray())
            assertEquals("bye", fs.readBytes("/b").decodeToString())
        }

    @Test fun hardLink_ofDirectory_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/d")
            val (rc, _, err) = runLn(fs, args = arrayOf("/d", "/e"))
            assertEquals(1, rc)
            assertTrue("hard link not allowed" in err, err)
            assertFalse(fs.exists("/e"))
        }

    @Test fun hardLink_missingTarget_errors() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runLn(fs, args = arrayOf("/nope", "/link"))
            assertEquals(1, rc)
            assertTrue("No such file" in err, err)
        }

    @Test fun multiSource_intoDirectory() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "A".encodeToByteArray())
            fs.writeBytes("/b", "B".encodeToByteArray())
            fs.writeBytes("/c", "C".encodeToByteArray())
            fs.mkdirs("/d")
            val (rc, _, err) = runLn(fs, args = arrayOf("/a", "/b", "/c", "/d"))
            assertEquals(0, rc, err)
            assertEquals("A", fs.readBytes("/d/a").decodeToString())
            assertEquals("B", fs.readBytes("/d/b").decodeToString())
            assertEquals("C", fs.readBytes("/d/c").decodeToString())
        }

    @Test fun symlink_storesTargetVerbatim_evenDangling() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runLn(fs, args = arrayOf("-s", "/etc/passwd", "/link"))
            assertEquals(0, rc, err)
            assertEquals("/etc/passwd", fs.readSymlink("/link"))
        }

    @Test fun symlink_targetNotCwdResolved() =
        runTest {
            // Critical: the target string of a symlink is stored verbatim even
            // when relative. cwd must NOT influence it.
            val fs = InMemoryFs()
            fs.mkdirs("/home/user")
            val (rc, _, err) = runLn(fs, cwd = "/home/user", args = arrayOf("-s", "../foo", "bar"))
            assertEquals(0, rc, err)
            assertEquals("../foo", fs.readSymlink("/home/user/bar"))
        }

    @Test fun existingLink_withoutForce_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.createSymlink("/link", "foo")
            val (rc, _, err) = runLn(fs, args = arrayOf("-s", "baz", "/link"))
            assertEquals(1, rc)
            assertTrue("File exists" in err, err)
            assertEquals("foo", fs.readSymlink("/link"))
        }

    @Test fun existingLink_withForce_replaces() =
        runTest {
            val fs = InMemoryFs()
            fs.createSymlink("/link", "foo")
            val (rc, _, err) = runLn(fs, args = arrayOf("-sf", "baz", "/link"))
            assertEquals(0, rc, err)
            assertEquals("baz", fs.readSymlink("/link"))
        }

    @Test fun symlinkIntoDirectory_usesBasename() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/dst")
            val (rc, _, err) = runLn(fs, args = arrayOf("-s", "/some/target", "/dst"))
            assertEquals(0, rc, err)
            assertEquals("/some/target", fs.readSymlink("/dst/target"))
        }

    @Test fun targetDirectoryFlag_multipleSymlinks() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/d")
            val (rc, _, err) = runLn(fs, args = arrayOf("-t", "/d", "-s", "a", "b", "c"))
            assertEquals(0, rc, err)
            assertEquals("a", fs.readSymlink("/d/a"))
            assertEquals("b", fs.readSymlink("/d/b"))
            assertEquals("c", fs.readSymlink("/d/c"))
        }

    @Test fun noTargetDirectoryFlag_refusesExistingDir() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/src", "x".encodeToByteArray())
            fs.mkdirs("/dst")
            val (rc, _, err) = runLn(fs, args = arrayOf("-T", "/src", "/dst"))
            assertEquals(1, rc)
            assertTrue("is a directory" in err, err)
        }

    @Test fun linkPath_isCwdResolved() =
        runTest {
            // Regression guard for the cwd-resolution bug class fixed across
            // tools. `ln -s X bar` from cwd=/home/user must place the link at
            // /home/user/bar.
            val fs = InMemoryFs()
            fs.mkdirs("/home/user")
            val (rc, _, err) = runLn(fs, cwd = "/home/user", args = arrayOf("-s", "/etc/x", "bar"))
            assertEquals(0, rc, err)
            assertEquals("/etc/x", fs.readSymlink("/home/user/bar"))
            assertFalse(fs.exists("/bar"))
        }

    @Test fun missingOperand_errors() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runLn(fs, args = arrayOf())
            assertEquals(2, rc)
            assertTrue("missing" in err, err)
        }

    @Test fun unknownShortFlag_errors() =
        runTest {
            val fs = InMemoryFs()
            val (rc, _, err) = runLn(fs, args = arrayOf("-Z", "/a", "/b"))
            assertEquals(2, rc)
            assertTrue("invalid option" in err, err)
        }
}
