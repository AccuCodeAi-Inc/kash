package com.accucodeai.kash.tools.git.porcelain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [parseGitDate], the `--date` value parser. Expected
 * epochs cross-checked against `/usr/bin/git` with `TZ=UTC`.
 */
class ParseGitDateTest {
    @Test fun rawSecondsAndTz() {
        assertEquals(1112937193L to "+0200", parseGitDate("1112937193 +0200", "+0000"))
        assertEquals(1700000000L to "-0500", parseGitDate("1700000000 -0500", "+0000"))
    }

    @Test fun bareSecondsUsesDefaultTz() {
        assertEquals(1700000000L to "+0000", parseGitDate("1700000000", "+0000"))
        assertEquals(1700000000L to "+0300", parseGitDate("1700000000", "+0300"))
    }

    @Test fun epochAtForm() {
        assertEquals(1112937193L to "+0000", parseGitDate("@1112937193", "+0000"))
        assertEquals(1112937193L to "+0200", parseGitDate("@1112937193 +0200", "+0000"))
    }

    @Test fun isoDateTimeWithExplicitOffset() {
        // 2005-04-07 22:13:13 +0200 → 1112904793 (verified against git).
        assertEquals(1112904793L to "+0200", parseGitDate("2005-04-07 22:13:13 +0200", "+0000"))
        assertEquals(1112904793L to "+0200", parseGitDate("2005-04-07T22:13:13+02:00", "+0000"))
    }

    @Test fun isoDateTimeZulu() {
        // 2005-04-07T22:13:13Z → 1112911993 UTC.
        assertEquals(1112911993L to "+0000", parseGitDate("2005-04-07T22:13:13Z", "+0500"))
    }

    @Test fun isoDateTimeNoOffsetUsesDefault() {
        // No offset → interpret in the default tz (UTC here).
        assertEquals(1112911993L to "+0000", parseGitDate("2005-04-07T22:13:13", "+0000"))
    }

    @Test fun isoDateOnly() {
        // 1970-01-02 00:00:00 UTC = 86400.
        assertEquals(86400L to "+0000", parseGitDate("1970-01-02", "+0000"))
    }

    @Test fun rejectsGarbage() {
        assertNull(parseGitDate("not a date", "+0000"))
        assertNull(parseGitDate("", "+0000"))
        assertNull(parseGitDate("2005-13", "+0000"))
    }
}
