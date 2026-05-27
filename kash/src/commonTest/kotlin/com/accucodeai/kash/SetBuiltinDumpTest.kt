package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `set` with no args prints every variable in sorted order, each as
 * `NAME=VALUE` with bash-style single-quoting. Pinned to the posix2
 * conformance corpus's variable-quoting tests:
 *   SQUOTE="'"     → `SQUOTE=\'`
 *   VTILDE='~'     → `VTILDE='~'`
 *   VHASH=ab#cd    → `VHASH=ab#cd`   (mid-word # is safe)
 *   VHASH2=#abcd   → `VHASH2='#abcd'` (leading # would start a comment)
 */
class SetBuiltinDumpTest {
    @Test
    fun squoteValueRendersAsBackslashQuote() =
        runTest {
            val r = Kash().exec("SQUOTE=\"'\"\nset")
            assertTrue("SQUOTE=\\'\n" in r.stdout, "got: ${r.stdout}")
        }

    @Test
    fun tildeIsSingleQuoted() =
        runTest {
            val r = Kash().exec("VTILDE='~'\nset")
            assertTrue("VTILDE='~'\n" in r.stdout, "got: ${r.stdout}")
        }

    @Test
    fun midWordHashIsRaw() =
        runTest {
            val r = Kash().exec("VHASH=ab#cd\nset")
            assertTrue("VHASH=ab#cd\n" in r.stdout, "got: ${r.stdout}")
        }

    @Test
    fun leadingHashIsQuoted() =
        runTest {
            val r = Kash().exec("VHASH2=#abcd\nset")
            assertTrue("VHASH2='#abcd'\n" in r.stdout, "got: ${r.stdout}")
        }

    @Test
    fun valueWithSpaceIsQuoted() =
        runTest {
            val r = Kash().exec("V='hello world'\nset")
            assertTrue("V='hello world'\n" in r.stdout, "got: ${r.stdout}")
        }

    @Test
    fun valueWithEmbeddedSingleQuoteIsEscaped() =
        runTest {
            // `it's` → `'it'\''s'` (the embedded `'` becomes `'\''`).
            val r = Kash().exec("V=\"it's\"\nset")
            assertTrue("V='it'\\''s'\n" in r.stdout, "got: ${r.stdout}")
        }
}
