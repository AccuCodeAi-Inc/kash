package com.accucodeai.kash.tools.vi

/** ANSI escape constants. Mirrors nano's Ansi helper; duplicated to keep modules independent. */
internal object Ansi {
    private const val ESC: String = ""

    const val CLEAR_SCREEN: String = "$ESC[2J"
    const val CURSOR_HOME: String = "$ESC[H"
    const val CLEAR_TO_EOL: String = "$ESC[K"
    const val RESET: String = "$ESC[0m"
    const val INVERSE: String = "$ESC[7m"
    const val HIDE_CURSOR: String = "$ESC[?25l"
    const val SHOW_CURSOR: String = "$ESC[?25h"

    /** 1-indexed row, col per CSI convention. */
    fun moveTo(
        row: Int,
        col: Int,
    ): String = "$ESC[$row;${col}H"
}
