package com.accucodeai.kash.terminal.posix

import com.accucodeai.kash.api.terminal.Key
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pure-Kotlin tests of [EscapeDecoder]. Feeds canned byte arrays through a
 * `Channel<Byte>`, collects the resulting `Key` events from a `Channel<Key>`,
 * asserts against the expected sequence. No PTY, no real terminal.
 */
class EscapeDecoderTest {
    /**
     * Decode [bytes] and collect every emitted [Key], returning when [count]
     * keys have arrived or the byte source has drained for [drainTimeout].
     */
    private fun decodeBytes(
        bytes: ByteArray,
        count: Int,
        drainTimeout: kotlin.time.Duration = 200.milliseconds,
        escTimeout: kotlin.time.Duration = 10.milliseconds,
    ): List<Key> {
        val inCh = Channel<Byte>(Channel.UNLIMITED)
        val outCh = Channel<Key>(Channel.UNLIMITED)
        for (b in bytes) inCh.trySend(b)
        return runBlocking {
            val decoder = EscapeDecoder(escTimeout = escTimeout)
            val job = async { decoder.decode(inCh, outCh) }
            val collected = mutableListOf<Key>()
            try {
                withTimeout(drainTimeout) {
                    repeat(count) { collected += outCh.receive() }
                }
            } catch (_: Throwable) {
                // Fewer keys than expected or decode bug — return what we have.
            }
            inCh.close()
            job.cancel()
            collected
        }
    }

    private fun decodeOne(bytes: ByteArray): Key {
        val keys = decodeBytes(bytes, count = 1)
        if (keys.isEmpty()) fail("decoder produced no Key from ${bytes.toList()}")
        return keys.first()
    }

    // ---- single bytes ----

    @Test fun asciiCharIsKeyChar() =
        runBlocking {
            assertEquals(Key.Char('a'.code), decodeOne(byteArrayOf('a'.code.toByte())))
        }

    @Test fun ctrlALetters() =
        runBlocking {
            assertEquals(Key.Ctrl('A'), decodeOne(byteArrayOf(0x01)))
            assertEquals(Key.Ctrl('Z'), decodeOne(byteArrayOf(0x1A)))
            assertEquals(Key.Ctrl('B'), decodeOne(byteArrayOf(0x02)))
        }

    @Test fun specialControls() =
        runBlocking {
            assertEquals(Key.Ctrl('@'), decodeOne(byteArrayOf(0x00)))
            assertEquals(Key.Ctrl('\\'), decodeOne(byteArrayOf(0x1C)))
            assertEquals(Key.Ctrl(']'), decodeOne(byteArrayOf(0x1D)))
            assertEquals(Key.Ctrl('^'), decodeOne(byteArrayOf(0x1E)))
            assertEquals(Key.Ctrl('_'), decodeOne(byteArrayOf(0x1F)))
        }

    @Test fun tabAndEnter() =
        runBlocking {
            assertEquals(Key.Named.TAB, decodeOne(byteArrayOf(0x09)))
            // CR (0x0D) submits → ENTER. LF (0x0A / Ctrl-J) inserts a literal
            // newline in the multi-line buffer, so the decoder maps it to
            // ALT_ENTER (the line editor's "newline" key) — not ENTER.
            assertEquals(Key.Named.ENTER, decodeOne(byteArrayOf(0x0D)))
            assertEquals(Key.Named.ALT_ENTER, decodeOne(byteArrayOf(0x0A)))
        }

    @Test fun backspaceVariants() =
        runBlocking {
            assertEquals(Key.Named.BACKSPACE, decodeOne(byteArrayOf(0x7F)))
            assertEquals(Key.Named.BACKSPACE, decodeOne(byteArrayOf(0x08)))
        }

    // ---- ESC handling ----

    @Test fun loneEscBecomesNamedEscAfterTimeout() =
        runBlocking {
            assertEquals(Key.Named.ESC, decodeOne(byteArrayOf(0x1B)))
        }

    @Test fun escFollowedByLetterIsAltKey() =
        runBlocking {
            // Alt-a: we emit ESC then 'a' (no Alt binding in v1).
            val keys = decodeBytes(byteArrayOf(0x1B, 'a'.code.toByte()), count = 2)
            assertEquals(listOf(Key.Named.ESC, Key.Char('a'.code)), keys)
        }

    // ---- CSI arrows / Home / End / PgUp / PgDn / Delete ----

    @Test fun csiArrows() =
        runBlocking {
            assertEquals(Key.Named.UP, decodeOne(csi("A")))
            assertEquals(Key.Named.DOWN, decodeOne(csi("B")))
            assertEquals(Key.Named.RIGHT, decodeOne(csi("C")))
            assertEquals(Key.Named.LEFT, decodeOne(csi("D")))
        }

    @Test fun csiHomeAndEnd() =
        runBlocking {
            assertEquals(Key.Named.HOME, decodeOne(csi("H")))
            assertEquals(Key.Named.END, decodeOne(csi("F")))
            assertEquals(Key.Named.HOME, decodeOne(csi("1~")))
            assertEquals(Key.Named.HOME, decodeOne(csi("7~")))
            assertEquals(Key.Named.END, decodeOne(csi("4~")))
            assertEquals(Key.Named.END, decodeOne(csi("8~")))
        }

    @Test fun csiPgUpPgDnDelete() =
        runBlocking {
            assertEquals(Key.Named.PGUP, decodeOne(csi("5~")))
            assertEquals(Key.Named.PGDN, decodeOne(csi("6~")))
            assertEquals(Key.Named.DELETE, decodeOne(csi("3~")))
        }

    @Test fun csiArrowsWithModifiersStripModifier() =
        runBlocking {
            // ESC [ 1 ; 2 A = Shift-Up. v1 ignores the modifier, emits UP.
            assertEquals(Key.Named.UP, decodeOne(csi("1;2A")))
            assertEquals(Key.Named.DOWN, decodeOne(csi("1;5B")))
        }

    // ---- CSI function keys ----

    @Test fun csiFunctionKeys() =
        runBlocking {
            assertEquals(Key.Function(1), decodeOne(csi("11~")))
            assertEquals(Key.Function(5), decodeOne(csi("15~")))
            assertEquals(Key.Function(6), decodeOne(csi("17~")))
            assertEquals(Key.Function(10), decodeOne(csi("21~")))
            assertEquals(Key.Function(11), decodeOne(csi("23~")))
            assertEquals(Key.Function(12), decodeOne(csi("24~")))
        }

    // ---- SS3 (application keypad mode) ----

    @Test fun ss3FunctionKeys() =
        runBlocking {
            assertEquals(Key.Function(1), decodeOne(ss3("P")))
            assertEquals(Key.Function(2), decodeOne(ss3("Q")))
            assertEquals(Key.Function(3), decodeOne(ss3("R")))
            assertEquals(Key.Function(4), decodeOne(ss3("S")))
        }

    @Test fun ss3Arrows() =
        runBlocking {
            assertEquals(Key.Named.UP, decodeOne(ss3("A")))
            assertEquals(Key.Named.DOWN, decodeOne(ss3("B")))
            assertEquals(Key.Named.RIGHT, decodeOne(ss3("C")))
            assertEquals(Key.Named.LEFT, decodeOne(ss3("D")))
        }

    // ---- UTF-8 multi-byte ----

    @Test fun utf8TwoByteRune() =
        runBlocking {
            // é = U+00E9 = 0xC3 0xA9
            assertEquals(Key.Char(0xE9), decodeOne(byteArrayOf(0xC3.toByte(), 0xA9.toByte())))
        }

    @Test fun utf8ThreeByteRune() =
        runBlocking {
            // 漢 = U+6F22 = 0xE6 0xBC 0xA2
            assertEquals(Key.Char(0x6F22), decodeOne(byteArrayOf(0xE6.toByte(), 0xBC.toByte(), 0xA2.toByte())))
        }

    @Test fun utf8FourByteRune() =
        runBlocking {
            // 😀 = U+1F600 = 0xF0 0x9F 0x98 0x80
            assertEquals(
                Key.Char(0x1F600),
                decodeOne(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte())),
            )
        }

    // ---- bracketed paste ----

    @Test fun bracketedPasteCollectsPayload() =
        runBlocking {
            val bytes = csi("200~") + "hello".encodeToByteArray() + csi("201~")
            val key = decodeOne(bytes)
            assertTrue(key is Key.Paste, "expected Paste, got $key")
            assertEquals("hello", key.text)
        }

    @Test fun bracketedPastePreservesNewlines() =
        runBlocking {
            val bytes = csi("200~") + "line1\nline2\nline3".encodeToByteArray() + csi("201~")
            val key = decodeOne(bytes)
            assertEquals("line1\nline2\nline3", (key as Key.Paste).text)
        }

    @Test fun bracketedPasteWithEmbeddedEscThatIsNotTerminatorIsPreserved() =
        runBlocking {
            // ESC followed by garbage (not [201~) should be retained verbatim
            // in the paste payload.
            val payload = byteArrayOf(0x68, 0x69, 0x1B, 0x78) // "hi" ESC 'x'
            val bytes = csi("200~") + payload + csi("201~")
            val key = decodeOne(bytes)
            assertTrue(key is Key.Paste)
            // The exact payload bytes round-trip (UTF-8 decoded).
            assertEquals(String(intArrayOf(0x68, 0x69, 0x1B, 0x78), 0, 4), key.text)
        }

    // ---- sequence of keys ----

    @Test fun mixedTypingSession() =
        runBlocking {
            // User types "hi", left-arrow, backspace, enter. CR for enter.
            val bytes = "hi".encodeToByteArray() + csi("D") + byteArrayOf(0x7F) + byteArrayOf(0x0D)
            val keys = decodeBytes(bytes, count = 5)
            assertEquals(
                listOf(Key.Char('h'.code), Key.Char('i'.code), Key.Named.LEFT, Key.Named.BACKSPACE, Key.Named.ENTER),
                keys,
            )
        }

    @Test fun unknownCsiFinalIsDroppedSilently() =
        runBlocking {
            // ESC [ 9 z — `z` isn't a final we handle. Dropped, no Key emitted.
            // Verify by checking that a *following* known key still comes through.
            val bytes = csi("9z") + byteArrayOf('x'.code.toByte())
            val keys = decodeBytes(bytes, count = 1)
            assertEquals(listOf(Key.Char('x'.code)), keys)
        }

    @Test fun emptyInputProducesNoKeys() =
        runBlocking {
            val keys = decodeBytes(byteArrayOf(), count = 0, drainTimeout = 50.milliseconds)
            assertEquals(emptyList(), keys)
        }

    // ---- helpers ----

    /** Build `ESC [ params` byte array, e.g. csi("11~") = ESC [ 1 1 ~. */
    private fun csi(s: String): ByteArray = byteArrayOf(0x1B, '['.code.toByte()) + s.encodeToByteArray()

    /** Build `ESC O X` byte array. */
    private fun ss3(s: String): ByteArray = byteArrayOf(0x1B, 'O'.code.toByte()) + s.encodeToByteArray()
}
