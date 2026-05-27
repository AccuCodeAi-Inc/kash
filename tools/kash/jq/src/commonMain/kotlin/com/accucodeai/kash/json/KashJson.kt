package com.accucodeai.kash.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Facade over kotlinx.serialization.json. Centralizes the [Json] config so
 * tools don't reach for the raw library and end up with inconsistent settings.
 */
public object KashJson {
    private val compact =
        Json {
            ignoreUnknownKeys = false
            prettyPrint = false
            allowSpecialFloatingPointValues = true
        }

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty =
        Json {
            ignoreUnknownKeys = false
            prettyPrint = true
            prettyPrintIndent = "  "
            allowSpecialFloatingPointValues = true
        }

    /** Parse a single JSON value. Throws [kotlinx.serialization.SerializationException] on syntax error. */
    public fun parse(text: String): JsonValue = compact.parseToJsonElement(text)

    /**
     * Parse a stream of whitespace-separated JSON values (the format jq consumes
     * from stdin). Lazy: each [JsonValue] is produced as it is parsed.
     */
    public fun parseStream(text: String): Sequence<JsonValue> =
        sequence {
            val scanner = JsonStreamScanner(text)
            while (scanner.skipWhitespace()) {
                val chunk = scanner.nextValue()
                yield(compact.parseToJsonElement(chunk))
            }
        }

    public fun encode(
        v: JsonValue,
        pretty: Boolean = false,
    ): String =
        if (pretty) {
            this.pretty.encodeToString(JsonElement.serializer(), v)
        } else {
            compact.encodeToString(JsonElement.serializer(), v)
        }
}

/**
 * Splits a concatenated JSON stream into top-level value chunks. Knows just
 * enough syntax (brace/bracket/string/escape) to find value boundaries; the
 * actual parse happens via kotlinx.serialization on each chunk.
 */
private class JsonStreamScanner(
    private val text: String,
) {
    private var i = 0

    fun skipWhitespace(): Boolean {
        while (i < text.length && text[i].isWhitespace()) i++
        return i < text.length
    }

    fun nextValue(): String {
        val start = i
        val c = text[i]
        when (c) {
            '{', '[' -> scanContainer()
            '"' -> scanString()
            else -> scanScalar()
        }
        return text.substring(start, i)
    }

    private fun scanContainer() {
        var depth = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '{', '[' -> {
                    depth++
                    i++
                }

                '}', ']' -> {
                    depth--
                    i++
                    if (depth == 0) return
                }

                '"' -> {
                    scanString()
                }

                else -> {
                    i++
                    @Suppress("UNUSED_EXPRESSION")
                    c
                }
            }
        }
    }

    private fun scanString() {
        check(text[i] == '"')
        i++
        while (i < text.length) {
            when (text[i]) {
                '\\' -> {
                    i += 2
                }

                '"' -> {
                    i++
                    return
                }

                else -> {
                    i++
                }
            }
        }
    }

    private fun scanScalar() {
        while (i < text.length && !text[i].isWhitespace() &&
            text[i] !in CONTAINER_END
        ) {
            i++
        }
    }

    companion object {
        val CONTAINER_END = setOf(',', '}', ']')
    }
}
