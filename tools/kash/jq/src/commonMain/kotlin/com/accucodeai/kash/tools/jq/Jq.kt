package com.accucodeai.kash.tools.jq

import com.accucodeai.kash.json.JsonValue
import com.accucodeai.kash.json.KashJson
import com.accucodeai.kash.json.asStringOrNull
import com.accucodeai.kash.tools.jq.ast.JqExpr
import com.accucodeai.kash.tools.jq.eval.JqContext
import com.accucodeai.kash.tools.jq.eval.eval
import com.accucodeai.kash.tools.jq.parser.parseJqProgram

/**
 * Public entry point for the jq engine.
 *
 * ```
 * val out = Jq.run(".users | sort_by(.age) | .[].name", input).toList()
 * ```
 *
 * Filters are pull-based [Sequence]s — jq is fundamentally synchronous and
 * backtracking-friendly, so no coroutine scope is required.
 */
public object Jq {
    /** Compile a filter once for repeated evaluation. */
    public fun compile(filter: String): JqProgram = JqProgram(parseJqProgram(filter))

    /** Convenience: compile + apply. */
    public fun run(
        filter: String,
        input: JsonValue,
        opts: JqOptions = JqOptions(),
    ): Sequence<JsonValue> = compile(filter).apply(input, opts)

    /**
     * Format a [JsonValue] for line-oriented output, jq-style.
     *
     * - When [raw] is true and the value is a string, emits the bare contents
     *   (jq's `-r` flag). Non-strings always render as JSON.
     * - When [pretty] is true, encodes JSON with indentation (jq's default
     *   when stdout is a terminal). Defaults to compact.
     */
    public fun format(
        v: JsonValue,
        raw: Boolean = false,
        pretty: Boolean = false,
    ): String {
        if (raw) {
            val s = v.asStringOrNull()
            if (s != null) return s
        }
        return KashJson.encode(v, pretty = pretty)
    }
}

public class JqProgram internal constructor(
    private val ast: JqExpr,
) {
    public fun apply(
        input: JsonValue,
        opts: JqOptions = JqOptions(),
    ): Sequence<JsonValue> {
        val ctx = JqContext(vars = opts.args)
        return ast.eval(ctx, input)
    }
}
