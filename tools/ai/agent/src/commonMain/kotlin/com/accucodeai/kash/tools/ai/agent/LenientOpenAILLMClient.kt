package com.accucodeai.kash.tools.ai.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIStreamChoice
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIStreamDelta
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionStreamResponse
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * [OpenAILLMClient] with two tolerances for Koog 1.0's OpenAI transport, both
 * targeting the same real-world workflow (local LM Studio / Ollama):
 *
 * **1. Tolerant streaming decode ([decodeStreamingResponse]).** Koog 1.0
 * filters only the literal `[DONE]` sentinel and strict-decodes every other
 * `data:` chunk as [OpenAIChatCompletionStreamResponse], with
 * `choices/created/id/model/object` marked required. It also hardcodes
 * `stream_options.include_usage=true` with no opt-out. LM Studio (and some
 * Ollama builds) emit a trailing usage/keep-alive chunk that omits those
 * fields, so Koog throws `Fields [...] are required ... missing at path: $`
 * — *after* the real content already streamed — and the turn dies. This is
 * the streaming sibling of the still-open koog#139. We swallow an
 * unparseable chunk into an empty no-op delta so the stream completes.
 *
 * **2. Un-double-encoded tool-call arguments ([serializeProviderChatRequest]).**
 * Koog 1.0 stores [ai.koog.prompt.message.MessagePart.Tool.Call.args] as an
 * already-JSON-encoded string (e.g. `{"command":"x"}`), then in
 * `convertPromptToMessages` serializes it with `Json.encodeToString(args)` —
 * which JSON-encodes the string *again*, putting `"\"{\\\"command\\\":...}\""`
 * on the wire (a JSON string wrapping a JSON object, instead of the bare
 * object). 0.7.1 passed `arguments = message.content` raw and was correct, so
 * this is a 1.0.0 regression. On the next turn the model's prior tool call is
 * fed back double-encoded; LM Studio `json.loads`-es it into a *string*
 * (not a dict), and templates that expect a dict (e.g. Gemma's
 * `... in tool_call.arguments`) fail to render with
 * `Cannot perform operation in on undefined`. We detect the double-encode
 * (arguments parse to a JSON *string* whose content is itself a JSON object)
 * and unwrap one level before handing the messages back to Koog.
 *
 * **3. Reasoning recovery ([decodeStreamingResponse] + [executeStreaming]).**
 * *** MASSIVE KOOG BUG. *** Koog 1.0's chat-completions STREAMING delta model
 * ([ai.koog.prompt.executor.clients.openai.base.models.OpenAIStreamDelta]) has
 * no reasoning field, and `processStreamingResponse` only emits text/tool/end
 * frames — so `delta.reasoning_content` / `delta.reasoning` (the field every
 * reasoning model + LM Studio emits) is **silently dropped on the floor**.
 * Koog wired reasoning streaming for the Responses API and Google but never
 * for the OpenAI completions delta, even though the *non-streaming* assistant
 * model has `reasoningContent`. To get reasoning at all on completions we
 * recover the raw field here and fold it back into the content stream wrapped
 * in `<think>…</think>` sentinels; [ThinkTagSplitter] in
 * [AgentSession.streamAssistant] then dims it, strips it from the answer, and
 * routes it to a `Reasoning` history part (which Koog *does* re-serialize as
 * `reasoning_content` on the request side, so prefix caching still works).
 *
 * This one is the load-bearing reason we tolerate Koog at all on this path —
 * **if it isn't fixed upstream we should seriously consider dropping Koog**;
 * and the moment Koog surfaces reasoning on the completions stream, delete
 * this whole override (it becomes redundant — the splitter would then see
 * proper reasoning frames instead of injected tags).
 *
 * Overrides 1 & 2 hit `protected open` seams; everything else is stock
 * [OpenAILLMClient]. Remove each once Koog fixes the corresponding behavior
 * upstream (the detection is conservative, so a future correct Koog produces
 * already-single-encoded args and these overrides become no-ops).
 */
internal open class LenientOpenAILLMClient(
    apiKey: String,
    settings: OpenAIClientSettings,
    httpClientFactory: KoogHttpClient.Factory,
) : OpenAILLMClient(apiKey, settings, httpClientFactory) {
    // Whether we're mid-reasoning in the folded content stream (tolerance #3),
    // so the open/close `<think>` sentinels balance across chunks. Reset per
    // stream in [executeStreaming]. Not thread-safe — one stream at a time.
    private var inReasoning = false

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> {
        inReasoning = false
        return super.executeStreaming(prompt, model, tools)
    }

    override fun decodeStreamingResponse(data: String): OpenAIChatCompletionStreamResponse {
        runCatching { return foldReasoningIntoContent(super.decodeStreamingResponse(data), data) }

        // Strict decode failed. DON'T blanket-drop the chunk — that loses real
        // content (esp. tool-call argument fragments). Distinguish the two
        // cases via a tolerant parse: a chunk with no `choices` is the trailing
        // usage/keep-alive chunk (koog#139's streaming sibling) and is safe to
        // no-op; a chunk that DOES carry choices is content and must be salvaged.
        val root = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
        val choices = (root?.get("choices") as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return noOpChunk()

        // A content-bearing chunk that failed strict decode is almost always a
        // tool-call continuation that omitted the required (no-default)
        // `OpenAIStreamToolCall.index`. Inject it and retry super's typed decode
        // so the argument fragment survives instead of vanishing.
        val patched = JsonObject(root + ("choices" to JsonArray(choices.map(::patchToolCallIndex)))).toString()
        return runCatching { foldReasoningIntoContent(super.decodeStreamingResponse(patched), data) }
            .getOrElse { noOpChunk() }
    }

    /**
     * Recover the reasoning Koog drops (tolerance #3) and splice it into the
     * delta's `content` as `<think>…</think>`. Toggles [inReasoning] so the
     * sentinels open on the first reasoning chunk and close when visible
     * content / a tool call / the finish arrives.
     */
    private fun foldReasoningIntoContent(
        base: OpenAIChatCompletionStreamResponse,
        rawData: String,
    ): OpenAIChatCompletionStreamResponse {
        val choice = base.choices.firstOrNull() ?: return base
        val reasoning = extractReasoning(rawData)
        val content = choice.delta.content
        val hasReasoning = !reasoning.isNullOrEmpty()
        val hasContent = !content.isNullOrEmpty()
        // Untouched when there's no reasoning and we aren't holding a span open.
        if (!hasReasoning && !inReasoning) return base

        val sb = StringBuilder()
        if (hasReasoning) {
            if (!inReasoning) {
                sb.append(THINK_OPEN)
                inReasoning = true
            }
            sb.append(reasoning)
        }
        // Leave the reasoning span when real output begins or the turn ends.
        val leaving = inReasoning && (hasContent || choice.finishReason != null || choice.delta.toolCalls != null)
        if (leaving) {
            sb.append(THINK_CLOSE)
            inReasoning = false
        }
        if (hasContent) sb.append(content)

        val newContent = sb.toString().ifEmpty { null }
        val newChoice =
            OpenAIStreamChoice(
                delta =
                    OpenAIStreamDelta(
                        content = newContent,
                        refusal = choice.delta.refusal,
                        role = choice.delta.role,
                        toolCalls = choice.delta.toolCalls,
                    ),
                finishReason = choice.finishReason,
                index = choice.index,
                logprobs = choice.logprobs,
            )
        return OpenAIChatCompletionStreamResponse(
            choices = listOf(newChoice) + base.choices.drop(1),
            created = base.created,
            id = base.id,
            model = base.model,
            serviceTier = base.serviceTier,
            systemFingerprint = base.systemFingerprint,
            objectType = base.objectType,
            usage = base.usage,
        )
    }

    override fun serializeProviderChatRequest(
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?,
        params: LLMParams,
        stream: Boolean,
    ): String =
        super.serializeProviderChatRequest(
            messages = messages.map(::unwrapDoubledToolArgs),
            model = model,
            tools = tools,
            toolChoice = toolChoice,
            params = params,
            stream = stream,
        )

    private companion object {
        // Must match ThinkTagSplitter's defaults — the splitter on the other
        // side keys off exactly these.
        const val THINK_OPEN = "<think>"
        const val THINK_CLOSE = "</think>"

        /** A choice-less chunk Koog's frame mapper emits nothing for. */
        fun noOpChunk(): OpenAIChatCompletionStreamResponse =
            OpenAIChatCompletionStreamResponse(
                choices = emptyList(),
                created = 0L,
                id = "",
                model = "",
                objectType = "chat.completion.chunk",
            )

        /**
         * Default any tool_call in this choice's `delta` that's missing the
         * required (no-default) `index` to 0. The realistic culprit when a
         * content-bearing stream chunk fails strict decode; patching it lets the
         * argument fragment survive re-decode. Untouched if there's nothing to fix.
         */
        fun patchToolCallIndex(choice: JsonElement): JsonElement {
            val obj = choice as? JsonObject ?: return choice
            val delta = obj["delta"] as? JsonObject ?: return choice
            val calls = delta["tool_calls"] as? JsonArray ?: return choice
            val fixed =
                calls.map { tc ->
                    val t = tc as? JsonObject ?: return@map tc
                    if ("index" in t) t else JsonObject(t + ("index" to JsonPrimitive(0)))
                }
            return JsonObject(obj + ("delta" to JsonObject(delta + ("tool_calls" to JsonArray(fixed)))))
        }

        /**
         * Pull `delta.reasoning_content` (DeepSeek shape) or `delta.reasoning`
         * (o3-mini / LM Studio shape) straight from the raw chunk JSON — the
         * field Koog's typed delta drops. Best-effort; any parse miss yields
         * null and the chunk passes through untouched.
         */
        fun extractReasoning(rawData: String): String? {
            val delta =
                runCatching {
                    Json
                        .parseToJsonElement(rawData)
                        .jsonObject["choices"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("delta")
                        ?.jsonObject
                }.getOrNull() ?: return null
            val field = (delta["reasoning_content"] ?: delta["reasoning"]) as? JsonPrimitive ?: return null
            return if (field.isString) field.content else null
        }

        /** Rebuild an assistant message with any double-encoded tool-call args unwrapped. */
        fun unwrapDoubledToolArgs(message: OpenAIMessage): OpenAIMessage {
            if (message !is OpenAIMessage.Assistant) return message
            val calls = message.toolCalls ?: return message
            return OpenAIMessage.Assistant(
                content = message.content,
                reasoningContent = message.reasoningContent,
                audio = message.audio,
                name = message.name,
                refusal = message.refusal,
                toolCalls =
                    calls.map { call ->
                        OpenAIToolCall(
                            id = call.id,
                            function = OpenAIFunction(call.function.name, unwrapOnce(call.function.arguments)),
                        )
                    },
                annotations = message.annotations,
            )
        }

        /**
         * Unwrap a double-JSON-encoded arguments string exactly once. The
         * double-encode signature: the string parses to a JSON *string*
         * primitive whose content is itself a JSON object. Correct (single)
         * arguments parse directly to an object and are returned unchanged;
         * so is anything we can't confidently identify.
         */
        fun unwrapOnce(arguments: String): String {
            val outer = runCatching { Json.parseToJsonElement(arguments) }.getOrNull() ?: return arguments
            if (outer !is JsonPrimitive || !outer.isString) return arguments
            val inner = outer.content
            val innerEl = runCatching { Json.parseToJsonElement(inner) }.getOrNull()
            return if (innerEl is JsonObject) inner else arguments
        }
    }
}
