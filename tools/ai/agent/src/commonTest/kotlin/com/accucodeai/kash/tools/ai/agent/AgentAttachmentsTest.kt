package com.accucodeai.kash.tools.ai.agent

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import com.accucodeai.kash.test.NullFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the drop-handler → `[attachment N]` → multipart user-message
 * pipeline without spinning a terminal, an LLM client, or the agent
 * coroutine loop.
 */
class AgentAttachmentsTest {
    /**
     * Tiny path → bytes FS for the image-bytes round-trip. Inherits
     * everything-returns-empty from NullFs and only models the read
     * path the test needs.
     */
    private class FakeFs(
        private val store: Map<String, ByteArray>,
    ) : NullFs() {
        override fun exists(path: String): Boolean = path in store

        override suspend fun readBytes(path: String): ByteArray = store[path] ?: error("FakeFs: no file at $path")
    }

    @Test
    fun add_assigns_one_based_indices_and_queues_notices() =
        runTest {
            val a = AgentAttachments(FakeFs(emptyMap()))
            val i1 = a.add("/tmp/drops/a.txt", "text/plain", 12, "a.txt")
            val i2 = a.add("/tmp/drops/b.png", "image/png", 4096, "b.png")
            assertEquals(1, i1)
            assertEquals(2, i2)
            val notices = a.drainNotices()
            assertEquals(2, notices.size)
            assertEquals(1, notices[0].index)
            assertEquals("a.txt", notices[0].fileName)
            assertEquals(2, notices[1].index)
            // Second drain is empty — queue consumed.
            assertTrue(a.drainNotices().isEmpty())
        }

    @Test
    fun add_queues_env_bindings_drained_once() =
        runTest {
            val a = AgentAttachments(FakeFs(emptyMap()))
            a.add("/tmp/drops/sess-2/ctf.zip", "application/zip", 100, "ctf.zip")
            a.add("/tmp/drops/sess-2/cat.png", "image/png", 200, "cat.png")
            val exports = a.drainPendingExports()
            assertEquals(2, exports.size)
            assertEquals("KASH_ATTACHMENT_1", exports[0].name)
            assertEquals("/tmp/drops/sess-2/ctf.zip", exports[0].path)
            assertEquals("KASH_ATTACHMENT_2", exports[1].name)
            assertEquals("/tmp/drops/sess-2/cat.png", exports[1].path)
            // Drained — a second call is empty (don't re-export every turn).
            assertTrue(a.drainPendingExports().isEmpty())
        }

    @Test
    fun no_reference_means_plain_text_only() =
        runTest {
            val a = AgentAttachments(FakeFs(emptyMap()))
            a.add("/tmp/drops/x.png", "image/png", 16, "x.png")
            val parts = a.buildUserParts("hello world")
            assertEquals(1, parts.size)
            val text = parts[0] as MessagePart.Text
            assertEquals("hello world", text.text)
        }

    @Test
    fun unknown_index_is_left_as_literal_text() =
        runTest {
            val a = AgentAttachments(FakeFs(emptyMap()))
            // No drops registered; index 7 is bogus.
            val parts = a.buildUserParts("look at [attachment 7]")
            assertEquals(1, parts.size)
            val text = parts[0] as MessagePart.Text
            // No "Attachments:" block, no image part — the marker passes
            // through unchanged for the model to complain about.
            assertEquals("look at [attachment 7]", text.text)
        }

    @Test
    fun decrypt_this_file_text_attachment_only() =
        runTest {
            val a = AgentAttachments(FakeFs(mapOf("/tmp/drops/cipher.bin" to ByteArray(0))))
            a.add("/tmp/drops/cipher.bin", "application/octet-stream", 4321, "cipher.bin")
            val parts = a.buildUserParts("decrypt this file: [attachment 1]")
            // Only a text part — no image inlined for application/octet-stream.
            assertEquals(1, parts.size)
            val text = (parts[0] as MessagePart.Text).text
            assertTrue(text.startsWith("Attachments:\n"), "got: $text")
            assertTrue("[attachment 1] cipher.bin" in text)
            assertTrue("application/octet-stream" in text)
            assertTrue("path: /tmp/drops/cipher.bin" in text)
            // The env-var binding is surfaced so the model can use it.
            assertTrue("\$KASH_ATTACHMENT_1" in text, "got: $text")
            assertTrue(text.endsWith("decrypt this file: [attachment 1]"))
            // A binary (octet-stream) is a path reference only — never inlined.
            assertTrue("--- contents ---" !in text, "binary must not be inlined · got: $text")
        }

    @Test
    fun small_text_attachment_is_inlined_with_its_contents() =
        runTest {
            val body = "CyberChef Mini-CTF\nGoal: recover the flag\n"
            val a = AgentAttachments(FakeFs(mapOf("/tmp/drops/challenge.txt" to body.encodeToByteArray())))
            a.add("/tmp/drops/challenge.txt", "text/plain", body.length.toLong(), "challenge.txt")
            val parts = a.buildUserParts("read [attachment 1]")
            assertEquals(1, parts.size, "text inlines into the text part, no extra content part")
            val text = (parts[0] as MessagePart.Text).text
            assertTrue("path: /tmp/drops/challenge.txt" in text, "path still surfaced · got: $text")
            assertTrue("--- contents ---" in text, "text file contents inlined · got: $text")
            assertTrue("Goal: recover the flag" in text, "the actual file body is inlined · got: $text")
        }

    @Test
    fun large_text_attachment_is_not_inlined() =
        runTest {
            // Beyond the cap: keep it a path reference so the prompt isn't bloated.
            val big = "x".repeat((AgentAttachments.INLINE_TEXT_CAP + 1).toInt())
            val a = AgentAttachments(FakeFs(mapOf("/tmp/drops/huge.txt" to big.encodeToByteArray())))
            a.add("/tmp/drops/huge.txt", "text/plain", big.length.toLong(), "huge.txt")
            val parts = a.buildUserParts("read [attachment 1]")
            val text = (parts[0] as MessagePart.Text).text
            assertTrue("path: /tmp/drops/huge.txt" in text, "path surfaced · got start: ${text.take(120)}")
            assertTrue("--- contents ---" !in text, "oversized text must not be inlined")
        }

    @Test
    fun image_attachment_emits_content_part_image_with_bytes() =
        runTest {
            val bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            val a =
                AgentAttachments(
                    FakeFs(mapOf("/tmp/drops/cat.png" to bytes)),
                )
            a.add("/tmp/drops/cat.png", "image/png", bytes.size.toLong(), "cat.png")
            val parts = a.buildUserParts("what is in [attachment 1]?")
            assertEquals(2, parts.size, "expected text + image parts, got $parts")
            val text = (parts[0] as MessagePart.Text).text
            assertTrue("[attachment 1] cat.png" in text)
            assertTrue("image/png" in text)
            val img = parts[1] as MessagePart.Attachment
            assertEquals("png", img.source.format)
            assertEquals("image/png", img.source.mimeType)
            assertEquals("cat.png", img.source.fileName)
            val payload = img.source.content as AttachmentContent.Binary.Bytes
            assertContentEquals(bytes, payload.data)
        }

    @Test
    fun mime_subtype_with_suffix_strips_to_canonical_format() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3)
            val a = AgentAttachments(FakeFs(mapOf("/tmp/drops/icon.svg" to bytes)))
            // `image/svg+xml; charset=utf-8` — format should land on "svg",
            // not "svg+xml" or "svg+xml; charset=utf-8".
            a.add("/tmp/drops/icon.svg", "image/svg+xml; charset=utf-8", 3, "icon.svg")
            val parts = a.buildUserParts("describe [attachment 1]")
            val img = parts.filterIsInstance<MessagePart.Attachment>().single()
            assertEquals("svg", img.source.format)
        }

    @Test
    fun duplicate_references_dedupe_and_unreferenced_are_omitted() =
        runTest {
            val img = byteArrayOf(0)
            val a =
                AgentAttachments(
                    FakeFs(
                        mapOf(
                            "/tmp/drops/a.png" to img,
                            "/tmp/drops/b.txt" to ByteArray(0),
                        ),
                    ),
                )
            a.add("/tmp/drops/a.png", "image/png", 1, "a.png")
            a.add("/tmp/drops/b.txt", "text/plain", 0, "b.txt")
            // Reference [attachment 1] twice; never mention [attachment 2].
            val parts = a.buildUserParts("compare [attachment 1] and [attachment 1] again")
            val text = (parts[0] as MessagePart.Text).text
            // The Attachments: block lists 1 once — dedup, no 2 mentioned.
            val attBlock = text.substringBefore("\n\n")
            assertEquals(1, attBlock.lines().filter { "[attachment 1]" in it }.size)
            assertTrue("[attachment 2]" !in attBlock)
            // Exactly one image part for index 1.
            val images = parts.filterIsInstance<MessagePart.Attachment>()
            assertEquals(1, images.size)
            assertEquals("a.png", images.single().source.fileName)
        }

    @Test
    fun image_attachment_survives_into_next_turn_if_not_referenced() =
        runTest {
            val bytes = byteArrayOf(7, 7, 7)
            val a = AgentAttachments(FakeFs(mapOf("/tmp/drops/p.jpg" to bytes)))
            a.add("/tmp/drops/p.jpg", "image/jpeg", bytes.size.toLong(), "p.jpg")
            // First turn doesn't reference the drop.
            a.buildUserParts("ignore it")
            // Second turn does — registry must still hold the entry.
            val parts = a.buildUserParts("now look at [attachment 1]")
            val img = parts.filterIsInstance<MessagePart.Attachment>().single()
            assertNotNull(img)
            assertContentEquals(bytes, (img.source.content as AttachmentContent.Binary.Bytes).data)
        }
}
