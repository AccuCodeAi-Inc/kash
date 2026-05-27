package com.accucodeai.kash.tools.patch

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.diff.DiffCommand
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatchCommandTest {
    private class Run(
        val exit: Int,
        val out: String,
        val err: String,
    )

    private suspend fun patch(
        fs: InMemoryFs,
        vararg args: String,
        stdin: String = "",
    ): Run {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                cwd = "/",
                stdin = Buffer().apply { writeBytes(stdin.encodeToByteArray()) }.asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val rc = PatchCommand().run(args.toList(), ctx)
        return Run(rc.exitCode, out.readString(), err.readString())
    }

    private suspend fun diff(
        fs: InMemoryFs,
        vararg args: String,
    ): String {
        val out = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                cwd = "/",
                stdout = out.asSuspendSink(),
                stderr = Buffer().asSuspendSink(),
            )
        DiffCommand().run(args.toList(), ctx)
        return out.readString()
    }

    private suspend fun write(
        fs: InMemoryFs,
        path: String,
        content: String,
    ) = fs.writeBytes(path, content.encodeToByteArray())

    private suspend fun read(
        fs: InMemoryFs,
        path: String,
    ) = fs.readBytes(path).decodeToString()

    @Test fun roundTripUnified() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "1\n2\n3\n4\n5\n")
            write(fs, "/b", "1\nX\n3\n4\nY\n")
            val patchText = diff(fs, "-u", "/a", "/b")
            assertTrue(patchText.isNotEmpty())
            val r = patch(fs, "/a", stdin = patchText)
            assertEquals(0, r.exit, r.err)
            assertEquals("1\nX\n3\n4\nY\n", read(fs, "/a"))
        }

    @Test fun roundTripNormal() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb\nc\n")
            write(fs, "/b", "a\nB\nc\n")
            val patchText = diff(fs, "/a", "/b")
            val r = patch(fs, "/a", stdin = patchText)
            assertEquals(0, r.exit, r.err)
            assertEquals("a\nB\nc\n", read(fs, "/a"))
        }

    @Test fun roundTripContext() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "1\n2\n3\n4\n5\n")
            write(fs, "/b", "1\n2\nX\n4\n5\n")
            val patchText = diff(fs, "-c", "/a", "/b")
            val r = patch(fs, "/a", stdin = patchText)
            assertEquals(0, r.exit, r.err)
            assertEquals("1\n2\nX\n4\n5\n", read(fs, "/a"))
        }

    @Test fun reverseRoundTrip() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "1\n2\n3\n")
            write(fs, "/b", "1\nX\n3\n")
            val patchText = diff(fs, "-u", "/a", "/b")
            // Apply forward, then -R to get back.
            patch(fs, "/a", stdin = patchText)
            assertEquals("1\nX\n3\n", read(fs, "/a"))
            val r = patch(fs, "-R", "/a", stdin = patchText)
            assertEquals(0, r.exit, r.err)
            assertEquals("1\n2\n3\n", read(fs, "/a"))
        }

    @Test fun appendHunk() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\n")
            write(fs, "/b", "a\nb\nc\n")
            val patchText = diff(fs, "-u", "/a", "/b")
            val r = patch(fs, "/a", stdin = patchText)
            assertEquals(0, r.exit, r.err)
            assertEquals("a\nb\nc\n", read(fs, "/a"))
        }

    @Test fun deleteHunk() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb\nc\n")
            write(fs, "/b", "a\nc\n")
            val patchText = diff(fs, "/a", "/b")
            val r = patch(fs, "/a", stdin = patchText)
            assertEquals(0, r.exit, r.err)
            assertEquals("a\nc\n", read(fs, "/a"))
        }

    @Test fun stripPN() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/work", "1\n2\n3\n")
            // Patch header names a/work b/work; -p1 strips the a//b/ prefix.
            val patchText =
                "--- a/work\n+++ b/work\n@@ -1,3 +1,3 @@\n 1\n-2\n+X\n 3\n"
            val r = patch(fs, "-p1", stdin = patchText)
            assertEquals(0, r.exit, r.err)
            assertEquals("1\nX\n3\n", read(fs, "/work"))
        }

    @Test fun dryRunNoOp() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "1\n2\n3\n")
            val patchText = "--- /a\n+++ /a\n@@ -1,3 +1,3 @@\n 1\n-2\n+X\n 3\n"
            val r = patch(fs, "--dry-run", "/a", stdin = patchText)
            assertEquals(0, r.exit, r.err)
            assertEquals("1\n2\n3\n", read(fs, "/a"))
        }

    @Test fun outputFile() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "1\n2\n3\n")
            val patchText = "--- /a\n+++ /a\n@@ -1,3 +1,3 @@\n 1\n-2\n+X\n 3\n"
            val r = patch(fs, "-o", "/out", "/a", stdin = patchText)
            assertEquals(0, r.exit, r.err)
            assertEquals("1\n2\n3\n", read(fs, "/a"))
            assertEquals("1\nX\n3\n", read(fs, "/out"))
        }

    @Test fun badHunkFails() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "totally\ndifferent\ncontent\n")
            val patchText = "--- /a\n+++ /a\n@@ -1,3 +1,3 @@\n 1\n-2\n+X\n 3\n"
            val r = patch(fs, "/a", stdin = patchText)
            assertEquals(1, r.exit)
            assertTrue("FAILED" in r.err, r.err)
            // Unchanged file on failure of the only hunk.
            assertEquals("totally\ndifferent\ncontent\n", read(fs, "/a"))
        }

    @Test fun garbageInputExit2() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "x\n")
            val r = patch(fs, "/a", stdin = "this is not a patch at all\n")
            assertEquals(2, r.exit)
        }

    @Test fun inputFromFile() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "1\n2\n3\n")
            write(fs, "/p", "--- /a\n+++ /a\n@@ -1,3 +1,3 @@\n 1\n-2\n+X\n 3\n")
            val r = patch(fs, "-i", "/p", "/a")
            assertEquals(0, r.exit, r.err)
            assertEquals("1\nX\n3\n", read(fs, "/a"))
        }

    @Test fun multiHunkRoundTrip() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", (1..30).joinToString("\n", postfix = "\n") { "$it" })
            val mutated =
                (1..30).joinToString("\n", postfix = "\n") {
                    if (it == 3) {
                        "THREE"
                    } else if (it == 27) {
                        "TWENTYSEVEN"
                    } else {
                        "$it"
                    }
                }
            write(fs, "/b", mutated)
            val patchText = diff(fs, "-u", "/a", "/b")
            val r = patch(fs, "/a", stdin = patchText)
            assertEquals(0, r.exit, r.err)
            assertEquals(mutated, read(fs, "/a"))
        }

    @Test fun helpExit0() =
        runTest {
            val fs = InMemoryFs()
            val r = patch(fs, "--help")
            assertEquals(0, r.exit)
            assertTrue("Usage: patch" in r.out)
        }
}
