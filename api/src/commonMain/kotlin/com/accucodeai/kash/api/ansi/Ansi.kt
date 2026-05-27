package com.accucodeai.kash.api.ansi

import com.accucodeai.kash.api.CommandContext

/**
 * Three-state color toggle a tool exposes via `--color[=WHEN]`. Matches the
 * GNU coreutils convention (`always|auto|never`); `tty` is accepted as an
 * alias of `auto`.
 */
public enum class ColorMode {
    AUTO,
    ALWAYS,
    NEVER,
    ;

    public companion object {
        /** Parse a `--color=WHEN` value. Returns null for unrecognized input. */
        public fun parse(value: String?): ColorMode? =
            when (value?.lowercase()) {
                null, "", "auto", "tty", "if-tty" -> AUTO
                "always", "yes", "force" -> ALWAYS
                "never", "no", "none" -> NEVER
                else -> null
            }
    }
}

/**
 * Wraps a string in an SGR escape sequence when [on] is true; returns the
 * bare string otherwise. Stateless and cheap to construct; instantiate per
 * command invocation from [Ansi.stylerFor].
 *
 * `style("foo", FG_RED, BOLD)` → ESC `[31;1mfoo` ESC `[0m` (or `"foo"`).
 */
public class AnsiStyler internal constructor(
    public val on: Boolean,
) {
    public fun style(
        text: String,
        vararg codes: Int,
    ): String {
        if (!on || codes.isEmpty()) return text
        return buildString {
            append(CSI)
            for ((i, c) in codes.withIndex()) {
                if (i > 0) append(';')
                append(c)
            }
            append('m')
            append(text)
            append(RESET_SEQ)
        }
    }

    /**
     * Variant taking an already-formed SGR string like `"01;31"` — what
     * `LS_COLORS` / `GREP_COLORS` parsers produce. Empty [sgr] is a no-op
     * (matches GNU convention: an empty value disables coloring for that
     * key without wrapping in escape codes).
     */
    public fun style(
        text: String,
        sgr: String,
    ): String {
        if (!on || sgr.isEmpty()) return text
        return buildString {
            append(CSI)
            append(sgr)
            append('m')
            append(text)
            append(RESET_SEQ)
        }
    }

    private companion object {
        const val CSI: String = "\u001B["
        const val RESET_SEQ: String = "\u001B[0m"
    }
}

public object Ansi {
    public enum class Stream { STDOUT, STDERR }

    /**
     * Build a styler for [ctx]. Decision order (highest precedence first):
     *  1. explicit [mode] (`--color=always|never`)
     *  2. `NO_COLOR` env set to anything non-empty → off
     *  3. `TERM=dumb` → off
     *  4. `CLICOLOR_FORCE` set to anything non-zero → on
     *  5. `CLICOLOR=0` → off
     *  6. else the per-stream tty flag on [ctx]
     */
    public fun stylerFor(
        ctx: CommandContext,
        stream: Stream = Stream.STDOUT,
        mode: ColorMode = ColorMode.AUTO,
    ): AnsiStyler = AnsiStyler(decide(ctx, stream, mode))

    private fun decide(
        ctx: CommandContext,
        s: Stream,
        m: ColorMode,
    ): Boolean {
        if (m == ColorMode.ALWAYS) return true
        if (m == ColorMode.NEVER) return false
        if (!ctx.process.env["NO_COLOR"].isNullOrEmpty()) return false
        if (ctx.process.env["TERM"] == "dumb") return false
        val force = ctx.process.env["CLICOLOR_FORCE"]
        if (!force.isNullOrEmpty() && force != "0") return true
        if (ctx.process.env["CLICOLOR"] == "0") return false
        return when (s) {
            Stream.STDOUT -> ctx.process.isTty(1)
            Stream.STDERR -> ctx.process.isTty(2)
        }
    }
}

/**
 * SGR (Select Graphic Rendition) codes. Only the codes used by kash tools
 * today are named. Extend rather than reach for a library — these are bytes,
 * not abstractions.
 */
public object Sgr {
    public const val RESET: Int = 0
    public const val BOLD: Int = 1
    public const val DIM: Int = 2
    public const val UNDERLINE: Int = 4
    public const val REVERSE: Int = 7

    public const val FG_BLACK: Int = 30
    public const val FG_RED: Int = 31
    public const val FG_GREEN: Int = 32
    public const val FG_YELLOW: Int = 33
    public const val FG_BLUE: Int = 34
    public const val FG_MAGENTA: Int = 35
    public const val FG_CYAN: Int = 36
    public const val FG_WHITE: Int = 37

    public const val BG_BLACK: Int = 40

    public const val FG_BRIGHT_BLACK: Int = 90
    public const val FG_BRIGHT_RED: Int = 91
    public const val FG_BRIGHT_GREEN: Int = 92
    public const val FG_BRIGHT_YELLOW: Int = 93
    public const val FG_BRIGHT_BLUE: Int = 94
    public const val FG_BRIGHT_MAGENTA: Int = 95
    public const val FG_BRIGHT_CYAN: Int = 96
    public const val FG_BRIGHT_WHITE: Int = 97
}
