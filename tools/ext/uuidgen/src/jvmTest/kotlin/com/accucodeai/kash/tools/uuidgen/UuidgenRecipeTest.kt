package com.accucodeai.kash.tools.uuidgen

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val UUID_LINE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\n$")
private val UUID_BARE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

class UuidgenRecipeTest {
    @Test
    fun defaultProducesOneUuid() =
        runBlocking {
            val (out, _, rc) = run(emptyList())
            assertEquals(0, rc)
            assertTrue(UUID_LINE.matches(out), "out was: $out")
        }

    @Test
    fun twoInvocationsDiffer() =
        runBlocking {
            val (a, _, _) = run(emptyList())
            val (b, _, _) = run(emptyList())
            assertNotEquals(a, b)
        }

    @Test
    fun explicitRandomFlag() =
        runBlocking {
            val (out, _, rc) = run(listOf("-r"))
            assertEquals(0, rc)
            assertTrue(UUID_LINE.matches(out), "out was: $out")
        }

    @Test
    fun longRandomFlag() =
        runBlocking {
            val (out, _, rc) = run(listOf("--random"))
            assertEquals(0, rc)
            assertTrue(UUID_LINE.matches(out))
        }

    @Test
    fun timeFlagProducesValidUuid() =
        runBlocking {
            val (out, _, rc) = run(listOf("-t"))
            assertEquals(0, rc)
            assertTrue(UUID_LINE.matches(out), "out was: $out")
            // v1 must have version nibble '1'.
            val u = out.trim()
            assertEquals('1', u[14])
        }

    @Test
    fun randomVersionNibbleIs4() =
        runBlocking {
            val (out, _, _) = run(listOf("-r"))
            val u = out.trim()
            assertEquals('4', u[14])
        }

    @Test
    fun variantBitsCorrectV4() =
        runBlocking {
            val (out, _, _) = run(listOf("-r"))
            val u = out.trim()
            // The 9th byte is u[19..20]; its top two bits must be 10.
            val byteHi = u[19].digitToInt(16)
            assertEquals(0b1000, byteHi and 0b1100, "variant nibble was ${u[19]}")
        }

    @Test
    fun variantBitsCorrectV1() =
        runBlocking {
            val (out, _, _) = run(listOf("-t"))
            val u = out.trim()
            val byteHi = u[19].digitToInt(16)
            assertEquals(0b1000, byteHi and 0b1100, "variant nibble was ${u[19]}")
        }

    @Test
    fun countEmitsMultiple() =
        runBlocking {
            val (out, _, rc) = run(listOf("-n", "5"))
            assertEquals(0, rc)
            val lines = out.trimEnd('\n').split('\n')
            assertEquals(5, lines.size)
            for (l in lines) assertTrue(UUID_BARE.matches(l), "line was: $l")
            assertEquals(5, lines.toSet().size, "all UUIDs should be unique")
        }

    @Test
    fun countZeroProducesNothing() =
        runBlocking {
            val (out, _, rc) = run(listOf("-n", "0"))
            assertEquals(0, rc)
            assertEquals("", out)
        }

    @Test
    fun countTimeMultipleUnique() =
        runBlocking {
            val (out, _, rc) = run(listOf("-t", "-n", "3"))
            assertEquals(0, rc)
            val lines = out.trimEnd('\n').split('\n')
            assertEquals(3, lines.size)
            for (l in lines) {
                assertTrue(UUID_BARE.matches(l), "line was: $l")
                assertEquals('1', l[14])
            }
            assertEquals(3, lines.toSet().size)
        }

    @Test
    fun unknownOptionErrors() =
        runBlocking {
            val (_, err, rc) = run(listOf("--bogus"))
            assertEquals(2, rc)
            assertTrue(err.contains("unknown option"), "err: $err")
        }

    @Test
    fun extraOperandErrors() =
        runBlocking {
            val (_, err, rc) = run(listOf("foo"))
            assertEquals(2, rc)
            assertTrue(err.contains("extra operand"), "err: $err")
        }

    @Test
    fun invalidCountErrors() =
        runBlocking {
            val (_, err, rc) = run(listOf("-n", "abc"))
            assertEquals(2, rc)
            assertTrue(err.contains("invalid count"), "err: $err")
        }

    @Test
    fun missingCountArgErrors() =
        runBlocking {
            val (_, err, rc) = run(listOf("-n"))
            assertEquals(2, rc)
            assertTrue(err.contains("requires an argument"), "err: $err")
        }

    // --- harness ---

    private data class R(
        val stdout: String,
        val stderr: String,
        val exit: Int,
    )

    private fun run(args: List<String>): R {
        val outBuf = Buffer()
        val errBuf = Buffer()
        val ctx =
            bareCommandContext(
                fs = NullFs,
                env = mutableMapOf(),
                cwd = "/",
                stdin = Buffer().asSuspendSource(),
                stdout = outBuf.asSuspendSink(),
                stderr = errBuf.asSuspendSink(),
            )
        val rc = runBlocking { UuidgenCommand().run(args, ctx).exitCode }
        return R(outBuf.readString(), errBuf.readString(), rc)
    }
}

private object NullFs : FileSystem {
    override fun exists(path: String): Boolean = false

    override fun isDirectory(path: String): Boolean = false

    override fun source(path: String): SuspendSource = Buffer().asSuspendSource()

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = Buffer().asSuspendSink()

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {}

    override fun list(path: String): List<String> = emptyList()

    override fun remove(path: String) {}
}
