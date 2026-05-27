package com.accucodeai.kash.fs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Filename hygiene for imported (dropped/uploaded) files. The product
 * requirement: a path pasted onto the command line must be a bare shell
 * word — no spaces, no parens, no metacharacters — so the agent never has
 * to quote it.
 */
class DropPathsTest {
    @Test
    fun spaces_become_underscores() {
        assertEquals("my_holiday_photo.jpg", sanitizeDropName("my holiday photo.jpg"))
    }

    @Test
    fun parens_and_space_from_the_bug_report() {
        // "ctf (1).zip" — the exact annoying case — must not keep its
        // space or parens.
        val s = sanitizeDropName("ctf (1).zip")
        assertEquals("ctf_1.zip", s)
        assertShellSafe(s)
    }

    @Test
    fun shell_metacharacters_are_stripped() {
        val s = sanitizeDropName("a;b|c&d\$e`f*g?h<i>j.txt")
        assertShellSafe(s)
        assertEquals("a_b_c_d_e_f_g_h_i_j.txt", s)
    }

    @Test
    fun path_separators_dropped_no_directory_escape() {
        assertEquals("passwd", sanitizeDropName("../../etc/passwd"))
        assertEquals("evil.sh", sanitizeDropName("/abs/path/evil.sh"))
        assertEquals("win.bat", sanitizeDropName("C:\\Users\\x\\win.bat"))
    }

    @Test
    fun leading_dots_trimmed_extension_dot_kept() {
        assertEquals("bashrc", sanitizeDropName("...bashrc"))
        assertEquals("a.tar.gz", sanitizeDropName("a.tar.gz"))
    }

    @Test
    fun empty_or_all_punctuation_falls_back_to_drop() {
        assertEquals("drop", sanitizeDropName(""))
        assertEquals("drop", sanitizeDropName("///"))
        assertEquals("drop", sanitizeDropName("..."))
    }

    @Test
    fun unicode_letters_and_digits_survive() {
        assertEquals("café.txt", sanitizeDropName("café.txt"))
    }

    @Test
    fun uniqueDropPath_no_collision_returns_direct() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/drops")
            assertEquals("/tmp/drops/ctf.zip", uniqueDropPath(fs, "/tmp/drops", "ctf.zip"))
        }

    @Test
    fun uniqueDropPath_collision_appends_underscore_index() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/drops")
            fs.writeBytes("/tmp/drops/ctf.zip", ByteArray(1))
            fs.writeBytes("/tmp/drops/ctf_1.zip", ByteArray(1))
            val p = uniqueDropPath(fs, "/tmp/drops", "ctf.zip")
            assertEquals("/tmp/drops/ctf_2.zip", p)
            // The collision suffix must itself stay shell-safe.
            assertShellSafe(p.substringAfterLast('/'))
        }

    @Test
    fun uniqueDropPath_collision_on_extensionless_name() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp/drops")
            fs.writeBytes("/tmp/drops/README", ByteArray(1))
            assertEquals("/tmp/drops/README_1", uniqueDropPath(fs, "/tmp/drops", "README"))
        }

    private fun assertShellSafe(s: String) {
        assertTrue(s.isNotEmpty(), "name should not be empty")
        assertFalse(' ' in s, "name must not contain spaces: '$s'")
        for (ch in s) {
            val ok = ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_'
            assertTrue(ok, "char '$ch' in '$s' is not shell-safe")
        }
    }
}
