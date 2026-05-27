package com.accucodeai.kash.api.io

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [readUtf8LineOrNull] / [readUtf8DelimitedOrNull]. Despite the
 * function name claiming UTF-8, the current implementation converts each
 * byte through `b.toInt().toChar()`, which sign-extends and masks to the
 * low 16 bits — corrupting every byte ≥ 0x80. These tests pin the
 * intended UTF-8 semantics so a future fix can make them pass.
 */
class ReadUtf8DelimitedTest {
    private fun bufferOf(vararg bytes: Int): Buffer {
        val b = Buffer()
        b.write(bytes.map { it.toByte() }.toByteArray())
        return b
    }

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
