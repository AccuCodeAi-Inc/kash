package com.accucodeai.kash.tools.ls

import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileType
import kotlin.test.Test
import kotlin.test.assertEquals

class LsColorsTest {
    private fun file(
        name: String,
        mode: Int = 0b110_100_100,
    ) = FileStat(name, FileType.REGULAR, 0, 0, mode)

    private fun dir(name: String = "/d") = FileStat(name, FileType.DIRECTORY, 0, 0, 0b111_101_101)

    @Test fun null_returns_defaults() {
        assertEquals(LsColors.DEFAULT, LsColors.parse(null))
    }

    @Test fun empty_returns_defaults() {
        assertEquals(LsColors.DEFAULT, LsColors.parse(""))
    }

    @Test fun parses_type_keys() {
        val c = LsColors.parse("di=01;34:ln=04;36:ex=33")
        assertEquals("01;34", c.di)
        assertEquals("04;36", c.ln)
        assertEquals("33", c.ex)
        // Unset keys keep their defaults.
        assertEquals(LsColors.DEFAULT.so, c.so)
    }

    @Test fun parses_extension_globs() {
        val c = LsColors.parse("*.tar=01;31:*.jpg=00;35")
        assertEquals("01;31", c.extensions[".tar"])
        assertEquals("00;35", c.extensions[".jpg"])
    }

    @Test fun unknown_keys_silently_ignored() {
        val c = LsColors.parse("tw=01:ow=02:fi=33") // tw/ow not modeled
        assertEquals("33", c.fi)
    }

    @Test fun malformed_entries_skipped() {
        val c = LsColors.parse("garbage:di=banana:ex=33")
        assertEquals(LsColors.DEFAULT.di, c.di) // banana isn't valid SGR
        assertEquals("33", c.ex)
    }

    @Test fun last_value_wins_on_duplicate_key() {
        val c = LsColors.parse("di=33:di=44")
        assertEquals("44", c.di)
    }

    @Test fun colorFor_extension_beats_fi() {
        val c = LsColors.parse("fi=00:*.tar=01;31")
        assertEquals("01;31", c.colorFor(file("/a/b/file.tar")))
    }

    @Test fun colorFor_executable_bit_picks_ex_not_fi() {
        val c = LsColors.parse("fi=00:ex=01;32")
        val exec = file("/x/run.sh", mode = 0b111_101_101)
        assertEquals("01;32", c.colorFor(exec))
    }

    @Test fun colorFor_extension_does_not_apply_to_directory() {
        // Real dirs are rare with extensions but the rule is type wins
        // for non-regular files. (.git dirs would otherwise hit *.git if
        // that ever existed in LS_COLORS.)
        val c = LsColors.parse("*.bak=33:di=01;34")
        assertEquals("01;34", c.colorFor(dir("/x.bak")))
    }

    @Test fun colorFor_returns_default_when_extension_misses() {
        val c = LsColors.parse("*.tar=01;31")
        assertEquals("", c.colorFor(file("/note.md")))
    }
}
