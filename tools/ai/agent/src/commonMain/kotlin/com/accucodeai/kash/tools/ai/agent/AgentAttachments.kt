package com.accucodeai.kash.tools.ai.agent

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import com.accucodeai.kash.api.AttachmentSink
import com.accucodeai.kash.fs.FileSystem

/**
 * Per-session attachment registry for [AgentSession]. Implements
 * [AttachmentSink] so the host UI's drop handler can deposit files; the
 * agent then surfaces them as `[attachment N]` tokens in the prompt and
 * expands them into structured user-message parts on submit.
 *
 * Separated from [AgentSession] so the prompt-expansion + multimodal
 * branching is unit-testable without spinning a terminal, an LLM client,
 * or a coroutine loop.
 */
internal class AgentAttachments(
    private val fs: FileSystem,
) : AttachmentSink {
    private val items: MutableList<DroppedAttachment> = mutableListOf()
    private val pendingNotices: MutableList<PendingNotice> = mutableListOf()
    private val pendingExports: MutableList<EnvBinding> = mutableListOf()

    data class DroppedAttachment(
        val path: String,
        val mimeType: String,
        val sizeBytes: Long,
        val fileName: String,
    )

    data class PendingNotice(
        val index: Int,
        val fileName: String,
        val sizeBytes: Long,
    )

    /** A `KASH_ATTACHMENT_N=path` shell-export to apply at the next quiescent point. */
    data class EnvBinding(
        val name: String,
        val path: String,
    )

    /** Snapshot of the registry, in insertion order. 1-based to match `[attachment N]`. */
    fun snapshot(): List<DroppedAttachment> = items.toList()

    /** Drain queued drop notifications (called by [AgentSession] before each prompt). */
    fun drainNotices(): List<PendingNotice> {
        if (pendingNotices.isEmpty()) return emptyList()
        val out = pendingNotices.toList()
        pendingNotices.clear()
        return out
    }

    /**
     * Drain queued `KASH_ATTACHMENT_N` env bindings. [AgentSession]
     * applies these to the subshell at a quiescent point (top of a turn)
     * — never from `add()`, which runs on the drop-handler coroutine and
     * could reenter a tool call mid-flight.
     */
    fun drainPendingExports(): List<EnvBinding> {
        if (pendingExports.isEmpty()) return emptyList()
        val out = pendingExports.toList()
        pendingExports.clear()
        return out
    }

    override suspend fun add(
        path: String,
        mimeType: String,
        sizeBytes: Long,
        fileName: String,
    ): Int {
        items += DroppedAttachment(path, mimeType, sizeBytes, fileName)
        val index = items.size
        pendingNotices += PendingNotice(index, fileName, sizeBytes)
        pendingExports += EnvBinding("KASH_ATTACHMENT_$index", path)
        return index
    }

    /**
     * Build the multipart user-message body for one turn.
     *
     *  - Scan [userText] for `[attachment N]` markers and resolve each to
     *    an entry. Unknown indices stay as literal text (the model sees
     *    them verbatim and can complain).
     *  - Prepend an `Attachments:` block to the text part listing each
     *    referenced file's name, mime, size, and absolute path. The model
     *    uses the path with `read_file` / `shell_exec` for decryption,
     *    parsing, etc.
     *  - For image attachments, also emit a [ContentPart.Image] holding
     *    the raw bytes — Koog base64-encodes per the OpenAI vision wire
     *    format. Non-vision backends will reject; the agent's
     *    `describeError` HTTP-400 hint covers that.
     *  - Files dropped but NOT referenced by `[attachment N]` are
     *    preserved for future turns ("look at this one too" on the next
     *    prompt works).
     */
    suspend fun buildUserParts(userText: String): List<MessagePart.RequestPart> {
        val referencedIndices =
            ATTACHMENT_REF
                .findAll(userText)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in 1..items.size }
                .distinct()
                .toList()
        if (referencedIndices.isEmpty()) {
            return listOf(MessagePart.Text(userText))
        }
        val referenced = referencedIndices.map { it to items[it - 1] }
        val inlinedText = mutableMapOf<Int, String>()
        for ((idx, a) in referenced) {
            if (a.mimeType.startsWith("text/") && a.sizeBytes > 0 && a.sizeBytes <= INLINE_TEXT_CAP) {
                val content =
                    try {
                        fs.readBytes(a.path).decodeToString()
                    } catch (_: Throwable) {
                        null
                    }
                if (!content.isNullOrEmpty()) inlinedText[idx] = content
            }
        }
        val expanded =
            buildString {
                append("Attachments:\n")
                for ((idx, a) in referenced) {
                    append("  [attachment ").append(idx).append("] ")
                    append(a.fileName).append(" — ")
                    append(a.mimeType).append(", ")
                    append(humanBytes(a.sizeBytes)).append('\n')
                    append("    path: ").append(a.path).append('\n')
                    append("    env:  \$KASH_ATTACHMENT_").append(idx).append('\n')
                    inlinedText[idx]?.let { content ->
                        append("    --- contents ---\n")
                        append(content)
                        if (!content.endsWith("\n")) append('\n')
                        append("    --- end contents ---\n")
                    }
                }
                append('\n')
                append(userText)
            }
        val parts = mutableListOf<MessagePart.RequestPart>(MessagePart.Text(expanded))
        for ((_, a) in referenced) {
            if (!a.mimeType.startsWith("image/")) continue
            val bytes =
                try {
                    fs.readBytes(a.path)
                } catch (_: Throwable) {
                    continue
                }
            parts += imagePart(bytes, a.mimeType, a.fileName)
        }
        return parts
    }

    companion object {
        const val INLINE_TEXT_CAP: Long = 32_768L

        /** Matches `[attachment N]` where N is a 1-based decimal index. */
        val ATTACHMENT_REF: Regex = Regex("""\[attachment (\d+)]""")

        /**
         * Build a Koog multimodal image part from raw bytes. [format] is the
         * mime subtype — Koog's file-extension hint for providers that need
         * it (Anthropic etc.) — derived by stripping the `image/` prefix and
         * any `+xml` / `;charset=…` suffix. Shared by the drag-drop attachment
         * path ([buildUserParts]) and read_file's image-read injection so both
         * emit identical wire parts.
         */
        fun imagePart(
            bytes: ByteArray,
            mimeType: String,
            fileName: String,
        ): MessagePart.Attachment {
            val format =
                mimeType
                    .substringAfter('/')
                    .substringBefore(';')
                    .substringBefore('+')
                    .ifEmpty { "png" }
            return MessagePart.Attachment(
                source =
                    AttachmentSource.Image(
                        content = AttachmentContent.Binary.Bytes(bytes),
                        format = format,
                        mimeType = mimeType,
                        fileName = fileName,
                    ),
            )
        }

        fun humanBytes(n: Long): String =
            when {
                n < 1024 -> "$n B"
                n < 1024 * 1024 -> "${(n / 1024.0 * 10).toLong() / 10.0} KB"
                else -> "${(n / (1024.0 * 1024.0) * 10).toLong() / 10.0} MB"
            }
    }
}
