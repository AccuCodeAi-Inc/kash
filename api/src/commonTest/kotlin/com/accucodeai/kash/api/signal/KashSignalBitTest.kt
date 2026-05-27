package com.accucodeai.kash.api.signal

import kotlin.test.Test
import kotlin.test.assertEquals

class KashSignalBitTest {
    @Test fun bit_setsCorrectPositionForNumberedSignals() {
        // HUP = 1 → bit 0
        assertEquals(1L, SigHup.bit())
        // INT = 2 → bit 1
        assertEquals(2L, SigInt.bit())
        // TERM = 15 → bit 14
        assertEquals(1L shl 14, SigTerm.bit())
    }

    @Test fun bit_returnsZeroForPseudoSignals() {
        // SigExit (number=0), SigDebug/SigReturn/SigErr (negative
        // sentinel numbers) — none representable in a kernel-shaped
        // sigmask. All return 0L.
        assertEquals(0L, SigExit.bit())
        assertEquals(0L, SigDebug.bit())
        assertEquals(0L, SigReturn.bit())
        assertEquals(0L, SigErr.bit())
    }

    @Test fun toSigmaskHex_emptySet_returns16Zeros() {
        assertEquals("0000000000000000", emptyList<KashSignal>().toSigmaskHex())
    }

    @Test fun toSigmaskHex_combinesMultipleSignals() {
        // HUP (1) + TERM (15): bits 0 and 14 → 0x4001
        val mask = listOf(SigHup, SigTerm).toSigmaskHex()
        assertEquals("0000000000004001", mask)
        assertEquals(16, mask.length)
    }

    @Test fun toSigmaskHex_ignoresPseudoSignals() {
        // Pseudo-signals contribute 0; result is identical to without them.
        assertEquals(
            listOf(SigInt).toSigmaskHex(),
            listOf(SigInt, SigExit, SigDebug, SigReturn).toSigmaskHex(),
        )
    }
}
