package com.accucodeai.kash.tools.ai.agent.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke tests on the *actual JSON bytes* [kashChatJson] emits and parses
 * back, separate from the agent-loop tests (which only exercise the Kotlin
 * object surface — they didn't catch the double-encode that started this
 * whole rewrite, nor the `role` discriminator collision, nor the missing
 * `encodeDefaults` that hid `type: "function"` from the wire).
 *
 * Anything the encoder is supposed to put on the wire belongs here. Add a
 * case the first time a server complains about a missing/wrong-typed field.
 */
class ChatWireRoundtripTest {
    private val parse: Json = Json { ignoreUnknownKeys = true }

    private fun parseObject(s: String): JsonObject = parse.parseToJsonElement(s).jsonObject

    private val sampleSchema: JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "command",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        },
                    )
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("command")) })
        }

    private fun sampleRequest(
        withTools: Boolean = true,
        withHistory: Boolean = true,
        argsJson: String = """{"command":"ls -la"}""",
    ): ChatRequest =
        ChatRequest(
            model = "qwen/qwen3.6-27b",
            messages =
                buildList {
                    add(ChatMessage.System("you are kash-agent"))
                    add(ChatMessage.User(JsonPrimitive("list this dir")))
                    if (withHistory) {
                        add(
                            ChatMessage.Assistant(
                                content = null,
                                reasoningContent = "user wants ls",
                                toolCalls =
                                    listOf(
                                        ToolCall(
                                            id = "call_abc",
                                            function = ToolCallFunction(name = "shell_exec", arguments = argsJson),
                                        ),
                                    ),
                            ),
                        )
                        add(ChatMessage.Tool(content = "total 0\nexit: 0\n", toolCallId = "call_abc"))
                    }
                },
            temperature = 0.0,
            tools =
                if (!withTools) {
                    null
                } else {
                    listOf(
                        ToolDescriptor(
                            function =
                                ToolDescriptorFunction(
                                    name = "shell_exec",
                                    description = "run a shell command",
                                    parameters = sampleSchema,
                                ),
                        ),
                    )
                },
            stream = true,
        )

    @Test
    fun every_message_has_an_explicit_role() {
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), sampleRequest())
        val msgs = parseObject(json)["messages"]?.jsonArray ?: error("no messages")
        val roles = msgs.map { it.jsonObject["role"]?.jsonPrimitive?.content }
        assertEquals(listOf("system", "user", "assistant", "tool"), roles)
    }

    @Test
    fun assistant_tool_call_has_type_function_and_id_and_name() {
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), sampleRequest())
        val msgs = parseObject(json)["messages"]!!.jsonArray
        val assistant = msgs.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }.jsonObject
        val toolCalls = assistant["tool_calls"]?.jsonArray ?: error("no tool_calls")
        assertEquals(1, toolCalls.size)
        val tc = toolCalls.first().jsonObject
        assertEquals("function", tc["type"]?.jsonPrimitive?.content, "type literal missing — encodeDefaults regression")
        assertEquals("call_abc", tc["id"]?.jsonPrimitive?.content)
        val fn = tc["function"]?.jsonObject ?: error("no function")
        assertEquals("shell_exec", fn["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun tool_call_arguments_is_a_single_encoded_string() {
        // This is THE regression. arguments must be a JSON string whose
        // value is a JSON object — NOT a JSON string whose value is a
        // JSON-encoded string of a JSON object (the Koog double-encode).
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), sampleRequest())
        val msgs = parseObject(json)["messages"]!!.jsonArray
        val assistant = msgs.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }.jsonObject
        val args =
            assistant["tool_calls"]!!
                .jsonArray
                .first()
                .jsonObject["function"]!!
                .jsonObject["arguments"]
        val argsString = args?.jsonPrimitive
        assertNotNull(argsString)
        assertTrue(argsString.isString, "arguments must be a JSON string, got $args")
        // The content of that string, parsed as JSON, must give an OBJECT
        // (not a quoted string of an object).
        val inner = parse.parseToJsonElement(argsString.content)
        assertNotNull(
            inner as? JsonObject,
            "arguments.content must parse to a JsonObject; got ${inner::class.simpleName}. " +
                "If this is a JsonPrimitive(string), you've reintroduced the double-encode.",
        )
        assertEquals("ls -la", inner["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun tools_descriptor_has_type_function_at_each_level() {
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), sampleRequest())
        val tools = parseObject(json)["tools"]?.jsonArray ?: error("tools missing")
        val first = tools.first().jsonObject
        assertEquals(
            "function",
            first["type"]?.jsonPrimitive?.content,
            "tools[i].type literal missing — encodeDefaults regression",
        )
        val fn = first["function"]?.jsonObject ?: error("no function")
        assertEquals("shell_exec", fn["name"]?.jsonPrimitive?.content)
        assertEquals("run a shell command", fn["description"]?.jsonPrimitive?.content)
        // parameters round-trip as a JSON object, not stringified.
        val params = fn["parameters"]?.jsonObject ?: error("parameters must be a JSON object")
        assertEquals("object", params["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun tool_result_carries_tool_call_id_linkage() {
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), sampleRequest())
        val tool =
            parseObject(json)["messages"]!!
                .jsonArray
                .first {
                    it.jsonObject["role"]?.jsonPrimitive?.content ==
                        "tool"
                }.jsonObject
        assertEquals("call_abc", tool["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("total 0\nexit: 0\n", tool["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun null_assistant_content_is_omitted_not_serialized_as_null_keyword() {
        // explicitNulls=false: a null content field should NOT appear on the
        // wire. Some servers reject the literal `null` keyword for content
        // when tool_calls is present.
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), sampleRequest())
        val assistant =
            parseObject(json)["messages"]!!
                .jsonArray
                .first {
                    it.jsonObject["role"]?.jsonPrimitive?.content ==
                        "assistant"
                }.jsonObject
        assertTrue("content" !in assistant, "null content key must be absent, got: $assistant")
    }

    @Test
    fun missing_tools_field_when_disabled() {
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), sampleRequest(withTools = false))
        val root = parseObject(json)
        assertTrue("tools" !in root, "tools key must be absent when null, got: $root")
    }

    @Test
    fun missing_temperature_when_null() {
        val req = sampleRequest().copy(temperature = null)
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), req)
        assertTrue("temperature" !in parseObject(json), "temperature must be absent when null")
    }

    @Test
    fun zero_temperature_is_emitted_as_zero_not_dropped() {
        val req = sampleRequest().copy(temperature = 0.0)
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), req)
        val temp = parseObject(json)["temperature"]?.jsonPrimitive?.content
        assertEquals("0.0", temp, "0.0 must round-trip; null only is the omission signal")
    }

    @Test
    fun decode_streaming_chunk_with_tool_call_delta_round_trips() {
        // Sample LM Studio chunk — exercises that all the optional/missing
        // fields don't fail strict decode.
        val raw =
            """
            {"id":"chatcmpl-x","object":"chat.completion.chunk","created":1,"model":"qwen","choices":[
              {"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[
                {"index":0,"id":"c1","type":"function","function":{"name":"shell_exec","arguments":"{\"command\":\"ls\"}"}}
              ]}}
            ]}
            """.trimIndent()
        val chunk = kashChatJson.decodeFromString(ChatStreamChunk.serializer(), raw)
        val tc =
            chunk.choices
                .single()
                .delta.toolCalls!!
                .single()
        assertEquals(0, tc.index)
        assertEquals("c1", tc.id)
        assertEquals("function", tc.type)
        assertEquals("shell_exec", tc.function?.name)
        assertEquals("""{"command":"ls"}""", tc.function?.arguments)
    }

    @Test
    fun decode_chunk_with_missing_index_defaults_to_zero() {
        // Some servers (vLLM ≤0.5, some Ollama builds) omit `index`. We
        // default it to 0 so accumulation works.
        val raw =
            """{"choices":[{"delta":{"tool_calls":[{"id":"c1","function":{"name":"f","arguments":"{}"}}]}}]}"""
        val chunk = kashChatJson.decodeFromString(ChatStreamChunk.serializer(), raw)
        assertEquals(
            0,
            chunk.choices
                .single()
                .delta.toolCalls!!
                .single()
                .index,
        )
    }

    @Test
    fun decode_chunk_with_no_choices_array_decodes_to_empty() {
        // LM Studio's trailing usage-only chunk has no `choices`. Must NOT
        // throw — the stream loop swallows empty-choices chunks.
        val raw = """{"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
        val chunk = kashChatJson.decodeFromString(ChatStreamChunk.serializer(), raw)
        assertTrue(chunk.choices.isEmpty())
    }

    @Test
    fun sse_data_line_parses_payload_and_strips_leading_space() {
        assertEquals("""{"a":1}""", parseSseDataLine("""data: {"a":1}"""))
        assertEquals("""{"a":1}""", parseSseDataLine("""data:{"a":1}"""))
        assertEquals("[DONE]", parseSseDataLine("data: [DONE]"))
    }

    @Test
    fun sse_data_line_returns_null_for_blanks_and_comments_and_other_fields() {
        assertEquals(null, parseSseDataLine(""))
        assertEquals(null, parseSseDataLine(": keepalive"))
        assertEquals(null, parseSseDataLine("event: foo"))
        assertEquals(null, parseSseDataLine("id: 1"))
    }

    @Test
    fun sse_error_chunk_is_detected_and_message_extracted() {
        // Real shape from OpenAI / LM Studio when a content filter / load
        // error fires mid-stream. We MUST surface these, not swallow them.
        val raw =
            """{"error":{"message":"context length exceeded","type":"invalid_request_error",""" +
                """"code":"context_length_exceeded"}}"""
        assertEquals("context length exceeded", extractSseErrorMessage(raw))
    }

    @Test
    fun sse_error_chunk_with_alternate_field_names_still_extracted() {
        assertEquals("nope", extractSseErrorMessage("""{"error":{"detail":"nope"}}"""))
    }

    @Test
    fun ordinary_chunks_are_not_misdetected_as_errors() {
        val raw = """{"choices":[{"delta":{"content":"the word error appears here"}}]}"""
        assertEquals(null, extractSseErrorMessage(raw))
    }

    @Test
    fun synthesized_tool_call_id_is_non_empty_and_unique() {
        val a = synthesizeToolCallId(0)
        val b = synthesizeToolCallId(0)
        assertTrue(a.isNotEmpty())
        assertTrue(a != b, "successive calls must mint distinct ids, got $a == $b")
        assertTrue(a.startsWith("call_"), "id should look like an OpenAI call id, got $a")
    }

    @Test
    fun user_message_with_only_text_emits_string_content_not_array() {
        // Servers that don't support content-as-array (older Ollama) must
        // still see a plain string when there are no attachments.
        val req = ChatRequest(model = "x", messages = listOf(userMessage("hi")))
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), req)
        val user = parseObject(json)["messages"]!!.jsonArray.single().jsonObject
        val content = user["content"]
        assertTrue(content is JsonPrimitive && content.isString, "expected JSON string, got $content")
        assertEquals("hi", content.content)
    }

    @Test
    fun user_message_with_image_emits_array_content_with_image_url_part() {
        val img = ContentPart.Image(imageUrl = ContentPart.ImageUrl(url = "data:image/png;base64,XXX"))
        val req = ChatRequest(model = "x", messages = listOf(userMessage("look", listOf(img))))
        val json = kashChatJson.encodeToString(ChatRequest.serializer(), req)
        val user = parseObject(json)["messages"]!!.jsonArray.single().jsonObject
        val content = user["content"]?.jsonArray ?: error("expected array content, got ${user["content"]}")
        assertEquals(2, content.size)
        val text = content[0].jsonObject
        assertEquals("text", text["type"]?.jsonPrimitive?.content)
        assertEquals("look", text["text"]?.jsonPrimitive?.content)
        val image = content[1].jsonObject
        assertEquals("image_url", image["type"]?.jsonPrimitive?.content)
        val urlField = image["image_url"]?.jsonObject ?: error("missing image_url nested object")
        assertEquals("data:image/png;base64,XXX", urlField["url"]?.jsonPrimitive?.content)
    }
}
