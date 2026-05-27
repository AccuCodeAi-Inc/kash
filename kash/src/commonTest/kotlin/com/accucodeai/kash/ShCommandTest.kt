package com.accucodeai.kash

import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * End-to-end `sh` builtin tests. Each test runs through the full kash
 * interpreter so the [ShCommand] is invoked with a real
 * [com.accucodeai.kash.api.ShellRunner] in [com.accucodeai.kash.api.CommandContext].
 */
class ShCommandTest {
    @Test fun dashC_runs_inline_script() =
        runTest {
            val r = Kash().exec("sh -c 'echo hello'")
            assertEquals(0, r.exitCode)
            assertEquals("hello\n", r.stdout)
        }

    @Test fun nounset_errors_on_unbound_bare_brace() =
        runTest {
            // `set -u` then bare `${foo}` (no default-value op) is a hard
            // error per POSIX §2.5.2; non-interactive shell exits non-zero.
            val r = Kash().exec($$"sh -uc 'unset foo; echo ${foo}'")
            assertNotEquals(0, r.exitCode)
        }

    @Test fun dashC_exit_code_propagates() =
        runTest {
            val r = Kash().exec($$"sh -c 'false'; echo $?")
            assertEquals("1\n", r.stdout)
        }

    @Test fun dashC_runs_in_subshell_env_does_not_leak() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
                    FOO=parent
                    sh -c 'FOO=child; echo $FOO'
                    echo $FOO
                    """.trimIndent(),
                )
            assertEquals("child\nparent\n", r.stdout)
        }

    @Test fun dashC_runs_in_subshell_cwd_does_not_leak() =
        runTest {
            val k = Kash()
            k.fs.mkdirs("/tmp")
            val r =
                k.exec(
                    """
                    sh -c 'cd /tmp; pwd'
                    pwd
                    """.trimIndent(),
                )
            val lines = r.stdout.lines()
            assertEquals("/tmp", lines[0])
            assertNotEquals("/tmp", lines[1])
        }

    @Test fun file_operand_executes_script_file() =
        runTest {
            val k = Kash()
            k.fs.writeBytes("/tmp/s.sh", "echo from-file\n".encodeToByteArray())
            val r = k.exec("sh /tmp/s.sh")
            assertEquals("from-file\n", r.stdout)
        }

    @Test fun missing_file_returns_127() =
        runTest {
            val r = Kash().exec($$"sh /nope.sh; echo rc=$?")
            assertEquals(true, r.stdout.contains("rc=127"))
        }

    @Test fun dashC_without_argument_returns_2() =
        runTest {
            val r = Kash().exec($$"sh -c; echo rc=$?")
            assertEquals(true, r.stdout.contains("rc=2"))
        }

    @Test fun dashC_propagates_positional_args() =
        runTest {
            val r = Kash().exec($$"sh -c 'echo $1 $2' x foo bar")
            assertEquals("foo bar\n", r.stdout)
        }

    @Test fun dashC_dollarHash_counts_only_positional() =
        runTest {
            val r = Kash().exec($$"sh -c 'echo $#' x a b c")
            assertEquals("3\n", r.stdout)
        }

    @Test fun dashC_dollarZero_is_name_operand() =
        runTest {
            val r = Kash().exec($$"sh -c 'echo $0' myname")
            assertEquals("myname\n", r.stdout)
        }

    @Test fun dashC_dollarAt_preserves_operand_boundaries() =
        runTest {
            val r =
                Kash(registry = standardRegistry()).newSession().let { s ->
                    s.exec($$"sh -c 'for a in \"$@\"; do echo \"<$a>\"; done' _ 'a b' c")
                }
            assertEquals("<a b>\n<c>\n", r.stdout)
        }

    @Test fun dashC_without_operands_clears_parent_positional() =
        runTest {
            val s = Kash(registry = standardRegistry()).newSession()
            s.exec("set -- parent1 parent2")
            val r = s.exec($$"sh -c 'echo got=$1#'")
            // Sub-script's $1 is empty, NOT inherited from parent.
            assertEquals("got=#\n", r.stdout)
        }

    @Test fun dashC_nested_save_restore_parent_positional() =
        runTest {
            val s = Kash(registry = standardRegistry()).newSession()
            s.exec("set -- a b c")
            val r1 = s.exec($$"sh -c 'echo $1' inner-only inner-arg")
            assertEquals("inner-arg\n", r1.stdout)
            // Parent positional must still be intact afterwards.
            val r2 = s.exec($$"echo $1")
            assertEquals("a\n", r2.stdout)
        }

    @Test fun file_operand_sets_dollarZero_and_positional() =
        runTest {
            val k = Kash()
            k.fs.writeBytes("/tmp/s.sh", $$"echo $0 $1 $2\n".encodeToByteArray())
            val r = k.exec("sh /tmp/s.sh foo bar")
            assertEquals("/tmp/s.sh foo bar\n", r.stdout)
        }

    @Test fun untermPE_discards_partial_statement() =
        runTest {
            // `echo "${foo:-"a}"` is unterminated mid-PE. POSIX shells
            // discard the failing command rather than running `echo` with
            // no args (which would otherwise emit a stray newline before
            // the parse diagnostic). Verify we match: prior `foo=bar`
            // runs, then the parse error fires with NO extra blank.
            val k = Kash(registry = standardRegistry())
            val script = "foo=bar\necho \"\${foo:-\"a}\"\n"
            k.fs.writeBytes("/tmp/p.sh", script.encodeToByteArray())
            val r = k.exec("sh /tmp/p.sh 2>&1")
            // Expect output to be JUST the error message (no leading blank).
            assertEquals(true, r.stdout.startsWith("/tmp/p.sh: line"))
            assertNotEquals(0, r.exitCode)
        }
}
