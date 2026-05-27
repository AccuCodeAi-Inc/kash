package com.accucodeai.kash.tools.ed

/**
 * Immutable buffer state for the [EdInterpreter]. Operations return a new
 * [EdBuffer]; the executor swaps the active reference and pushes the prior
 * state onto a single-slot undo (POSIX `u` is single-level and toggles).
 *
 * Lines are 1-indexed externally. The internal [lines] list is 0-indexed —
 * helpers in [Companion] do the translation so callers don't have to.
 *
 * @property lines the buffer contents, 0-indexed; size N means lines `1..N`.
 * @property dot the "current line" (`.`) — 0 only when buffer is empty.
 * @property dirty true when modifications have happened since the last `w`/`e`.
 * @property filename the implicit default for `w` / `r` / `e` / `f`.
 * @property marks character marks set by `k<c>`; line numbers track edits.
 * @property lastSearch last regex used by `/`/`?`/`s` (POSIX `//` reuse).
 * @property lastReplace last replacement string used by `s` (POSIX `s//%/` reuse).
 */
public data class EdBuffer(
    val lines: List<String> = emptyList(),
    val dot: Int = 0,
    val dirty: Boolean = false,
    val filename: String? = null,
    val marks: Map<Char, Int> = emptyMap(),
    val lastSearch: String? = null,
    val lastReplace: String? = null,
) {
    val size: Int get() = lines.size

    /** Get line number [n] (1-indexed). */
    public fun line(n: Int): String = lines[n - 1]

    /** Append text [text] after line [after]; new dot = last inserted line. */
    public fun append(
        after: Int,
        text: List<String>,
    ): EdBuffer {
        if (text.isEmpty()) return this
        val newLines = lines.subList(0, after).toMutableList()
        newLines.addAll(text)
        newLines.addAll(lines.subList(after, lines.size))
        val newDot = after + text.size
        val newMarks = shiftMarks(marks, after, text.size)
        return copy(lines = newLines, dot = newDot, dirty = true, marks = newMarks)
    }

    /** Delete lines [from]..[to] inclusive. dot = from (or last line / 0). */
    public fun delete(
        from: Int,
        to: Int,
    ): EdBuffer {
        require(from in 1..lines.size && to in from..lines.size)
        val newLines = lines.subList(0, from - 1) + lines.subList(to, lines.size)
        val deleted = to - from + 1
        val newDot = if (newLines.isEmpty()) 0 else minOf(from, newLines.size)
        // Marks within the deleted range are removed; marks above shift down.
        val newMarks = mutableMapOf<Char, Int>()
        for ((k, v) in marks) {
            when {
                v < from -> newMarks[k] = v

                v in from..to -> Unit

                // mark deleted
                else -> newMarks[k] = v - deleted
            }
        }
        return copy(lines = newLines, dot = newDot, dirty = true, marks = newMarks)
    }

    /** Replace lines [from]..[to] with [text]. */
    public fun change(
        from: Int,
        to: Int,
        text: List<String>,
    ): EdBuffer {
        val afterDelete = delete(from, to)
        return afterDelete.append(from - 1, text)
    }

    /** Join lines [from]..[to] into a single line at `from`. */
    public fun join(
        from: Int,
        to: Int,
    ): EdBuffer {
        if (from >= to) return this
        val joined = lines.subList(from - 1, to).joinToString("")
        val newLines = lines.subList(0, from - 1).toMutableList()
        newLines.add(joined)
        newLines.addAll(lines.subList(to, lines.size))
        val removed = to - from
        val newMarks = mutableMapOf<Char, Int>()
        for ((k, v) in marks) {
            when {
                v < from -> newMarks[k] = v
                v in from..to -> newMarks[k] = from
                else -> newMarks[k] = v - removed
            }
        }
        return copy(lines = newLines, dot = from, dirty = true, marks = newMarks)
    }

    /** Move lines [from]..[to] to AFTER [dest]. POSIX `m`. */
    public fun move(
        from: Int,
        to: Int,
        dest: Int,
    ): EdBuffer {
        require(dest < from || dest >= to) { "move destination cannot be inside source range" }
        val moved = lines.subList(from - 1, to).toList()
        val withoutMoved = lines.subList(0, from - 1) + lines.subList(to, lines.size)
        val count = to - from + 1
        val adjustedDest = if (dest >= to) dest - count else dest
        val newLines = withoutMoved.subList(0, adjustedDest).toMutableList()
        newLines.addAll(moved)
        newLines.addAll(withoutMoved.subList(adjustedDest, withoutMoved.size))
        val newDot = adjustedDest + count
        // Recompute marks: any mark in the moved range moves with it; others
        // are recomputed by walking and translating.
        val newMarks = mutableMapOf<Char, Int>()
        for ((k, v) in marks) {
            when {
                v in from..to -> newMarks[k] = adjustedDest + (v - from) + 1
                v < from && v <= adjustedDest -> newMarks[k] = v
                v < from && v > adjustedDest -> newMarks[k] = v + count
                v > to && v > dest -> newMarks[k] = v - count
                else -> newMarks[k] = v
            }
        }
        return copy(lines = newLines, dot = newDot, dirty = true, marks = newMarks)
    }

    /** Copy lines [from]..[to] to AFTER [dest]. POSIX `t`. */
    public fun transfer(
        from: Int,
        to: Int,
        dest: Int,
    ): EdBuffer {
        val copied = lines.subList(from - 1, to).toList()
        val newLines = lines.subList(0, dest).toMutableList()
        newLines.addAll(copied)
        newLines.addAll(lines.subList(dest, lines.size))
        val count = to - from + 1
        val newDot = dest + count
        val newMarks = shiftMarks(marks, dest, count)
        return copy(lines = newLines, dot = newDot, dirty = true, marks = newMarks)
    }

    public fun setMark(
        ch: Char,
        line: Int,
    ): EdBuffer = copy(marks = marks + (ch to line))

    public fun setDot(n: Int): EdBuffer = copy(dot = n)

    public fun clean(): EdBuffer = copy(dirty = false)

    public fun withFilename(name: String?): EdBuffer = copy(filename = name)

    public fun withSearch(re: String): EdBuffer = copy(lastSearch = re)

    public fun withReplace(s: String): EdBuffer = copy(lastReplace = s)

    private fun shiftMarks(
        marks: Map<Char, Int>,
        after: Int,
        count: Int,
    ): Map<Char, Int> {
        if (count == 0) return marks
        val out = mutableMapOf<Char, Int>()
        for ((k, v) in marks) {
            out[k] = if (v > after) v + count else v
        }
        return out
    }
}
