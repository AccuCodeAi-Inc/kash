package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bash printf preserves a `\x` escape verbatim (both characters) when no
 * hex digits follow. Verified against bash 3.2/5.x:
 *
 *   $ bash -c "printf '\\x'"
 *   bash: printf: missing hex digit for \x
 *   \x
 *
 * The two-byte literal output is what makes the iquote conformance
 * fixture's `eval tmp=\`printf "$'\\\\\x%x'\n" 127\`` produce
 * `$'\x7f'` (with `\x` intact) so the subsequent ANSI-C eval decodes
 * it to DEL. An earlier version of this test expected a single `x` —
 * that was a misreading of the fixture; kash matches bash today.
 */
class PrintfHexEscapeTest {
    @Test
    fun backslashXFollowedByNonHexPreservesBothChars() =
        runTest {
            val r = Kash().exec("printf '\\x'")
            assertEquals("\\x", r.stdout)
        }

    @Test
    fun backslashXFollowedByPercentPreservesBackslashThenFormatsArg() =
        runTest {
            val r = Kash().exec("printf '\\x%x' 127")
            assertEquals("\\x7f", r.stdout)
        }
}
