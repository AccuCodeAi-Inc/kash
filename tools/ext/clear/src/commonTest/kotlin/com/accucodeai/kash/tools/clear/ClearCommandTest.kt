package com.accucodeai.kash.tools.clear

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun runClear(args: List<String>): Triple<Int, String, String> {
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
    val res = ClearCommand().run(args, ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class ClearCommandTest {
    @Test fun defaultClearsScreenAndScrollback() =
        runTest {
            val (rc, out, err) = runClear(emptyList())
            assertEquals(0, rc)
            assertEquals("", err)
            assertEquals("\u001B[H\u001B[2J\u001B[3J", out)
        }

    @Test fun dashXSkipsScrollback() =
        runTest {
            val (rc, out, _) = runClear(listOf("-x"))
            assertEquals(0, rc)
            assertEquals("\u001B[H\u001B[2J", out)
        }

    @Test fun dashCapitalTConsumesNextArg() =
        runTest {
            val (rc, out, _) = runClear(listOf("-T", "xterm-256color"))
            assertEquals(0, rc)
            assertEquals("\u001B[H\u001B[2J\u001B[3J", out)
        }

    @Test fun dashCapitalTRequiresValue() =
        runTest {
            val (rc, _, err) = runClear(listOf("-T"))
            assertEquals(1, rc)
            assertTrue(err.contains("requires an argument"))
        }

    @Test fun unknownOperandIsError() =
        runTest {
            val (rc, _, err) = runClear(listOf("garbage"))
            assertEquals(1, rc)
            assertTrue(err.contains("invalid operand"))
        }

    @Test fun versionPrints() =
        runTest {
            val (rc, out, _) = runClear(listOf("-V"))
            assertEquals(0, rc)
            assertTrue(out.startsWith("clear (kash)"))
        }

    @Test fun helpPrints() =
        runTest {
            val (rc, out, _) = runClear(listOf("--help"))
            assertEquals(0, rc)
            assertTrue(out.contains("Usage: clear"))
        }
}
