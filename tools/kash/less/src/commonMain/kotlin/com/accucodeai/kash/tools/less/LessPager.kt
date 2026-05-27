package com.accucodeai.kash.tools.less

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl

/**
 * Pager entry point. Owns the terminal lifecycle (raw mode + alt screen)
 * and the main key-dispatch loop. Returns an exit code.
 *
 *  - 0 on clean quit
 *  - 1 on an unrecoverable I/O error
 */
internal class LessPager(
    private val term: TerminalControl,
    initialText: String,
    filename: String?,
    showLineNumbers: Boolean = false,
) {
    private val renderer = LessRenderer(term)
    private val minibuffer = LessMinibuffer(term)
    private var notice: String? = null
    private var pendingDigits: StringBuilder = StringBuilder()

    private var buffer: LessBuffer

    init {
        val size = term.size()
        val bodyHeight = (size.rows - 1).coerceAtLeast(1)
        buffer =
            LessBuffer.fromText(
                text = initialText,
                filename = filename,
                bodyHeight = bodyHeight,
                showLineNumbers = showLineNumbers,
            )
    }

    suspend fun run(): Int {
        term.enterRawMode()
        term.useAlternateScreen(true)
        try {
            while (true) {
                // Re-poll size so resize takes effect at each frame.
                val size = term.size()
                buffer = buffer.withBodyHeight((size.rows - 1).coerceAtLeast(1))
                renderer.draw(buffer, statusOverride = notice)
                notice = null
                val key = term.readKey()
                val cmd = classify(key) ?: continue
                if (!dispatch(cmd)) return 0
            }
        } finally {
            try {
                term.useAlternateScreen(false)
            } catch (_: Throwable) {
            }
            try {
                term.exitRawMode()
            } catch (_: Throwable) {
            }
            try {
                term.flush()
            } catch (_: Throwable) {
            }
        }
    }

    /** What the user just asked for. */
    private sealed interface Op {
        data class LineForward(
            val n: Int,
        ) : Op

        data class LineBackward(
            val n: Int,
        ) : Op

        object ScreenForward : Op

        object ScreenBackward : Op

        object HalfForward : Op

        object HalfBackward : Op

        object GotoStart : Op

        object GotoEnd : Op

        data class GotoLine(
            val n: Int,
        ) : Op

        data class GotoPercent(
            val pct: Int,
        ) : Op

        object SearchForward : Op

        object SearchBackward : Op

        object SearchRepeat : Op

        object SearchRepeatReverse : Op

        object ClearHighlight : Op

        object Help : Op

        object Repaint : Op

        object ShowPosition : Op

        object Quit : Op

        data class Digit(
            val d: Int,
        ) : Op

        object ColonPrompt : Op
    }

    /**
     * Decode the key into an [Op]. Returns null for keys we ignore. Numeric
     * digits are accumulated into [pendingDigits] and consumed by the next
     * operation that takes a count.
     */
    private fun classify(key: Key): Op? =
        when (key) {
            is Key.Char -> {
                when (val c = key.codepoint.toChar()) {
                    in '0'..'9' -> {
                        Op.Digit(c.digitToInt())
                    }

                    'j' -> {
                        Op.LineForward(consumeCount(1))
                    }

                    'k' -> {
                        Op.LineBackward(consumeCount(1))
                    }

                    ' ', 'f' -> {
                        Op.ScreenForward
                    }

                    'b' -> {
                        Op.ScreenBackward
                    }

                    'd' -> {
                        Op.HalfForward
                    }

                    'u' -> {
                        Op.HalfBackward
                    }

                    'g', '<' -> {
                        Op.GotoStart
                    }

                    'G', '>' -> {
                        val n = consumeCountOrNull()
                        if (n != null) Op.GotoLine(n) else Op.GotoEnd
                    }

                    'p', '%' -> {
                        Op.GotoPercent(consumeCount(0))
                    }

                    '/' -> {
                        Op.SearchForward
                    }

                    '?' -> {
                        Op.SearchBackward
                    }

                    'n' -> {
                        Op.SearchRepeat
                    }

                    'N' -> {
                        Op.SearchRepeatReverse
                    }

                    'h', 'H' -> {
                        Op.Help
                    }

                    'r', 'R' -> {
                        Op.Repaint
                    }

                    '=' -> {
                        Op.ShowPosition
                    }

                    'q', 'Q' -> {
                        Op.Quit
                    }

                    '&' -> {
                        Op.ClearHighlight
                    }

                    ':' -> {
                        Op.ColonPrompt
                    }

                    else -> {
                        null
                    }
                }
            }

            Key.Named.DOWN, Key.Named.ENTER -> {
                Op.LineForward(consumeCount(1))
            }

            Key.Named.UP -> {
                Op.LineBackward(consumeCount(1))
            }

            Key.Named.PGDN -> {
                Op.ScreenForward
            }

            Key.Named.PGUP -> {
                Op.ScreenBackward
            }

            Key.Named.HOME -> {
                Op.GotoStart
            }

            Key.Named.END -> {
                Op.GotoEnd
            }

            Key.Named.ESC -> {
                null
            }

            is Key.Ctrl -> {
                when (key.letter) {
                    'F' -> Op.ScreenForward
                    'B' -> Op.ScreenBackward
                    'D' -> Op.HalfForward
                    'U' -> Op.HalfBackward
                    'L', 'R' -> Op.Repaint
                    'G' -> Op.ShowPosition
                    'C' -> Op.Quit
                    else -> null
                }
            }

            else -> {
                null
            }
        }

    /** Pop the pending numeric prefix, defaulting to [default] if none. */
    private fun consumeCount(default: Int): Int {
        if (pendingDigits.isEmpty()) return default
        val v = pendingDigits.toString().toIntOrNull() ?: default
        pendingDigits.clear()
        return v
    }

    /** Pop the pending numeric prefix or null if there was none. */
    private fun consumeCountOrNull(): Int? {
        if (pendingDigits.isEmpty()) return null
        val v = pendingDigits.toString().toIntOrNull()
        pendingDigits.clear()
        return v
    }

    /** @return true to keep going, false to exit. */
    private suspend fun dispatch(op: Op): Boolean {
        when (op) {
            is Op.Digit -> {
                if (pendingDigits.length < 10) pendingDigits.append(op.d)
            }

            is Op.LineForward -> {
                buffer = buffer.scrollLineForward(op.n)
            }

            is Op.LineBackward -> {
                buffer = buffer.scrollLineBackward(op.n)
            }

            Op.ScreenForward -> {
                buffer = buffer.scrollScreenForward()
            }

            Op.ScreenBackward -> {
                buffer = buffer.scrollScreenBackward()
            }

            Op.HalfForward -> {
                buffer = buffer.scrollHalfForward()
            }

            Op.HalfBackward -> {
                buffer = buffer.scrollHalfBackward()
            }

            Op.GotoStart -> {
                buffer = buffer.gotoStart()
            }

            Op.GotoEnd -> {
                buffer = buffer.gotoEnd()
            }

            is Op.GotoLine -> {
                buffer = buffer.gotoLine(op.n)
            }

            is Op.GotoPercent -> {
                buffer = buffer.gotoPercent(op.pct)
            }

            Op.SearchForward -> {
                doSearch(forward = true)
            }

            Op.SearchBackward -> {
                doSearch(forward = false)
            }

            Op.SearchRepeat -> {
                repeatSearch(reverse = false)
            }

            Op.SearchRepeatReverse -> {
                repeatSearch(reverse = true)
            }

            Op.ClearHighlight -> {
                buffer = buffer.copy(highlightPattern = null)
            }

            Op.Help -> {
                showHelp()
            }

            Op.Repaint -> {
                Unit
            }

            // next iteration repaints
            Op.ShowPosition -> {
                notice = positionReport()
            }

            Op.ColonPrompt -> {
                if (!colonCommand()) return false
            }

            Op.Quit -> {
                return false
            }
        }
        return true
    }

    private suspend fun doSearch(forward: Boolean) {
        val prompt = if (forward) "/" else "?"
        val q = minibuffer.readLine(prompt) ?: return
        if (q.isEmpty()) {
            // Empty query repeats the previous search.
            repeatSearch(reverse = !forward && buffer.lastSearch?.forward == true)
            return
        }
        val regex =
            try {
                Regex(q)
            } catch (t: Throwable) {
                notice = "Bad pattern: ${t.message ?: q}"
                return
            }
        val hit =
            if (forward) {
                buffer.searchForward(regex)
            } else {
                buffer.searchBackward(regex)
            }
        val ls = LastSearch(regex, q, forward)
        if (hit == null) {
            notice = "Pattern not found"
            buffer = buffer.copy(lastSearch = ls, highlightPattern = regex)
        } else {
            buffer =
                buffer.copy(top = hit, lastSearch = ls, highlightPattern = regex).normalized()
        }
    }

    private fun repeatSearch(reverse: Boolean) {
        val ls = buffer.lastSearch
        if (ls == null) {
            notice = "No previous search"
            return
        }
        val forward = if (reverse) !ls.forward else ls.forward
        val hit =
            if (forward) {
                buffer.searchForward(ls.pattern)
            } else {
                buffer.searchBackward(ls.pattern)
            }
        if (hit == null) {
            notice = "Pattern not found"
        } else {
            buffer = buffer.copy(top = hit).normalized()
        }
    }

    /**
     * `:`-prefixed commands. Returns true to keep going, false to quit.
     * v1 supports `:q` only; everything else surfaces a notice.
     */
    private suspend fun colonCommand(): Boolean {
        val q = minibuffer.readLine(":") ?: return true
        when (q.trim()) {
            "q", "quit" -> return false
            "" -> Unit
            else -> notice = "Unknown command: :$q"
        }
        return true
    }

    private fun positionReport(): String {
        val first = buffer.top + 1
        val last = (buffer.top + buffer.bodyHeight).coerceAtMost(buffer.rowCount)
        val total = buffer.rowCount
        val pct = if (total <= 0) 0 else ((last.toLong() * 100L) / total).toInt()
        val name = buffer.filename ?: "(stdin)"
        return "$name  lines $first-$last/$total  byte $pct%"
    }

    private suspend fun showHelp() {
        val sb = StringBuilder()
        sb.append(Ansi.HIDE_CURSOR)
        sb.append(Ansi.CURSOR_HOME)
        sb.append(Ansi.CLEAR_SCREEN)
        sb.append("kash less — quick reference\r\n")
        sb.append("\r\n")
        sb.append("  Move by line     j  k    Down  Up    Enter\r\n")
        sb.append("  Move by screen   Space f b   PgDn  PgUp   Ctrl-F  Ctrl-B\r\n")
        sb.append("  Move by half     d u  Ctrl-D  Ctrl-U\r\n")
        sb.append("  Jump             g <  Home   G > End    Ng  goto line N\r\n")
        sb.append("                   Np  N%     jump to N percent through file\r\n")
        sb.append("  Search           /pat   forward   ?pat   backward\r\n")
        sb.append("  Repeat           n  same direction        N  reverse\r\n")
        sb.append("  Clear matches    &\r\n")
        sb.append("  Status           =  Ctrl-G    Refresh   r  Ctrl-L\r\n")
        sb.append("  Help / quit      h  H        q  Q  :q\r\n")
        sb.append("\r\n")
        sb.append("Press any key to dismiss this help screen.")
        term.write(sb.toString())
        term.flush()
        term.readKey() // wait for a dismissal key
    }
}
