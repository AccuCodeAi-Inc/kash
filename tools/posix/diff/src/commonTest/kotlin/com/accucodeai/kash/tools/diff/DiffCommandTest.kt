package com.accucodeai.kash.tools.diff

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffCommandTest {
    private class Run(
        val exit: Int,
        val out: String,
        val err: String,
    )

    private suspend fun run(
        fs: InMemoryFs,
        vararg args: String,
        stdin: String? = null,
    ): Run {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                cwd = "/",
                stdin = Buffer().apply { writeBytes((stdin ?: "").encodeToByteArray()) }.asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val rc = DiffCommand().run(args.toList(), ctx)
        return Run(rc.exitCode, out.readString(), err.readString())
    }

    private suspend fun write(
        fs: InMemoryFs,
        path: String,
        content: String,
    ) {
        fs.writeBytes(path, content.encodeToByteArray())
    }

    @Test fun identicalFilesExit0() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "x\ny\n")
            write(fs, "/b", "x\ny\n")
            val r = run(fs, "/a", "/b")
            assertEquals(0, r.exit)
            assertEquals("", r.out)
        }

    @Test fun normalChange() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb\nc\n")
            write(fs, "/b", "a\nB\nc\n")
            val r = run(fs, "/a", "/b")
            assertEquals(1, r.exit)
            assertEquals("2c2\n< b\n---\n> B\n", r.out)
        }

    @Test fun normalDelete() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb\nc\n")
            write(fs, "/b", "a\nc\n")
            val r = run(fs, "/a", "/b")
            assertEquals(1, r.exit)
            assertEquals("2d1\n< b\n", r.out)
        }

    @Test fun normalAppend() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\n")
            write(fs, "/b", "a\nb\n")
            val r = run(fs, "/a", "/b")
            assertEquals(1, r.exit)
            assertEquals("1a2\n> b\n", r.out)
        }

    @Test fun normalMultiHunk() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "1\n2\n3\n4\n5\n6\n7\n8\n")
            write(fs, "/b", "X\n2\n3\n4\n5\n6\n7\nY\n")
            val r = run(fs, "/a", "/b")
            assertEquals(1, r.exit)
            assertEquals("1c1\n< 1\n---\n> X\n8c8\n< 8\n---\n> Y\n", r.out)
        }

    @Test fun unifiedChange() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb\nc\n")
            write(fs, "/b", "a\nB\nc\n")
            val r = run(fs, "-u", "/a", "/b")
            assertEquals(1, r.exit)
            assertEquals(
                "--- /a\n+++ /b\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n",
                r.out,
            )
        }

    @Test fun unifiedContextN() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "1\n2\n3\n4\n5\n")
            write(fs, "/b", "1\n2\nX\n4\n5\n")
            val r = run(fs, "-U", "1", "/a", "/b")
            assertEquals(1, r.exit)
            assertEquals(
                "--- /a\n+++ /b\n@@ -2,3 +2,3 @@\n 2\n-3\n+X\n 4\n",
                r.out,
            )
        }

    @Test fun contextFormat() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb\nc\n")
            write(fs, "/b", "a\nB\nc\n")
            val r = run(fs, "-c", "/a", "/b")
            assertEquals(1, r.exit)
            assertTrue(r.out.startsWith("*** /a\n--- /b\n"), r.out)
            assertTrue("***************" in r.out, r.out)
            assertTrue("! b" in r.out && "! B" in r.out, r.out)
        }

    @Test fun contextDeleteUsesDashMarker() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb\nc\n")
            write(fs, "/b", "a\nc\n")
            val r = run(fs, "-c", "/a", "/b")
            assertEquals(1, r.exit)
            assertTrue("- b" in r.out, r.out)
        }

    @Test fun edScript() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb\nc\n")
            write(fs, "/b", "a\nB\nc\n")
            val r = run(fs, "-e", "/a", "/b")
            assertEquals(1, r.exit)
            assertEquals("2c\nB\n.\n", r.out)
        }

    @Test fun edScriptBottomUp() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "1\n2\n3\n4\n")
            write(fs, "/b", "X\n2\n3\nY\n")
            val r = run(fs, "-e", "/a", "/b")
            // Emitted bottom-up: line 4 before line 1.
            assertEquals("4c\nY\n.\n1c\nX\n.\n", r.out)
        }

    @Test fun briefDiffer() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\n")
            write(fs, "/b", "b\n")
            val r = run(fs, "-q", "/a", "/b")
            assertEquals(1, r.exit)
            assertEquals("Files /a and /b differ\n", r.out)
        }

    @Test fun reportIdentical() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\n")
            write(fs, "/b", "a\n")
            val r = run(fs, "-s", "/a", "/b")
            assertEquals(0, r.exit)
            assertEquals("Files /a and /b are identical\n", r.out)
        }

    @Test fun oneSideEmpty() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "")
            write(fs, "/b", "x\ny\n")
            val r = run(fs, "/a", "/b")
            assertEquals(1, r.exit)
            assertEquals("0a1,2\n> x\n> y\n", r.out)
        }

    @Test fun noTrailingNewlineUnified() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb")
            write(fs, "/b", "a\nB")
            val r = run(fs, "-u", "/a", "/b")
            assertEquals(1, r.exit)
            assertTrue("\\ No newline at end of file" in r.out, r.out)
        }

    @Test fun ignoreCase() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "Hello\n")
            write(fs, "/b", "hello\n")
            val r = run(fs, "-i", "/a", "/b")
            assertEquals(0, r.exit)
        }

    @Test fun ignoreAllWhitespace() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a b c\n")
            write(fs, "/b", "ab    c\n")
            val r = run(fs, "-w", "/a", "/b")
            assertEquals(0, r.exit)
        }

    @Test fun ignoreSpaceChange() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a b\n")
            write(fs, "/b", "a    b\n")
            val r = run(fs, "-b", "/a", "/b")
            assertEquals(0, r.exit)
            // A genuine token change still differs under -b.
            write(fs, "/c", "a x\n")
            val r2 = run(fs, "-b", "/a", "/c")
            assertEquals(1, r2.exit)
        }

    @Test fun ignoreBlankLines() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb\n")
            write(fs, "/b", "a\n\nb\n")
            val r = run(fs, "-B", "/a", "/b")
            assertEquals(0, r.exit)
        }

    @Test fun recursiveDirs() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/d1")
            fs.mkdirs("/d2")
            write(fs, "/d1/common", "x\n")
            write(fs, "/d2/common", "y\n")
            write(fs, "/d1/onlyhere", "z\n")
            val r = run(fs, "-r", "/d1", "/d2")
            assertEquals(1, r.exit)
            assertTrue("Only in /d1: onlyhere" in r.out, r.out)
            assertTrue("diff /d1/common /d2/common" in r.out, r.out)
            assertTrue("< x" in r.out && "> y" in r.out, r.out)
        }

    @Test fun stdinOperand() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\nb\n")
            val r = run(fs, "/a", "-", stdin = "a\nB\n")
            assertEquals(1, r.exit)
            assertEquals("2c2\n< b\n---\n> B\n", r.out)
        }

    @Test fun missingFileExit2() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\n")
            val r = run(fs, "/a", "/nope")
            assertEquals(2, r.exit)
            assertTrue("No such file" in r.err, r.err)
        }

    @Test fun missingOperandExit2() =
        runTest {
            val fs = InMemoryFs()
            write(fs, "/a", "a\n")
            val r = run(fs, "/a")
            assertEquals(2, r.exit)
        }
}
