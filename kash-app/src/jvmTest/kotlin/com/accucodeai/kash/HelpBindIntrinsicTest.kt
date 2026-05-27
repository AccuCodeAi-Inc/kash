package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke tests for the `help` and `bind`.
 */
class HelpBindIntrinsicTest {
    // -------- help --------

    @Test fun help_no_args_lists_topics() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("help")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.lines()
            // Spot-check a few well-known topics.
            assertTrue(lines.any { it.startsWith("cd ") }, "cd missing")
            assertTrue(lines.any { it.startsWith("if ") }, "if missing")
            assertTrue(lines.any { it.startsWith("history ") }, "history missing")
            assertTrue(lines.any { it.startsWith("export ") }, "export missing")
        }

    @Test fun help_with_topic_prints_synopsis_and_body() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("help cd")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertTrue(r.stdout.contains("cd "), "synopsis missing: ${r.stdout}")
            assertTrue(r.stdout.contains("\$HOME") || r.stdout.contains("OLDPWD"), "body missing: ${r.stdout}")
        }

    @Test fun help_d_prints_short_description_only() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("help -d cd")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val out = r.stdout.trim()
            assertTrue(out.startsWith("cd - "), "stdout=${r.stdout}")
            // Short desc is one line.
            assertEquals(1, out.lines().size)
        }

    @Test fun help_s_prints_synopsis_only() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("help -s pushd")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val out = r.stdout.trim()
            assertTrue(out.startsWith("pushd"), "stdout=${r.stdout}")
            assertEquals(1, out.lines().size)
        }

    @Test fun help_m_prints_man_page_format() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("help -m cd")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertTrue(r.stdout.contains("NAME"), "NAME section: ${r.stdout}")
            assertTrue(r.stdout.contains("SYNOPSIS"), "SYNOPSIS section: ${r.stdout}")
            assertTrue(r.stdout.contains("DESCRIPTION"), "DESCRIPTION section: ${r.stdout}")
        }

    @Test fun help_unknown_topic_errors() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("help zzznosuchtopic")
            assertTrue(r.exitCode != 0)
            assertTrue(r.stderr.contains("no help topics match"), "stderr=${r.stderr}")
        }

    @Test fun help_glob_pattern_matches() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("help 'push*'")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertTrue(r.stdout.contains("pushd"), "pushd missing: ${r.stdout}")
        }

    @Test fun help_bad_option_errors() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("help -z")
            assertTrue(r.exitCode != 0)
            assertTrue(r.stderr.contains("invalid option"), "stderr=${r.stderr}")
        }

    // -------- bind --------

    @Test fun bind_l_lists_readline_functions() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("bind -l")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val names = r.stdout.lines().toSet()
            assertTrue("self-insert" in names, "self-insert missing: $names")
            assertTrue("complete" in names, "complete missing: $names")
            assertTrue("beginning-of-line" in names, "beginning-of-line missing: $names")
        }

    @Test fun bind_q_known_function() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("bind -q complete")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertTrue(r.stdout.contains("not bound"), "stdout=${r.stdout}")
        }

    @Test fun bind_q_unknown_function_errors() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("bind -q zzznosuchfunction")
            assertTrue(r.exitCode != 0)
            assertTrue(r.stderr.contains("unknown function name"), "stderr=${r.stderr}")
        }

    @Test fun bind_bare_keyseq_accepts() =
        runTest {
            // Bash's `bind '"\C-x": clear-screen'` form. We accept silently.
            val r =
                Kash(registry = standardRegistry()).exec(
                    "bind '\"\\C-x\":clear-screen'",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
        }

    @Test fun bind_bad_option_errors() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("bind -Z")
            assertTrue(r.exitCode != 0)
            assertTrue(r.stderr.contains("invalid option"), "stderr=${r.stderr}")
            assertTrue(r.stderr.contains("usage:"), "stderr=${r.stderr}")
        }
}
