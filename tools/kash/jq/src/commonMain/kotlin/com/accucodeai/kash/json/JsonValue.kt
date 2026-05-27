package com.accucodeai.kash.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared JSON value type for kash tools (jq today; future yq, jc, etc.).
 *
 * Aliases kotlinx.serialization's [JsonElement] so we get a battle-tested
 * parser/encoder without re-inventing one. The hierarchy is:
 *   - [JsonNull]
 *   - [JsonPrimitive] (booleans, numbers, strings — distinguished by `isString`)
 *   - [JsonArray]
 *   - [JsonObject]
 */
public typealias JsonValue = JsonElement

public fun jsonNull(): JsonValue = JsonNull

public fun jsonBool(v: Boolean): JsonValue = JsonPrimitive(v)

public fun jsonNumber(v: Long): JsonValue = JsonPrimitive(v)

public fun jsonNumber(v: Double): JsonValue = JsonPrimitive(v)

public fun jsonString(v: String): JsonValue = JsonPrimitive(v)

public fun jsonArray(items: List<JsonValue>): JsonValue = JsonArray(items)

public fun jsonObject(entries: Map<String, JsonValue>): JsonValue = JsonObject(entries)

public enum class JsonKind { Null, Boolean, Number, String, Array, Object }

public fun JsonValue.kind(): JsonKind =
    when (this) {
        is JsonNull -> {
            JsonKind.Null
        }

        is JsonArray -> {
            JsonKind.Array
        }

        is JsonObject -> {
            JsonKind.Object
        }

        is JsonPrimitive -> {
            when {
                isString -> JsonKind.String
                content == "true" || content == "false" -> JsonKind.Boolean
                else -> JsonKind.Number
            }
        }
    }

public fun JsonValue.typeName(): String =
    when (kind()) {
        JsonKind.Null -> "null"
        JsonKind.Boolean -> "boolean"
        JsonKind.Number -> "number"
        JsonKind.String -> "string"
        JsonKind.Array -> "array"
        JsonKind.Object -> "object"
    }

public fun JsonValue.asObjectOrNull(): JsonObject? = this as? JsonObject

public fun JsonValue.asArrayOrNull(): JsonArray? = this as? JsonArray

public fun JsonValue.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

public fun JsonValue.asBoolOrNull(): Boolean? =
    when {
        this !is JsonPrimitive || isString -> null
        content == "true" -> true
        content == "false" -> false
        else -> null
    }

public fun JsonValue.asLongOrNull(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

public fun JsonValue.asDoubleOrNull(): Double? =
    (this as? JsonPrimitive)
        ?.takeIf {
            !it.isString
        }?.content
        ?.toDoubleOrNull()

/** True if this is `null`, `false`, or absent. Mirrors jq's truthiness. */
public fun JsonValue.isTruthy(): Boolean =
    when (kind()) {
        JsonKind.Null -> false
        JsonKind.Boolean -> asBoolOrNull() == true
        else -> true
    }
