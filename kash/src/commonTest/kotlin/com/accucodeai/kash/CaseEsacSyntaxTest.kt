package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * POSIX.2 grammar rule 4: in `case word in pattern) ... esac`, an
 * unparenthesized `esac` is reserved as the case-statement terminator and
 * may not be used as a pattern. Bash 5.2+ rejects this with a syntax error
 * near `)`.
 */
class CaseEsacSyntaxTest {
    @Test
    fun unparenthesizedEsacPatternIsSyntaxError() =
        runTest {
            // Direct (non-eval) form: surfaces the parse error as the
            // exec result's exitCode.
            val r =
                Kash().exec(
                    "case foo in esac) echo bad ;; esac",
                )
            assertNotEquals(0, r.exitCode, "stdout=${r.stdout} stderr=${r.stderr}")
        }

    @Test
    fun parenthesizedEsacPatternIsOk() =
        runTest {
            // With `(esac)` the keyword *is* allowed as a pattern.
            val r =
                Kash().exec(
                    "eval 'case esac in (esac) echo ok ;; esac' ; echo exit=\$?",
                )
            assertEquals("ok\nexit=0\n", r.stdout)
        }
}
