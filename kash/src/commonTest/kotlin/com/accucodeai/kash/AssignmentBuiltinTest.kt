package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * For the bash "assignment builtins" (`declare`, `typeset`, `local`,
 * `readonly`, `export`) the parser must tokenize `NAME=value` and
 * `NAME=(...)` operands past the command name as assignments — not as
 * plain words. The structured form survives into the intrinsic so an assoc
 * array literal is applied as a real assoc init, not as a scalar named
 * after the textual `=(...)` blob.
 */
class AssignmentBuiltinTest {
    @Test
    fun typesetAWithAssocLiteralInitializes() =
        runTest {
            val r = Kash().exec("typeset -A foo=([one]=bar [two]=baz)\ntypeset -p foo")
            // The order of keys in `declare -p` matches insertion order
            // (LinkedHashMap) — bash's hash-bucket order is implementation
            // defined; assert on content, not key order.
            assertTrue("expected assoc dump, got <${r.stdout}>") {
                r.stdout.contains("[one]=\"bar\"") && r.stdout.contains("[two]=\"baz\"")
            }
            assertTrue { r.stdout.startsWith("declare -A foo=(") }
        }

    @Test
    fun typesetIWithIndexedArrayLiteralInitializes() =
        runTest {
            val r = Kash().exec("typeset -ai iarr=(1 2 3)\ntypeset -p iarr")
            assertEquals("declare -ai iarr=([0]=\"1\" [1]=\"2\" [2]=\"3\")\n", r.stdout)
        }

    @Test
    fun declarePlainAssignmentAfterFlag() =
        runTest {
            val r = Kash().exec("declare -i x=5\necho \$x")
            assertEquals("5\n", r.stdout)
        }

    @Test
    fun integerPrefixAppendArithmeticAdds() =
        runTest {
            val script =
                """
                typeset -i x=2
                x+=5 echo prefixed
                echo "${'$'}x"
                """.trimIndent()
            // Without the integer-attr-aware prefix assignment, `x+=5` would
            // concat "2"+"5"="25". With it, x becomes 7 (2+5). Since `echo`
            // is not a special builtin, the prefix doesn't persist past the
            // command — so the final echo still prints 2.
            val r = Kash().exec(script)
            assertEquals("prefixed\n2\n", r.stdout)
        }

    @Test
    fun integerPrefixAppendPersistsForSpecialBuiltin() =
        runTest {
            val script =
                """
                typeset -i x=2
                x+=5 eval echo run
                echo "${'$'}x"
                """.trimIndent()
            // eval is a POSIX special builtin — prefix assignments persist.
            val r = Kash().exec(script)
            assertEquals("run\n7\n", r.stdout)
        }
}
