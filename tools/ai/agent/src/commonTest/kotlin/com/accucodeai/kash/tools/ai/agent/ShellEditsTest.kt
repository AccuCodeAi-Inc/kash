package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.fs.AccessKind
import com.accucodeai.kash.fs.FileAccess
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.ai.agent.tools.renderShellEdits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `shell_exec` edit-visualization path: [renderShellEdits] turns a
 * command's recorded [FileAccess] mutations (with captured `before` content)
 * plus the file's current content into diff blocks / honest notes, and
 * [KashAgentShell] actually populates `touched` through the shared trace path.
 */
class ShellEditsTest {
    private fun access(
        path: String,
        kind: AccessKind,
        before: String? = null,
    ) = FileAccess(
        path = path,
        kind = kind,
        label = null,
        pid = 1,
        scopeId = 0L,
        before = before?.encodeToByteArray(),
    )

    private suspend fun render(
        seed: Map<String, ByteArray>,
        touched: List<FileAccess>,
    ): String {
        val ctx = bareCommandContext()
        for ((p, b) in seed) ctx.fs.writeBytes(p, b)
        val sb = StringBuilder()
        renderShellEdits({ sb.append(it) }, ctx, touched)
        return sb.toString()
    }

    @Test
    fun in_place_write_with_before_renders_a_diff() =
        runTest {
            val out =
                render(
                    seed = mapOf("/a" to "one\nTWO\nthree\n".encodeToByteArray()),
                    touched = listOf(access("/a", AccessKind.WRITE, before = "one\ntwo\nthree\n")),
                )
            assertTrue("edit: /a" in out, "expected an edit diff header; got:\n$out")
            assertTrue("- two" in out && "+ TWO" in out, "expected the changed line as -/+; got:\n$out")
        }

    @Test
    fun created_file_renders_as_new() =
        runTest {
            val out =
                render(
                    seed = mapOf("/n" to "hello\n".encodeToByteArray()),
                    touched = listOf(access("/n", AccessKind.CREATE)),
                )
            assertTrue("new: /n" in out, "expected a new-file header; got:\n$out")
            assertTrue("+ hello" in out, "expected the content as inserts; got:\n$out")
        }

    @Test
    fun deletion_renders_a_note() =
        runTest {
            // No seed → the path doesn't exist now → it was deleted.
            val out = render(seed = emptyMap(), touched = listOf(access("/gone", AccessKind.DELETE, before = "x\n")))
            assertTrue("deleted: /gone" in out, "got:\n$out")
        }

    @Test
    fun write_without_captured_before_degrades_to_a_note() =
        runTest {
            val out =
                render(
                    seed = mapOf("/a" to "whatever\n".encodeToByteArray()),
                    touched = listOf(access("/a", AccessKind.WRITE, before = null)),
                )
            assertTrue("diff unavailable" in out, "must not fake a diff; got:\n$out")
            assertTrue("edit:" !in out, "must not render a diff frame; got:\n$out")
        }

    @Test
    fun binary_result_renders_a_note_not_a_diff() =
        runTest {
            val out =
                render(
                    seed = mapOf("/b" to byteArrayOf(0x00, 0x01, 0xFF.toByte())),
                    touched = listOf(access("/b", AccessKind.WRITE, before = "text\n")),
                )
            assertTrue("binary" in out, "got:\n$out")
            assertTrue("edit:" !in out, "got:\n$out")
        }

    @Test
    fun unchanged_content_renders_nothing() =
        runTest {
            val out =
                render(
                    seed = mapOf("/a" to "same\n".encodeToByteArray()),
                    touched = listOf(access("/a", AccessKind.WRITE, before = "same\n")),
                )
            assertEquals("", out, "a no-net-change write must be silent")
        }

    @Test
    fun metadata_only_touch_is_ignored() =
        runTest {
            val out =
                render(
                    seed = mapOf("/a" to "x\n".encodeToByteArray()),
                    touched = listOf(access("/a", AccessKind.META)),
                )
            assertEquals("", out, "chmod/mtime must not render an edit")
        }

    @Test
    fun shell_exec_redirection_is_captured_and_scoped_in_touched() =
        runTest {
            val ctx = bareCommandContext()
            val shell = KashAgentShell(ctx)
            // `: > /created` truncates-or-creates with no external command, so
            // it works against the bare (empty-registry) test machine while
            // still exercising the real traced + scope-filter path.
            val r = shell.execute(": > /created")
            assertEquals(0, r.exitCode, "stderr: ${r.stderr}")
            val mutated = r.touched.filter { it.kind != AccessKind.READ }.map { it.path }
            assertTrue("/created" in mutated, "expected /created among touched mutations; got ${r.touched}")
            assertTrue(ctx.fs.exists("/created"), "the file should now exist")
        }
}
