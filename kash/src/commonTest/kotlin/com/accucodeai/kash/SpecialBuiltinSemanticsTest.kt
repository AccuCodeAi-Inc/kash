package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for POSIX special-builtin semantics added in the Koin-spec
 * migration ([Special Builtins, bash manual](
 * https://www.gnu.org/software/bash/manual/html_node/Special-Builtins.html)):
 *
 *  1. prefix `VAR=val` persists into the parent shell for special builtins,
 *     stays scoped for regular builtins
 *  2. a failed special builtin aborts the script in a non-interactive shell
 *  3. special builtins win command-lookup precedence over user functions
 */
class SpecialBuiltinSemanticsTest {
    @Test fun prefixAssignmentPersistsForExport() =
        runTest {
            // export is a special builtin → assignment persists.
            val r = Kash().exec("FOO=bar export FOO; echo \$FOO")
            assertEquals("bar\n", r.stdout)
            assertEquals(0, r.exitCode)
        }

    @Test fun prefixAssignmentIsScopedForRegularBuiltin() =
        runTest {
            // echo is a regular builtin → FOO is only visible during echo's
            // own command, NOT after.
            val r = Kash().exec("FOO=bar echo first; echo second=\$FOO")
            assertEquals("first\nsecond=\n", r.stdout)
        }

    @Test fun failedSpecialBuiltinAbortsNonInteractiveScript() =
        runTest {
            // `exec NONEXISTENT` is a special-builtin failure that
            // terminates a non-interactive shell. Verified against bash:
            //   bash -c 'exec /no/such/cmd; echo unreachable'
            //   → "cannot execute" (exit 126), no "unreachable".
            // Plain non-zero exits from special builtins (e.g. `shift 99`
            // out of range) do NOT abort in any bash mode — see
            // NON_ABORTING_SPECIAL_BUILTINS in Interpreter.kt.
            val r = Kash().exec("exec /no/such/cmd; echo unreachable")
            assertEquals(false, r.stdout.contains("unreachable"))
            assertTrue(r.exitCode != 0, "expected non-zero exit, got ${r.exitCode}")
        }

    @Test fun specialBuiltinRejectsUserFunctionInPosixMode() =
        runTest {
            // POSIX/bash posix mode: attempting to define a function
            // with the same name as a special builtin is a fatal
            // error (`name': is a special builtin`). The script
            // aborts before any subsequent commands run.
            val r =
                Kash().exec(
                    """
                    set -o posix
                    export() { echo from-function; }
                    echo unreachable
                    """.trimIndent(),
                )
            assertEquals(false, r.stdout.contains("from-function"))
            assertEquals(false, r.stdout.contains("unreachable"))
            assertTrue(r.exitCode != 0, "expected non-zero exit, got ${r.exitCode}")
        }

    @Test fun userFunctionBeatsSpecialBuiltinByDefault() =
        runTest {
            // Bash non-posix default: a function definition with the
            // same name as a special builtin shadows it (`break()` in
            // bash/func5.sub relies on this).
            val r =
                Kash().exec(
                    """
                    : () { echo from-function; }
                    :
                    """.trimIndent(),
                )
            assertTrue(r.stdout.contains("from-function"), "stdout=${r.stdout}")
        }

    @Test fun regularBuiltinLosesToUserFunction() =
        runTest {
            // echo is a regular builtin — a user function named `echo`
            // SHOULD shadow it.
            val r =
                Kash().exec(
                    """
                    echo() { command echo shadowed; }
                    echo hi
                    """.trimIndent(),
                )
            assertTrue(r.stdout.contains("shadowed"))
        }
}
