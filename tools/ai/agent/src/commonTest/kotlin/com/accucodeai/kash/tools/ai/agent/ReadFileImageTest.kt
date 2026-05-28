package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.ai.agent.tools.PendingImage
import com.accucodeai.kash.tools.ai.agent.tools.ReadFileTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `read_file`'s content-sniffing branch: images are recognized by magic
 * signature, handed to the toolset's `onImage` sink for vision injection,
 * and acknowledged with text (the bytes can't ride back in the text-only
 * tool result). Text and non-image binary keep their prior behavior.
 */
class ReadFileImageTest {
    // 8-byte PNG signature — KMagic matches it with no further validation.
    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngBytes(): ByteArray = pngMagic + ByteArray(64) { it.toByte() }

    private fun pathArgs(path: String) = buildJsonObject { put("path", JsonPrimitive(path)) }

    @Test
    fun image_is_queued_for_vision_and_acked_as_text() =
        runTest {
            val ctx = bareCommandContext()
            val bytes = pngBytes()
            ctx.fs.writeBytes("/pic.png", bytes)
            var captured: PendingImage? = null
            val tool = ReadFileTool(ctx, KashAgentShell(ctx), onImage = { captured = it })

            val result = tool.execute(pathArgs("/pic.png"))

            assertTrue(result.startsWith("ok: loaded pic.png"), "ack: $result")
            assertTrue("image/png" in result, "ack names mime: $result")
            val img = captured ?: error("onImage never fired")
            assertEquals("image/png", img.mimeType)
            assertEquals("pic.png", img.fileName)
            assertContentEquals(bytes, img.bytes)
        }

    @Test
    fun utf8_text_returns_content_without_queuing_image() =
        runTest {
            val ctx = bareCommandContext()
            ctx.fs.writeBytes("/notes.txt", "hello world\n".encodeToByteArray())
            var captured: PendingImage? = null
            val tool = ReadFileTool(ctx, KashAgentShell(ctx), onImage = { captured = it })

            val result = tool.execute(pathArgs("/notes.txt"))

            assertEquals("hello world\n", result)
            assertNull(captured, "text file must not enqueue an image")
        }

    @Test
    fun non_image_binary_keeps_utf8_refusal() =
        runTest {
            val ctx = bareCommandContext()
            // Leading NUL, matches no signature → classified as data, not image.
            ctx.fs.writeBytes("/blob.bin", byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0x00))
            var captured: PendingImage? = null
            val tool = ReadFileTool(ctx, KashAgentShell(ctx), onImage = { captured = it })

            val result = tool.execute(pathArgs("/blob.bin"))

            assertTrue(result.startsWith("error: file is not valid UTF-8 text"), "got: $result")
            assertNull(captured)
        }

    @Test
    fun oversized_image_is_refused_and_not_queued() =
        runTest {
            val ctx = bareCommandContext()
            // PNG signature + padding past the 5 MiB cap.
            val huge = pngMagic + ByteArray(5 * 1024 * 1024 + 1)
            ctx.fs.writeBytes("/huge.png", huge)
            var captured: PendingImage? = null
            val tool = ReadFileTool(ctx, KashAgentShell(ctx), onImage = { captured = it })

            val result = tool.execute(pathArgs("/huge.png"))

            assertTrue(result.startsWith("error: image is"), "got: $result")
            assertTrue("too large" in result, "got: $result")
            assertNull(captured, "oversized image must not enqueue")
        }

    @Test
    fun imagePart_builds_data_url_from_bytes() {
        val bytes = pngBytes()
        val part = AgentAttachments.imagePart(bytes, "image/png")
        assertTrue(part.imageUrl.url.startsWith("data:image/png;base64,"), "got: ${part.imageUrl.url}")
    }
}
