package com.accucodeai.kash.api.terminal

/**
 * Direct, raw-mode access to the host terminal for the rare in-process tool
 * that needs it (the only v1 consumer is `nano`). Most tools should never
 * touch this — they read `stdin`/`stdout`/`stderr` from [com.accucodeai.kash
 * .api.CommandContext] like usual. Reach for [TerminalControl] only when:
 *
 *  - the tool must paint the screen and read individual keystrokes
 *    (escape-decoded), not lines
 *  - the tool is willing to refuse politely when the host can't provide a
 *    real terminal (non-interactive shell, piped input, JS/Node fallback)
 *
 * Exposed via [com.accucodeai.kash.api.CommandContext.terminal] as a
 * nullable field. Null is the default — common in tests and in any
 * non-interactive invocation. Tools should mirror the python3 REPL gating
 * pattern: refuse on `null` with a clear stderr message and a non-zero exit.
 *
 * Lifecycle contract: a tool that calls [enterRawMode] MUST call
 * [exitRawMode] before returning (use try/finally). Likewise for
 * [useAlternateScreen]. Raw mode is process-global on JVM — the
 * implementation serializes raw-mode owners with a mutex.
 */
public interface TerminalControl {
    /**
     * Switch the terminal into raw mode: no line buffering, no echo, no
     * kernel-generated signals on Ctrl-C/Ctrl-Z (raw bytes flow through
     * [readKey] instead). Saves prior attributes; [exitRawMode] restores.
     */
    public suspend fun enterRawMode()

    /** Restore the attributes captured by [enterRawMode]. Idempotent. */
    public suspend fun exitRawMode()

    /**
     * Toggle the DEC alternate screen buffer (terminfo `smcup`/`rmcup`).
     * Tools that draw a fixed UI (editors, viewers) should enable on entry
     * and disable on exit so the user's REPL scrollback stays intact.
     */
    public suspend fun useAlternateScreen(enable: Boolean)

    /** Current terminal size in cells. Re-query each frame to handle resize. */
    public fun size(): TerminalSize

    /**
     * Read one decoded key event, suspending until a key arrives. Escape
     * sequences (`ESC [ A` etc.) are pre-parsed into [Key.Named] entries by
     * the implementation. Control characters arrive as [Key.Ctrl]. Printable
     * Unicode arrives as [Key.Char].
     */
    public suspend fun readKey(): Key

    /**
     * Write raw bytes (UTF-8) to the terminal — used for ANSI escape
     * sequences and rendered text. No interpretation, no line-ending
     * translation. Caller manages cursor placement.
     */
    public suspend fun write(s: String)

    /** Flush pending writes to the terminal. */
    public suspend fun flush()

    /**
     * Inject a synthetic key into the read stream so the current
     * [readKey] consumer sees it as if the user had pressed it. Used by
     * host-side hooks (drag-and-drop, paste fallback, accessibility tools)
     * to deliver events that didn't originate from the keyboard.
     *
     * Non-suspending: implementations enqueue into an unbounded key
     * channel (`trySend`), never blocking the caller. Default no-op —
     * only impls that own a key queue (`ComposeTerminal`,
     * `PosixTerminalControl`) override.
     */
    public fun pushKey(key: Key) {
        // No-op default; see kdoc.
    }

    /**
     * Non-blocking drain of every key currently queued for [readKey].
     * Each pulled key is shown to [keep]; keys for which `keep`
     * returns `true` are re-queued in original order (out-of-band
     * events like [Key.Paste] / [Key.PrintAbove] survive that way),
     * and the rest are discarded and returned to the caller.
     *
     * Used by hosts that own a window where no `readKey` consumer is
     * active and want to swallow type-ahead — for example the agent's
     * stream loop drains before and after each LLM stream so a user
     * who typed during the agent's response doesn't see their input
     * auto-submit when the next prompt opens.
     *
     * Non-suspending: implementations pull from their internal channel
     * via `tryReceive`. Default no-op for impls without a key queue
     * (tests, headless invocations).
     */
    public fun drainKeys(keep: (Key) -> Boolean = { false }): List<Key> = emptyList()
}

public data class TerminalSize(
    val cols: Int,
    val rows: Int,
)

/** Decoded keystroke. Wide enough for a nano-class editor; not exhaustive. */
public sealed interface Key {
    /** A printable Unicode codepoint (post-escape-decoding). */
    public data class Char(
        val codepoint: Int,
    ) : Key

    /** Ctrl-letter. [letter] is uppercase 'A'..'Z' (or '@', '[', '\\', ']', '^', '_'). */
    public data class Ctrl(
        val letter: kotlin.Char,
    ) : Key

    /** Function key F1..F12. */
    public data class Function(
        val n: Int,
    ) : Key

    /**
     * Bracketed paste — a chunk of text delivered atomically by terminals
     * that support DEC mode 2004. The [text] preserves embedded newlines
     * verbatim; line editors should insert it without per-line `isComplete`
     * interpretation (otherwise pasting a multi-line `for` loop would
     * trigger statement execution at the first line break).
     *
     * Terminals that don't enable bracketed paste send the pasted bytes
     * as individual key events; we never synthesize this variant from a
     * non-bracketed stream.
     */
    public data class Paste(
        val text: String,
    ) : Key

    /**
     * Out-of-band content the line editor should park *above* the prompt
     * without disturbing the user's in-progress input. Example: an
     * "attached file.png as [attachment 1]" banner that fires while the
     * user is mid-typing.
     *
     * Handled by the line editor (not by raw consumers), which:
     *  1. Walks back over the currently-rendered prompt + buffer.
     *  2. Erases that region.
     *  3. Writes [text] + a trailing newline so it sits in scrollback.
     *  4. Re-draws the prompt + buffer on the fresh line below.
     *
     * Writing `\r\n{text}\n` directly to stdout doesn't work: the editor's
     * prevTotalLen / prevCursorPos tracking still thinks the prompt is on
     * the old line, so its next redraw erases the wrong region and leaves
     * a ghost prompt visible. Routing through the key stream lets the
     * editor coordinate the erase with the redraw it's about to do.
     *
     * Should embed any required ANSI styling — the line editor writes
     * [text] verbatim. Don't include a trailing newline; the editor adds
     * one.
     */
    public data class PrintAbove(
        val text: String,
    ) : Key

    /** Named keys with platform-stable identity. */
    public enum class Named : Key {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        HOME,
        END,
        PGUP,
        PGDN,
        BACKSPACE,
        DELETE,
        ENTER,
        TAB,
        ESC,

        /**
         * Alt-Enter (a.k.a. Meta-Return). Distinct from [ENTER] so line
         * editors can bind it to "insert literal newline" while [ENTER]
         * still means "submit." Emitted by decoders that observe an ESC
         * byte followed by CR/LF — the conventional Alt-prefix encoding.
         *
         * Terminal-side caveats:
         *  - macOS Terminal.app: requires Preferences → Profile → Keyboard
         *    → "Use Option as Meta key" (otherwise Option-Enter is just
         *    Enter and we never see the ESC prefix).
         *  - iTerm2 / Ghostty / Alacritty default to sending ESC+CR for
         *    Option-Enter, so this lands without extra config.
         *  - tmux passes the sequence through if `xterm-keys` is on.
         */
        ALT_ENTER,
    }
}
