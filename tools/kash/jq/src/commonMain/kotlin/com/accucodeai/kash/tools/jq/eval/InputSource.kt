package com.accucodeai.kash.tools.jq.eval

import com.accucodeai.kash.json.JsonValue

/**
 * The shared, stateful cursor over a program's remaining inputs. A single
 * instance is created per top-level run and threaded through every
 * [JqContext] (the reference rides along `copy()`), so the top-level driver
 * and the `input` / `inputs` builtins all pull from the *same* position — the
 * defining property of jq's input model (e.g. `jq -n '[inputs]'`, or
 * `., input` consuming the next value mid-filter).
 *
 * Not thread-safe: the jq evaluator is single-threaded and pull-based.
 */
internal class InputSource(
    private val it: Iterator<JsonValue>,
) {
    /** The next input, or null when the stream is exhausted. */
    fun nextOrNull(): JsonValue? = if (it.hasNext()) it.next() else null

    companion object {
        /** A permanently-empty source — the default for single-input applies. */
        val EMPTY: InputSource = InputSource(emptyList<JsonValue>().iterator())
    }
}
