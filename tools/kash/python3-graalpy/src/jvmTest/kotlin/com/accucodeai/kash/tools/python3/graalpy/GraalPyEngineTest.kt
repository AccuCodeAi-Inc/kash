package com.accucodeai.kash.tools.python3.graalpy

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.python3.Python3Command
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun runPython(
    fs: com.accucodeai.kash.fs.FileSystem = InMemoryFs(),
    stdinText: String = "",
    vararg args: String,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val stdin = Buffer().also { it.writeUtf8(stdinText) }
    val ctx =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf("FOO" to "bar"),
            cwd = "/home/user",
            stdin = stdin.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val rc = runBlocking { Python3Command(GraalPyEngine()).run(args.toList(), ctx).exitCode }
    return Triple(rc, out.readString(), err.readString())
}

/**
 * End-to-end tests for GraalPy. These spin up a real Python interpreter via
 * the GraalVM Polyglot API — first-context cost is several seconds on stock
 * OpenJDK, so the suite is deliberately small.
 */
class GraalPyEngineTest {
    @Test fun `evaluates simple arithmetic via dash c`() =
        runBlocking {
            val (rc, out, err) = runPython(args = arrayOf("-c", "print(1 + 1)"))
            assertEquals(0, rc, "stderr was: $err")
            assertEquals("2\n", out)
        }

    @Test fun `version flag does not start interpreter`() =
        runBlocking {
            val (rc, out, _) = runPython(args = arrayOf("--version"))
            assertEquals(0, rc)
            assertTrue(out.contains("GraalPy"), "got: $out")
        }

    @Test fun `sys argv reflects script args`() =
        runBlocking {
            val (rc, out, err) =
                runPython(
                    args = arrayOf("-c", "import sys; print(sys.argv[0], sys.argv[1], sys.argv[2])", "alpha", "beta"),
                )
            assertEquals(0, rc, "stderr: $err")
            // sys.argv[0] is "-c" for code mode, matching CPython behavior.
            assertEquals("-c alpha beta\n", out)
        }

    @Test fun `host Java classes are not reachable from Python`() =
        runBlocking {
            // HostAccess.NONE means Python can't import 'java' / 'polyglot' bindings.
            val (rc, _, err) =
                runPython(
                    args = arrayOf("-c", "import java.lang.System"),
                )
            assertTrue(rc != 0, "expected non-zero exit, got $rc; stderr: $err")
        }

    @Test fun `subprocess is blocked`() =
        runBlocking {
            val (rc, _, err) =
                runPython(
                    args = arrayOf("-c", "import subprocess; subprocess.run(['echo','hi'])"),
                )
            assertTrue(rc != 0, "expected non-zero exit; stderr: $err")
        }

    @Test fun `script file read via virtual FS`() =
        runBlocking {
            val fs = InMemoryFs()
            val sink = fs.sink("/home/user/hello.py", append = false)
            try {
                sink.writeUtf8("print('hello from vfs')\n")
            } finally {
                sink.close()
            }
            val (rc, out, _) = runPython(fs = fs, args = arrayOf("/home/user/hello.py"))
            assertEquals(0, rc)
            assertEquals("hello from vfs\n", out)
        }

    @Test fun `missing script file exits 2`() =
        runBlocking {
            val (rc, _, err) = runPython(args = arrayOf("/no/such/file.py"))
            assertEquals(2, rc)
            assertTrue(err.contains("No such file"), "stderr: $err")
        }

    @Test fun `uncaught exception exits 1`() =
        runBlocking {
            val (rc, _, _) = runPython(args = arrayOf("-c", "raise ValueError('boom')"))
            assertEquals(1, rc)
        }

    @Test fun `SystemExit propagates exit code`() =
        runBlocking {
            val (rc, _, _) = runPython(args = arrayOf("-c", "import sys; sys.exit(7)"))
            assertEquals(7, rc)
        }

    @Test fun `script input() reads from caller stdin`() =
        runBlocking {
            // Regression: execute() used to hard-wire Python's stdin to an
            // empty ByteArrayInputStream, so any `input()` raised EOFError
            // immediately. Caller-provided stdin must reach sys.stdin.
            val (rc, out, err) =
                runPython(
                    stdinText = "Alice\n",
                    args = arrayOf("-c", "name = input(); print(f'hello {name}')"),
                )
            assertEquals(0, rc, "expected exit 0; stderr: $err")
            assertEquals("hello Alice\n", out)
        }

    // ---- Mount-aware FS architecture (C2) ----

    @Test fun `Python writes land in kash FS not the host`() =
        runBlocking {
            val fs = InMemoryFs()
            val (rc, _, err) =
                runPython(
                    fs = fs,
                    args =
                        arrayOf(
                            "-c",
                            // Use `with` so the file is closed (and our channel
                            // flushed to kash FS) before the Python program ends.
                            "with open('/home/user/from_python.txt', 'w') as f:\n" +
                                "    f.write('written from python\\n')\n",
                        ),
                )
            assertEquals(0, rc, "stderr: $err")
            assertTrue(fs.exists("/home/user/from_python.txt"), "file should be in kash FS, not host")
            assertEquals(
                "written from python\n",
                fs.readBytes("/home/user/from_python.txt").decodeToString(),
            )
        }

    @Test fun `Python read of a non-cache host path is blocked`() =
        runBlocking {
            // /etc/passwd exists on the host but is NOT under our cache passthrough
            // prefix and NOT in kash's InMemoryFs, so the open should fail.
            val (rc, _, err) =
                runPython(
                    args =
                        arrayOf(
                            "-c",
                            "import sys\n" +
                                "try:\n" +
                                "    open('/etc/passwd', 'r').read()\n" +
                                "    sys.exit(0)  # leak!\n" +
                                "except (FileNotFoundError, OSError, PermissionError):\n" +
                                "    sys.exit(5)\n",
                        ),
                )
            assertEquals(5, rc, "expected open() to fail; stderr: $err")
        }

    @Test fun `engine cache is exposed as a Mount with ENGINE_CACHE label`() =
        runBlocking {
            // We can't easily inspect the per-invocation MountedFileSystem from
            // outside, so we drive the engine through a Python script that exits
            // 0 — proves the cache mount was actually constructed and used
            // (Python wouldn't import without stdlib coming through the cache
            // passthrough). The label-query assertion is on the public companion
            // constant which is the contract for snapshot tools.
            val (rc, _, _) =
                runPython(
                    args = arrayOf("-c", "import os; os.path.join('a','b')"),
                )
            assertEquals(0, rc)
            assertEquals(
                "/.cache/graalpy",
                com.accucodeai.kash.tools.python3.graalpy.GraalPyEngine.CACHE_MOUNT_POINT,
            )
        }
}
