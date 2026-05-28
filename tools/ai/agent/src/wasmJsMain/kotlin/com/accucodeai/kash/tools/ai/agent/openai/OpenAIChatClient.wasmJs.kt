package com.accucodeai.kash.tools.ai.agent.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

/**
 * wasmJs HTTP client: the JS engine, which talks to the browser's Fetch
 * API. The browser handles streaming SSE bodies natively via
 * `ReadableStream` — Ktor wires it up to a `ByteReadChannel` so the
 * common-code [stream] loop reads chunks as they arrive.
 *
 * No timeout plugin here: timeouts on `fetch()` are controlled by the
 * browser anyway, and a default would cut off long completions.
 */
internal actual fun newChatHttpClient(): HttpClient = HttpClient(Js)
