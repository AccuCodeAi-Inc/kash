package com.accucodeai.kash.tools.tput

// Terminfo capability table for `tput`. We synthesize ANSI/VT100 escape
// sequences directly rather than load a system terminfo database -- the
// host may not have one, and we always target an ANSI-like target.
//
// The leading ESC byte (0x1B) is encoded as the literal U+001B character.
// Verified with od -c.

/** ESC (0x1B). The single literal byte every ANSI sequence starts with. */
internal const val ESC: String = ""

/** Static (no-argument) capabilities, mapping name → output string. */
internal val STATIC_CAPS: Map<String, String> =
    mapOf(
        // Clear screen and home cursor.
        "clear" to "$ESC[2J$ESC[H",
        "cl" to "$ESC[2J$ESC[H",
        // Erase to end of line / screen.
        "el" to "$ESC[K",
        "ce" to "$ESC[K",
        "ed" to "$ESC[J",
        "cd" to "$ESC[J",
        // Cursor positioning.
        "home" to "$ESC[H",
        "ho" to "$ESC[H",
        // SGR attributes.
        "bold" to "$ESC[1m",
        "md" to "$ESC[1m",
        "dim" to "$ESC[2m",
        "mh" to "$ESC[2m",
        "smul" to "$ESC[4m",
        "us" to "$ESC[4m",
        "rmul" to "$ESC[24m",
        "ue" to "$ESC[24m",
        "smso" to "$ESC[7m",
        "so" to "$ESC[7m",
        "rmso" to "$ESC[27m",
        "se" to "$ESC[27m",
        "rev" to "$ESC[7m",
        "mr" to "$ESC[7m",
        "blink" to "$ESC[5m",
        "mb" to "$ESC[5m",
        "invis" to "$ESC[8m",
        "mk" to "$ESC[8m",
        "sgr0" to "$ESC[0m",
        "me" to "$ESC[0m",
        "reset" to "$ESC[0m",
        "op" to "$ESC[39;49m",
        // Cursor visibility.
        "civis" to "$ESC[?25l",
        "vi" to "$ESC[?25l",
        "cnorm" to "$ESC[?25h",
        "ve" to "$ESC[?25h",
        // Alternate screen.
        "smcup" to "$ESC[?1049h",
        "ti" to "$ESC[?1049h",
        "rmcup" to "$ESC[?1049l",
        "te" to "$ESC[?1049l",
        // Save / restore cursor.
        "sc" to "${ESC}7",
        "rc" to "${ESC}8",
        // Bell.
        "bel" to "",
        // Newline / carriage return.
        "cr" to "\r",
        "nel" to "\r\n",
    )

/** Boolean capabilities — supported (exit 0). */
internal val BOOL_CAPS: Set<String> =
    setOf(
        "am", // auto-margin
        "bce", // back-color erase
        "msgr", // safe to move in standout mode
        "xenl", // newline ignored after 80 cols
        "hs", // has status line — false, but kept for completeness; see UNSUPPORTED_BOOLS
    )

/** Boolean capabilities we explicitly report as unsupported (exit 1). */
internal val UNSUPPORTED_BOOLS: Set<String> =
    setOf(
        "chts", // cursor is hard to see
    )

/**
 * Parametric capabilities. Returns null if the capability is unknown or
 * the parameters don't fit. ANSI sequences are 1-indexed for cursor moves
 * — `tput cup 5 10` becomes ESC[6;11H.
 */
internal fun renderParametric(
    name: String,
    args: List<String>,
): String? {
    fun intArg(idx: Int): Int? = args.getOrNull(idx)?.toIntOrNull()
    return when (name) {
        "cup", "cm" -> {
            val r = intArg(0) ?: return null
            val c = intArg(1) ?: return null
            "$ESC[${r + 1};${c + 1}H"
        }

        "hpa", "ch" -> {
            val c = intArg(0) ?: return null
            "$ESC[${c + 1}G"
        }

        "vpa", "cv" -> {
            val r = intArg(0) ?: return null
            "$ESC[${r + 1}d"
        }

        "cub", "LE" -> {
            intArg(0)?.let { "$ESC[${it}D" }
        }

        "cuf", "RI" -> {
            intArg(0)?.let { "$ESC[${it}C" }
        }

        "cuu", "UP" -> {
            intArg(0)?.let { "$ESC[${it}A" }
        }

        "cud", "DO" -> {
            intArg(0)?.let { "$ESC[${it}B" }
        }

        "setaf", "AF" -> {
            intArg(0)?.let { renderSetaf(it) }
        }

        "setab", "AB" -> {
            intArg(0)?.let { renderSetab(it) }
        }

        "setf" -> {
            intArg(0)?.let { "$ESC[3${it.coerceIn(0, 7)}m" }
        }

        "setb" -> {
            intArg(0)?.let { "$ESC[4${it.coerceIn(0, 7)}m" }
        }

        "dch" -> {
            intArg(0)?.let { "$ESC[${it}P" }
        }

        "ich" -> {
            intArg(0)?.let { "$ESC[$it@" }
        }

        "il" -> {
            intArg(0)?.let { "$ESC[${it}L" }
        }

        "dl" -> {
            intArg(0)?.let { "$ESC[${it}M" }
        }

        else -> {
            null
        }
    }
}

private fun renderSetaf(n: Int): String =
    when {
        n in 0..7 -> "$ESC[3${n}m"
        n in 8..15 -> "$ESC[9${n - 8}m"
        n in 16..255 -> "$ESC[38;5;${n}m"
        else -> "$ESC[39m"
    }

private fun renderSetab(n: Int): String =
    when {
        n in 0..7 -> "$ESC[4${n}m"
        n in 8..15 -> "$ESC[10${n - 8}m"
        n in 16..255 -> "$ESC[48;5;${n}m"
        else -> "$ESC[49m"
    }

/** Numeric capability resolution. Returns the number, or null if unknown. */
internal fun renderNumeric(
    name: String,
    cols: Int,
    lines: Int,
    colors: Int = 8,
): Int? =
    when (name) {
        "cols", "co" -> cols

        "lines", "li" -> lines

        "colors", "Co" -> colors

        "it" -> 8

        // tab width
        else -> null
    }

/** True iff [name] is the name of a numeric capability. */
internal fun isNumeric(name: String): Boolean = name in setOf("cols", "co", "lines", "li", "colors", "Co", "it")

/** True iff [name] is the name of a parametric (argument-taking) capability. */
internal fun isParametric(name: String): Boolean =
    name in
        setOf(
            "cup",
            "cm",
            "hpa",
            "ch",
            "vpa",
            "cv",
            "cub",
            "LE",
            "cuf",
            "RI",
            "cuu",
            "UP",
            "cud",
            "DO",
            "setaf",
            "AF",
            "setab",
            "AB",
            "setf",
            "setb",
            "dch",
            "ich",
            "il",
            "dl",
        )
