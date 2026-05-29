package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.ai.agent.tools.EditFileTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `edit_file`'s str-replace semantics: a unique `old_string` is swapped for
 * `new_string` and written back; non-unique matches fail unless `replace_all`
 * is set; missing matches, identical strings, and binary files are refused
 * without mutating the file. The diff render is best-effort and not asserted
 * here (it's covered by DiffRenderer's own tests).
 */
class EditFileToolTest {
    private fun args(
        path: String,
        old: String,
        new: String,
        replaceAll: Boolean? = null,
    ) = buildJsonObject {
        put("path", JsonPrimitive(path))
        put("old_string", JsonPrimitive(old))
        put("new_string", JsonPrimitive(new))
        if (replaceAll != null) put("replace_all", JsonPrimitive(replaceAll))
    }

    @Test
    fun unique_match_is_replaced_and_persisted() =
        runTest {
            val ctx = bareCommandContext()
            ctx.fs.writeBytes("/f.txt", "alpha\nbeta\ngamma\n".encodeToByteArray())
            // Sink the diff so it doesn't bleed into test stdout.
            val tool = EditFileTool(ctx, KashAgentShell(ctx), emit = {})

            val result = tool.execute(args("/f.txt", "beta", "BETA"))

            assertTrue(result.startsWith("ok: replaced 1 occurrence in"), "got: $result")
            assertEquals("alpha\nBETA\ngamma\n", ctx.fs.readBytes("/f.txt").decodeToString())
        }

    @Test
    fun ambiguous_match_fails_without_writing() =
        runTest {
            val ctx = bareCommandContext()
            ctx.fs.writeBytes("/f.txt", "x\nx\n".encodeToByteArray())
            val tool = EditFileTool(ctx, KashAgentShell(ctx), emit = {})

            val result = tool.execute(args("/f.txt", "x", "y"))

            assertTrue("matches 2 times" in result, "got: $result")
            assertEquals("x\nx\n", ctx.fs.readBytes("/f.txt").decodeToString(), "file must be untouched")
        }

    @Test
    fun replace_all_swaps_every_occurrence() =
        runTest {
            val ctx = bareCommandContext()
            ctx.fs.writeBytes("/f.txt", "x\nx\nx\n".encodeToByteArray())
            val tool = EditFileTool(ctx, KashAgentShell(ctx), emit = {})

            val result = tool.execute(args("/f.txt", "x", "y", replaceAll = true))

            assertTrue(result.startsWith("ok: replaced 3 occurrences in"), "got: $result")
            assertEquals("y\ny\ny\n", ctx.fs.readBytes("/f.txt").decodeToString())
        }

    @Test
    fun missing_match_is_reported() =
        runTest {
            val ctx = bareCommandContext()
            ctx.fs.writeBytes("/f.txt", "hello\n".encodeToByteArray())
            val tool = EditFileTool(ctx, KashAgentShell(ctx), emit = {})

            val result = tool.execute(args("/f.txt", "goodbye", "hi"))

            assertTrue(result.startsWith("error: old_string not found"), "got: $result")
        }

    @Test
    fun identical_strings_are_a_noop_error() =
        runTest {
            val ctx = bareCommandContext()
            ctx.fs.writeBytes("/f.txt", "same\n".encodeToByteArray())
            val tool = EditFileTool(ctx, KashAgentShell(ctx), emit = {})

            val result = tool.execute(args("/f.txt", "same", "same"))

            assertTrue("identical" in result, "got: $result")
        }

    @Test
    fun missing_file_is_reported() =
        runTest {
            val ctx = bareCommandContext()
            val tool = EditFileTool(ctx, KashAgentShell(ctx), emit = {})

            val result = tool.execute(args("/nope.txt", "a", "b"))

            assertTrue(result.startsWith("error: file not found"), "got: $result")
        }

    @Test
    fun binary_file_is_refused() =
        runTest {
            val ctx = bareCommandContext()
            ctx.fs.writeBytes("/blob.bin", byteArrayOf(0x00, 0x01, 0xFF.toByte()))
            val tool = EditFileTool(ctx, KashAgentShell(ctx), emit = {})

            val result = tool.execute(args("/blob.bin", "a", "b"))

            assertTrue("not valid UTF-8 text" in result, "got: $result")
        }
}
