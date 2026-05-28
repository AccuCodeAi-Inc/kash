package com.accucodeai.kash.tools.ai.agent.openai

import kotlinx.coroutines.flow.Flow

/**
 * The chat-completions surface [com.accucodeai.kash.tools.ai.agent.AgentSession]
 * depends on. [OpenAIChatClient] is the production implementation, talking
 * to a real `/v1/chat/completions` endpoint over Ktor; tests inject a fake
 * implementation that scripts responses.
 *
 * Three operations: streaming chat, non-streaming chat (used for history
 * compaction), model-list probing for the picker. [close] is idempotent.
 */
internal interface ChatClient {
    fun stream(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double?,
        tools: List<ToolDescriptor>?,
    ): Flow<ChatStreamEvent>

    suspend fun complete(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double?,
        tools: List<ToolDescriptor>?,
    ): ChatResponse

    suspend fun listModels(): List<String>

    fun close()
}
