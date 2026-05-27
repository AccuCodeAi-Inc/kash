package com.accucodeai.kash.tools.printf

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun runPrintf(
    env: MutableMap<String, String> = mutableMapOf(),
    vararg args: String,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            env = env,
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = PrintfCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class PrintfCommandTest {
    /** POSIX: `%c` on an empty string emits *nothing* — kash previously emitted NUL. */
    @Test fun percentCOnEmptyStringEmitsNothing() =
        runTest {
            val (rc, out, _) = runPrintf(args = arrayOf("%c", ""))
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test fun percentCOnNonEmptyTakesFirstChar() =
        runTest {
            val (rc, out, _) = runPrintf(args = arrayOf("%c", "hello"))
            assertEquals(0, rc)
            assertEquals("h", out)
        }

    /** bash exits 2 — not 1 — when `-v VAR` names an invalid identifier. */
    @Test fun dashVBadIdentExits2() =
        runTest {
            val (rc, _, err) = runPrintf(args = arrayOf("-v", "1bad", "%s", "x"))
            assertEquals(2, rc)
            assertTrue("not a valid identifier" in err, err)
        }
}
