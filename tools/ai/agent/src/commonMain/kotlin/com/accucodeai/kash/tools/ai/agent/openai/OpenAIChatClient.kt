package com.accucodeai.kash.tools.ai.agent.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Lenient Json instance for the wire layer. Ignores unknown fields so a
 * field we don't model never breaks a chunk; tolerates missing nullable
 * fields so a partial delta still decodes; coerces malformed `null`s on
 * required-with-default fields to the default. **Strict on the way out**
 * implicitly (we serialize exactly what we set) — never emits unknown keys.
 *
 * Uses `role` as the [ChatMessage] discriminator (OpenAI's wire convention).
 */
internal val kashChatJson: Json =
    Json {
        ignoreUnknownKeys = true
        // Skip null fields on encode (keeps `temperature = null` etc. off the
        // wire) but DO emit non-null defaults — `ToolDescriptor.type = "function"`
        // is a required literal LM Studio rejects the request without.
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
        classDiscriminator = "role"
    }

/**
 * A second Json instance keyed on the `type` discriminator for content
 * parts (`text` / `image_url`). The default in [kashChatJson] uses `role`
 * for [ChatMessage], which would collide; we encode content arrays through
 * this one.
 */
internal val contentPartJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

/**
 * Encode a list of [ContentPart] to a [JsonElement] suitable for embedding
 * as a [ChatMessage.User.content] when the message has any image
 * attachments. Goes through [contentPartJson] so the array elements carry
 * a `type` discriminator instead of `role`.
 */
internal fun encodeContentParts(parts: List<ContentPart>): JsonElement =
    contentPartJson.encodeToJsonElement(ListSerializer(ContentPart.serializer()), parts)

/**
 * Per-platform constructor for a Ktor [HttpClient] suitable for our outbound
 * traffic. JVM picks CIO; wasmJs picks the JS (browser-Fetch) engine.
 * Long-lived streaming friendly — no per-request read timeout — since SSE
 * holds the channel open across the model's whole response.
 */
internal expect fun newChatHttpClient(): HttpClient

/**
 * Hand-rolled OpenAI Chat Completions client. Two public chat methods —
 * [stream] for SSE token-by-token responses, [complete] for one-shot — plus
 * [listModels] for the `/v1/models` picker.
 *
 * Lifecycle: construct once per agent session, [close] in the `finally`
 * branch. Safe to share across multiple turns; the underlying [HttpClient]
 * pools its own connections.
 *
 * Wire-level decisions worth knowing about:
 *  - `tool_calls[i].function.arguments` is passed through verbatim on resend
 *    — the same string the model emitted. We do NOT `Json.encodeToString` it
 *    again (the Koog double-encode bug we're here to not repeat).
 *  - SSE chunks are decoded leniently; one bad chunk doesn't abort the
 *    stream. The trailing usage-only chunk LM Studio sometimes emits with
 *    no `choices` parses as an empty [ChatStreamChunk] and is skipped.
 *  - `tool_calls` deltas missing `index` default to `0`. Continuations are
 *    accumulated by index.
 *  - A stream that ends without a `finish_reason` chunk defaults to `"stop"`
 *    — LM Studio drops the finalizing chunk on some completions.
 */
internal class OpenAIChatClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: HttpClient = newChatHttpClient(),
) : ChatClient {
    private val endpoint: String = "${baseUrl.trimEnd('/')}/v1/chat/completions"
    private val modelsEndpoint: String = "${baseUrl.trimEnd('/')}/v1/models"

    override fun close() {
        http.close()
    }

    /**
     * Issue a non-streaming request. Used for history compaction (we just
     * want the summary string, not a live stream) and any future single-shot
     * side calls. [tools] = `null` omits the `tools` array entirely; an
     * empty list would tell the server "no tools allowed" which is a
     * slightly different thing — we never want to send that.
     */
    override suspend fun complete(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double?,
        tools: List<ToolDescriptor>?,
    ): ChatResponse {
        val req =
            ChatRequest(
                model = model,
                messages = messages,
                temperature = temperature,
                tools = tools,
                stream = false,
            )
        val body = kashChatJson.encodeToString(ChatRequest.serializer(), req)
        val resp =
            http.post(endpoint) {
                contentType(ContentType.Application.Json)
                applyAuth()
                setBody(body)
            }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            error("HTTP ${resp.status.value} ${resp.status.description} from $endpoint: ${text.take(500)}")
        }
        return kashChatJson.decodeFromString(ChatResponse.serializer(), text)
    }

    /**
     * Issue a streaming request and emit one [ChatStreamEvent] per
     * meaningful chunk. The flow completes naturally on `data: [DONE]` or on
     * channel close. Cancel the collecting coroutine to abort early.
     *
     * Tool calls are accumulated inside this method by `index`: the first
     * delta produces [ChatStreamEvent.ToolCallStarted] when `name` is known
     * (so the renderer can switch the spinner label); the final flush (on
     * `finish_reason` or end-of-stream) produces one
     * [ChatStreamEvent.ToolCallComplete] per call, in `index` order. Text
     * and reasoning deltas pass through unaccumulated.
     */
    override fun stream(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double?,
        tools: List<ToolDescriptor>?,
    ): Flow<ChatStreamEvent> =
        flow {
            val req =
                ChatRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature,
                    tools = tools,
                    stream = true,
                )
            val body = kashChatJson.encodeToString(ChatRequest.serializer(), req)

            // Accumulator state — built up across deltas, flushed at End.
            val toolCalls = linkedMapOf<Int, PendingToolCall>()
            var finishReason: String? = null

            http
                .preparePost(endpoint) {
                    contentType(ContentType.Application.Json)
                    headers {
                        append(HttpHeaders.Accept, "text/event-stream")
                    }
                    applyAuth()
                    setBody(body)
                }.execute { response: HttpResponse ->
                    if (!response.status.isSuccess()) {
                        val errText = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                        error("HTTP ${response.status.value} ${response.status.description}: ${errText.take(500)}")
                    }
                    val channel = response.bodyAsChannel()
                    // SSE is line-delimited: payload chunks are `data: …` lines
                    // separated by blank lines. We don't model named events (the
                    // chat-completions endpoint only emits unnamed `data:`), so the
                    // line-level parser is all we need.
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        val payload = parseSseDataLine(line) ?: continue
                        if (payload == "[DONE]") break

                        // Server-emitted error chunk: `data: {"error":{…}}`.
                        // We MUST surface these — silently swallowing them
                        // turns content-filter / overload errors into blank
                        // replies that the nudge cycle then re-asks into.
                        extractSseErrorMessage(payload)?.let { msg ->
                            error("server reported error mid-stream: $msg")
                        }

                        val chunk =
                            runCatching {
                                kashChatJson.decodeFromString(ChatStreamChunk.serializer(), payload)
                            }.getOrNull() ?: continue

                        for (choice in chunk.choices) {
                            val delta = choice.delta
                            delta.content?.takeIf { it.isNotEmpty() }?.let {
                                emit(ChatStreamEvent.TextDelta(it))
                            }
                            delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
                                emit(ChatStreamEvent.ReasoningDelta(it))
                            }
                            delta.toolCalls?.forEach { tcDelta ->
                                applyToolCallDelta(toolCalls, tcDelta)?.let { emit(it) }
                            }
                            choice.finishReason?.let { finishReason = it }
                        }
                    }
                }

            // Flush any accumulated tool calls in index order. Synthesize an
            // id for any call the model didn't supply one for — OpenAI rejects
            // empty `tool_call_id` linkages, and we need a non-empty value to
            // pair the assistant's tool_call with the follow-up `tool` message.
            for ((_, pending) in toolCalls) {
                emit(
                    ChatStreamEvent.ToolCallComplete(
                        index = pending.index,
                        id = pending.id?.takeIf { it.isNotEmpty() } ?: synthesizeToolCallId(pending.index),
                        name = pending.name ?: "",
                        arguments = pending.argumentsBuilder.toString(),
                    ),
                )
            }
            // Default to "stop" — LM Studio routinely drops the finalizing chunk.
            emit(ChatStreamEvent.End(finishReason ?: "stop"))
        }

    /**
     * GET `/v1/models`. Returns the IDs the server reports, filtered down to
     * chat models (drops obvious embed/rerank/whisper/tts entries). Lenient
     * decode: the `created` field LM Studio doesn't ship is allowed missing.
     */
    override suspend fun listModels(): List<String> {
        val resp =
            http.get(modelsEndpoint) {
                applyAuth()
            }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            error("HTTP ${resp.status.value} ${resp.status.description} fetching $modelsEndpoint: ${body.take(500)}")
        }
        val parsed = kashChatJson.decodeFromString(ModelListResponse.serializer(), body)
        val all = parsed.data.map { it.id }
        val chat =
            all.filter { id ->
                val lower = id.lowercase()
                !lower.contains("embed") &&
                    !lower.contains("rerank") &&
                    !lower.contains("whisper") &&
                    !lower.contains("tts")
            }
        return chat.takeIf { it.isNotEmpty() } ?: all
    }

    private fun HttpRequestBuilder.applyAuth() {
        if (apiKey.isNotBlank()) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }
    }
}

/**
 * Accumulator slot for one tool call. The model can — and LM Studio does —
 * dribble `function.arguments` across many deltas; we concatenate verbatim.
 * `id` and `name` usually arrive in the first delta but we tolerate either
 * arriving later.
 */
private class PendingToolCall(
    val index: Int,
    var id: String? = null,
    var name: String? = null,
    val argumentsBuilder: StringBuilder = StringBuilder(),
) {
    var announced: Boolean = false
}

/**
 * Merge one `tool_calls[]` delta into the accumulator. Returns a
 * [ChatStreamEvent.ToolCallStarted] the first time this call's `name` is
 * known (so the renderer can flip the spinner label), or `null` if there's
 * nothing new to announce yet.
 */
private fun applyToolCallDelta(
    acc: MutableMap<Int, PendingToolCall>,
    delta: ToolCallDelta,
): ChatStreamEvent.ToolCallStarted? {
    val slot = acc.getOrPut(delta.index) { PendingToolCall(delta.index) }
    delta.id?.let { slot.id = it }
    delta.function
        ?.name
        ?.takeIf { it.isNotEmpty() }
        ?.let { slot.name = it }
    delta.function?.arguments?.let { slot.argumentsBuilder.append(it) }
    if (!slot.announced && slot.name != null) {
        slot.announced = true
        return ChatStreamEvent.ToolCallStarted(
            index = slot.index,
            // Pre-flush we don't synthesize an id yet — the model may still
            // supply one in a later delta. The renderer only uses this for
            // the spinner label, not for wire correlation; an empty id here
            // is harmless. The flush at end-of-stream is the canonical site
            // for [synthesizeToolCallId] so the id seen by the assistant
            // message AND the matching tool-result message match.
            id = slot.id?.takeIf { it.isNotEmpty() } ?: "",
            name = slot.name ?: "",
        )
    }
    return null
}

/**
 * Parse one SSE line into its `data:` payload. Returns the payload (with
 * the optional leading space trimmed) for `data:` lines; `null` for blank
 * separators, comments (`:`-prefixed), or any other line type we don't care
 * about (`event:`, `id:`, `retry:` — none of which the chat completions
 * endpoint emits today).
 *
 * NB: SSE spec allows an event to span multiple consecutive `data:` lines
 * (joined with `\n` until a blank line terminates the event). The chat-
 * completions endpoint emits one JSON object per `data:` line and we rely
 * on that. If a future server starts multi-line-ing payloads we'll need to
 * buffer here.
 */
internal fun parseSseDataLine(line: String): String? {
    if (line.isEmpty()) return null
    if (line.startsWith(":")) return null
    if (!line.startsWith("data:")) return null
    val rest = line.substring(5)
    return if (rest.startsWith(" ")) rest.substring(1) else rest
}

/**
 * If [payload] is an `{"error": {…}}` chunk, return the server-supplied
 * message; otherwise `null`. Cheap pre-check (substring match) before we
 * attempt the JSON decode so we don't pay for parsing on every chunk.
 *
 * OpenAI's spec docs the shape as `{"error": {"message": "...", "type":
 * "...", "code": "...", "param": "..."}}`; LM Studio and Ollama emit the
 * same shape. Different servers use different field names for the human
 * string (`message`, `error`, `detail`), so we walk a few common ones.
 */
internal fun extractSseErrorMessage(payload: String): String? {
    if ("\"error\"" !in payload) return null
    val obj =
        runCatching {
            kotlinx.serialization.json.Json
                .parseToJsonElement(payload)
                .jsonObjectOrNull()
        }.getOrNull() ?: return null
    val err = obj["error"]?.jsonObjectOrNull() ?: return null
    val msg =
        err["message"]?.jsonPrimitiveContentOrNull()
            ?: err["error"]?.jsonPrimitiveContentOrNull()
            ?: err["detail"]?.jsonPrimitiveContentOrNull()
            ?: return "(server error, no message field)"
    return msg
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): kotlinx.serialization.json.JsonObject? =
    this as? kotlinx.serialization.json.JsonObject

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContentOrNull(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Mint a synthetic tool-call id for cases where the model omitted one.
 * Shape mirrors OpenAI's `call_<hex>` convention so it doesn't look out
 * of place. [index] disambiguates parallel calls within one turn; the
 * process-wide [synthesizedIdCounter] disambiguates across turns.
 */
internal fun synthesizeToolCallId(index: Int): String {
    val n = synthesizedIdCounter++
    return "call_kash${n.toString(16)}_$index"
}

private var synthesizedIdCounter: Long = 0L
