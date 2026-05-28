package com.accucodeai.kash.api

import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.terminal.Candidate
import com.accucodeai.kash.api.terminal.Completer
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.LineEditor
import com.accucodeai.kash.api.terminal.LineEditorResult
import com.accucodeai.kash.api.terminal.TerminalControl

/**
 * Pure-Kotlin [LineEditor] on top of [TerminalControl]. The default — and
 * currently only — line editor; serves the top-level shell, recursive
 * `kash`-in-kash invocations, and the wasmJs target uniformly.
 *
 * Multi-line buffer model. A single [StringBuilder] holds the entire
 * editable input including any explicit `\n` line breaks the user
 * inserted; a single integer cursor points into it. Submission is
 * driven by `isComplete(buf)` — if the shell's parser says the
 * accumulated text is complete, Enter returns it; otherwise Enter
 * inserts a `\n` and the user keeps editing. Alt-Enter always
 * inserts a `\n` regardless. This mirrors fish / PSReadLine /
 * reedline — the user can revisit and edit any line of a multi-line
 * input until they choose to submit. Up/Down move within the buffer
 * when there are rows above/below the cursor; at the top or bottom
 * row they fall through to history navigation.
 *
 * V1 scope:
 *
 *  - cursor: ←/→, Home/End, Ctrl-A/Ctrl-E, Ctrl-B/Ctrl-F (line-aware)
 *  - edit: Backspace, Delete, Ctrl-K (kill to logical-line end),
 *    Ctrl-U (kill from logical-line start to cursor), Ctrl-W
 *    (kill prev word, stops at `\n`)
 *  - history: ↑/↓ navigate an in-memory ring buffer when at the
 *    visual top/bottom of the buffer; otherwise move within the
 *    buffer. Recall lands the cursor at the end of the first line
 *    of the recalled entry (fish convention).
 *  - submit: Enter via [LineEditor.readLine.isComplete] validator;
 *    Alt-Enter to force-insert `\n`
 *  - paste: bracketed-paste arrives whole via `Key.Paste`, including
 *    embedded `\n`s — the user can edit and then submit
 *  - Ctrl-C → [LineEditorResult.Interrupted]; Ctrl-D at empty buffer →
 *    [LineEditorResult.Eof]; Ctrl-D otherwise = forward-delete
 *
 * Tab completion: opt-in via the [completer] constructor parameter — see
 * [com.accucodeai.kash.api.terminal.Completer]. Bash-style: unique match
 * inserts text + trailing; ambiguous prefix extends to longest common
 * prefix; second TAB on the same state lists candidates above the prompt.
 *
 * Not in v1: history search (Ctrl-R), vi mode, mouse, syntax highlighting,
 * a queued-input panel during agent streams (see Phase 2 plan).
 * Wrap-to-next-row mid-line is approximated by re-drawing the whole
 * buffer on every keystroke; that's slow for very long inputs but
 * bounded. Buffers rendering taller than the visible viewport let
 * their top rows scroll into scrollback and become un-editable in
 * place — that's a universal limit of in-place terminal line editing,
 * matched by readline / fish / PSReadLine.
 *
 * Wide-character handling: cursor accounting goes through [charWidth]
 * (see [Wcwidth.kt]) so CJK / fullwidth glyphs that occupy two grid
 * cells are tracked correctly; backspace and arrow keys jump by
 * codepoints (one wide char = one step), and on-screen cursor
 * positioning uses display columns. JVM terminals (iTerm2 / WezTerm /
 * etc.) follow the same wcwidth model by default, so the same logic
 * is correct on both targets.
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

    /**
     * Read one (possibly multi-line) input until the user submits. Stays
     * in raw mode for the duration; redraws on every keystroke.
     *
     * @param prompt drawn at column 0 of the first row.
     * @param continuationPrompt kept in the signature for API compatibility
     *   with the prior two-loop design. The multi-line model doesn't draw
     *   a per-row continuation sigil — every editable row sits flush-left
     *   and the buffer is honest about whitespace. If a future caller wants
     *   PS2-style continuation marks we can repaint them on the inner rows
     *   without changing the API.
     * @param isComplete invoked with the current buffer when the user
     *   presses Enter. Returning true causes [LineEditorResult.Line] to
     *   be returned; false causes the editor to insert a `\n` and continue
     *   editing. Alt-Enter bypasses this and always inserts `\n`.
     */
    override suspend fun readLine(
        prompt: String,
        continuationPrompt: String,
        isComplete: (accumulated: String) -> Boolean,
    ): LineEditorResult {
        // [continuationPrompt] is retained on the interface for API
        // compatibility with the prior two-loop design. The new model
        // doesn't draw it because every editable row sits flush-left;
        // re-introducing a PS2 sigil on inner rows is a render-time
        // tweak that doesn't need a signature change.
        terminal.enterRawMode()
        try {
            val buf = StringBuilder()
            var cursor = 0

            // History navigation snapshot: when the user presses ↑ at the
            // top row we walk backward; ↓ at the bottom walks forward.
            // `historyIndex == history.size` means "current buffer (not
            // from history)" and `stash` holds whatever the user had
            // typed before they started navigating, so ↓ back to the
            // present restores it.
            var historyIndex = history.size
            var stash: String? = null

            // Tab-completion second-press state. When the first TAB on an
            // ambiguous prefix produces no LCP extension, bash beeps; a
            // second TAB on the exact same buffer+cursor lists the
            // candidates. We track the buffer snapshot at which the next
            // TAB should list, and clear it on any non-TAB key.
            var tabListArmedBuf: String? = null
            var tabListArmedCursor: Int = -1

            // Multi-row redraw state. The previous single-line version
            // cleared one row with `\r[K`, which left wrapped rows
            // above the cursor untouched and corrupted long inputs.
            // We now walk back to where the previous render's cursor
            // ended up (saved as `prevCursorRow`), erase to end of
            // display, and re-render the whole buffer. With explicit
            // `\n` breaks in the buffer the same algorithm holds —
            // [simulateTermPos] honors `\n` as a forced row break.
            var prevHadContent = false
            var prevCursorRow = 0

            // ---- Line-aware helpers over `buf` -----------------------

            fun lineStart(at: Int): Int {
                var i = at
                while (i > 0 && buf[i - 1] != '\n') i--
                return i
            }

            fun lineEnd(at: Int): Int {
                var i = at
                while (i < buf.length && buf[i] != '\n') i++
                return i
            }

            fun colOf(at: Int): Int = at - lineStart(at)

            fun rowOf(at: Int): Int {
                var count = 0
                for (i in 0 until at) if (buf[i] == '\n') count++
                return count
            }

            fun lineCount(): Int {
                var count = 1
                for (i in 0 until buf.length) if (buf[i] == '\n') count++
                return count
            }

            // ---- Display geometry ------------------------------------

            // Walk [text] character by character applying the terminal's
            // write/wrap rule: a char of display width `w` first wraps
            // if it wouldn't fit on the current row (`col + w > cols`),
            // then advances col by w. `\n` forces a row break with no
            // wrap check. Returns the (row, col) the cursor sits at
            // after the last char was written.
            //
            // Wide-char aware via [charWidth] — each CJK codepoint
            // occupies two grid cells so a buf of N codepoints can be
            // 2N display columns.
            //
            // Pending-wrap behavior: when the last char fills the row
            // exactly so col reaches cols, the wrap hasn't actually
            // happened yet — the cursor stays latched at the previous
            // row's right margin (`cols - 1`) until the next char
            // forces it. xterm and the wasmJs grid both implement this.
            fun simulateTermPos(
                text: CharSequence,
                cols: Int,
            ): Pair<Int, Int> {
                var row = 0
                var col = 0
                for (i in text.indices) {
                    val ch = text[i]
                    if (ch == '\n') {
                        row++
                        col = 0
                        continue
                    }
                    val w = charWidth(ch)
                    if (col + w > cols) {
                        row++
                        col = 0
                    }
                    col += w
                }
                return if (col >= cols && text.isNotEmpty()) row to (cols - 1) else row to col
            }

            // Emit `buf` to the terminal, translating bare `\n` to
            // `\r\n`. Raw mode on JVM doesn't auto-CR on LF (no `ONLCR`),
            // so a bare `\n` would leave the cursor at the same column
            // one row down — a "stair-step" of input. The wasmJs grid's
            // AnsiInterpreter already treats `\n` as CR+LF (see
            // `handleGround`), so the conversion is harmless there.
            fun bufWire(): String = buf.toString().replace("\n", "\r\n")

            suspend fun redraw() {
                val cols = terminal.size().cols.coerceAtLeast(1)
                if (prevHadContent) {
                    // Walk back to the top-left of the previously
                    // rendered block. `Ansi.cursorUp(0)` returns ""
                    // so no guard on `prevCursorRow > 0` is needed.
                    terminal.write(Ansi.cursorUp(prevCursorRow))
                    terminal.write("\r" + Ansi.ERASE_TO_END_OF_DISPLAY)
                } else {
                    terminal.write("\r" + Ansi.ERASE_TO_END_OF_LINE)
                }
                terminal.write(prompt)
                terminal.write(bufWire())

                val totalText = prompt + buf.toString()
                val cursorText = prompt + buf.substring(0, cursor)
                val (writtenRow, writtenCol) = simulateTermPos(totalText, cols)
                val (targetRow, targetCol) = simulateTermPos(cursorText, cols)
                terminal.write(Ansi.cursorUp(writtenRow - targetRow))
                when {
                    targetCol < writtenCol -> terminal.write(Ansi.cursorBack(writtenCol - targetCol))
                    targetCol > writtenCol -> terminal.write(Ansi.cursorForward(targetCol - writtenCol))
                }
                terminal.flush()

                prevHadContent = totalText.isNotEmpty()
                prevCursorRow = targetRow
            }

            // Move the cursor below the entire rendered input block, at
            // column 0 — used when submitting so the caller's output
            // starts on a fresh line below the user's edited input.
            // Without this, if the cursor was inside the buffer when
            // Enter was pressed, the next write would overwrite the
            // trailing rows of input.
            suspend fun cursorBelowInput() {
                val cols = terminal.size().cols.coerceAtLeast(1)
                val totalText = prompt + buf.toString()
                val cursorText = prompt + buf.substring(0, cursor)
                val (writtenRow, _) = simulateTermPos(totalText, cols)
                val (targetRow, _) = simulateTermPos(cursorText, cols)
                terminal.write(Ansi.cursorDown(writtenRow - targetRow))
                terminal.write("\r\n")
                terminal.flush()
            }

            redraw()

            while (true) {
                val key = terminal.readKey()
                // Any non-TAB key disarms the "list candidates on next
                // TAB" state — matches bash, where editing the buffer
                // between TABs resets the listing affordance.
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
                                cursor = lineStart(cursor)
                                redraw()
                            }

                            'E' -> {
                                cursor = lineEnd(cursor)
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
                                // Kill to end of current logical line; do
                                // not delete the trailing `\n`.
                                val end = lineEnd(cursor)
                                if (cursor < end) {
                                    buf.deleteRange(cursor, end)
                                    redraw()
                                }
                            }

                            'U' -> {
                                // Kill from start of current logical line
                                // up to the cursor. Bash semantics — only
                                // the current line, never crosses `\n`.
                                val start = lineStart(cursor)
                                if (start < cursor) {
                                    buf.deleteRange(start, cursor)
                                    cursor = start
                                    redraw()
                                }
                            }

                            'W' -> {
                                // Kill previous word, stopping at `\n`
                                // boundaries so we never devour the prior
                                // logical line.
                                if (cursor > 0) {
                                    var i = cursor
                                    while (i > 0 && buf[i - 1] != '\n' && buf[i - 1].isWhitespace()) i--
                                    while (i > 0 && buf[i - 1] != '\n' && !buf[i - 1].isWhitespace()) i--
                                    if (i < cursor) {
                                        buf.deleteRange(i, cursor)
                                        cursor = i
                                        redraw()
                                    }
                                }
                            }

                            'C' -> {
                                // Ctrl-C: walk below the input, emit ^C,
                                // and return Interrupted. The shell
                                // discards the partial input and reprompts.
                                cursorBelowInput()
                                terminal.write("^C\r\n")
                                terminal.flush()
                                return LineEditorResult.Interrupted
                            }

                            'D' -> {
                                // Ctrl-D: EOF iff the buffer is empty;
                                // otherwise forward-delete (like Delete).
                                if (buf.isEmpty()) {
                                    terminal.write("\r\n")
                                    terminal.flush()
                                    return LineEditorResult.Eof
                                }
                                if (cursor < buf.length) {
                                    buf.deleteAt(cursor)
                                    redraw()
                                }
                            }

                            'L' -> {
                                // Clear screen and redraw the prompt
                                // fresh at (0,0). Reset the walk-back
                                // state since there's nothing above
                                // anymore.
                                terminal.write(Ansi.CLEAR_SCREEN_AND_HOME)
                                prevHadContent = false
                                prevCursorRow = 0
                                redraw()
                            }

                            else -> {
                                // Unhandled Ctrl-x: ignore.
                            }
                        }
                    }

                    Key.Named.ENTER -> {
                        if (isComplete(buf.toString())) {
                            cursorBelowInput()
                            return LineEditorResult.Line(buf.toString())
                        }
                        // Validator says "not done yet" — insert `\n`
                        // at the cursor and stay in the buffer.
                        buf.insert(cursor, '\n')
                        cursor++
                        redraw()
                    }

                    Key.Named.ALT_ENTER -> {
                        // Always insert `\n` regardless of the
                        // validator — the "I know this isn't done yet,
                        // give me a fresh line" override.
                        buf.insert(cursor, '\n')
                        cursor++
                        redraw()
                    }

                    Key.Named.BACKSPACE -> {
                        if (cursor > 0) {
                            // Deletes the char before the cursor. If
                            // that char is `\n`, naturally joins lines.
                            buf.deleteAt(cursor - 1)
                            cursor--
                            redraw()
                        }
                    }

                    Key.Named.DELETE -> {
                        if (cursor < buf.length) {
                            // If the char at cursor is `\n`, naturally
                            // joins with the next line.
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
                        cursor = lineStart(cursor)
                        redraw()
                    }

                    Key.Named.END -> {
                        cursor = lineEnd(cursor)
                        redraw()
                    }

                    Key.Named.UP -> {
                        val row = rowOf(cursor)
                        if (row > 0) {
                            // Multi-row buffer — move cursor up one
                            // logical line preserving column.
                            val targetCol = colOf(cursor)
                            val curLineStart = lineStart(cursor)
                            val prevLineEnd = curLineStart - 1 // index of '\n'
                            val prevLineStart = lineStart(prevLineEnd)
                            val prevLineLen = prevLineEnd - prevLineStart
                            cursor = prevLineStart + minOf(targetCol, prevLineLen)
                            redraw()
                        } else if (historyIndex > 0) {
                            // At top of buffer — fall through to history.
                            if (historyIndex == history.size) stash = buf.toString()
                            historyIndex--
                            buf.clear()
                            buf.append(history[historyIndex])
                            // Fish convention: cursor lands at the end
                            // of the first line of the recalled entry.
                            cursor = lineEnd(0)
                            redraw()
                        }
                    }

                    Key.Named.DOWN -> {
                        val row = rowOf(cursor)
                        if (row < lineCount() - 1) {
                            // Multi-row buffer — move cursor down one
                            // logical line preserving column.
                            val targetCol = colOf(cursor)
                            val curLineEnd = lineEnd(cursor)
                            val nextLineStart = curLineEnd + 1 // past the '\n'
                            val nextLineEnd = lineEnd(nextLineStart)
                            val nextLineLen = nextLineEnd - nextLineStart
                            cursor = nextLineStart + minOf(targetCol, nextLineLen)
                            redraw()
                        } else if (historyIndex < history.size) {
                            // At bottom of buffer — fall through to history.
                            historyIndex++
                            buf.clear()
                            if (historyIndex == history.size) {
                                buf.append(stash ?: "")
                                stash = null
                            } else {
                                buf.append(history[historyIndex])
                            }
                            cursor = lineEnd(0)
                            redraw()
                        }
                    }

                    Key.Named.TAB -> {
                        val c = completer
                        if (c == null) {
                            // No completer wired in: legacy behavior —
                            // insert a literal tab so the keystroke
                            // isn't silently lost.
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
                                        listCandidates(result.candidates)
                                        prevHadContent = false
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

                    Key.Named.ESC, Key.Named.PGUP, Key.Named.PGDN -> {
                        // No bindings yet.
                    }

                    is Key.Function -> {
                        // No bindings yet.
                    }

                    is Key.Paste -> {
                        // Bracketed paste: insert the whole chunk
                        // verbatim. Embedded `\n` is now a first-class
                        // citizen — the user can edit any line of the
                        // pasted block before submitting.
                        buf.insert(cursor, key.text)
                        cursor += key.text.length
                        redraw()
                    }

                    is Key.PrintAbove -> {
                        // Out-of-band line content (e.g. a drop-attached
                        // banner that fires mid-typing). Walk back over
                        // the rendered prompt + buffer, erase to end of
                        // screen, write the banner + newline, reset
                        // redraw tracking, then redraw the prompt on
                        // the fresh line below.
                        //
                        // All ANSI sequences go through [Ansi] (in
                        // `api/.../ansi/Ansi.kt`) so the source never
                        // carries raw `` bytes — editors and
                        // tools have a habit of stripping those on
                        // save/copy and we'd silently end up writing
                        // literal `[J` to the terminal.
                        if (prevHadContent) {
                            terminal.write(Ansi.cursorUp(prevCursorRow))
                            terminal.write("\r" + Ansi.ERASE_TO_END_OF_DISPLAY)
                        } else {
                            terminal.write("\r" + Ansi.ERASE_TO_END_OF_LINE)
                        }
                        terminal.write(key.text)
                        terminal.write("\r\n")
                        prevHadContent = false
                        prevCursorRow = 0
                        redraw()
                    }
                }
            }
            // Unreachable.
            @Suppress("UNREACHABLE_CODE")
            return LineEditorResult.Eof
        } finally {
            terminal.exitRawMode()
        }
    }

    /** ASCII BEL — the conventional "no completion / ambiguous first-tab" beep. */
    private suspend fun bell() {
        terminal.write(Ansi.BEL)
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
