package com.accucodeai.kash.tools.sort

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SortOptionParserTest {
    @Test fun no_args() {
        val (opt, files) = SortOptionParser.parse(emptyList())
        assertEquals(SortOptions(), opt)
        assertEquals(emptyList(), files)
    }

    @Test fun simple_flags() {
        val (opt, _) = SortOptionParser.parse(listOf("-n", "-r", "-u"))
        assertTrue(opt.numeric && opt.reverse && opt.unique)
    }

    @Test fun bundled_flags() {
        val (opt, _) = SortOptionParser.parse(listOf("-nru"))
        assertTrue(opt.numeric && opt.reverse && opt.unique)
    }

    @Test fun key_separate_arg() {
        val (opt, _) = SortOptionParser.parse(listOf("-k", "2"))
        assertEquals(listOf(KeySpec(2)), opt.keys)
    }

    @Test fun key_joined_arg() {
        val (opt, _) = SortOptionParser.parse(listOf("-k2"))
        assertEquals(listOf(KeySpec(2)), opt.keys)
    }

    @Test fun key_range() {
        val (opt, _) = SortOptionParser.parse(listOf("-k", "2,3"))
        assertEquals(listOf(KeySpec(2, 1, 3, null)), opt.keys)
    }

    @Test fun key_with_char() {
        val (opt, _) = SortOptionParser.parse(listOf("-k", "2.5"))
        assertEquals(listOf(KeySpec(2, 5)), opt.keys)
    }

    @Test fun key_range_with_chars() {
        val (opt, _) = SortOptionParser.parse(listOf("-k", "2.3,4.7"))
        assertEquals(listOf(KeySpec(2, 3, 4, 7)), opt.keys)
    }

    @Test fun separator_separate() {
        val (opt, _) = SortOptionParser.parse(listOf("-t", ":"))
        assertEquals(':', opt.separator)
    }

    @Test fun separator_joined() {
        val (opt, _) = SortOptionParser.parse(listOf("-t:"))
        assertEquals(':', opt.separator)
    }

    @Test fun files_after_double_dash() {
        val (_, files) = SortOptionParser.parse(listOf("-n", "--", "-weird-name", "a.txt"))
        assertEquals(listOf("-weird-name", "a.txt"), files)
    }

    @Test fun single_dash_is_a_file() {
        val (_, files) = SortOptionParser.parse(listOf("-", "a.txt"))
        assertEquals(listOf("-", "a.txt"), files)
    }

    @Test fun unknown_flag_errors() {
        assertFailsWith<SortOptionError> { SortOptionParser.parse(listOf("-Z")) }
    }

    @Test fun bad_key_errors() {
        assertFailsWith<SortOptionError> { SortOptionParser.parse(listOf("-k", "abc")) }
    }

    @Test fun multi_char_separator_errors() {
        assertFailsWith<SortOptionError> { SortOptionParser.parse(listOf("-t", "ab")) }
    }

    @Test fun multiple_keys_in_order() {
        val (opt, _) = SortOptionParser.parse(listOf("-k", "1,1", "-k", "3.2"))
        assertEquals(listOf(KeySpec(1, 1, 1, null), KeySpec(3, 2)), opt.keys)
    }
}
