package com.accucodeai.kash.tools.ai.agent.openai

/**
 * The accumulator-emitted view of one assistant turn's streaming response.
 * What you see *after* the SSE parser has done its job: text/reasoning are
 * already split, tool calls are already accumulated by index, the trailing
 * usage-only chunk that LM Studio emits is already discarded.
 *
 * Consumers render text/reasoning deltas live, collect tool calls until
 * [End] arrives, then dispatch any pending calls.
 */
internal sealed interface ChatStreamEvent {
    /** Live text fragment to append to the visible reply. */
    data class TextDelta(
        val text: String,
    ) : ChatStreamEvent

    /**
     * Live reasoning fragment (LM Studio's `delta.reasoning_content`). Always
     * emitted separately from [TextDelta] — never interleaved with answer
     * text the way `<think>` tags inside content would be.
     */
    data class ReasoningDelta(
        val text: String,
    ) : ChatStreamEvent

    /**
     * The model started a tool call. Fires the first time the accumulator
     * learns the call's [name] (which usually arrives in the very first
     * `tool_calls[i]` delta). Renderers use this to flip the spinner label
     * from "thinking…" to "calling <tool>…". One event per tool call.
     */
    data class ToolCallStarted(
        val index: Int,
        val id: String,
        val name: String,
    ) : ChatStreamEvent

    /**
     * The model finished assembling one tool call. Fires once per call when
     * the stream ends (i.e. on the final chunk with `finish_reason`, or just
     * end-of-stream if the server forgot it).
     *
     * [arguments] is the JSON string the model emitted, exactly as it came
     * off the wire. We do not validate or wrap it — if it's empty / invalid,
     * the tool's own arg-parse will reject it and the model gets a precise
     * error to self-correct from.
     */
    data class ToolCallComplete(
        val index: Int,
        val id: String,
        val name: String,
        val arguments: String,
    ) : ChatStreamEvent

    /**
     * Stream finished. [finishReason] is what the server reported — `"stop"`,
     * `"tool_calls"`, `"length"`, … — or `"stop"` as a default when the server
     * dropped the finalizing chunk (LM Studio does this routinely).
     */
    data class End(
        val finishReason: String,
    ) : ChatStreamEvent
}
