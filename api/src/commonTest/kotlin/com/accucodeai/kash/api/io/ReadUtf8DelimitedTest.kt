package com.accucodeai.kash.api.io

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [readUtf8LineOrNull] / [readUtf8DelimitedOrNull]. These pin the
 * UTF-8 semantics the function names promise: bytes are accumulated and
 * decoded as UTF-8 at the delimiter, not converted one-at-a-time through
 * `b.toInt().toChar()` (which sign-extends and corrupts every byte ≥ 0x80).
 *
 * Both the suspend [SuspendSource] overload (via [asSuspendSource]) and the
 * sync [kotlinx.io.RawSource] overload (a [Buffer] is a `RawSource`) are
 * exercised — the two had independent implementations and the sync one
 * regressed after the suspend one was fixed.
 */
class ReadUtf8DelimitedTest {
    private fun bufferOf(vararg bytes: Int): Buffer {
        val b = Buffer()
        b.write(bytes.map { it.toByte() }.toByteArray())
        return b
    }

    // ---- sync RawSource overload (Buffer is a RawSource) -------------------

    @Test fun syncAsciiLineRoundTrips() {
        val src = bufferOf(0x68, 0x69, 0x0A) // "hi\n"
        assertEquals("hi", src.readUtf8LineOrNull())
        assertNull(src.readUtf8LineOrNull())
    }

    @Test fun syncTwoByteUtf8CharSurvives() {
        // "é\n" — U+00E9 is 0xC3 0xA9. The sign-extending bug turned this into U+FFC3.
        assertEquals("é", bufferOf(0xC3, 0xA9, 0x0A).readUtf8LineOrNull())
    }

    @Test fun syncThreeAndFourByteUtf8CharsSurvive() {
        assertEquals("中", bufferOf(0xE4, 0xB8, 0xAD, 0x0A).readUtf8LineOrNull())
        assertEquals("😀", bufferOf(0xF0, 0x9F, 0x98, 0x80, 0x0A).readUtf8LineOrNull())
    }

    @Test fun syncMixedAndTrailingNoNewline() {
        assertEquals("café", bufferOf(0x63, 0x61, 0x66, 0xC3, 0xA9, 0x0A).readUtf8LineOrNull())
        // "héllo" with no trailing newline — returned at EOF, then null.
        val src = bufferOf(0x68, 0xC3, 0xA9, 0x6C, 0x6C, 0x6F)
        assertEquals("héllo", src.readUtf8LineOrNull())
        assertNull(src.readUtf8LineOrNull())
    }

    // ---- suspend SuspendSource overload ------------------------------------

    @Test fun asciiLineRoundTrips() =
        runTest {
            // Sanity: pure-ASCII path must keep working.
            val src = bufferOf(0x68, 0x69, 0x0A).asSuspendSource() // "hi\n"
            assertEquals("hi", src.readUtf8LineOrNull())
            assertNull(src.readUtf8LineOrNull())
        }

    @Test fun twoByteUtf8CharSurvivesRoundTrip() =
        runTest {
            // "é\n" — U+00E9 is 0xC3 0xA9 in UTF-8.
            val src = bufferOf(0xC3, 0xA9, 0x0A).asSuspendSource()
            assertEquals("é", src.readUtf8LineOrNull())
        }

    @Test fun threeByteUtf8CharSurvivesRoundTrip() =
        runTest {
            // "中\n" — U+4E2D is 0xE4 0xB8 0xAD in UTF-8.
            val src = bufferOf(0xE4, 0xB8, 0xAD, 0x0A).asSuspendSource()
            assertEquals("中", src.readUtf8LineOrNull())
        }

    @Test fun fourByteUtf8CharSurvivesRoundTrip() =
        runTest {
            // "😀\n" — U+1F600 is 0xF0 0x9F 0x98 0x80 in UTF-8.
            val src = bufferOf(0xF0, 0x9F, 0x98, 0x80, 0x0A).asSuspendSource()
            assertEquals("😀", src.readUtf8LineOrNull())
        }

    @Test fun mixedAsciiAndNonAsciiOnSameLine() =
        runTest {
            // "café\n" — "caf" ASCII, then 0xC3 0xA9 for é, then \n.
            val src = bufferOf(0x63, 0x61, 0x66, 0xC3, 0xA9, 0x0A).asSuspendSource()
            assertEquals("café", src.readUtf8LineOrNull())
        }

    @Test fun multipleUtf8LinesIterateCleanly() =
        runTest {
            // "α\nβ\n" — U+03B1 = 0xCE 0xB1, U+03B2 = 0xCE 0xB2.
            val src = bufferOf(0xCE, 0xB1, 0x0A, 0xCE, 0xB2, 0x0A).asSuspendSource()
            assertEquals("α", src.readUtf8LineOrNull())
            assertEquals("β", src.readUtf8LineOrNull())
            assertNull(src.readUtf8LineOrNull())
        }

    @Test fun trailingLineWithoutNewline() =
        runTest {
            // "héllo" without trailing \n — should return the line at EOF.
            val src = bufferOf(0x68, 0xC3, 0xA9, 0x6C, 0x6C, 0x6F).asSuspendSource()
            assertEquals("héllo", src.readUtf8LineOrNull())
            assertNull(src.readUtf8LineOrNull())
        }
}
