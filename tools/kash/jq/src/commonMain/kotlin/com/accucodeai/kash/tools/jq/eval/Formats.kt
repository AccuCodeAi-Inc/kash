package com.accucodeai.kash.tools.jq.eval

import com.accucodeai.kash.json.JsonValue
import com.accucodeai.kash.json.KashJson
import com.accucodeai.kash.json.asArrayOrNull
import com.accucodeai.kash.json.asStringOrNull
import com.accucodeai.kash.json.jsonString
import com.accucodeai.kash.json.typeName
import com.accucodeai.kash.tools.jq.JqRuntimeError
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Apply a `@name` format/encoding to [v], producing a string [JsonValue].
 * Mirrors jq's `@text`/`@json`/`@base64`/`@base64d`/`@uri`/`@csv`/`@tsv`/
 * `@sh`/`@html`. The `@fmt "interp"` form isn't modeled (the grammar only
 * admits a bare `@name`), so these act as plain filters over the input.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun applyFormat(
    name: String,
    v: JsonValue,
): JsonValue =
    when (name) {
        // @text and string interpolation stringify identically: a string is
        // itself, everything else is its compact JSON.
        "text" -> {
            jsonString(stringify(v))
        }

        "json" -> {
            jsonString(KashJson.encode(v))
        }

        "base64" -> {
            jsonString(Base64.encode(stringify(v).encodeToByteArray()))
        }

        "base64d" -> {
            val s = stringify(v)
            val bytes =
                try {
                    // Tolerate missing padding, like jq's lenient decoder.
                    Base64.decode(s.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })
                } catch (e: Throwable) {
                    throw JqRuntimeError("@base64d: invalid base64: ${e.message}")
                }
            jsonString(bytes.decodeToString())
        }

        "uri" -> {
            jsonString(uriEncode(stringify(v)))
        }

        "csv" -> {
            jsonString(delimited(v, ",", "@csv") { csvField(it) })
        }

        "tsv" -> {
            jsonString(delimited(v, "\t", "@tsv") { tsvField(it) })
        }

        "sh" -> {
            jsonString(shFormat(v))
        }

        "html" -> {
            jsonString(htmlEscape(stringify(v)))
        }

        else -> {
            throw JqRuntimeError("@$name is not a supported format")
        }
    }

/** jq's `tostring`: a string is itself; anything else is its compact JSON. */
private fun stringify(v: JsonValue): String = v.asStringOrNull() ?: KashJson.encode(v)

private const val URI_UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

private fun uriEncode(s: String): String =
    buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if (c < 0x80 && URI_UNRESERVED.indexOf(c.toChar()) >= 0) {
                append(c.toChar())
            } else {
                append('%')
                append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

/** Render an array as a delimited row; non-array input is an error (jq). */
private fun delimited(
    v: JsonValue,
    sep: String,
    fmt: String,
    field: (JsonValue) -> String,
): String {
    val arr = v.asArrayOrNull() ?: throw JqRuntimeError("$fmt: input (${v.typeName()}) must be an array")
    return arr.joinToString(sep) { field(it) }
}

private fun csvField(e: JsonValue): String =
    when {
        e.asStringOrNull() != null -> "\"" + e.asStringOrNull()!!.replace("\"", "\"\"") + "\""
        e.typeName() == "null" -> ""
        e.typeName() == "number" || e.typeName() == "boolean" -> stringify(e)
        else -> throw JqRuntimeError("@csv: invalid value (${e.typeName()}); row fields must be scalar")
    }

private fun tsvField(e: JsonValue): String =
    when {
        e.asStringOrNull() != null -> {
            e
                .asStringOrNull()!!
                .replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        }

        e.typeName() == "null" -> {
            ""
        }

        e.typeName() == "number" || e.typeName() == "boolean" -> {
            stringify(e)
        }

        else -> {
            throw JqRuntimeError("@tsv: invalid value (${e.typeName()}); row fields must be scalar")
        }
    }

/** `@sh`: shell-quote a scalar, or space-join shell-quoted array elements. */
private fun shFormat(v: JsonValue): String {
    v.asArrayOrNull()?.let { arr -> return arr.joinToString(" ") { shScalar(it) } }
    return shScalar(v)
}

private fun shScalar(e: JsonValue): String =
    when {
        e.asStringOrNull() != null -> "'" + e.asStringOrNull()!!.replace("'", "'\\''") + "'"
        e.typeName() == "number" || e.typeName() == "boolean" -> stringify(e)
        else -> throw JqRuntimeError("@sh: ${e.typeName()} can not be escaped for shell")
    }

private fun htmlEscape(s: String): String =
    buildString {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")

                '<' -> append("&lt;")

                '>' -> append("&gt;")

                '\'' -> append("&apos;")

                // matches jq 1.7's @html (not the older &#39;)
                '"' -> append("&quot;")

                else -> append(c)
            }
        }
    }
