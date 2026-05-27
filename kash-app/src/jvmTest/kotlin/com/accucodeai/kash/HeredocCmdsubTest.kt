package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Heredoc body: `$(…)` modern cmdsub and `$((…))` arithmetic. */
class HeredocCmdsubTest {
    private fun run(script: String): String = runBlocking { Kash(registry = standardRegistry()).exec(script).stdout }

    @Test fun modernCmdsubInHeredocBody() {
        val out =
            run(
                """
                cat <<EOF
                hi=${'$'}(echo hello)
                EOF
                """.trimIndent(),
            )
        assertEquals("hi=hello\n", out)
    }

    @Test fun arithmeticInHeredocBody() {
        val out =
            run(
                """
                cat <<EOF
                n=${'$'}((1+1))
                EOF
                """.trimIndent(),
            )
        assertEquals("n=2\n", out)
    }

    @Test fun cmdsubWithVariableInHeredocBody() {
        val out =
            run(
                """
                x=42
                cat <<EOF
                sub=${'$'}(echo "x is ${'$'}x")
                EOF
                """.trimIndent(),
            )
        assertEquals("sub=x is 42\n", out)
    }

    @Test fun quotedHeredocSuppressesAllExpansion() {
        val out =
            run(
                """
                x=42
                cat <<'EOF'
                sub=${'$'}(echo "x is ${'$'}x")
                n=${'$'}((1+1))
                EOF
                """.trimIndent(),
            )
        assertEquals("sub=\$(echo \"x is \$x\")\nn=\$((1+1))\n", out)
    }
}
