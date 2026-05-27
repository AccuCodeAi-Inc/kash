package com.accucodeai.kash.tools.uname

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

private suspend fun runUname(args: List<String>): Triple<Int, String, String> {
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
    val res = UnameCommand().run(args, ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class UnameCommandTest {
    @Test fun noArgPrintsNonEmptySysname() =
        runTest {
            val (rc, out, _) = runUname(emptyList())
            assertEquals(0, rc)
            assertTrue(out.endsWith("\n"))
            assertTrue(out.trim().isNotEmpty())
            assertEquals(1, out.trim().split(" ").size)
        }

    @Test fun sysnameFlagOneToken() =
        runTest {
            val (rc, out, _) = runUname(listOf("-s"))
            assertEquals(0, rc)
            assertEquals(1, out.trim().split(" ").size)
        }

    @Test fun nodenameFlagOneToken() =
        runTest {
            val (rc, out, _) = runUname(listOf("-n"))
            assertEquals(0, rc)
            assertEquals(1, out.trim().split(" ").size)
        }

    @Test fun machineFlagOneToken() =
        runTest {
            val (rc, out, _) = runUname(listOf("-m"))
            assertEquals(0, rc)
            assertEquals(1, out.trim().split(" ").size)
        }

    @Test fun osFlagOneToken() =
        runTest {
            val (rc, out, _) = runUname(listOf("-o"))
            assertEquals(0, rc)
            assertEquals(1, out.trim().split(" ").size)
        }

    @Test fun allFlagMultipleTokensNewlineTerminated() =
        runTest {
            val (rc, out, _) = runUname(listOf("-a"))
            assertEquals(0, rc)
            assertTrue(out.endsWith("\n"))
            assertTrue(out.trim().split(" ").size >= 5, "expected >=5 tokens: $out")
        }

    @Test fun longAllFlag() =
        runTest {
            val (rc, out, _) = runUname(listOf("--all"))
            assertEquals(0, rc)
            assertTrue(out.trim().split(" ").size >= 5)
        }

    @Test fun bundledFlagsCanonicalOrder() =
        runTest {
            // -sm must print sysname then machine, same as -ms.
            val (rc1, sm, _) = runUname(listOf("-sm"))
            val (rc2, ms, _) = runUname(listOf("-ms"))
            assertEquals(0, rc1)
            assertEquals(0, rc2)
            assertEquals(sm, ms)
            assertEquals(2, sm.trim().split(" ").size)
        }

    @Test fun separateFlagsSameAsBundled() =
        runTest {
            val (_, bundled, _) = runUname(listOf("-sm"))
            val (_, separate, _) = runUname(listOf("-s", "-m"))
            assertEquals(bundled, separate)
        }

    @Test fun helpExitsZero() =
        runTest {
            val (rc, out, _) = runUname(listOf("--help"))
            assertEquals(0, rc)
            assertTrue(out.contains("Usage:"))
        }

    @Test fun unknownShortOptionErrors() =
        runTest {
            val (rc, _, err) = runUname(listOf("-z"))
            assertEquals(1, rc)
            assertTrue(err.contains("invalid option"))
        }

    @Test fun unknownLongOptionErrors() =
        runTest {
            val (rc, _, err) = runUname(listOf("--bogus"))
            assertEquals(1, rc)
            assertTrue(err.contains("unrecognized option"))
        }

    @Test fun extraOperandErrors() =
        runTest {
            val (rc, _, err) = runUname(listOf("foo"))
            assertEquals(1, rc)
            assertTrue(err.contains("extra operand"))
        }
}
