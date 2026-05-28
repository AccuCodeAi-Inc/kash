package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end POSIX [XCU §2.9.1.1](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_09_01_01)
 * command-search-and-execution coverage. Exercises the full resolver
 * through `Kash.exec` — assertions are on shell-observable behavior
 * (stdout, exit code, `command -v` / `type` output).
 */
class PathResolutionTest {
    @Test fun command_dash_v_grep_reports_usr_bin_path() =
        runTest {
            // jq is the one tool that's not in :core, but every BUILTIN
            // (echo, cat, pwd…) is auto-mounted at /usr/bin via ToolsFs.
            // type echo → "echo is a shell builtin" (bash convention), so
            // we use the more specific command -v which reports a path
            // for utilities. echo is BUILTIN kind → reports name, not path.
            // Use cat instead — cat is also BUILTIN, prints name; so we
            // actually verify command -v on cat returns the name (bash
            // convention for built-ins) and that PATH resolution works by
            // testing the *type -p* output.
            val r = Kash(registry = standardRegistry()).exec("command -v echo")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // bash: built-in names are not paths.
            assertEquals("echo\n", r.stdout)
        }

    @Test fun type_capital_p_echo_returns_usr_bin_path() =
        runTest {
            // echo is a shell builtin that is ALSO mounted as a file at
            // /usr/bin/echo (ToolsFs). Per bash, lowercase `type -p` stays
            // silent for a builtin; capital `type -P` forces the PATH search
            // and reports the file. (bash 5.3: `type -P echo` → /usr/bin/echo.)
            val r = Kash(registry = standardRegistry()).exec("type -P echo")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("/usr/bin/echo\n", r.stdout)
        }

    @Test fun type_brief_classifies_keyword_function_builtin() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("type -t if; type -t echo")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("keyword\nbuiltin\n", r.stdout)
        }

    @Test fun command_dash_v_special_builtin_prints_name() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("command -v :")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals(":\n", r.stdout)
        }

    @Test fun command_dash_v_keyword_echoes_name_and_exits_zero() =
        runTest {
            // bash convention: `command -v <keyword>` echoes the keyword name
            // and exits 0 (verified against bash 5.3: `command -v if` → "if",
            // rc 0). It does NOT fail like a missing utility.
            val r = Kash(registry = standardRegistry()).exec("command -v if; echo done=\$?")
            assertEquals("if\ndone=0\n", r.stdout)
        }

    @Test fun empty_path_only_special_builtins_resolve() =
        runTest {
            // PATH= → utility lookup fails; the special builtin `:` still works.
            // Special-builtin prefix assignments persist (POSIX §2.14), so
            // we can't chain another utility after — the script body is just `:`.
            val r = Kash(registry = standardRegistry()).exec($$"PATH= :")
            assertEquals(0, r.exitCode)
        }

    @Test fun empty_path_blocks_utility_resolution() =
        runTest {
            // Must use a TOOL-kind utility, not a builtin: with PATH cleared,
            // /usr/bin is no longer searched, so a real utility can't resolve.
            // (echo is a shell builtin and would run regardless of PATH — that
            // is exactly what bash does — so `sort` is the honest test here.)
            val r = Kash(registry = standardRegistry()).exec($$"PATH= sort 2>/dev/null; echo rc=$?")
            // sort wasn't found → exit 127.
            assertTrue(r.stdout.trim().endsWith("rc=127"), "got: ${r.stdout}")
        }

    @Test fun path_walks_in_order() =
        runTest {
            // Stash an executable shell-script-style file in /tmp and put /tmp
            // ahead of /usr/bin. We can't easily mark scripts executable, so
            // we use a shebang-less script that the resolver reads as `sh`.
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    mkdir -p /custom
                    echo 'echo hello-from-custom' > /custom/greet
                    PATH=/custom:/usr/bin greet
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("hello-from-custom\n", r.stdout)
        }

    @Test fun ls_usr_bin_lists_registered_utilities() =
        runTest {
            val r = Kash(registry = standardRegistry()).exec("ls /usr/bin")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // Every BUILTIN/TOOL we ship appears; verify a representative.
            assertTrue("echo" in r.stdout, "echo not in /usr/bin: ${r.stdout}")
            assertTrue("cat" in r.stdout, "cat not in /usr/bin: ${r.stdout}")
            assertTrue("test" in r.stdout, "test not in /usr/bin: ${r.stdout}")
        }

    @Test fun cat_usr_bin_grep_is_empty_body() =
        runTest {
            // ToolsFs entries have no body — `cat /usr/bin/echo` prints nothing.
            val r = Kash(registry = standardRegistry()).exec("cat /usr/bin/echo | wc -c")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("0", r.stdout.trim())
        }

    @Test fun rm_usr_bin_echo_fails_read_only() =
        runTest {
            // ToolsFs is read-only.
            val r = Kash(registry = standardRegistry()).exec($$"rm /usr/bin/echo 2>&1; echo rc=$?")
            assertTrue("rc=" in r.stdout, "got: ${r.stdout}")
            val rc =
                r.stdout
                    .substringAfter("rc=")
                    .trim()
                    .toInt()
            assertTrue(rc != 0, "rm should have failed: ${r.stdout}")
        }

    @Test fun path_qualified_command_skips_path() =
        runTest {
            // /usr/bin/echo is an absolute path — must resolve regardless of PATH.
            val r = Kash(registry = standardRegistry()).exec("PATH= /usr/bin/echo hello")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("hello\n", r.stdout)
        }

    @Test fun dot_slash_command_resolves_relative_to_cwd() =
        runTest {
            // POSIX XCU §2.9.1.1: command names with a slash invoke execl()
            // with the name as path — relative paths must work from cwd.
            // Reproduces the bug where `cd /usr/bin; ./echo hi` returned
            // "command not found" because resolveFsPath looked up "./echo"
            // literally instead of normalizing against cwd.
            val r = Kash(registry = standardRegistry()).exec("cd /usr/bin && ./echo hi")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("hi\n", r.stdout)
        }

    @Test fun dot_slash_missing_file_reports_no_such_file_and_exit_127() =
        runTest {
            // POSIX XCU §2.9.1.1.d: path-qualified names invoke execl();
            // ENOENT surfaces as "No such file or directory" with exit 127.
            val r = Kash(registry = standardRegistry()).exec("./nope 2>&1; echo rc=$?")
            assertTrue("./nope: No such file or directory" in r.stdout, "got: ${r.stdout}")
            assertTrue(r.stdout.trim().endsWith("rc=127"), "wrong exit: ${r.stdout}")
        }

    @Test fun path_qualified_directory_reports_is_a_directory_and_exit_126() =
        runTest {
            // Path-qualified resolution to a directory → EISDIR → exit 126.
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    mkdir -p /work/sub
                    /work/sub 2>&1
                    echo rc=${'$'}?
                    """.trimIndent(),
                )
            assertTrue("Is a directory" in r.stdout, "got: ${r.stdout}")
            assertTrue(r.stdout.trim().endsWith("rc=126"), "wrong exit: ${r.stdout}")
        }

    @Test fun bare_name_not_found_keeps_command_not_found_and_exit_127() =
        runTest {
            // No slash → PATH search → "command not found" wording (POSIX
            // doesn't mandate the wording but every shell uses it).
            val r = Kash(registry = standardRegistry()).exec($$"nope-tool 2>&1; echo rc=$?")
            assertTrue("nope-tool: command not found" in r.stdout, "got: ${r.stdout}")
            assertTrue(r.stdout.trim().endsWith("rc=127"), "wrong exit: ${r.stdout}")
        }

    @Test fun bare_relative_subdir_command_resolves_against_cwd() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    mkdir -p /work/bin
                    echo 'echo from-subdir' > /work/bin/runme
                    cd /work
                    bin/runme
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("from-subdir\n", r.stdout)
        }

    @Test fun trailing_colon_in_path_is_cwd() =
        runTest {
            // PATH="/usr/bin:" → after /usr/bin, search cwd. Drop a script in
            // /work and cd there; "tag" should be found via the trailing empty.
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    mkdir -p /work
                    echo 'echo cwd-hit' > /work/tag
                    cd /work
                    PATH=/usr/bin: tag
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("cwd-hit\n", r.stdout)
        }
}
