@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.python3.pyodide

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.python3.Python3Command
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the Pyodide engine — mirrors
 * [com.accucodeai.kash.tools.python3.graalpy.GraalPyEngineTest] for the
 * wasmJs target so Pyodide has the same regression net the GraalPy side
 * has. Runs in headless Chrome under Karma; the Pyodide tarball is
 * downloaded + unpacked by `bundlePyodideForTest` and served via the
 * config in `karma.config.d/`.
 *
 * **Not `runTest`.** See [pyodideTest] for why — the short version is
 * that `runTest`'s `TestCoroutineScheduler` owns the JS event loop
 * synchronously, the Web Worker never gets to run, and the only
 * scheduler-visible task is `PyodideEngine`'s `withTimeout(30s)` arm —
 * which fires at virtual t=30s instantly, failing every test. We use a
 * `MainScope().promise { ... }` wrapper instead so kotlin-test awaits
 * real wall-clock completion and the worker actually gets dispatched.
 *
 * Lower-level wire-protocol coverage still lives in
 * [worker.SabFsServerTest] — that suite is cheap (no worker boot) and
 * pins every FS-bridge regression we've seen (the original agent-bug
 * flush race, REPL `IsADirectoryError`, WASI errno table, stat-of-open-
 * fd, append, truncate, rename, multi-fd, chunked reads).
 *
 * Each Pyodide-backed test pays a ~3s cold boot (worker spawn + wasm
 * load + Python runtime init), so the suite adds ~30s to a clean run.
 */
class PyodideEngineEndToEndTest {
    private suspend fun runPython(
        fs: com.accucodeai.kash.fs.FileSystem = InMemoryFs(),
        cwd: String = "/home/user",
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
                cwd = cwd,
                stdin = stdin.asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val rc = Python3Command(PyodideEngine()).run(args.toList(), ctx).exitCode
        return Triple(rc, out.readString(), err.readString())
    }

    // NOT `runTest`. The Pyodide engine talks to a real Web Worker, and the
    // worker's `onmessage` (plus SAB visibility) only advances when the JS
    // host event loop gets a turn. `runTest`'s `TestCoroutineScheduler` owns
    // the event loop synchronously — every `yield`/`delay` is enqueued on
    // *its* queue and resumed in-place, so the host loop never runs, the
    // worker never delivers, and the only scheduler-visible task ends up
    // being PyodideEngine's `withTimeout(30s)` arm. The scheduler then does
    // exactly what it's documented to do: with nothing else queued at t=0,
    // virtual time fast-forwards to the timeout and fires it instantly.
    //
    // The fix is structural, not "find another stray delay()": run on the
    // real dispatcher and return a `Promise` so kotlin-test on wasmJs
    // awaits real wall-clock completion. `MainScope().promise { ... }` does
    // exactly that — coroutines suspend through real JS microtasks, the
    // worker gets its turns, and the 30s engine watchdog becomes a real
    // wall-clock budget again. SabFsServerTest stays on `runTest` because
    // it has no out-of-scheduler dependency.
    private fun pyodideTest(body: suspend PyodideEngineEndToEndTest.() -> Unit): Promise<JsAny?> =
        MainScope().promise {
            body()
            null
        }

    @Test
    fun evaluatesSimpleArithmeticViaDashC(): Promise<JsAny?> =
        pyodideTest {
            val (rc, out, err) = runPython(args = arrayOf("-c", "print(1 + 1)"))
            assertEquals(0, rc, "stderr was: $err")
            assertEquals("2\n", out)
        }

    @Test
    fun sysArgvReflectsScriptArgs(): Promise<JsAny?> =
        pyodideTest {
            val (rc, out, err) =
                runPython(
                    args = arrayOf("-c", "import sys; print(sys.argv[0], sys.argv[1], sys.argv[2])", "alpha", "beta"),
                )
            assertEquals(0, rc, "stderr: $err")
            assertEquals("-c alpha beta\n", out)
        }

    @Test
    fun systemExitPropagatesExitCode(): Promise<JsAny?> =
        pyodideTest {
            val (rc, _, _) = runPython(args = arrayOf("-c", "import sys; sys.exit(7)"))
            assertEquals(7, rc)
        }

    @Test
    fun uncaughtExceptionExitsOne(): Promise<JsAny?> =
        pyodideTest {
            val (rc, _, err) = runPython(args = arrayOf("-c", "raise RuntimeError('boom')"))
            assertEquals(1, rc)
            assertTrue(err.contains("RuntimeError") || err.contains("boom"), "stderr: $err")
        }

    @Test
    fun pythonWritesLandInKashFsRoundTrip(): Promise<JsAny?> =
        pyodideTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home/user")
            val (rc, _, err) =
                runPython(
                    fs = fs,
                    args =
                        arrayOf(
                            "-c",
                            "with open('/home/user/from_python.txt', 'w') as f:\n" +
                                "    f.write('written from python\\n')\n",
                        ),
                )
            assertEquals(0, rc, "stderr: $err")
            assertTrue(fs.exists("/home/user/from_python.txt"), "file should be in kash FS")
            assertEquals(
                "written from python\n",
                fs.readBytes("/home/user/from_python.txt").decodeToString(),
            )
        }

    @Test
    fun pythonReadsFileWrittenByKash(): Promise<JsAny?> =
        pyodideTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home/user")
            fs.writeBytes("/home/user/seeded.txt", "from kash\n".encodeToByteArray())
            val (rc, out, err) =
                runPython(
                    fs = fs,
                    args = arrayOf("-c", "print(open('/home/user/seeded.txt').read(), end='')"),
                )
            assertEquals(0, rc, "stderr: $err")
            assertEquals("from kash\n", out)
        }

    @Test
    fun appendModeAppendsThroughRealPyodide(): Promise<JsAny?> =
        pyodideTest {
            // Exercises O_APPEND end-to-end through real Emscripten — the unit
            // suite only feeds the wire protocol directly, so this is the one
            // place that proves Pyodide's append fd reaches the bridge and
            // lands at EOF rather than clobbering existing content.
            val fs = InMemoryFs()
            fs.mkdirs("/home/user")
            fs.writeBytes("/home/user/log.txt", "one\n".encodeToByteArray())
            val (rc, _, err) =
                runPython(
                    fs = fs,
                    args = arrayOf("-c", "open('/home/user/log.txt', 'a').write('two\\n')"),
                )
            assertEquals(0, rc, "stderr: $err")
            assertEquals(
                "one\ntwo\n",
                fs.readBytes("/home/user/log.txt").decodeToString(),
            )
        }

    @Test
    fun jsonRoundTripThroughBridge(): Promise<JsAny?> =
        pyodideTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home/user")
            val (rc, out, err) =
                runPython(
                    fs = fs,
                    args =
                        arrayOf(
                            "-c",
                            "import json\n" +
                                "json.dump({'k':[1,2,3]}, open('/home/user/x.json','w'))\n" +
                                "print(json.load(open('/home/user/x.json')))\n",
                        ),
                )
            assertEquals(0, rc, "stderr: $err")
            assertEquals("{'k': [1, 2, 3]}\n", out)
        }

    @Test
    fun missingFileRaisesFileNotFoundError(): Promise<JsAny?> =
        pyodideTest {
            val (rc, _, err) =
                runPython(args = arrayOf("-c", "open('/tmp/nope.txt').read()"))
            assertEquals(1, rc)
            assertTrue(
                err.contains("FileNotFoundError") || err.contains("No such file"),
                "expected FileNotFoundError, got: $err",
            )
        }

    @Test
    fun inputReadsFromSuspendingStdin(): Promise<JsAny?> =
        pyodideTest {
            val (rc, out, err) =
                runPython(
                    stdinText = "alice\n",
                    args = arrayOf("-c", "print('hi', input())"),
                )
            assertEquals(0, rc, "stderr: $err")
            assertEquals("hi alice\n", out)
        }

    @Test
    fun multipleInputCallsConsumeSequentialLines(): Promise<JsAny?> =
        pyodideTest {
            val (rc, out, err) =
                runPython(
                    stdinText = "42\n7\n",
                    args = arrayOf("-c", "a=int(input()); b=int(input()); print('sum=', a+b)"),
                )
            assertEquals(0, rc, "stderr: $err")
            assertEquals("sum= 49\n", out)
        }

    @Test
    fun osListdirSeesKashEntries(): Promise<JsAny?> =
        pyodideTest {
            val fs = InMemoryFs()
            fs.mkdirs("/home/user/d")
            fs.writeBytes("/home/user/d/a", "a".encodeToByteArray())
            fs.writeBytes("/home/user/d/b", "b".encodeToByteArray())
            val (rc, out, err) =
                runPython(
                    fs = fs,
                    args = arrayOf("-c", "import os; print(sorted(os.listdir('/home/user/d')))"),
                )
            assertEquals(0, rc, "stderr: $err")
            assertEquals("['a', 'b']\n", out)
        }
}
