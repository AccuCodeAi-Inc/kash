package com.accucodeai.kash.tools.python3

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Captures every argument the command hands to the engine, returns 0. */
private class RecordingEngine : PythonEngine {
    override val name: String = "recording"
    var lastSource: PythonSource? = null
    var lastScriptArgs: List<String>? = null
    var calls: Int = 0

    override suspend fun execute(
        source: PythonSource,
        scriptArgs: List<String>,
        fs: FileSystem,
        env: Map<String, String>,
        cwd: String,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
        timeoutMillis: Long,
        sandbox: com.accucodeai.kash.api.sandbox.SandboxPolicy,
        networkPolicy: com.accucodeai.kash.api.sandbox.NetworkPolicy,
    ): Int {
        lastSource = source
        lastScriptArgs = scriptArgs
        calls++
        return 0
    }
}

private suspend fun runPy(
    engine: PythonEngine = RecordingEngine(),
    vararg args: String,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            env = mutableMapOf(),
            cwd = "/",
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = Python3Command(engine).run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class Python3CommandTest {
    @Test fun `dash c routes Code source to engine`() =
        runTest {
            val eng = RecordingEngine()
            val (rc, _, _) = runPy(eng, "-c", "print(1+1)")
            assertEquals(0, rc)
            assertEquals(PythonSource.Code("print(1+1)"), eng.lastSource)
            assertEquals(emptyList(), eng.lastScriptArgs)
        }

    @Test fun `dash c missing argument exits 2`() =
        runTest {
            val (rc, _, err) = runPy(args = arrayOf("-c"))
            assertEquals(2, rc)
            assertTrue(err.contains("requires an argument"), "stderr was: $err")
        }

    @Test fun `dash m routes Module source`() =
        runTest {
            val eng = RecordingEngine()
            runPy(eng, "-m", "http.server", "8000")
            assertEquals(PythonSource.Module("http.server"), eng.lastSource)
            assertEquals(listOf("8000"), eng.lastScriptArgs)
        }

    @Test fun `bare positional becomes File source`() =
        runTest {
            val eng = RecordingEngine()
            runPy(eng, "/tmp/script.py", "a", "b")
            assertEquals(PythonSource.File("/tmp/script.py"), eng.lastSource)
            assertEquals(listOf("a", "b"), eng.lastScriptArgs)
        }

    @Test fun `no args reads program from stdin`() =
        runTest {
            val eng = RecordingEngine()
            runPy(eng)
            assertEquals(PythonSource.Stdin, eng.lastSource)
        }

    @Test fun `dash operand is stdin`() =
        runTest {
            val eng = RecordingEngine()
            runPy(eng, "-", "arg1")
            assertEquals(PythonSource.Stdin, eng.lastSource)
            assertEquals(listOf("arg1"), eng.lastScriptArgs)
        }

    @Test fun `double dash ends options and next arg is file`() =
        runTest {
            val eng = RecordingEngine()
            runPy(eng, "--", "-weird-name.py", "x")
            assertEquals(PythonSource.File("-weird-name.py"), eng.lastSource)
            assertEquals(listOf("x"), eng.lastScriptArgs)
        }

    @Test fun `double dash alone means stdin`() =
        runTest {
            val eng = RecordingEngine()
            runPy(eng, "--")
            assertEquals(PythonSource.Stdin, eng.lastSource)
        }

    @Test fun `version prints engine name and does not invoke engine`() =
        runTest {
            val eng = RecordingEngine()
            val (rc, out, _) = runPy(eng, "--version")
            assertEquals(0, rc)
            assertTrue(out.startsWith("Python 3 (recording)"), "out was: $out")
            assertEquals(0, eng.calls)
        }

    @Test fun `dash V is version`() =
        runTest {
            val (rc, out, _) = runPy(args = arrayOf("-V"))
            assertEquals(0, rc)
            assertTrue(out.contains("Python 3"))
        }

    @Test fun `help prints usage and does not invoke engine`() =
        runTest {
            val eng = RecordingEngine()
            val (rc, out, _) = runPy(eng, "--help")
            assertEquals(0, rc)
            assertTrue(out.contains("usage: python3"))
            assertEquals(0, eng.calls)
        }

    @Test fun `unknown option exits 2`() =
        runTest {
            val (rc, _, err) = runPy(args = arrayOf("--bogus"))
            assertEquals(2, rc)
            assertTrue(err.contains("unknown option"))
        }

    @Test fun `engine exit code propagates`() =
        runTest {
            val failing =
                object : PythonEngine {
                    override val name = "failing"

                    override suspend fun execute(
                        source: PythonSource,
                        scriptArgs: List<String>,
                        fs: FileSystem,
                        env: Map<String, String>,
                        cwd: String,
                        stdin: SuspendSource,
                        stdout: SuspendSink,
                        stderr: SuspendSink,
                        timeoutMillis: Long,
                        sandbox: com.accucodeai.kash.api.sandbox.SandboxPolicy,
                        networkPolicy: com.accucodeai.kash.api.sandbox.NetworkPolicy,
                    ): Int = 42
                }
            val (rc, _, _) = runPy(failing, "-c", "raise SystemExit(42)")
            assertEquals(42, rc)
        }
}
