package com.accucodeai.kash.tools.uname

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun run(args: List<String>): Pair<Int, String> {
    val out = Buffer()
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            env = mutableMapOf(),
            cwd = "/",
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = Buffer().asSuspendSink(),
        )
    val rc = runBlocking { UnameCommand().run(args, ctx).exitCode }
    return rc to out.readString()
}

// The JVM os.name maps to one of these kernel names; assert against the actual host.
private val expectedSysname: String =
    (System.getProperty("os.name") ?: "").let { os ->
        when {
            os.startsWith("Mac", ignoreCase = true) -> "Darwin"
            os.startsWith("Windows", ignoreCase = true) -> "Windows_NT"
            else -> os
        }
    }

class UnameJvmTest {
    @Test fun noArgPrintsKernelName() {
        val (rc, out) = run(emptyList())
        assertEquals(0, rc)
        assertEquals("$expectedSysname\n", out)
    }

    @Test fun sysnameFlagMatchesHostKernel() {
        val (_, out) = run(listOf("-s"))
        assertEquals("$expectedSysname\n", out)
    }

    @Test fun machineIsNormalizedArch() {
        val (_, out) = run(listOf("-m"))
        val m = out.trim()
        // arm64/x86_64 are the normalized values; anything else passes through.
        assertTrue(m.isNotEmpty())
        assertTrue(!m.contains("amd64") && !m.contains("aarch64"), "machine not normalized: $m")
    }

    @Test fun osFlagIsGnuLinuxOrSysname() {
        val (_, out) = run(listOf("-o"))
        val expected = if (expectedSysname == "Linux") "GNU/Linux" else expectedSysname
        assertEquals("$expected\n", out)
    }

    @Test fun allStartsWithSysname() {
        val (_, out) = run(listOf("-a"))
        assertTrue(out.startsWith(expectedSysname), "all output: $out")
    }
}
