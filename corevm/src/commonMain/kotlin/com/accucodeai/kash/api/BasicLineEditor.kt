package com.accucodeai.kash.api

import com.accucodeai.kash.api.terminal.Candidate
import com.accucodeai.kash.api.terminal.Completer
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.LineEditor
import com.accucodeai.kash.api.terminal.LineEditorResult
import com.accucodeai.kash.api.terminal.TerminalControl

/**
 * Pure-Kotlin [LineEditor] on top of [TerminalControl]. The default — and
 * currently only — line editor; serves the top-level shell, recursive
 * `kash`-in-kash invocations, and the eventual JS target uniformly.
 *
 * V1 scope (deliberately small — extend as needed):
 *
 *  - cursor: ←/→, Home/End, Ctrl-A/Ctrl-E, Ctrl-B/Ctrl-F
 *  - edit: Backspace, Delete, Ctrl-K (kill to EOL), Ctrl-U (kill line),
 *    Ctrl-W (kill prev word)
 *  - history: ↑/↓ navigate an in-memory ring buffer
 *  - continuation: when [LineEditor.readLine.isComplete] returns false, draws
 *    the continuation prompt and keeps reading
 *  - backslash-newline: `\<Enter>` joins lines without consulting isComplete
 *  - Ctrl-C → [LineEditorResult.Interrupted]; Ctrl-D at empty buffer →
 *    [LineEditorResult.Eof]
 *
 * Tab completion: opt-in via the [completer] constructor parameter — see
 * [com.accucodeai.kash.api.terminal.Completer]. Bash-style: unique match
 * inserts text + trailing; ambiguous prefix extends to longest common
 * prefix; second TAB on the same state lists candidates above the prompt.
 *
 * Not in v1: history search (Ctrl-R), vi mode, mouse, syntax highlighting,
 * multi-byte display-width handling.
 * Wrap-to-next-row mid-line is approximated by re-drawing the whole line on
 * every keystroke; that's slow for very long lines but bounded.
 *
 * Cursor math assumes each codepoint is one column wide. CJK / wide chars
 * render correctly to the user but the cursor position drifts; that's a
 * known v1 limitation worth fixing if it bites.
 */
public class BasicLineEditor(
    private val terminal: TerminalControl,
    private val historyCapacity: Int = 1000,
    /**
     * Optional tab-completion engine. When null, TAB inserts a literal `\t`
     * (the legacy v1 behavior). When non-null, TAB consults the completer and
     * applies bash-style insertion / longest-common-prefix extension /
     * candidate-listing on second consecutive TAB.
     */
    private val completer: Completer? = null,
) : LineEditor {
    private val history: ArrayDeque<String> = ArrayDeque()

    override suspend fun addHistory(entry: String) {
        if (entry.isBlank()) return
        // Dedupe consecutive duplicates — bash's default HISTCONTROL=ignoredups.
        if (history.lastOrNull() == entry) return
        if (history.size >= historyCapacity) history.removeFirst()
        history.addLast(entry)
    }

    override suspend fun history(): List<String> = history.toList()

    override suspend fun readLine(
        prompt: String,
        continuationPrompt: String,
        isComplete: (accumulated: String) -> Boolean,
    ): LineEditorResult {
        // Each iteration reads one physical line (until Enter). The
        // outer loop handles multi-line continuation — when the accumulated
        // buffer is incomplete, we re-prompt with [continuationPrompt].
        terminal.enterRawMode()
        try {
            val lines = mutableListOf<String>()
            var firstLine = true
            while (true) {
                val activePrompt = if (firstLine) prompt else continuationPrompt
                // Snapshot history position only for the current physical line —
                // ↑/↓ navigate within the editor of *this* line, not the
                // accumulator. Same as bash's PS2-continuation behavior.
                when (val line = readOnePhysicalLine(activePrompt)) {
                    is PhysicalLine.Done -> {
                        // Backslash continuation: drop the trailing `\` and keep
                        // reading on the next line without consulting isComplete.
                        if (line.text.endsWith("\\")) {
                            lines += line.text.dropLast(1)
                            firstLine = false
                            continue
                        }
                        lines += line.text
                        val accumulated = lines.joinToString("\n")
                        if (isComplete(accumulated)) {
                            return LineEditorResult.Line(accumulated)
                        }
                        firstLine = false
                    }

                    is PhysicalLine.Continue -> {
                        // Alt-Enter: bank the line and re-prompt on PS2 without
                        // consulting isComplete. Pairs with the [Key.Named.ALT_ENTER]
                        // case in [readOnePhysicalLine].
                        lines += line.text
                        firstLine = false
                    }

                    PhysicalLine.Interrupted -> {
                        return LineEditorResult.Interrupted
                    }

                    PhysicalLine.Eof -> {
                        // Ctrl-D at an empty buffer (with no accumulated input)
                        // is EOF. Anywhere else it's "submit what's here" — same
                        // as bash. lines is empty iff this is the very first
                        // input AND nothing was typed.
                        if (lines.isEmpty()) return LineEditorResult.Eof
                        return LineEditorResult.Line(lines.joinToString("\n"))
                    }
                }
            }
        } finally {
            terminal.exitRawMode()
        }
    }

    private sealed interface PhysicalLine {
        data class Done(
            val text: String,
        ) : PhysicalLine

        /**
         * The user pressed Alt-Enter — submit the current physical line
         * but don't consult [LineEditor.readLine.isComplete]; the outer
         * loop should re-prompt with PS2 and accumulate the next line.
         */
        data class Continue(
            val text: String,
        ) : PhysicalLine

        data object Interrupted : PhysicalLine

        data object Eof : PhysicalLine
    }

    /**
     * Read one physical line until Enter is pressed. Handles cursor and
     * edit ops in-place; redraws the line on every keystroke so the user
     * sees their edits.
     */
    private suspend fun readOnePhysicalLine(prompt: String): PhysicalLine {
        val buf = StringBuilder()
        var cursor = 0
        // History navigation snapshot: when the user presses ↑ we walk
        // backward; ↓ walks forward. `historyIndex == history.size` means
        // "current line (not from history)" and stash holds whatever the
        // user had typed before they started navigating, so ↓ back to the
        // present restores it.
        var historyIndex = history.size
        var stash: String? = null

        // Tab-completion second-press state. When the first TAB on an
        // ambiguous prefix produces no LCP extension, bash beeps; a second
        // TAB on the exact same buffer+cursor lists the candidates. We
        // track the buffer snapshot at which the next TAB should list,
        // and clear it on any non-TAB key.
        var tabListArmedBuf: String? = null
        var tabListArmedCursor: Int = -1

        // Multi-row redraw state. The previous implementation cleared
        // one row with `\r[K`, which silently left wrapped rows above
        // the current cursor row untouched whenever prompt+buf exceeded
        // the terminal width — long input duplicated / disappeared on
        // every keystroke. We now mirror readline's algorithm: track
        // where the previous render's cursor ended up, walk back to the
        // top-left of that block, and erase to end of display.
        var prevTotalLen = 0
        var prevCols = 1
        var prevCursorPos = 0

        // Map a logical character position (0..totalLen) to the terminal
        // cursor's actual (row, col). For positions that are an exact
        // non-zero multiple of cols, the cursor is "pending-wrap"-latched
        // at the right margin of the *previous* row, not at column 0 of
        // the next row. Treat that explicitly — the old naive formula
        // walked the cursor up one row too many on every redraw, which
        // eroded screen content above the prompt every time prompt+buf
        // hit a cols boundary.
        fun termPos(
            pos: Int,
            cols: Int,
        ): Pair<Int, Int> {
            if (pos == 0) return 0 to 0
            if (pos % cols == 0) return (pos / cols - 1) to (cols - 1)
            return (pos / cols) to (pos % cols)
        }

        suspend fun redraw() {
            val cols = terminal.size().cols.coerceAtLeast(1)
            // Terminal resized between redraws. The wasm grid reflows
            // wrapped lines on resize, leaving the grid cursor at the
            // SAME logical position (prompt+cursor offset) within the
            // text — just laid out at the new column count. So our
            // `prevCursorPos` / `prevTotalLen` are still semantically
            // correct; we just need the up-walk math (termPos) to use
            // the NEW cols, otherwise we walk to the wrong row and
            // either dup the prompt (under-clear) or erase output
            // above (over-clear).
            if (cols != prevCols) {
                prevCols = cols
            }
            if (prevTotalLen > 0) {
                val (prevCurRow, _) = termPos(prevCursorPos, prevCols)
                if (prevCurRow > 0) terminal.write("[${prevCurRow}A")
                terminal.write("\r[J")
            } else {
                terminal.write("\r[K")
            }
            terminal.write(prompt)
            terminal.write(buf.toString())

            val totalLen = prompt.length + buf.length
            val cursorPos = prompt.length + cursor
            val (writtenRow, writtenCol) = termPos(totalLen, cols)
            val (targetRow, targetCol) = termPos(cursorPos, cols)
            if (writtenRow > targetRow) terminal.write("[${writtenRow - targetRow}A")
            when {
                targetCol < writtenCol -> terminal.write("[${writtenCol - targetCol}D")
                targetCol > writtenCol -> terminal.write("[${targetCol - writtenCol}C")
            }
            terminal.flush()

            prevTotalLen = totalLen
            prevCols = cols
            prevCursorPos = cursorPos
        }

        redraw()

        while (true) {
            val key = terminal.readKey()
            // Any non-TAB key disarms the "list candidates on next TAB" state —
            // matches bash, where editing the buffer between TABs resets the
            // listing affordance.
            if (key !is Key.Named || key != Key.Named.TAB) {
                tabListArmedBuf = null
                tabListArmedCursor = -1
            }
            when (key) {
                is Key.Char -> {
                    val s = codepointToString(key.codepoint)
                    buf.insert(cursor, s)
                    cursor += s.length
                    redraw()
                }

                is Key.Ctrl -> {
                    when (key.letter) {
                        'A' -> {
                            cursor = 0
                            redraw()
                        }

                        'E' -> {
                            cursor = buf.length
                            redraw()
                        }

                        'B' -> {
                            if (cursor > 0) {
                                cursor--
                                redraw()
                            }
                        }

                        'F' -> {
                            if (cursor < buf.length) {
                                cursor++
                                redraw()
                            }
                        }

                        'K' -> {
                            // Kill to end of line.
                            if (cursor < buf.length) {
                                buf.deleteRange(cursor, buf.length)
                                redraw()
                            }
                        }

                        'U' -> {
                            // Kill whole line.
                            if (buf.isNotEmpty()) {
                                buf.clear()
                                cursor = 0
                                redraw()
                            }
                        }

                        'W' -> {
                            // Kill previous word: skip spaces, then non-spaces.
                            if (cursor > 0) {
                                var i = cursor
                                while (i > 0 && buf[i - 1].isWhitespace()) i--
                                while (i > 0 && !buf[i - 1].isWhitespace()) i--
                                buf.deleteRange(i, cursor)
                                cursor = i
                                redraw()
                            }
                        }

                        'C' -> {
                            // Ctrl-C: write a fresh line and report Interrupted —
                            // the outer readLine returns Interrupted, and the
                            // shell discards partial input and reprompts.
                            terminal.write("^C\r\n")
                            terminal.flush()
                            return PhysicalLine.Interrupted
                        }

                        'D' -> {
                            // Ctrl-D: EOF iff buffer is empty.
                            if (buf.isEmpty()) {
                                terminal.write("\r\n")
                                terminal.flush()
                                return PhysicalLine.Eof
                            }
                            // Otherwise: forward-delete (like Delete key).
                            if (cursor < buf.length) {
                                buf.deleteAt(cursor)
                                redraw()
                            }
                        }

                        'L' -> {
                            // Clear screen and redraw prompt.
                            terminal.write("[2J[H")
                            redraw()
                        }

                        else -> {
                            // Unhandled Ctrl-x: ignore.
                        }
                    }
                }

                Key.Named.ENTER -> {
                    terminal.write("\r\n")
                    terminal.flush()
                    return PhysicalLine.Done(buf.toString())
                }

                Key.Named.BACKSPACE -> {
                    if (cursor > 0) {
                        buf.deleteAt(cursor - 1)
                        cursor--
                        redraw()
                    }
                }

                Key.Named.DELETE -> {
                    if (cursor < buf.length) {
                        buf.deleteAt(cursor)
                        redraw()
                    }
                }

                Key.Named.LEFT -> {
                    if (cursor > 0) {
                        cursor--
                        redraw()
                    }
                }

                Key.Named.RIGHT -> {
                    if (cursor < buf.length) {
                        cursor++
                        redraw()
                    }
                }

                Key.Named.HOME -> {
                    cursor = 0
                    redraw()
                }

                Key.Named.END -> {
                    cursor = buf.length
                    redraw()
                }

                Key.Named.UP -> {
                    if (historyIndex > 0) {
                        if (historyIndex == history.size) stash = buf.toString()
                        historyIndex--
                        buf.clear()
                        buf.append(history[historyIndex])
                        cursor = buf.length
                        redraw()
                    }
                }

                Key.Named.DOWN -> {
                    if (historyIndex < history.size) {
                        historyIndex++
                        buf.clear()
                        if (historyIndex == history.size) {
                            buf.append(stash ?: "")
                            stash = null
                        } else {
                            buf.append(history[historyIndex])
                        }
                        cursor = buf.length
                        redraw()
                    }
                }

                Key.Named.TAB -> {
                    val c = completer
                    if (c == null) {
                        // No completer wired in: legacy v1 behavior — insert
                        // a literal tab so the character isn't silently lost.
                        buf.insert(cursor, '\t')
                        cursor++
                        redraw()
                    } else {
                        val result = c.complete(buf.toString(), cursor)
                        val isSecondTab =
                            tabListArmedBuf == buf.toString() && tabListArmedCursor == cursor
                        when {
                            result.candidates.isEmpty() -> {
                                bell()
                                tabListArmedBuf = null
                                tabListArmedCursor = -1
                            }

                            result.candidates.size == 1 -> {
                                val cand = result.candidates[0]
                                val (newBuf, newCursor) =
                                    applyCompletion(
                                        buf,
                                        result.replaceStart,
                                        result.replaceEnd,
                                        cand.text + cand.trailing,
                                    )
                                buf.clear()
                                buf.append(newBuf)
                                cursor = newCursor
                                redraw()
                                tabListArmedBuf = null
                                tabListArmedCursor = -1
                            }

                            else -> {
                                val typed =
                                    buf.substring(result.replaceStart, result.replaceEnd)
                                if (result.commonPrefix.length > typed.length) {
                                    // Extend to longest common prefix; arm
                                    // for listing on the next TAB.
                                    val (newBuf, newCursor) =
                                        applyCompletion(
                                            buf,
                                            result.replaceStart,
                                            result.replaceEnd,
                                            result.commonPrefix,
                                        )
                                    buf.clear()
                                    buf.append(newBuf)
                                    cursor = newCursor
                                    redraw()
                                    tabListArmedBuf = buf.toString()
                                    tabListArmedCursor = cursor
                                } else if (isSecondTab) {
                                    // Second TAB on the same state → list.
                                    listCandidates(result.candidates)
                                    // After listing, redraw the prompt fresh.
                                    prevTotalLen = 0
                                    redraw()
                                    tabListArmedBuf = null
                                    tabListArmedCursor = -1
                                } else {
                                    bell()
                                    tabListArmedBuf = buf.toString()
                                    tabListArmedCursor = cursor
                                }
                            }
                        }
                    }
                }

                Key.Named.ESC, Key.Named.PGUP, Key.Named.PGDN, Key.Named.ALT_ENTER -> {
                    // No bindings yet.
                }

                is Key.Function -> {
                    // No bindings yet.
                }

                is Key.Paste -> {
                    // Bracketed paste: insert the whole chunk verbatim and
                    // redraw. Embedded `\n` stays as-is — the outer
                    // readLine() loop treats this physical "line" as
                    // potentially incomplete (consults isComplete after
                    // Enter), so multi-line pastes get gathered correctly.
                    // Without this branch the decoder's per-char fallback
                    // would press Enter mid-paste and execute prematurely.
                    buf.insert(cursor, key.text)
                    cursor += key.text.length
                    redraw()
                }

                is Key.PrintAbove -> {
                    // Out-of-band line content (e.g. a drop-attached banner
                    // that fires mid-typing). Walk back over the rendered
                    // prompt + buffer, erase to end of screen, write the
                    // banner + newline, reset redraw tracking, then
                    // redraw the prompt on the fresh line below.
                    //
                    // ESC sequences use Kotlin's  escape rather
                    // than a raw 0x1B byte — some editors strip raw ESC
                    // on copy-paste, which would degrade to literal
                    // "[J" rendering on screen.
                    val esc = ""
                    if (prevTotalLen > 0) {
                        val (prevCurRow, _) = termPos(prevCursorPos, prevCols)
                        if (prevCurRow > 0) terminal.write("$esc[${prevCurRow}A")
                        terminal.write("\r$esc[J")
                    } else {
                        terminal.write("\r$esc[K")
                    }
                    terminal.write(key.text)
                    terminal.write("\r\n")
                    // Reset so the next redraw uses the simple
                    // "\r[K" branch (no walk-up) — cursor is at
                    // column 0 of a fresh line below the banner.
                    prevTotalLen = 0
                    prevCursorPos = 0
                    redraw()
                }
            }
        }
    }

    /** ASCII BEL — the conventional "no completion / ambiguous first-tab" beep. */
    private suspend fun bell() {
        terminal.write("")
        terminal.flush()
    }

    /**
     * Replace `buf[start..end)` with [insertion] and return the new buffer
     * + cursor position. Cursor lands at `start + insertion.length`.
     */
    private fun applyCompletion(
        buf: StringBuilder,
        start: Int,
        end: Int,
        insertion: String,
    ): Pair<String, Int> {
        val newBuf = buf.substring(0, start) + insertion + buf.substring(end)
        val newCursor = start + insertion.length
        return newBuf to newCursor
    }

    /**
     * Print [candidates] as a column-formatted listing above the prompt.
     * Width is taken from the live terminal size; entries get two-space
     * padding between columns (bash convention).
     */
    private suspend fun listCandidates(candidates: List<Candidate>) {
        if (candidates.isEmpty()) return
        terminal.write("\r\n")
        val cols = terminal.size().cols.coerceAtLeast(1)
        // Each cell renders as text + trailing slash hint (for dirs we want
        // the user to *see* the trailing slash; for files we don't pad with
        // a phantom space — match bash's display).
        val display =
            candidates.map { c ->
                if (c.trailing == "/") c.text + "/" else c.text
            }
        val maxLen = display.maxOf { it.length }
        val colW = maxLen + 2
        val perRow = (cols / colW).coerceAtLeast(1)
        val sb = StringBuilder()
        for ((i, name) in display.withIndex()) {
            sb.append(name)
            val pad = colW - name.length
            if ((i + 1) % perRow == 0 || i == display.size - 1) {
                sb.append("\r\n")
            } else {
                repeat(pad) { sb.append(' ') }
            }
        }
        terminal.write(sb.toString())
        terminal.flush()
    }

    private fun codepointToString(cp: Int): String =
        if (cp <= 0xFFFF) {
            cp.toChar().toString()
        } else {
            // Surrogate pair for astral codepoints.
            val high = (((cp - 0x10000) shr 10) + 0xD800).toChar()
            val low = (((cp - 0x10000) and 0x3FF) + 0xDC00).toChar()
            "$high$low"
        }
}
