package com.accucodeai.kash.tools.which

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

/**
 * Engine-level tests for the `which` tool. We construct a [CommandContext]
 * directly and pre-populate a backing [InMemoryFs] with real files at PATH
 * locations — `which` walks the FS via `ctx.process.fs.exists`, so the fakes
 * provide everything we need.
 */
class WhichCommandTest {
    private suspend fun runWhich(
        path: String,
        cwd: String = "/",
        files: List<String> = emptyList(),
        vararg args: String,
    ): Triple<Int, String, String> {
        val fs = InMemoryFs()
        for (f in files) {
            // Create parent dir then write empty file.
            val parent = f.substringBeforeLast('/').ifEmpty { "/" }
            fs.mkdirs(parent)
            fs.writeBytes(f, ByteArray(0))
        }
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf("PATH" to path),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = WhichCommand().run(args.toList(), ctx)
        return Triple(res.exitCode, out.readString(), err.readString())
    }

    @Test fun finds_single_file_in_path() =
        runTest {
            val (rc, out, _) =
                runWhich(
                    path = "/usr/bin",
                    files = listOf("/usr/bin/grep"),
                    args = arrayOf("grep"),
                )
            assertEquals(0, rc)
            assertEquals("/usr/bin/grep\n", out)
        }

    @Test fun not_found_exits_one() =
        runTest {
            val (rc, out, err) =
                runWhich(
                    path = "/usr/bin",
                    files = emptyList(),
                    args = arrayOf("nope"),
                )
            assertEquals(1, rc)
            assertEquals("", out)
            assertTrue(err.contains("nope: not found"))
        }

    @Test fun stops_at_first_match_without_dash_a() =
        runTest {
            val (rc, out, _) =
                runWhich(
                    path = "/a:/b",
                    files = listOf("/a/dup", "/b/dup"),
                    args = arrayOf("dup"),
                )
            assertEquals(0, rc)
            assertEquals("/a/dup\n", out)
        }

    @Test fun dash_a_prints_all_matches() =
        runTest {
            val (rc, out, _) =
                runWhich(
                    path = "/a:/b",
                    files = listOf("/a/dup", "/b/dup"),
                    args = arrayOf("-a", "dup"),
                )
            assertEquals(0, rc)
            assertEquals("/a/dup\n/b/dup\n", out)
        }

    @Test fun multiple_names_mixed_results_exits_one() =
        runTest {
            val (rc, out, err) =
                runWhich(
                    path = "/u",
                    files = listOf("/u/found"),
                    args = arrayOf("found", "missing"),
                )
            assertEquals(1, rc)
            assertEquals("/u/found\n", out)
            assertTrue(err.contains("missing: not found"))
        }

    @Test fun silent_suppresses_output_but_keeps_status() =
        runTest {
            val (rc, out, err) =
                runWhich(
                    path = "/u",
                    files = listOf("/u/grep"),
                    args = arrayOf("-s", "grep", "nope"),
                )
            assertEquals(1, rc)
            assertEquals("", out)
            assertEquals("", err)
        }

    @Test fun path_qualified_argument_is_returned_verbatim() =
        runTest {
            val (rc, out, _) =
                runWhich(
                    path = "/u",
                    files = listOf("/etc/hosts"),
                    args = arrayOf("/etc/hosts"),
                )
            assertEquals(0, rc)
            assertEquals("/etc/hosts\n", out)
        }

    @Test fun missing_operand_is_usage_error() =
        runTest {
            val (rc, _, err) = runWhich(path = "/u")
            assertEquals(2, rc)
            assertTrue(err.contains("missing operand"))
        }

    @Test fun bundled_short_flags() =
        runTest {
            // -as silently locates everything; exit 0.
            val (rc, out, err) =
                runWhich(
                    path = "/a:/b",
                    files = listOf("/a/dup", "/b/dup"),
                    args = arrayOf("-as", "dup"),
                )
            assertEquals(0, rc)
            assertEquals("", out)
            assertEquals("", err)
        }
}
