package com.accucodeai.kash.tools.nano

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl

/**
 * Bottom-row prompts: filename entry for save, search query, yes/no
 * confirmations. Self-contained — reads keys from the terminal and
 * paints onto a single row, never disturbing the editor body.
 *
 * Returns null on Ctrl-C (user aborted the prompt).
 */
internal class NanoMinibuffer(
    private val term: TerminalControl,
) {
    /**
     * Single-line text prompt. [label] is shown as the prompt prefix,
     * [initial] pre-fills the input. Enter confirms (returns the string,
     * possibly empty); Ctrl-C cancels (returns null).
     */
    suspend fun readLine(
        label: String,
        initial: String = "",
    ): String? {
        val sb = StringBuilder(initial)
        var cursor = sb.length
        while (true) {
            paint(label, sb, cursor)
            when (val k = term.readKey()) {
                is Key.Char -> {
                    val s = codepointToString(k.codepoint)
                    sb.insert(cursor, s)
                    cursor += s.length
                }

                Key.Named.ENTER -> {
                    return sb.toString()
                }

                Key.Named.ESC -> {
                    return null
                }

                Key.Named.BACKSPACE -> {
                    if (cursor > 0) {
                        sb.deleteAt(cursor - 1)
                        cursor--
                    }
                }

                Key.Named.DELETE -> {
                    if (cursor < sb.length) sb.deleteAt(cursor)
                }

                Key.Named.LEFT -> {
                    if (cursor > 0) cursor--
                }

                Key.Named.RIGHT -> {
                    if (cursor < sb.length) cursor++
                }

                Key.Named.HOME -> {
                    cursor = 0
                }

                Key.Named.END -> {
                    cursor = sb.length
                }

                is Key.Ctrl -> {
                    when (k.letter) {
                        'C', 'G' -> {
                            return null
                        }

                        // Ctrl-C / Ctrl-G cancel
                        'A' -> {
                            cursor = 0
                        }

                        'E' -> {
                            cursor = sb.length
                        }

                        else -> {}
                    }
                }

                else -> {}
            }
        }
    }

    /**
     * Yes/No/Cancel prompt — returns true (yes), false (no), null (cancel).
     */
    suspend fun confirm(label: String): Boolean? {
        while (true) {
            paint(label, StringBuilder(" [Y/N]"), -1)
            when (val k = term.readKey()) {
                is Key.Char -> {
                    when (k.codepoint.toChar().lowercaseChar()) {
                        'y' -> {
                            return true
                        }

                        'n' -> {
                            return false
                        }

                        else -> {}
                    }
                }

                Key.Named.ESC -> {
                    return null
                }

                is Key.Ctrl -> {
                    if (k.letter == 'C' || k.letter == 'G') return null
                }

                else -> {}
            }
        }
    }

    private suspend fun paint(
        label: String,
        text: StringBuilder,
        cursor: Int,
    ) {
        val size = term.size()
        val row = size.rows
        val cols = size.cols.coerceAtLeast(20)
        val combined = "$label$text"
        val truncated = if (combined.length > cols) combined.substring(combined.length - cols) else combined
        val sb = StringBuilder()
        sb.append(Ansi.HIDE_CURSOR)
        sb.append(Ansi.moveTo(row, 1))
        sb.append(Ansi.CLEAR_TO_EOL)
        sb.append(Ansi.INVERSE).append(label).append(Ansi.RESET)
        sb.append(text)
        // Place caret if visible cursor was requested
        if (cursor >= 0) {
            val caretCol = (label.length + cursor + 1).coerceAtMost(cols)
            sb.append(Ansi.moveTo(row, caretCol))
            sb.append(Ansi.SHOW_CURSOR)
        }
        term.write(sb.toString())
        term.flush()
    }
}
