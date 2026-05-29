package com.accucodeai.kash.tools.less

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl

/**
 * Bottom-row prompt for `/`, `?`, and `:`-class commands. Returns the
 * user-entered string on Enter, or null if the user aborted with Esc /
 * Ctrl-C / Ctrl-G.
 */
internal class LessMinibuffer(
    private val term: TerminalControl,
) {
    suspend fun readLine(prompt: String): String? {
        val sb = StringBuilder()
        var cursor = 0
        while (true) {
            paint(prompt, sb, cursor)
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
                    } else if (sb.isEmpty()) {
                        // Backspace on empty prompt cancels the prompt.
                        return null
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

                        'A' -> {
                            cursor = 0
                        }

                        'E' -> {
                            cursor = sb.length
                        }

                        'U' -> {
                            sb.clear()
                            cursor = 0
                        }

                        else -> {}
                    }
                }

                else -> {}
            }
        }
    }

    private suspend fun paint(
        prompt: String,
        text: StringBuilder,
        cursor: Int,
    ) {
        val size = term.size()
        val row = size.rows
        val cols = size.cols.coerceAtLeast(20)
        val sb = StringBuilder()
        sb.append(Ansi.HIDE_CURSOR)
        sb.append(Ansi.moveTo(row, 1))
        sb.append(Ansi.CLEAR_TO_EOL)
        // Clamp to cols so a long query doesn't wrap the bottom row and
        // scroll the pager body out from under itself on narrow screens.
        // Keep the tail (where the caret sits) visible — same approach as
        // the nano / vi minibuffers.
        val combined = prompt + text
        sb.append(if (combined.length > cols) combined.substring(combined.length - cols) else combined)
        val caretCol = (prompt.length + cursor + 1).coerceAtMost(cols)
        sb.append(Ansi.moveTo(row, caretCol))
        sb.append(Ansi.SHOW_CURSOR)
        term.write(sb.toString())
        term.flush()
    }
}
