@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.accucodeai.kash.tools.mkfifo

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun newCtx(fs: InMemoryFs = InMemoryFs()): Triple<CommandContext, Buffer, Buffer> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            cwd = "/",
        )
    return Triple(ctx, out, err)
}

class MkfifoCommandTest {
    @Test fun parseMode_octal_644() {
        assertEquals(0b110_100_100, parseMode("644"))
    }

    @Test fun parseMode_leading_zero() {
        assertEquals(0b110_100_100, parseMode("0644"))
    }

    @Test fun parseMode_777() {
        assertEquals(0b111_111_111, parseMode("777"))
    }

    @Test fun parseMode_rejects_nonOctal() {
        assertEquals(null, parseMode("abc"))
        assertEquals(null, parseMode("89"))
        assertEquals(null, parseMode(""))
    }

    @Test fun createsSingleFifo() =
        runTest {
            val fs = InMemoryFs()
            val (ctx, _, err) = newCtx(fs)
            val rc = MkfifoCommand().run(listOf("/a"), ctx)
            assertEquals(0, rc.exitCode, err.readString())
            assertTrue(fs.exists("/a"))
        }

    @Test fun createsMultipleFifos() =
        runTest {
            val fs = InMemoryFs()
            val (ctx, _, err) = newCtx(fs)
            val rc = MkfifoCommand().run(listOf("/a", "/b", "/c"), ctx)
            assertEquals(0, rc.exitCode, err.readString())
            assertTrue(fs.exists("/a"))
            assertTrue(fs.exists("/b"))
            assertTrue(fs.exists("/c"))
        }

    @Test fun partial_failure_continuesOtherOperands() =
        runTest {
            val fs = InMemoryFs()
            // "/missing-parent/x" should fail; "/y" should succeed.
            val (ctx, _, err) = newCtx(fs)
            val rc = MkfifoCommand().run(listOf("/missing/x", "/y"), ctx)
            assertEquals(1, rc.exitCode)
            assertTrue(err.readString().contains("missing"))
            assertTrue(fs.exists("/y"), "second operand should still be attempted")
        }

    @Test fun existingPath_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/exists", ByteArray(0))
            val (ctx, _, err) = newCtx(fs)
            val rc = MkfifoCommand().run(listOf("/exists"), ctx)
            assertEquals(1, rc.exitCode)
            assertTrue(err.readString().contains("File exists"))
        }

    @Test fun dashM_acceptsMode() =
        runTest {
            val fs = InMemoryFs()
            val (ctx, _, err) = newCtx(fs)
            val rc = MkfifoCommand().run(listOf("-m", "0600", "/a"), ctx)
            assertEquals(0, rc.exitCode, err.readString())
            assertTrue(fs.exists("/a"))
        }

    @Test fun dashM_invalidMode_errors() =
        runTest {
            val fs = InMemoryFs()
            val (ctx, _, err) = newCtx(fs)
            val rc = MkfifoCommand().run(listOf("-m", "u+rwx", "/a"), ctx)
            assertEquals(1, rc.exitCode)
            assertTrue(err.readString().contains("invalid mode"))
        }

    @Test fun modeLong_form() =
        runTest {
            val fs = InMemoryFs()
            val (ctx, _, err) = newCtx(fs)
            val rc = MkfifoCommand().run(listOf("--mode=0644", "/a"), ctx)
            assertEquals(0, rc.exitCode, err.readString())
        }

    @Test fun missingOperand_errors() =
        runTest {
            val (ctx, _, err) = newCtx()
            val rc = MkfifoCommand().run(emptyList(), ctx)
            assertEquals(1, rc.exitCode)
            assertTrue(err.readString().contains("missing operand"))
        }

    @Test fun missingDashMValue_errors() =
        runTest {
            val (ctx, _, err) = newCtx()
            val rc = MkfifoCommand().run(listOf("-m"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().contains("requires an argument"))
        }

    @Test fun parentNotADirectory_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/notdir", ByteArray(0))
            val (ctx, _, err) = newCtx(fs)
            val rc = MkfifoCommand().run(listOf("/notdir/x"), ctx)
            assertEquals(1, rc.exitCode)
            assertTrue(err.readString().contains("Not a directory") || err.readString().contains("not a directory"))
        }

    @Test fun doubleDash_endsOptions() =
        runTest {
            val fs = InMemoryFs()
            val (ctx, _, err) = newCtx(fs)
            // "--" then a path that looks like an option.
            val rc = MkfifoCommand().run(listOf("--", "/-weird"), ctx)
            assertEquals(0, rc.exitCode, err.readString())
            assertTrue(fs.exists("/-weird"))
        }

    @Test fun unknownOption_errors() =
        runTest {
            val (ctx, _, err) = newCtx()
            val rc = MkfifoCommand().run(listOf("-Z", "/x"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().contains("invalid option"))
        }

    @Test fun help_exits_0() =
        runTest {
            val (ctx, out, _) = newCtx()
            val rc = MkfifoCommand().run(listOf("--help"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue(out.readString().contains("Usage"))
        }

    @Test fun version_exits_0() =
        runTest {
            val (ctx, out, _) = newCtx()
            val rc = MkfifoCommand().run(listOf("-V"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue(out.readString().contains("mkfifo"))
        }
}
