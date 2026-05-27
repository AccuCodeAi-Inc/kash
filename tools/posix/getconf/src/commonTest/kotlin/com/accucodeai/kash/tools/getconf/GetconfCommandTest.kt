package com.accucodeai.kash.tools.getconf

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun runGetconf(
    vararg args: String,
    env: MutableMap<String, String> = mutableMapOf(),
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
    val rc = GetconfCommand().run(args.toList(), ctx)
    return Triple(rc.exitCode, out.readString(), err.readString())
}

class GetconfCommandTest {
    @Test fun posixVersion() =
        runTest {
            val (rc, out, _) = runGetconf("_POSIX_VERSION")
            assertEquals(0, rc)
            assertEquals("200809\n", out)
        }

    @Test fun posix2Version() =
        runTest {
            val (rc, out, _) = runGetconf("_POSIX2_VERSION")
            assertEquals(0, rc)
            assertEquals("200809\n", out)
        }

    @Test fun lineMax() =
        runTest {
            val (_, out, _) = runGetconf("LINE_MAX")
            assertEquals("2048\n", out)
        }

    @Test fun nameMax() =
        runTest {
            val (_, out, _) = runGetconf("NAME_MAX")
            assertEquals("255\n", out)
        }

    @Test fun pathMax() =
        runTest {
            val (_, out, _) = runGetconf("PATH_MAX")
            assertEquals("4096\n", out)
        }

    @Test fun argMax() =
        runTest {
            val (_, out, _) = runGetconf("ARG_MAX")
            assertEquals("4096\n", out)
        }

    @Test fun openMax() =
        runTest {
            val (_, out, _) = runGetconf("OPEN_MAX")
            assertEquals("64\n", out)
        }

    @Test fun pageSize() =
        runTest {
            val (_, out, _) = runGetconf("PAGESIZE")
            assertEquals("4096\n", out)
        }

    @Test fun clkTck() =
        runTest {
            val (_, out, _) = runGetconf("_SC_CLK_TCK")
            assertEquals("100\n", out)
        }

    @Test fun pathFromEnv() =
        runTest {
            val (_, out, _) = runGetconf("PATH", env = mutableMapOf("PATH" to "/custom:/dev"))
            assertEquals("/custom:/dev\n", out)
        }

    @Test fun pathDefault() =
        runTest {
            val (_, out, _) = runGetconf("PATH")
            assertEquals("/usr/bin:/bin\n", out)
        }

    @Test fun unknownName_exit1() =
        runTest {
            val (rc, _, err) = runGetconf("BOGUS_NAME")
            assertEquals(1, rc)
            assertContains(err, "BOGUS_NAME")
        }

    @Test fun listAll() =
        runTest {
            val (rc, out, _) = runGetconf("-a")
            assertEquals(0, rc)
            assertContains(out, "_POSIX_VERSION: 200809")
            assertContains(out, "LINE_MAX: 2048")
            assertContains(out, "PATH:")
        }

    @Test fun listAll_longFlag() =
        runTest {
            val (rc, out, _) = runGetconf("--all")
            assertEquals(0, rc)
            assertContains(out, "_POSIX_VERSION")
        }

    @Test fun dashV_acceptedNoOp() =
        runTest {
            val (rc, out, _) = runGetconf("-v", "POSIX_V7", "LINE_MAX")
            assertEquals(0, rc)
            assertEquals("2048\n", out)
        }

    @Test fun nameWithPath_pathIndependent() =
        runTest {
            val (rc, out, _) = runGetconf("NAME_MAX", "/tmp")
            assertEquals(0, rc)
            assertEquals("255\n", out)
        }

    @Test fun missingOperand_exit2() =
        runTest {
            val (rc, _, err) = runGetconf()
            assertEquals(2, rc)
            assertContains(err, "missing operand")
        }

    @Test fun unknownOption_exit2() =
        runTest {
            val (rc, _, _) = runGetconf("--bogus")
            assertEquals(2, rc)
        }

    @Test fun help() =
        runTest {
            val (rc, out, _) = runGetconf("--help")
            assertEquals(0, rc)
            assertContains(out, "getconf")
        }

    @Test fun csPath() =
        runTest {
            val (rc, out, _) = runGetconf("_CS_PATH", env = mutableMapOf("PATH" to "/x"))
            assertEquals(0, rc)
            assertEquals("/x\n", out)
        }

    @Test fun posixThreads() =
        runTest {
            val (rc, out, _) = runGetconf("_POSIX_THREADS")
            assertEquals(0, rc)
            assertTrue(out.trim().isNotEmpty())
        }
}
