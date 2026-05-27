package com.accucodeai.kash.tools.grep

import kotlin.test.Test
import kotlin.test.assertEquals

class GrepColorsTest {
    @Test fun null_returns_defaults() {
        assertEquals(GrepColors.DEFAULT, GrepColors.parse(null))
    }

    @Test fun empty_returns_defaults() {
        assertEquals(GrepColors.DEFAULT, GrepColors.parse(""))
    }

    @Test fun parses_ms_fn_ln_se() {
        val c = GrepColors.parse("ms=01;33:fn=34:ln=37:se=90")
        assertEquals("01;33", c.ms)
        assertEquals("34", c.fn)
        assertEquals("37", c.ln)
        assertEquals("90", c.se)
    }

    @Test fun mc_defaults_to_ms_when_unset() {
        val c = GrepColors.parse("ms=01;33")
        assertEquals("01;33", c.ms)
        assertEquals("01;33", c.mc)
    }

    @Test fun mc_keeps_explicit_value_even_with_later_ms_change() {
        // Order independence for the mc-tracks-ms quirk: explicit mc wins
        // regardless of where it appears relative to ms.
        val a = GrepColors.parse("mc=44:ms=01;31")
        assertEquals("44", a.mc)
        val b = GrepColors.parse("ms=01;31:mc=44")
        assertEquals("44", b.mc)
    }

    @Test fun unknown_keys_silently_ignored() {
        // `bn` and `sl` are real GREP_COLORS keys we don't model; should
        // parse cleanly without affecting the rest.
        val c = GrepColors.parse("bn=33:fn=35:sl=01")
        assertEquals("35", c.fn)
        assertEquals(GrepColors.DEFAULT.ln, c.ln)
    }

    @Test fun malformed_entries_skipped() {
        // No `=`, garbage characters: skipped, defaults survive.
        val c = GrepColors.parse("garbage:fn=:ln=abc:se=36")
        assertEquals("", c.fn) // empty value is valid (disables coloring)
        assertEquals(GrepColors.DEFAULT.ln, c.ln) // "abc" is not valid SGR
        assertEquals("36", c.se)
    }

    @Test fun double_colons_tolerated() {
        val c = GrepColors.parse("::fn=35::ln=32::")
        assertEquals("35", c.fn)
        assertEquals("32", c.ln)
    }

    @Test fun last_value_wins_on_duplicate_key() {
        val c = GrepColors.parse("fn=35:fn=44")
        assertEquals("44", c.fn)
    }
}
