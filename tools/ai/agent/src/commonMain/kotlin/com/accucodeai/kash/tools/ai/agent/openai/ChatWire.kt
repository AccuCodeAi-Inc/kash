package com.accucodeai.kash.tools.ai.agent.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire types for the OpenAI Chat Completions API. The file-level overview
 * is on [ChatRequest] below.
 *
 * The full body POSTed to `/v1/chat/completions`. Only the fields we actually
 * set are present — we deliberately don't model every OpenAI option to keep
 * the surface small and the decoder lenient on the way back.
 *
 * `stream = true` produces an SSE response (server-sent events with
 * `data: {…}\n\n` chunks terminated by `data: [DONE]\n\n`).
 */
@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val tools: List<ToolDescriptor>? = null,
    val stream: Boolean = false,
)

/**
 * One message in the prompt history. OpenAI uses a `role`-discriminated
 * union: we model that as a sealed hierarchy whose `role` field is **only**
 * the kotlinx-serialization class discriminator — written and read off the
 * `@SerialName` of each variant via `classDiscriminator = "role"` on
 * [kashChatJson]. (kotlinx forbids declaring a property whose name matches
 * the discriminator, hence no explicit `role: String` field.)
 *
 * Tool results come back as [Tool], not [User] — that's the OpenAI shape,
 * regardless of how we represent them internally. `tool_call_id` ties the
 * result to the assistant's `tool_calls[i].id`.
 *
 * The assistant variant carries either text content, tool calls, or both;
 * `reasoning_content` is an OpenAI-extension field LM Studio uses to ship
 * back the model's thinking out-of-band (we re-send it verbatim when the
 * user enables reasoning-history echo).
 */
@Serializable
internal sealed interface ChatMessage {
    @Serializable
    @SerialName("system")
    data class System(
        val content: String,
    ) : ChatMessage

    @Serializable
    @SerialName("user")
    data class User(
        // `content` is `string | array<ContentPart>`. We always emit the array
        // form when there's an image attachment — a JsonElement lets us keep
        // both shapes without modelling them as a union.
        val content: JsonElement,
    ) : ChatMessage

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val content: String? = null,
        @SerialName("reasoning_content")
        val reasoningContent: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ToolCall>? = null,
    ) : ChatMessage

    @Serializable
    @SerialName("tool")
    data class Tool(
        val content: String,
        @SerialName("tool_call_id")
        val toolCallId: String,
    ) : ChatMessage
}

/**
 * One element of a user message's content array. We only emit two kinds:
 * `text` and `image_url`. Modeled as a sealed type so consumers can build the
 * mixed text-plus-image content for vision turns without hand-assembling a
 * `JsonObject`.
 */
@Serializable
internal sealed interface ContentPart {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
    ) : ContentPart

    @Serializable
    @SerialName("image_url")
    data class Image(
        @SerialName("image_url")
        val imageUrl: ImageUrl,
    ) : ContentPart

    @Serializable
    data class ImageUrl(
        // `data:<mime>;base64,<…>` data URL or a plain http(s) URL.
        val url: String,
    )
}

/**
 * One tool call inside an assistant message. The OpenAI shape: `id`, `type`
 * always `"function"`, and a nested `function` object with `name` plus an
 * **already-JSON-encoded** `arguments` string. Resending: pass the string
 * through verbatim. **Never** call `Json.encodeToString` on it again — that
 * was the Koog double-encode bug.
 */
@Serializable
internal data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction,
)

@Serializable
internal data class ToolCallFunction(
    val name: String,
    // JSON-encoded args, exactly as the model emitted them. Empty string when
    // the model produced no arguments. Pass through; do not re-encode.
    val arguments: String,
)

/**
 * One tool the model is allowed to call. The schema is freeform JSON because
 * we accept whatever shape the tool's own definition produces (`parameters`
 * is a JSON Schema object).
 */
@Serializable
internal data class ToolDescriptor(
    val type: String = "function",
    val function: ToolDescriptorFunction,
)

@Serializable
internal data class ToolDescriptorFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * Non-streaming response. The full `/v1/chat/completions` response when
 * `stream = false`.
 */
@Serializable
internal data class ChatResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
) {
    @Serializable
    data class Choice(
        val message: ChatMessage.Assistant,
        @SerialName("finish_reason")
        val finishReason: String? = null,
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0,
    )
}

/**
 * Streaming response. One SSE chunk from `/v1/chat/completions` with `stream
 * = true`. The chunks
 * are nearly the response shape, but each `choices[i].message` is replaced by
 * `choices[i].delta` carrying the **incremental** content/reasoning/tool-call
 * fragments. The final non-`[DONE]` chunk usually carries `finish_reason`;
 * LM Studio occasionally drops it on the floor, so we tolerate its absence.
 */
@Serializable
internal data class ChatStreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: ChatResponse.Usage? = null,
)

@Serializable
internal data class StreamChoice(
    val index: Int = 0,
    val delta: ChatDelta = ChatDelta(),
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class ChatDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCallDelta>? = null,
)

/**
 * A single fragment of a streamed tool call. Multiple chunks combine by
 * `index` (which OpenAI sometimes omits — we default to `0`). The first
 * delta for a given index usually carries `id` and `function.name`; later
 * ones carry only `function.arguments` continuations to be concatenated.
 */
@Serializable
internal data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ToolCallFunctionDelta? = null,
)

@Serializable
internal data class ToolCallFunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)

/** `/v1/models` — just enough to populate the picker. */
@Serializable
internal data class ModelListResponse(
    val data: List<ModelEntry> = emptyList(),
)

@Serializable
internal data class ModelEntry(
    val id: String,
)

/**
 * Build a user message with mixed text + image content parts. When there
 * are no images, content is a plain string (legacy shape every server
 * supports). With images, content is the array form, where vision-capable
 * servers read off the `image_url` parts.
 */
internal fun userMessage(
    text: String,
    images: List<ContentPart.Image> = emptyList(),
): ChatMessage.User =
    if (images.isEmpty()) {
        ChatMessage.User(content = JsonPrimitive(text))
    } else {
        val parts: List<ContentPart> =
            buildList {
                if (text.isNotEmpty()) add(ContentPart.Text(text))
                addAll(images)
            }
        ChatMessage.User(content = encodeContentParts(parts))
    }
