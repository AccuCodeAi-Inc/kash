package com.accucodeai.kash.tools.ai.agent

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Koog 1.0 tool-call-arguments double-encode regression and proves
 * [LenientOpenAILLMClient]'s shim corrects it.
 *
 * The assistant tool-call message is assembled exactly as production does —
 * stream frames through Koog's own [toMessageResponse] — so the `Tool.Call`
 * we hand the client is canonical (`args` is a single-encoded JSON object
 * string). The bug lives purely in Koog's request serialization
 * (`convertPromptToMessages` does `Json.encodeToString(args)` on an
 * already-encoded string), which is why the stock client double-encodes and
 * our subclass must unwrap it.
 *
 * If Koog fixes this upstream, [stock_client_double_encodes] flips to a
 * failure — that's the signal the serialize override can be removed.
 */
class KoogToolArgsSerializationProofTest {
    private val model =
        LLModel(
            provider = LLMProvider.OpenAI,
            id = "fake",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        )

    private fun toolCallPrompt(): Prompt {
        val assistant =
            listOf(
                StreamFrame.ToolCallComplete(id = "c1", name = "shell_exec", content = """{"command":"echo hi"}"""),
                StreamFrame.End(finishReason = "tool_calls"),
            ).toMessageResponse()
        return Prompt(
            messages =
                listOf(
                    Message.System("sys", RequestMetaInfo.Empty),
                    Message.User("hi", RequestMetaInfo.Empty),
                    assistant,
                ),
            id = "proof",
        )
    }

    private open class Probe(
        apiKey: String = "",
    ) : OpenAILLMClient(apiKey, OpenAIClientSettings(baseUrl = "http://localhost:1234"), KtorKoogHttpClient.Factory()) {
        fun wire(
            prompt: Prompt,
            model: LLModel,
        ): String =
            serializeProviderChatRequest(
                messages = convertPromptToMessages(prompt, model),
                model = model,
                tools = null as List<OpenAITool>?,
                toolChoice = null as OpenAIToolChoice?,
                params = LLMParams(),
                stream = false,
            )
    }

    /** Same probe but routed through the Lenient subclass's serialize override. */
    private class LenientProbe :
        LenientOpenAILLMClient(
            "",
            OpenAIClientSettings(baseUrl = "http://localhost:1234"),
            KtorKoogHttpClient.Factory(),
        ) {
        fun wire(
            prompt: Prompt,
            model: LLModel,
        ): String =
            serializeProviderChatRequest(
                messages = convertPromptToMessages(prompt, model),
                model = model,
                tools = null as List<OpenAITool>?,
                toolChoice = null as OpenAIToolChoice?,
                params = LLMParams(),
                stream = false,
            )
    }

    // Double-encoded (Koog bug):  "arguments":"\"{\\\"command\\\":...}\""
    // Single-encoded (correct):   "arguments":"{\"command\":\"echo hi\"}"
    private val doubleEncodedMarker = "\"arguments\":\"\\\"{"
    private val singleEncodedMarker = "\"arguments\":\"{\\\"command\\\":\\\"echo hi\\\"}\""

    @Test
    fun stock_client_double_encodes() {
        val wire = Probe().wire(toolCallPrompt(), model)
        assertTrue(
            doubleEncodedMarker in wire,
            "Koog 1.0 stock client should still double-encode tool args (if not, remove the shim) · wire=$wire",
        )
    }

    @Test
    fun lenient_client_single_encodes() {
        val wire = LenientProbe().wire(toolCallPrompt(), model)
        assertFalse(doubleEncodedMarker in wire, "Lenient shim must not leave double-encoded args · wire=$wire")
        assertTrue(
            singleEncodedMarker in wire,
            "Lenient shim must emit a bare JSON-object arguments string · wire=$wire",
        )
    }
}
