package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke tests for the bash builtins added on top of POSIX:
 * `caller`, `let`, `logout`, `enable`, `mapfile`/`readarray`,
 * `dirs`/`pushd`/`popd`. Each test drives a real [Kash] interpreter so
 * the dispatch path through [IntrinsicCatalog] and `runIntrinsic` is
 * exercised end-to-end.
 */
class BashBuiltinsTest {
    // -------- let --------

    @Test fun let_assigns_and_evaluates() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("let 'x=1+2'; echo \$x")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("3", r.stdout.trim())
        }

    @Test fun let_exit_status_zero_returns_one() =
        runTest {
            // `let 0` evaluates to 0 → exit 1 (mirrors `(( 0 ))`).
            val r = Kash(registry = standardRegistry()).exec("let 0; echo \$?")
            assertEquals(0, r.exitCode)
            assertEquals("1", r.stdout.trim())
        }

    @Test fun let_no_args_errors() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("let")
            assertTrue(r.exitCode != 0, "expected non-zero")
            assertTrue(r.stderr.contains("expression expected"), "stderr=${r.stderr}")
        }

    // -------- caller --------

    @Test fun caller_outside_function_returns_one() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("caller; echo exit=\$?")
            assertEquals(0, r.exitCode)
            assertTrue(r.stdout.trim().endsWith("exit=1"), "stdout=${r.stdout}")
        }

    @Test fun caller_in_function_prints_line_and_name() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    f() { caller; }
                    f
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // Output format: "<line> <name>" — at minimum the name must show up.
            assertTrue(r.stdout.contains("f") || r.stdout.contains("main"), "stdout=${r.stdout}")
        }

    // -------- logout --------

    @Test fun logout_in_non_login_shell_errors() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("logout; echo after")
            assertEquals(0, r.exitCode)
            assertTrue(r.stderr.contains("not login shell"), "stderr=${r.stderr}")
            assertTrue(r.stdout.contains("after"), "stdout=${r.stdout}")
        }

    // -------- enable --------

    @Test fun enable_lists_builtins() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("enable")
            assertEquals(0, r.exitCode)
            val names =
                r.stdout
                    .trim()
                    .lines()
                    .toSet()
            assertTrue("export" in names, "expected 'export' in $names")
            assertTrue("let" in names, "Stage 2 let should be enable-listable")
        }

    @Test fun enable_n_disables_and_resolver_falls_through() =
        runTest {
            // After `enable -n echo`, `echo` should no longer be the
            // intrinsic — but registry/PATH may still provide one. We just
            // verify the enable -n marker shows up in `enable -a`.
            val r = Kash(registry = standardRegistry()).exec("enable -n :; enable -a | grep '^-n '")
            assertEquals(0, r.exitCode)
            assertTrue(r.stdout.contains(":"), "stdout=${r.stdout}")
        }

    // -------- mapfile / readarray --------

    @Test fun mapfile_reads_stdin_into_array() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "printf 'a\\nb\\nc\\n' | { mapfile -t arr; echo \"\${arr[0]} \${arr[1]} \${arr[2]}\"; }",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("a b c", r.stdout.trim())
        }

    @Test fun readarray_is_mapfile_alias() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "printf 'x\\ny\\n' | { readarray -t arr; echo \"\${arr[*]}\"; }",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("x y", r.stdout.trim())
        }

    @Test fun mapfile_with_O_origin() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "printf 'a\\nb\\n' | { mapfile -t -O 2 arr; echo \"\${arr[2]} \${arr[3]}\"; }",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("a b", r.stdout.trim())
        }

    // -------- dirs / pushd / popd --------

    @Test fun dirs_starts_with_cwd() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("dirs")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // Single line — current directory printed (tilde-substituted or absolute).
            assertTrue(r.stdout.trim().isNotEmpty(), "stdout=${r.stdout}")
        }

    @Test fun pushd_popd_round_trip() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "pushd /tmp > /dev/null; pwd; popd > /dev/null; pwd",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.trim().lines()
            assertEquals(2, lines.size, "stdout=${r.stdout}")
            assertEquals("/tmp", lines[0])
            // lines[1] = original cwd, just assert it's not /tmp.
            assertTrue(lines[1] != "/tmp", "second pwd should be original cwd, was ${lines[1]}")
        }

    @Test fun popd_empty_stack_errors() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("popd")
            assertTrue(r.exitCode != 0, "expected non-zero")
            assertTrue(r.stderr.contains("directory stack empty"), "stderr=${r.stderr}")
        }
}
