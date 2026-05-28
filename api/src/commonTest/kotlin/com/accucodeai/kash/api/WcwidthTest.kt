package com.accucodeai.kash.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [codepointWidth] — the supplementary-plane-aware width used by the web
 * `TerminalGrid` when it coalesces a surrogate pair into a single cell. The
 * emoji = 2 contract is what stops 😀 from being laid out in one column and
 * clipping its neighbour.
 */
class WcwidthTest {
    @Test fun bmpDefersToCharWidth() {
        assertEquals(1, codepointWidth('a'.code))
        assertEquals(1, codepointWidth(' '.code))
        // CJK ideograph (BMP) is wide.
        assertEquals(2, codepointWidth(0x4E2D)) // 中
        // Combining mark attaches to the previous cell → zero advance.
        assertEquals(0, codepointWidth(0x0301)) // combining acute accent
    }

    @Test fun astralEmojiIsWide() {
        assertEquals(2, codepointWidth(0x1F600)) // 😀 emoticons
        assertEquals(2, codepointWidth(0x1F389)) // 🎉 party popper
        assertEquals(2, codepointWidth(0x1F680)) // 🚀 rocket
        assertEquals(2, codepointWidth(0x1FAE0)) // 🫠 melting face (Symbols Ext-A)
    }

    @Test fun astralCjkExtensionIsWide() {
        assertEquals(2, codepointWidth(0x20000)) // CJK Ext-B
        assertEquals(2, codepointWidth(0x2A6DF))
    }

    @Test fun otherAstralDefaultsToSingle() {
        // Outside the emoji / CJK-ext ranges we don't claim two cells.
        assertEquals(1, codepointWidth(0x10000)) // Linear B syllable
        assertEquals(1, codepointWidth(0x1D400)) // mathematical bold A
    }
}
