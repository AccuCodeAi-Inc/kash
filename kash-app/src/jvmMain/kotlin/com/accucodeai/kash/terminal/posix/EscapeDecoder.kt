package com.accucodeai.kash.terminal.posix

import com.accucodeai.kash.api.terminal.Key
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Bytes-to-[Key] state machine. Consumes from a [ReceiveChannel] of raw
 * bytes (the `rawBytes` channel that [PosixTerminalControl]'s byte-routing
 * pump feeds when raw mode is active) and emits decoded [Key] events to
 * a [SendChannel] (the keyChannel that [PosixTerminalControl] exposes via
 * `readKey()`).
 *
 * Scope (v1):
 *
 *  - Control: 0x01..0x1A → [Key.Ctrl] (excluding TAB/ENTER which are named)
 *  - 0x09 → [Key.Named.TAB]
 *  - 0x0D / 0x0A → [Key.Named.ENTER]
 *  - 0x7F / 0x08 → [Key.Named.BACKSPACE]
 *  - 0x1B alone (after [escTimeout]) → [Key.Named.ESC]
 *  - CSI `ESC [ … final-byte`:
 *      `A` / `B` / `C` / `D` → UP / DOWN / RIGHT / LEFT
 *      `H` / `1~` / `7~` → HOME
 *      `F` / `4~` / `8~` → END
 *      `2~` → INSERT (mapped to TAB stand-in; v1 has no INSERT in Key.Named)
 *      `3~` → DELETE; `5~` → PGUP; `6~` → PGDN
 *      `11~`..`15~` → F1..F5; `17~`..`21~` → F6..F10;
 *        `23~` / `24~` → F11 / F12
 *      `200~` …payload… `ESC [ 201~` → [Key.Paste]
 *  - SS3 `ESC O X` → `P`/`Q`/`R`/`S` = F1..F4; `A`..`D` = arrows (app-keypad)
 *  - Plain ASCII (`< 0x80`) → [Key.Char]
 *  - UTF-8 multi-byte → single [Key.Char] with codepoint
 *
 * Out of scope: Alt-byte (ESC + ch), mouse events, modifier keys on arrows
 * (`ESC [ 1 ; <mod> A`), Shift-Tab. Modifiers are parsed and dropped; the
 * base key still emits.
 *
 * Concurrency: [decode] is a `suspend fun` driven by one coroutine. Don't
 * call it from multiple coroutines on the same channels.
 */
internal class EscapeDecoder(
    /**
     * How long to wait for a follow-up byte after an `ESC` before treating
     * the ESC as a literal keystroke. 50 ms matches JLine; matches vim's
     * default `ttimeoutlen`.
     */
    private val escTimeout: Duration = 50.milliseconds,
) {
    /**
     * Drain [input] forever, emitting [Key] events to [output]. Returns
     * when [input] closes (typically: stdin reached EOF or was closed for
     * shutdown).
     */
    suspend fun decode(
        input: ReceiveChannel<Byte>,
        output: SendChannel<Key>,
    ) {
        try {
            while (true) {
                val b = input.receive().toInt() and 0xFF
                when {
                    b == 0x1B -> handleEsc(input, output)

                    b == 0x09 -> output.send(Key.Named.TAB)

                    // Enter (CR, 0x0D) and Ctrl-J (LF, 0x0A) arrive as
                    // separate bytes in raw mode — ICRNL translation is off.
                    // Treat them distinctly so line editors can bind Enter
                    // to "submit" while Ctrl-J inserts a literal newline.
                    // This is what Claude Code / Aider / bash-readline do
                    // when "modifyOtherKeys" isn't available: Ctrl-J becomes
                    // the universal-no-setup newline shortcut on terminals
                    // (notably macOS Terminal.app and iTerm2 default profile)
                    // that don't surface Option-Enter as a distinct sequence.
                    b == 0x0D -> output.send(Key.Named.ENTER)

                    b == 0x0A -> output.send(Key.Named.ALT_ENTER)

                    b == 0x7F || b == 0x08 -> output.send(Key.Named.BACKSPACE)

                    b in 0x01..0x1A -> output.send(Key.Ctrl('A' + (b - 1)))

                    b == 0x00 -> output.send(Key.Ctrl('@'))

                    // NUL → Ctrl-Space
                    b == 0x1C -> output.send(Key.Ctrl('\\'))

                    b == 0x1D -> output.send(Key.Ctrl(']'))

                    b == 0x1E -> output.send(Key.Ctrl('^'))

                    b == 0x1F -> output.send(Key.Ctrl('_'))

                    b < 0x80 -> output.send(Key.Char(b))

                    else -> handleUtf8(b, input, output)
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // Stdin closed — clean shutdown.
        }
    }

    private suspend fun handleEsc(
        input: ReceiveChannel<Byte>,
        output: SendChannel<Key>,
    ) {
        // Race a single follow-up byte against [escTimeout]. Null → ESC was
        // a literal press; some terminals tap ESC alone with no follow-up.
        val next: Int? =
            try {
                withTimeout(escTimeout) { input.receive().toInt() and 0xFF }
            } catch (_: TimeoutCancellationException) {
                null
            }
        if (next == null) {
            output.send(Key.Named.ESC)
            return
        }
        when (next) {
            '['.code -> {
                handleCsi(input, output)
            }

            'O'.code -> {
                handleSs3(input, output)
            }

            else -> {
                // Alt-<char>: not bound in v1. Emit ESC, then dispatch the
                // follow-up byte as a normal key by re-entering the main
                // loop's classification logic.
                output.send(Key.Named.ESC)
                dispatchPlainByte(next, output)
            }
        }
    }

    /**
     * Single-byte dispatch used when re-feeding the byte after an
     * unhandled ESC prefix. Note `ESC + CR/LF` becomes [Key.Named.ALT_ENTER]
     * rather than plain ENTER — Alt-typed Return is the conventional
     * "insert newline at cursor" affordance for terminal line editors
     * (iTerm2, Ghostty, Alacritty all send this sequence; macOS Terminal.app
     * needs "Use Option as Meta key" turned on in the profile prefs).
     */
    private suspend fun dispatchPlainByte(
        b: Int,
        output: SendChannel<Key>,
    ) {
        when {
            b == 0x09 -> output.send(Key.Named.TAB)
            b == 0x0D || b == 0x0A -> output.send(Key.Named.ALT_ENTER)
            b == 0x7F || b == 0x08 -> output.send(Key.Named.BACKSPACE)
            b in 0x01..0x1A -> output.send(Key.Ctrl('A' + (b - 1)))
            b < 0x80 -> output.send(Key.Char(b))
            // Multibyte UTF-8 after ESC is exceedingly rare (would mean an
            // Alt-typed non-ASCII char); drop the bytes silently.
        }
    }

    /**
     * Parse a CSI (Control Sequence Introducer) sequence: `ESC [ params final`.
     * Params are digits and `;`; final is `0x40..0x7E`. After dispatch the
     * decoder returns to the main loop.
     */
    private suspend fun handleCsi(
        input: ReceiveChannel<Byte>,
        output: SendChannel<Key>,
    ) {
        val params = StringBuilder()
        var final: Int
        while (true) {
            val b = input.receive().toInt() and 0xFF
            if (b in 0x30..0x3F) {
                // parameter byte (digit / ; / ? / etc.)
                params.append(b.toChar())
            } else if (b in 0x20..0x2F) {
                // intermediate byte — rare; collect but treat as part of params
                params.append(b.toChar())
            } else {
                final = b
                break
            }
        }
        dispatchCsi(params.toString(), final, input, output)
    }

    private suspend fun dispatchCsi(
        params: String,
        final: Int,
        input: ReceiveChannel<Byte>,
        output: SendChannel<Key>,
    ) {
        // Strip modifier suffix `;<mod>` for v1 — we don't track Shift /
        // Ctrl / Alt modifiers on arrows yet. `1;2A` (shift-up) becomes
        // just UP. The leading "1" is the default-key parameter for some
        // sequences; we tolerate it.
        val leading = params.substringBefore(';').trimStart('?')

        when (final) {
            'A'.code -> output.send(Key.Named.UP)

            'B'.code -> output.send(Key.Named.DOWN)

            'C'.code -> output.send(Key.Named.RIGHT)

            'D'.code -> output.send(Key.Named.LEFT)

            'H'.code -> output.send(Key.Named.HOME)

            'F'.code -> output.send(Key.Named.END)

            'Z'.code -> output.send(Key.Named.TAB)

            // Shift-Tab → fallback to TAB
            '~'.code -> dispatchTilde(leading, input, output)
        }
        // Unknown finals: drop silently. xterm publishes ~50 of them;
        // adding more here is mechanical when they come up.
    }

    private suspend fun dispatchTilde(
        leading: String,
        input: ReceiveChannel<Byte>,
        output: SendChannel<Key>,
    ) {
        when (leading) {
            "1", "7" -> output.send(Key.Named.HOME)

            "2" -> Unit

            // INSERT — v1 has no INSERT in Key.Named, drop
            "3" -> output.send(Key.Named.DELETE)

            "4", "8" -> output.send(Key.Named.END)

            "5" -> output.send(Key.Named.PGUP)

            "6" -> output.send(Key.Named.PGDN)

            "11" -> output.send(Key.Function(1))

            "12" -> output.send(Key.Function(2))

            "13" -> output.send(Key.Function(3))

            "14" -> output.send(Key.Function(4))

            "15" -> output.send(Key.Function(5))

            "17" -> output.send(Key.Function(6))

            "18" -> output.send(Key.Function(7))

            "19" -> output.send(Key.Function(8))

            "20" -> output.send(Key.Function(9))

            "21" -> output.send(Key.Function(10))

            "23" -> output.send(Key.Function(11))

            "24" -> output.send(Key.Function(12))

            "200" -> handleBracketedPaste(input, output)
            // "201" is the paste terminator — only reached if we see an
            // unsolicited 201 outside paste mode. Drop.
        }
    }

    /**
     * Collect bytes until the `ESC [ 201 ~` paste-end terminator. Emits one
     * [Key.Paste] with the UTF-8-decoded payload. Embedded ESC bytes that
     * aren't followed by `[201~` are kept verbatim — vim's `paste_format`
     * style.
     */
    private suspend fun handleBracketedPaste(
        input: ReceiveChannel<Byte>,
        output: SendChannel<Key>,
    ) {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = input.receive()
            if (b == 0x1B.toByte()) {
                // Peek for `[201~`. If it matches, we're done; else push back
                // verbatim as part of the paste payload.
                val b1 = input.receive()
                val b2 = if (b1 == '['.code.toByte()) input.receive() else null
                val b3 = if (b2 == '2'.code.toByte()) input.receive() else null
                val b4 = if (b3 == '0'.code.toByte()) input.receive() else null
                val b5 = if (b4 == '1'.code.toByte()) input.receive() else null
                if (b5 == '~'.code.toByte()) {
                    // Successfully matched ESC [ 201 ~ — paste ends.
                    break
                }
                // Mismatch: push the consumed bytes back into the payload.
                // b1 is always present (unconditional receive); b2..b5 are
                // each gated on the prior byte matching, so a null at any
                // position means we never read the rest either.
                bytes += 0x1B.toByte()
                bytes += b1
                if (b2 != null) bytes += b2
                if (b3 != null) bytes += b3
                if (b4 != null) bytes += b4
                if (b5 != null) bytes += b5
            } else {
                bytes += b
            }
        }
        val text = bytes.toByteArray().decodeToString()
        output.send(Key.Paste(text))
    }

    /**
     * SS3 sequences: `ESC O X`. Used by terminals in DEC application keypad
     * mode. We treat them as arrow / F-key equivalents to CSI.
     */
    private suspend fun handleSs3(
        input: ReceiveChannel<Byte>,
        output: SendChannel<Key>,
    ) {
        val b = input.receive().toInt() and 0xFF
        when (b) {
            'P'.code -> output.send(Key.Function(1))
            'Q'.code -> output.send(Key.Function(2))
            'R'.code -> output.send(Key.Function(3))
            'S'.code -> output.send(Key.Function(4))
            'A'.code -> output.send(Key.Named.UP)
            'B'.code -> output.send(Key.Named.DOWN)
            'C'.code -> output.send(Key.Named.RIGHT)
            'D'.code -> output.send(Key.Named.LEFT)
            'H'.code -> output.send(Key.Named.HOME)
            'F'.code -> output.send(Key.Named.END)
        }
    }

    /**
     * UTF-8 multi-byte decode. [lead] is the first byte (already read);
     * we read continuation bytes from [input] and emit one [Key.Char] with
     * the assembled codepoint.
     *
     * Lead byte high-bit pattern → continuation count:
     *  - `110x_xxxx` → 1 continuation (2-byte rune, U+0080..U+07FF)
     *  - `1110_xxxx` → 2 continuations (3-byte rune, U+0800..U+FFFF)
     *  - `1111_0xxx` → 3 continuations (4-byte rune, U+10000..U+10FFFF)
     */
    private suspend fun handleUtf8(
        lead: Int,
        input: ReceiveChannel<Byte>,
        output: SendChannel<Key>,
    ) {
        val (extra, mask) =
            when {
                lead and 0b1110_0000 == 0b1100_0000 -> 1 to 0b0001_1111
                lead and 0b1111_0000 == 0b1110_0000 -> 2 to 0b0000_1111
                lead and 0b1111_1000 == 0b1111_0000 -> 3 to 0b0000_0111
                else -> return // malformed lead byte — drop silently
            }
        var cp = lead and mask
        repeat(extra) {
            val c = input.receive().toInt() and 0xFF
            // Continuation bytes are `10xx_xxxx`. Tolerate broken sequences
            // by accepting whatever bits are there.
            cp = (cp shl 6) or (c and 0b0011_1111)
        }
        output.send(Key.Char(cp))
    }
}
