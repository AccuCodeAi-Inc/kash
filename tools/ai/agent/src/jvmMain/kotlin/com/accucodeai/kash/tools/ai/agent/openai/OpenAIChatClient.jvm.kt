package com.accucodeai.kash.tools.ai.agent.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig

/**
 * JVM HTTP client: CIO engine. No request/socket timeouts — SSE streams
 * stay open for the entire model response; a 60-second default would kill
 * the connection mid-completion on slower local models.
 */
internal actual fun newChatHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }
