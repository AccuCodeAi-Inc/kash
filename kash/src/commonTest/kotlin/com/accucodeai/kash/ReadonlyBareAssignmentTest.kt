package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `readonly x=5; x=6` must report a diagnostic and leave x unchanged, but
 * the surrounding script keeps running. Matches bash, which only aborts
 * non-interactive scripts when the failing assignment is a *prefix* to a
 * special builtin (POSIX §2.8.1).
 */
class ReadonlyBareAssignmentTest {
    @Test
    fun bareScalarAssignmentToReadonlyEmitsDiagnostic() =
        runTest {
            val script =
                """
                readonly x=5
                x=6
                echo "x is ${'$'}x"
                """.trimIndent()
            val r = Kash().exec(script, ExecOptions(scriptName = "./t.sh"))
            assertEquals("x is 5\n", r.stdout)
            assertTrue("expected readonly diagnostic, got <${r.stderr}>") {
                r.stderr.contains("x: readonly variable")
            }
        }

    @Test
    fun bareAppendAssignmentToReadonlyEmitsDiagnostic() =
        runTest {
            val script =
                """
                readonly x=7
                x+=3
                echo "${'$'}x"
                """.trimIndent()
            val r = Kash().exec(script, ExecOptions(scriptName = "./t.sh"))
            assertEquals("7\n", r.stdout)
            assertTrue { r.stderr.contains("x: readonly variable") }
        }

    @Test
    fun readonlyDoesNotAbortFollowingStatements() =
        runTest {
            val r = Kash().exec("readonly x=1; x=2; echo after", ExecOptions(scriptName = "./t.sh"))
            assertEquals("after\n", r.stdout)
        }
}
