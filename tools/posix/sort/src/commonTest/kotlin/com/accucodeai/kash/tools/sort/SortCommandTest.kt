package com.accucodeai.kash.tools.sort

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals

class SortCommandTest {
    private class StubFs : FileSystem {
        override fun exists(path: String): Boolean = false

        override fun isDirectory(path: String): Boolean = false

        override fun source(path: String): SuspendSource = error("not used")

        override fun sink(
            path: String,
            append: Boolean,
            mode: Int,
        ): SuspendSink = error("not used")

        override fun mkdirs(
            path: String,
            mode: Int,
        ) = error("not used")

        override fun list(path: String): List<String> = emptyList()

        override fun remove(path: String) = error("not used")
    }

    private suspend fun runSort(
        stdin: String,
        vararg args: String,
    ): Pair<String, Int> {
        val inBuf = Buffer().apply { writeString(stdin) }
        val outBuf = Buffer()
        val errBuf = Buffer()
        val ctx =
            bareCommandContext(
                fs = StubFs(),
                env = mutableMapOf(),
                cwd = "/",
                stdin = inBuf.asSuspendSource(),
                stdout = outBuf.asSuspendSink(),
                stderr = errBuf.asSuspendSink(),
            )
        val result = SortCommand().run(args.toList(), ctx)
        return outBuf.readByteArray().decodeToString() to result.exitCode
    }

    @Test fun basic_pipeline() =
        runTest {
            val (out, code) = runSort("cherry\nbanana\napple\n")
            assertEquals(0, code)
            assertEquals("apple\nbanana\ncherry\n", out)
        }

    @Test fun numeric_via_command() =
        runTest {
            val (out, code) = runSort("10\n2\n100\n", "-n")
            assertEquals(0, code)
            assertEquals("2\n10\n100\n", out)
        }

    @Test fun unique_reverse_via_command() =
        runTest {
            val (out, code) = runSort("b\na\nb\nc\na\n", "-ru")
            assertEquals(0, code)
            assertEquals("c\nb\na\n", out)
        }

    @Test fun unknown_flag_exits_2() =
        runTest {
            val (_, code) = runSort("a\n", "-Z")
            assertEquals(2, code)
        }

    @Test fun handles_no_trailing_newline() =
        runTest {
            val (out, code) = runSort("c\nb\na")
            assertEquals(0, code)
            assertEquals("a\nb\nc\n", out)
        }

    @Test fun empty_stdin_emits_nothing() =
        runTest {
            val (out, code) = runSort("")
            assertEquals(0, code)
            assertEquals("", out)
        }
}
