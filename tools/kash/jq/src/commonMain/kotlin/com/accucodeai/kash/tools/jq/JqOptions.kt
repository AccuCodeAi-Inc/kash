package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.JsonValue

/**
 * Runtime options for a jq evaluation. Mirrors the CLI's `--arg`/`--argjson`.
 *
 * [args] maps `$name` references inside the filter to JSON values.
 */
public data class JqOptions(
    public val args: Map<String, JsonValue> = emptyMap(),
)
