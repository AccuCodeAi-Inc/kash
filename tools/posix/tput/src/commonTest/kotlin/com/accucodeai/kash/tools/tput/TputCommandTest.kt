package com.accucodeai.kash.tools.tput

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.api.terminal.TerminalSize
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeTerm(
    private val c: Int,
    private val r: Int,
) : TerminalControl {
    override suspend fun enterRawMode() = Unit

    override suspend fun exitRawMode() = Unit

    override suspend fun useAlternateScreen(enable: Boolean) = Unit

    override fun size(): TerminalSize = TerminalSize(c, r)

    override suspend fun readKey(): Key = Key.Named.ENTER

    override suspend fun write(s: String) = Unit

    override suspend fun flush() = Unit
}

private suspend fun runTput(
    args: List<String>,
    env: MutableMap<String, String> = mutableMapOf(),
    terminal: TerminalControl? = null,
    stdin: String = "",
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val stdinBuf = Buffer().also { it.writeString(stdin) }
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            env = env,
            cwd = "/",
            stdin = stdinBuf.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            stdinIsTty = terminal != null,
            terminal = terminal,
        )
    val res = TputCommand().run(args, ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

private const val ESC = ""

class TputCommandTest {
    @Test fun clearEmitsAnsi() =
        runTest {
            val (rc, out, _) = runTput(listOf("clear"))
            assertEquals(0, rc)
            assertEquals("$ESC[2J$ESC[H", out)
        }

    @Test fun cupOneIndexed() =
        runTest {
            val (rc, out, _) = runTput(listOf("cup", "5", "10"))
            assertEquals(0, rc)
            assertEquals("$ESC[6;11H", out)
        }

    @Test fun boldEmitsSgr1() =
        runTest {
            val (rc, out, _) = runTput(listOf("bold"))
            assertEquals(0, rc)
            assertEquals("$ESC[1m", out)
        }

    @Test fun sgr0Resets() =
        runTest {
            val (rc, out, _) = runTput(listOf("sgr0"))
            assertEquals(0, rc)
            assertEquals("$ESC[0m", out)
        }

    @Test fun resetAliasesSgr0() =
        runTest {
            val (_, out, _) = runTput(listOf("reset"))
            assertEquals("$ESC[0m", out)
        }

    @Test fun setafBasicColor() =
        runTest {
            val (rc, out, _) = runTput(listOf("setaf", "1"))
            assertEquals(0, rc)
            assertEquals("$ESC[31m", out)
        }

    @Test fun setabBasicColor() =
        runTest {
            val (rc, out, _) = runTput(listOf("setab", "4"))
            assertEquals(0, rc)
            assertEquals("$ESC[44m", out)
        }

    @Test fun setafBrightColor() =
        runTest {
            val (_, out, _) = runTput(listOf("setaf", "9"))
            assertEquals("$ESC[91m", out)
        }

    @Test fun setaf256() =
        runTest {
            val (_, out, _) = runTput(listOf("setaf", "208"))
            assertEquals("$ESC[38;5;208m", out)
        }

    @Test fun colsFromTerminalSize() =
        runTest {
            val (rc, out, _) = runTput(listOf("cols"), terminal = FakeTerm(132, 50))
            assertEquals(0, rc)
            assertEquals("132\n", out)
        }

    @Test fun linesFromTerminalSize() =
        runTest {
            val (rc, out, _) = runTput(listOf("lines"), terminal = FakeTerm(132, 50))
            assertEquals(0, rc)
            assertEquals("50\n", out)
        }

    @Test fun colsFromEnvFallback() =
        runTest {
            val (rc, out, _) =
                runTput(
                    listOf("cols"),
                    env = mutableMapOf("COLUMNS" to "100"),
                    terminal = null,
                )
            assertEquals(0, rc)
            assertEquals("100\n", out)
        }

    @Test fun colsDefaults80() =
        runTest {
            val (rc, out, _) = runTput(listOf("cols"))
            assertEquals(0, rc)
            assertEquals("80\n", out)
        }

    @Test fun linesDefault24() =
        runTest {
            val (_, out, _) = runTput(listOf("lines"))
            assertEquals("24\n", out)
        }

    @Test fun colorsNumeric() =
        runTest {
            val (rc, out, _) = runTput(listOf("colors"))
            assertEquals(0, rc)
            assertEquals("8\n", out)
        }

    @Test fun elClearsToEol() =
        runTest {
            val (_, out, _) = runTput(listOf("el"))
            assertEquals("$ESC[K", out)
        }

    @Test fun homeMovesCursorHome() =
        runTest {
            val (_, out, _) = runTput(listOf("home"))
            assertEquals("$ESC[H", out)
        }

    @Test fun civisHidesCursor() =
        runTest {
            val (_, out, _) = runTput(listOf("civis"))
            assertEquals("$ESC[?25l", out)
        }

    @Test fun cnormShowsCursor() =
        runTest {
            val (_, out, _) = runTput(listOf("cnorm"))
            assertEquals("$ESC[?25h", out)
        }

    @Test fun unknownCapabilityErrors() =
        runTest {
            val (rc, _, err) = runTput(listOf("nosuchthing"))
            assertEquals(1, rc)
            assertTrue(err.contains("unknown capability"))
        }

    @Test fun dashTAcceptsTermType() =
        runTest {
            val (rc, out, _) = runTput(listOf("-T", "xterm-256color", "bold"))
            assertEquals(0, rc)
            assertEquals("$ESC[1m", out)
        }

    @Test fun dashSReadsCommandsFromStdin() =
        runTest {
            val (rc, out, _) =
                runTput(
                    listOf("-S"),
                    stdin = "clear\nbold\nsgr0\n",
                )
            assertEquals(0, rc)
            assertEquals("$ESC[2J$ESC[H$ESC[1m$ESC[0m", out)
        }

    @Test fun dashSSkipsBlankLines() =
        runTest {
            val (rc, out, _) = runTput(listOf("-S"), stdin = "\nbold\n\n  \nsgr0\n")
            assertEquals(0, rc)
            assertEquals("$ESC[1m$ESC[0m", out)
        }

    @Test fun missingCapabilityIsUsageError() =
        runTest {
            val (rc, _, err) = runTput(emptyList())
            assertEquals(2, rc)
            assertTrue(err.contains("missing"))
        }

    @Test fun cupRequiresNumericArgs() =
        runTest {
            val (rc, _, err) = runTput(listOf("cup", "x", "y"))
            assertEquals(1, rc)
            assertTrue(err.contains("numeric"))
        }

    @Test fun escByteIsLiteral0x1B() =
        runTest {
            val (_, out, _) = runTput(listOf("bold"))
            assertTrue(out.isNotEmpty())
            assertEquals(0x1B, out[0].code, "first byte of `tput bold` must be ESC (0x1B)")
        }

    @Test fun cubMovesLeft() =
        runTest {
            val (_, out, _) = runTput(listOf("cub", "5"))
            assertEquals("$ESC[5D", out)
        }

    @Test fun helpPrints() =
        runTest {
            val (rc, out, _) = runTput(listOf("--help"))
            assertEquals(0, rc)
            assertTrue(out.startsWith("Usage: tput"))
        }
}
