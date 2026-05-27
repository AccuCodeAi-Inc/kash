package com.accucodeai.kash.tools.vi

/** Editing modes. */
internal enum class ViMode { NORMAL, INSERT, VISUAL_CHAR, VISUAL_LINE, COMMAND }

/** Pending operator captured while waiting for a motion (d, c, y) or doubled (dd/cc/yy). */
internal enum class PendingOp { D, C, Y }

/** Yank type — controls paste behaviour (p/P). */
internal enum class YankType { CHAR, LINE }

internal data class Register(
    val text: String,
    val type: YankType,
)

internal data class Mark(
    val row: Int,
    val col: Int,
)

/** A position in the buffer (row, col). */
internal data class Pos(
    val row: Int,
    val col: Int,
) : Comparable<Pos> {
    override fun compareTo(other: Pos): Int = if (row != other.row) row - other.row else col - other.col
}

/**
 * Immutable editor state. Edits return a new buffer; the main loop reassigns.
 * Undo is snapshot-based — push the prior buffer onto [undoStack] before each
 * mutating edit. Redo stack is cleared by any new edit.
 *
 * Cursor units: codepoints (== Kotlin Char in the BMP; v1 doesn't try to handle SMP cell widths).
 */
internal data class ViBuffer(
    val lines: List<String>,
    val cursorRow: Int,
    val cursorCol: Int,
    val topRow: Int,
    val dirty: Boolean,
    val filename: String?,
    val mode: ViMode = ViMode.NORMAL,
    val desiredCol: Int = 0,
    val pendingCount: Int = 0,
    val pendingOp: PendingOp? = null,
    /** For f/F/t/T repetition via ; and , */
    val lastFind: LastFind? = null,
    val lastSearch: LastSearch? = null,
    val registers: Map<Char, Register> = emptyMap(),
    val marks: Map<Char, Mark> = emptyMap(),
    val visualAnchor: Pos? = null,
    /** For undo: prior buffers (bounded). */
    val undoStack: List<ViBuffer> = emptyList(),
    val redoStack: List<ViBuffer> = emptyList(),
    /** Trailing-newline-on-disk flag — preserved through save. */
    val trailingNewline: Boolean = true,
    /** Toggleable from `:set number`. */
    val showLineNumbers: Boolean = false,
    /** Last ex command run (not heavily used; reserved for `.` repeat). */
    val lastEdit: LastEdit? = null,
    /** Position before the last "jump" motion (G, gg, /, ?, %, mark-jump). Powers `''` / ` ` ` `. */
    val lastJumpPos: Pos? = null,
) {
    val rowCount: Int get() = lines.size

    fun lineLen(row: Int): Int = lines.getOrNull(row)?.length ?: 0

    /** Clamp cursor. In NORMAL mode cursor cannot sit on the empty position past EOL. */
    fun normalized(): ViBuffer {
        val r = cursorRow.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
        val maxCol =
            when (mode) {
                ViMode.INSERT -> lineLen(r)
                else -> (lineLen(r) - 1).coerceAtLeast(0)
            }
        val c = cursorCol.coerceIn(0, maxCol)
        return if (r == cursorRow && c == cursorCol) this else copy(cursorRow = r, cursorCol = c)
    }

    /** Snapshot self onto undo stack; clear redo. Returns a buffer ready to mutate. */
    fun pushUndo(): ViBuffer {
        val stripped = copy(undoStack = emptyList(), redoStack = emptyList(), pendingOp = null, pendingCount = 0)
        val newStack = (undoStack + stripped).takeLast(MAX_UNDO)
        return copy(undoStack = newStack, redoStack = emptyList())
    }

    fun undo(): ViBuffer {
        if (undoStack.isEmpty()) return this
        val prev = undoStack.last()
        val newUndo = undoStack.dropLast(1)
        // Snapshot self (without stacks) onto redo
        val selfNoStacks = copy(undoStack = emptyList(), redoStack = emptyList())
        val newRedo = (redoStack + selfNoStacks).takeLast(MAX_UNDO)
        return prev.copy(undoStack = newUndo, redoStack = newRedo, mode = mode)
    }

    fun redo(): ViBuffer {
        if (redoStack.isEmpty()) return this
        val next = redoStack.last()
        val newRedo = redoStack.dropLast(1)
        val selfNoStacks = copy(undoStack = emptyList(), redoStack = emptyList())
        val newUndo = (undoStack + selfNoStacks).takeLast(MAX_UNDO)
        return next.copy(undoStack = newUndo, redoStack = newRedo, mode = mode)
    }

    companion object {
        const val MAX_UNDO: Int = 100

        fun fromText(
            text: String,
            filename: String?,
        ): ViBuffer {
            val trailing = text.endsWith("\n")
            val raw = if (text.isEmpty()) listOf("") else text.split('\n')
            val lines = if (raw.size > 1 && raw.last().isEmpty()) raw.dropLast(1) else raw
            return ViBuffer(
                lines = lines.ifEmpty { listOf("") },
                cursorRow = 0,
                cursorCol = 0,
                topRow = 0,
                dirty = false,
                filename = filename,
                trailingNewline = trailing || text.isEmpty(),
            )
        }

        fun empty(filename: String?): ViBuffer =
            ViBuffer(
                lines = listOf(""),
                cursorRow = 0,
                cursorCol = 0,
                topRow = 0,
                dirty = false,
                filename = filename,
            )
    }
}

internal data class LastFind(
    val char: Int,
    val forward: Boolean,
    val till: Boolean,
)

internal data class LastSearch(
    val pattern: String,
    val forward: Boolean,
)

/** Reserved (currently unused) for `.` repeat — kept for future expansion. */
internal data class LastEdit(
    val tag: String,
)

internal fun List<String>.replaceAt(
    i: Int,
    s: String,
): List<String> = toMutableList().also { it[i] = s }
