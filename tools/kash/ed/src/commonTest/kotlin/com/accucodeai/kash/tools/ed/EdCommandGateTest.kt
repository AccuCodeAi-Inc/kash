package com.accucodeai.kash.tools.ed

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class EdCommandGateTest {
    private suspend fun invoke(args: List<String>): Pair<Int, String> {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = InMemoryFs(),
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val rc = EdCommand().run(args, ctx).exitCode
        return rc to (out.readString() + err.readString())
    }

    @Test fun versionExitsZero() =
        runTest {
            val (rc, out) = invoke(listOf("-V"))
            assertEquals(0, rc)
            assertContains(out, "ed")
        }

    @Test fun helpExits2() =
        runTest {
            val (rc, out) = invoke(listOf("--help"))
            assertEquals(2, rc)
            assertContains(out, "Usage")
        }

    @Test fun unknownFlagExits2() =
        runTest {
            val (rc, out) = invoke(listOf("--no-such"))
            assertEquals(2, rc)
            assertContains(out, "Usage")
        }

    @Test fun acceptsDashS() =
        runTest {
            val (rc, _) = invoke(listOf("-s"))
            // No stdin -> immediate EOF -> exit 0
            assertEquals(0, rc)
        }

    @Test fun acceptsPromptFlagSeparate() =
        runTest {
            val (rc, _) = invoke(listOf("-p", ">", "-s"))
            assertEquals(0, rc)
        }

    @Test fun acceptsPromptFlagAttached() =
        runTest {
            val (rc, _) = invoke(listOf("-p>", "-s"))
            assertEquals(0, rc)
        }

    @Test fun missingPromptArgExits2() =
        runTest {
            val (rc, _) = invoke(listOf("-p"))
            assertEquals(2, rc)
        }

    @Test fun twoFilesExit2() =
        runTest {
            val (rc, _) = invoke(listOf("-s", "/a", "/b"))
            assertEquals(2, rc)
        }
}
