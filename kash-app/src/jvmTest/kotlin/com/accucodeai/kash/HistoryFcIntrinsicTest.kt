package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke tests for the bash `history` and POSIX `fc` builtins. Drives a real [Kash]
 * interpreter, populates history via `history -s`, then exercises print/
 * delete/clear and the `fc -l` listing surface.
 */
class HistoryFcIntrinsicTest {
    @Test fun history_s_appends_and_prints() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    history -s "echo first"
                    history -s "echo second"
                    history
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.trim().lines()
            assertEquals(2, lines.size, "stdout=${r.stdout}")
            assertTrue(lines[0].endsWith("echo first"), lines[0])
            assertTrue(lines[1].endsWith("echo second"), lines[1])
        }

    @Test fun history_c_clears() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    history -s "echo a"
                    history -c
                    history
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode)
            assertEquals("", r.stdout.trim())
        }

    @Test fun history_d_deletes() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    history -s "echo a"
                    history -s "echo b"
                    history -s "echo c"
                    history -d 2
                    history
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.trim().lines()
            assertEquals(2, lines.size)
            assertTrue(lines[0].endsWith("echo a"))
            assertTrue(lines[1].endsWith("echo c"))
        }

    @Test fun history_bad_option_prints_usage() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("history -x")
            assertTrue(r.exitCode != 0)
            assertTrue(r.stderr.contains("invalid option"), "stderr=${r.stderr}")
            assertTrue(r.stderr.contains("usage:"), "stderr=${r.stderr}")
        }

    @Test fun history_mutually_exclusive_flags_error() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("history -r -w /dev/null")
            assertTrue(r.exitCode != 0)
            assertTrue(r.stderr.contains("cannot use more than one"), "stderr=${r.stderr}")
        }

    @Test fun fc_l_lists_with_line_numbers() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    history -s "echo one"
                    history -s "echo two"
                    fc -l
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // Each line is `<n>\t <cmd>`.
            val lines = r.stdout.trim().lines()
            assertEquals(2, lines.size, "stdout=${r.stdout}")
            assertTrue(lines[0].startsWith("1\t"))
            assertTrue(lines[1].startsWith("2\t"))
        }

    @Test fun fc_ln_lists_without_numbers() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    history -s "echo one"
                    fc -nl
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // Leading tab + space, then content.
            assertTrue(r.stdout.trimEnd().endsWith("echo one"), "stdout=${r.stdout}")
            assertTrue(!r.stdout.startsWith("1"), "expected no number prefix: ${r.stdout}")
        }

    @Test fun fc_lr_lists_reversed() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    history -s "echo one"
                    history -s "echo two"
                    fc -lr
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.trim().lines()
            assertEquals(2, lines.size)
            assertTrue(lines[0].endsWith("echo two"), lines[0])
            assertTrue(lines[1].endsWith("echo one"), lines[1])
        }

    @Test fun fc_v_invalid_option() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("fc -v")
            assertTrue(r.exitCode != 0)
            assertTrue(r.stderr.contains("-v: invalid option"), "stderr=${r.stderr}")
            assertTrue(r.stderr.contains("usage:"), "stderr=${r.stderr}")
        }
}
