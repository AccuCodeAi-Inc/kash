package com.accucodeai.kash.tools.ai.agent

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tolerance #3: Koog 1.0 drops `delta.reasoning_content` / `delta.reasoning`
 * on the chat-completions stream. [LenientOpenAILLMClient] recovers it and
 * folds it into the content as `<think>…</think>` so the splitter downstream
 * can surface it. These pin that folding at the decode seam.
 *
 * If Koog ever adds a reasoning field to its streaming delta, super's decode
 * would carry it and the assertions here would change — the signal to delete
 * the whole override.
 */
class LenientReasoningFoldTest {
    private class Probe :
        LenientOpenAILLMClient(
            apiKey = "",
            settings = OpenAIClientSettings(baseUrl = "http://localhost:1234"),
            httpClientFactory = KtorKoogHttpClient.Factory(),
        ) {
        /** Decode one chunk and return the (possibly think-folded) delta content. */
        fun content(data: String): String? =
            decodeStreamingResponse(data)
                .choices
                .firstOrNull()
                ?.delta
                ?.content
    }

    /** Minimal valid OpenAI streaming chunk with the given raw delta body. */
    private fun chunk(
        delta: String,
        finish: String? = null,
    ): String {
        val fin = if (finish != null) ""","finish_reason":"$finish"""" else ""
        return """{"id":"x","object":"chat.completion.chunk","created":0,"model":"m",""" +
            """"choices":[{"index":0,"delta":$delta$fin}]}"""
    }

    @Test
    fun reasoning_then_content_is_wrapped_in_think_tags() {
        val p = Probe()
        assertEquals("<think>mulling it over", p.content(chunk("""{"reasoning_content":"mulling it over"}""")))
        assertEquals("</think>The answer.", p.content(chunk("""{"content":"The answer."}""")))
    }

    @Test
    fun reasoning_split_across_chunks_only_opens_once() {
        val p = Probe()
        assertEquals("<think>part one ", p.content(chunk("""{"reasoning_content":"part one "}""")))
        assertEquals("part two", p.content(chunk("""{"reasoning_content":"part two"}""")))
        assertEquals("</think>done", p.content(chunk("""{"content":"done"}""")))
    }

    @Test
    fun the_reasoning_field_alias_is_also_recovered() {
        // o3-mini / LM Studio shape uses `reasoning` rather than `reasoning_content`.
        val p = Probe()
        assertEquals("<think>hmm", p.content(chunk("""{"reasoning":"hmm"}""")))
    }

    @Test
    fun plain_content_passes_through_untouched() {
        val p = Probe()
        assertEquals("just answering", p.content(chunk("""{"content":"just answering"}""")))
    }

    @Test
    fun reasoning_then_finish_with_no_content_closes_the_span() {
        val p = Probe()
        assertEquals("<think>thinking", p.content(chunk("""{"reasoning_content":"thinking"}""")))
        // Final chunk: empty delta + finish_reason → close the dangling <think>.
        assertEquals("</think>", p.content(chunk("""{}""", finish = "stop")))
    }
}
