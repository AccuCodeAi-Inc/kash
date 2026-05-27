package com.accucodeai.kash.tools.python3.graalpy

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct exercise of [GraalPyEngine.runInteractiveRepl] with synthetic
 * SuspendSource/SuspendSink. Skips kash-app entirely; verifies that the
 * Layer-C [SuspendSourceInputStream] / [SuspendSinkOutputStream] adapters
 * deliver bytes intact across the Truffle boundary.
 *
 * Pre-fix, feeding `print('hello world')\n` to a REPL that read directly
 * from `System.in` would race against kash-app's StdinReader. Post-fix the
 * engine reads through our adapter, which has no parallel consumer, so the
 * full script reaches `code.interact()` and `hello world\n` lands in stdout.
 */
class GraalPyReplStdinTest {
    @Test fun `REPL receives full piped stdin and prints expected output`() =
        runTest {
            val stdin = Buffer().also { it.writeUtf8("print('hello world')\nexit()\n") }
            val stdout = Buffer()
            val stderr = Buffer()
            val rc =
                GraalPyEngine().runInteractiveRepl(
                    fs = InMemoryFs(),
                    cwd = "/home/user",
                    env = emptyMap(),
                    stdin = stdin.asSuspendSource(),
                    stdout = stdout.asSuspendSink(),
                    stderr = stderr.asSuspendSink(),
                )
            val outText = stdout.readString()
            val errText = stderr.readString()
            // exit() raises SystemExit(0) which code.interact intercepts and
            // re-raises; runInteractiveReplBlocking maps that to exit code 0.
            assertEquals(0, rc, "rc=$rc stdout='$outText' stderr='$errText'")
            assertTrue(
                "hello world" in outText,
                "expected 'hello world' in stdout but got '$outText' (stderr: '$errText')",
            )
        }
}
