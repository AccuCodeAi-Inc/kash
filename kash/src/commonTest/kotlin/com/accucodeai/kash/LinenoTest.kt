package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LinenoTest {
    private suspend fun out(script: String): String = Kash().exec(script).stdout

    @Test fun linenoReflectsCurrentLine() =
        runTest {
            // Each `echo $LINENO` prints its own source line. Bash counts
            // lines from 1 at the first statement of the script.
            val script =
                """
                echo $\LINENO
                echo $\LINENO
                echo $\LINENO
                """.trimIndent().replace("\$\\", "\$")
            assertEquals("1\n2\n3\n", out(script))
        }

    @Test fun linenoInsideIf() =
        runTest {
            // `then` clause: bash reports the line of the executed command,
            // not the line of `if`.
            val script =
                "if true; then\n" +
                    "  echo \$LINENO\n" +
                    "fi\n"
            assertEquals("2\n", out(script))
        }

    @Test fun linenoInsideFunctionBody() =
        runTest {
            // Inside a function body, bash 5.x reports the absolute script
            // line of the executing command (the bash-4 behavior of
            // body-relative counts has been retired — see the dbg-support
            // conformance tests, which require absolute).
            val script =
                "f() {\n" +
                    "  echo \$LINENO\n" +
                    "}\n" +
                    "f\n"
            assertEquals("2\n", out(script))
        }

    @Test fun linenoInsideFunctionBodyDeeper() =
        runTest {
            // Absolute script lines: the body statements are on lines 3 and 4.
            val script =
                "echo prelude\n" +
                    "f() {\n" +
                    "  echo \$LINENO\n" +
                    "  echo \$LINENO\n" +
                    "}\n" +
                    "f\n"
            assertEquals("prelude\n3\n4\n", out(script))
        }

    @Test fun linenoRestoresAfterFunctionReturns() =
        runTest {
            val script =
                "f() {\n" +
                    "  echo \$LINENO\n" +
                    "}\n" +
                    "f\n" +
                    "echo \$LINENO\n"
            assertEquals("2\n5\n", out(script))
        }

    @Test fun linenoExpandsInDoubleQuotes() =
        runTest {
            assertEquals("line=1\n", out("echo \"line=\$LINENO\""))
        }

    @Test fun linenoOnlyValidName() =
        runTest {
            // Plain env vars still resolve. `LINENO` doesn't shadow others.
            assertEquals("hi\n", out("MSG=hi\necho \$MSG"))
        }
}
