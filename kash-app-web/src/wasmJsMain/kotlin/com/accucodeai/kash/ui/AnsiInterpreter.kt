package com.accucodeai.kash.ui

import androidx.compose.ui.graphics.Color

/**
 * Minimal VT100/xterm parser driving a [TerminalGrid]. Sized to cover
 * everything `nano` emits (`NanoRenderer.kt`), everything `BasicLineEditor`
 * emits (`BasicLineEditor.kt`), and the SGR subset that `ls --color` uses.
 *
 * What we honor:
 *  - C0 controls: \r, \n, \b, \t, BEL (drop), ESC
 *  - ESC sequences: ESC 7 / ESC 8 (DECSC/DECRC), ESC c (RIS — full reset),
 *    ESC [ (CSI), ESC ] (OSC, consumed and discarded), ESC ( B (charset, ignored)
 *  - CSI finals: A B C D (cursor moves), H f (absolute move), J K (erase),
 *    m (SGR), s u (save/restore cursor), G (column abs), d (row abs),
 *    L M (insert/delete line — minimal), n (DSR — consumed, no reply),
 *    private modes via `?`: 25 (cursor), 1049/1047/47 (alt screen)
 *  - 16-color SGR only (30-37, 40-47, 90-97, 100-107, 0, 1, 2, 4, 7, 22, 24, 27, 39, 49).
 *    256/truecolor (38;5;n / 48;2;r;g;b) are parsed but mapped to the
 *    nearest 16-color base — sufficient for v1.
 *
 * What we drop silently:
 *  - Unknown CSI finals (forward-compatible — newer tools may use them)
 *  - OSC contents (title bars etc. — not visible in our headless canvas)
 *  - DCS/PM/APC (rare; would need ST termination handling)
 *
 * Implementation: a stream-friendly state machine that survives ANY split
 * across `feed()` calls. Critical because the line editor's "\r[K" + prompt
 * + body + "[<N>D" arrives in a single `terminal.write` but a tool's stdout
 * (e.g. nano repaints) flows through `feedBytes` in arbitrary chunks.
 */
public class AnsiInterpreter(
    private val grid: TerminalGrid,
) {
    private enum class State { GROUND, ESC, CSI, OSC, CHARSET }

    private var state: State = State.GROUND
    private val csiParams: StringBuilder = StringBuilder()
    private var csiPrivate: Boolean = false

    public fun feed(s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (state) {
                State.GROUND -> {
                    handleGround(c)
                }

                State.ESC -> {
                    handleEsc(c)
                }

                State.CSI -> {
                    handleCsi(c)
                }

                State.OSC -> {
                    handleOsc(c)
                }

                State.CHARSET -> {
                    state = State.GROUND
                }
            }
            i++
        }
    }

    private fun handleGround(c: Char) {
        when (c) {
            '\r' -> {
                grid.carriageReturn()
            }

            // LF acts as CR+LF here. POSIX terminals do this via the `ONLCR`
            // output mode by default on cooked ttys, and every tool in the
            // tree writes plain `\n` expecting that translation. Without it,
            // `ls -la`'s second row continues at the column where the first
            // row ended — which is "fucky" output.
            '\n' -> {
                grid.carriageReturn()
                grid.lineFeed()
            }

            '\b' -> {
                grid.backspace()
            }

            '\t' -> {
                grid.tab()
            }

            '' -> {}

            // BEL
            '' -> {
                state = State.ESC
            }

            else -> {
                if (c.code >= 0x20) grid.put(c)
                // Other C0 controls dropped.
            }
        }
    }

    private fun handleEsc(c: Char) {
        when (c) {
            '[' -> {
                csiParams.setLength(0)
                csiPrivate = false
                state = State.CSI
            }

            ']' -> {
                state = State.OSC
            }

            '(', ')' -> {
                state = State.CHARSET
            }

            // ignore G0/G1 designator
            '7' -> {
                grid.saveCursor()
                state = State.GROUND
            }

            '8' -> {
                grid.restoreCursor()
                state = State.GROUND
            }

            'c' -> {
                hardReset()
                state = State.GROUND
            }

            'D' -> {
                grid.lineFeed()
                state = State.GROUND
            }

            // IND
            'M' -> { // RI (reverse line feed)
                if (grid.cursorRow > 0) {
                    grid.cursorRow--
                } else { // scroll down — skip in v1
                }
                state = State.GROUND
            }

            'E' -> { // NEL
                grid.lineFeed()
                grid.carriageReturn()
                state = State.GROUND
            }

            '' -> {}

            // stay in ESC
            else -> {
                state = State.GROUND
            }
        }
    }

    private fun handleCsi(c: Char) {
        if (csiParams.isEmpty() && c == '?') {
            csiPrivate = true
            return
        }
        // Intermediates (space..'/') and parameter bytes ('0'..'?'): accumulate.
        if (c in ' '..'?') {
            csiParams.append(c)
            return
        }
        // Final byte (0x40..0x7E): dispatch.
        if (c in '@'..'~') {
            dispatchCsi(c)
            csiParams.setLength(0)
            csiPrivate = false
            state = State.GROUND
            return
        }
        // Anything else (e.g. embedded control bytes) — abort the sequence.
        state = State.GROUND
    }

    private fun handleOsc(c: Char) {
        // Consume until BEL or ESC \\ (string terminator). v1 just discards.
        if (c == '') {
            state = State.GROUND
            return
        }
        if (c == '') {
            // Next char should be '\\' — but we don't strictly enforce.
            state = State.GROUND
        }
    }

    private fun params(): IntArray {
        if (csiParams.isEmpty()) return IntArray(0)
        return csiParams
            .toString()
            .split(';')
            .map { it.toIntOrNull() ?: 0 }
            .toIntArray()
    }

    private fun dispatchCsi(final: Char) {
        if (csiPrivate) {
            dispatchPrivateMode(final)
            return
        }
        val p = params()
        when (final) {
            'A' -> {
                grid.moveBy(-nonZero(p, 0, 1), 0)
            }

            'B' -> {
                grid.moveBy(nonZero(p, 0, 1), 0)
            }

            'C' -> {
                grid.moveBy(0, nonZero(p, 0, 1))
            }

            'D' -> {
                grid.moveBy(0, -nonZero(p, 0, 1))
            }

            'E' -> {
                grid.cursorCol = 0
                grid.moveBy(nonZero(p, 0, 1), 0)
            }

            'F' -> {
                grid.cursorCol = 0
                grid.moveBy(-nonZero(p, 0, 1), 0)
            }

            'G' -> {
                grid.moveTo(grid.cursorRow, nonZero(p, 0, 1) - 1)
            }

            // CHA
            'd' -> {
                grid.moveTo(nonZero(p, 0, 1) - 1, grid.cursorCol)
            }

            // VPA
            'H', 'f' -> {
                val r = nonZero(p, 0, 1) - 1
                val cc = nonZero(p, 1, 1) - 1
                grid.moveTo(r, cc)
            }

            'J' -> {
                grid.eraseInDisplay(p.getOrElse(0) { 0 })
            }

            'K' -> {
                grid.eraseInLine(p.getOrElse(0) { 0 })
            }

            'L' -> {
                insertLines(nonZero(p, 0, 1))
            }

            'M' -> {
                deleteLines(nonZero(p, 0, 1))
            }

            'P' -> {
                deleteChars(nonZero(p, 0, 1))
            }

            '@' -> {
                insertChars(nonZero(p, 0, 1))
            }

            'X' -> {
                eraseChars(nonZero(p, 0, 1))
            }

            's' -> {
                grid.saveCursor()
            }

            'u' -> {
                grid.restoreCursor()
            }

            'm' -> {
                applySgr(p)
            }

            'n' -> { /* DSR — no reply channel in v1 */ }

            'r' -> { /* DECSTBM scroll region — v1 ignores; nano uses full screen */ }

            'h', 'l' -> { /* non-private mode set/reset — ignore */ }

            else -> { /* unknown final — forward-compatible drop */ }
        }
    }

    private fun dispatchPrivateMode(final: Char) {
        val set = (final == 'h')
        if (final != 'h' && final != 'l') return
        for (mode in params()) {
            when (mode) {
                25 -> grid.cursorVisible = set
                1049, 1047, 47 -> if (set) grid.enterAltScreen() else grid.exitAltScreen()
                // 2004 bracketed paste, 1000-1006 mouse, etc. — ignored
            }
        }
    }

    private fun applySgr(params: IntArray) {
        if (params.isEmpty()) {
            grid.currentStyle = CellStyle.Default
            return
        }
        var i = 0
        var style = grid.currentStyle
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> {
                    style = CellStyle.Default
                }

                1 -> {
                    style = style.copy(bold = true)
                }

                2 -> {
                    style = style.copy(dim = true)
                }

                4 -> {
                    style = style.copy(underline = true)
                }

                7 -> {
                    style = style.copy(inverse = true)
                }

                22 -> {
                    // Normal intensity: clears both bold and faint.
                    style = style.copy(bold = false, dim = false)
                }

                24 -> {
                    style = style.copy(underline = false)
                }

                27 -> {
                    style = style.copy(inverse = false)
                }

                in 30..37 -> {
                    style = style.copy(fg = ANSI_16[p - 30])
                }

                39 -> {
                    style = style.copy(fg = null)
                }

                in 40..47 -> {
                    style = style.copy(bg = ANSI_16[p - 40])
                }

                49 -> {
                    style = style.copy(bg = null)
                }

                in 90..97 -> {
                    style = style.copy(fg = ANSI_16_BRIGHT[p - 90])
                }

                in 100..107 -> {
                    style = style.copy(bg = ANSI_16_BRIGHT[p - 100])
                }

                38, 48 -> {
                    // Extended color: `38;5;n` (256) or `38;2;r;g;b` (true).
                    val isFg = (p == 38)
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> {
                                if (i + 2 < params.size) {
                                    val c = palette256(params[i + 2])
                                    style = if (isFg) style.copy(fg = c) else style.copy(bg = c)
                                    i += 2
                                }
                            }

                            2 -> {
                                if (i + 4 < params.size) {
                                    val c =
                                        Color(
                                            red = (params[i + 2] and 0xFF) / 255f,
                                            green = (params[i + 3] and 0xFF) / 255f,
                                            blue = (params[i + 4] and 0xFF) / 255f,
                                        )
                                    style = if (isFg) style.copy(fg = c) else style.copy(bg = c)
                                    i += 4
                                }
                            }
                        }
                    }
                }

                else -> {} // ignore unknown
            }
            i++
        }
        grid.currentStyle = style
    }

    private fun hardReset() {
        grid.eraseInDisplay(2)
        grid.moveTo(0, 0)
        grid.currentStyle = CellStyle.Default
        grid.cursorVisible = true
        if (grid.onAlt) grid.exitAltScreen()
    }

    private fun insertLines(n: Int) {
        // Simplified IL: blank current line + shift n-1 more down. v1 nano doesn't need it.
        repeat(n.coerceAtMost(grid.rows - grid.cursorRow)) { grid.eraseInLine(2) }
    }

    private fun deleteLines(n: Int) {
        repeat(n.coerceAtMost(grid.rows - grid.cursorRow)) { grid.eraseInLine(2) }
    }

    private fun deleteChars(n: Int) {
        val line = grid.row(grid.cursorRow)
        val k = n.coerceAtMost(grid.cols - grid.cursorCol)
        for (c in grid.cursorCol until grid.cols - k) line[c] = line[c + k]
        for (c in grid.cols - k until grid.cols) line[c] = Cell.Blank
    }

    private fun insertChars(n: Int) {
        val line = grid.row(grid.cursorRow)
        val k = n.coerceAtMost(grid.cols - grid.cursorCol)
        for (c in (grid.cols - 1) downTo (grid.cursorCol + k)) line[c] = line[c - k]
        for (c in grid.cursorCol until grid.cursorCol + k) line[c] = Cell.Blank
    }

    private fun eraseChars(n: Int) {
        val line = grid.row(grid.cursorRow)
        val k = n.coerceAtMost(grid.cols - grid.cursorCol)
        for (c in grid.cursorCol until grid.cursorCol + k) line[c] = Cell.Blank
    }

    private fun nonZero(
        p: IntArray,
        idx: Int,
        default: Int,
    ): Int {
        val v = p.getOrElse(idx) { default }
        return if (v == 0) default else v
    }

    private companion object {
        // Standard 16-color ANSI palette (xterm defaults).
        val ANSI_16: Array<Color> =
            arrayOf(
                Color(0xFF000000), // black
                Color(0xFFCC0000), // red
                Color(0xFF4E9A06), // green
                Color(0xFFC4A000), // yellow
                Color(0xFF3465A4), // blue
                Color(0xFF75507B), // magenta
                Color(0xFF06989A), // cyan
                Color(0xFFD3D7CF), // white
            )
        val ANSI_16_BRIGHT: Array<Color> =
            arrayOf(
                Color(0xFF555753),
                Color(0xFFEF2929),
                Color(0xFF8AE234),
                Color(0xFFFCE94F),
                Color(0xFF729FCF),
                Color(0xFFAD7FA8),
                Color(0xFF34E2E2),
                Color(0xFFEEEEEC),
            )

        fun palette256(n: Int): Color =
            when (n) {
                in 0..7 -> {
                    ANSI_16[n]
                }

                in 8..15 -> {
                    ANSI_16_BRIGHT[n - 8]
                }

                in 16..231 -> {
                    val v = n - 16
                    val r = (v / 36) % 6
                    val g = (v / 6) % 6
                    val b = v % 6

                    fun c(k: Int) = if (k == 0) 0 else (55 + k * 40)
                    Color(red = c(r) / 255f, green = c(g) / 255f, blue = c(b) / 255f)
                }

                in 232..255 -> {
                    val v = (8 + (n - 232) * 10) / 255f
                    Color(red = v, green = v, blue = v)
                }

                else -> {
                    Color.Unspecified
                }
            }
    }
}
