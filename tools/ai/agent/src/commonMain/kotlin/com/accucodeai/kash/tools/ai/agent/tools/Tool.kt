package com.accucodeai.kash.tools.ai.agent.tools

import com.accucodeai.kash.tools.ai.agent.openai.ToolDescriptor
import com.accucodeai.kash.tools.ai.agent.openai.ToolDescriptorFunction
import kotlinx.serialization.json.JsonObject

/**
 * One callable tool exposed to the model. Identified by [name] (sent over
 * the wire as `function.name`); described to the model by [description] and
 * its JSON-Schema [paramsSchema] (sent as `function.parameters`).
 *
 * The lifecycle on one call:
 *  1. The model emits a `tool_calls[i]` with `arguments` = a JSON string.
 *  2. The session JSON-parses that string to a [JsonObject] (or fails — we
 *     hand back a precise error so the model can self-correct).
 *  3. The session invokes [execute] with the parsed args; the returned
 *     string is fed back as the `tool` role message's content.
 *
 * Tools are responsible for their own argument validation; missing or
 * malformed fields should be reported via a thrown
 * [IllegalArgumentException], whose `message` becomes the tool result the
 * model sees.
 */
internal interface Tool {
    val name: String
    val description: String
    val paramsSchema: JsonObject

    suspend fun execute(args: JsonObject): String
}

/** Convert a [Tool] to its OpenAI `tools[i]` wire descriptor. */
internal fun Tool.toDescriptor(): ToolDescriptor =
    ToolDescriptor(
        function =
            ToolDescriptorFunction(
                name = name,
                description = description,
                parameters = paramsSchema,
            ),
    )

/**
 * The tools available to one agent session, keyed by [Tool.name]. Built
 * once at session start; immutable afterwards.
 */
internal class ToolRegistry(
    tools: List<Tool>,
) {
    private val byName: Map<String, Tool> = tools.associateBy { it.name }
    val all: List<Tool> = tools

    fun get(name: String): Tool? = byName[name]

    /** All tools as OpenAI wire descriptors, ready to drop into a [com.accucodeai.kash.tools.ai.agent.openai.ChatRequest]. */
    fun descriptors(): List<ToolDescriptor> = all.map { it.toDescriptor() }
}
