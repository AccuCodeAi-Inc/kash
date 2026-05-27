package com.accucodeai.kash

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** `shopt -s lastpipe` — last stage of pipeline runs in parent shell. */
class LastPipeTest {
    private fun out(script: String): String = runBlocking { Kash().exec(script).stdout }

    @Test fun defaultLastStageInSubshell() {
        // Without lastpipe, `x` is set in a subshell and lost.
        assertEquals(
            "x=initial\n",
            out(
                """
                x=initial
                printf 'new' | read x
                echo "x=${'$'}x"
                """.trimIndent(),
            ),
        )
    }

    @Test fun lastpipeKeepsAssignment() {
        assertEquals(
            "x=new\n",
            out(
                """
                shopt -s lastpipe
                x=initial
                printf 'new' | read x
                echo "x=${'$'}x"
                """.trimIndent(),
            ),
        )
    }

    @Test fun exitInForkedStageDoesNotKillParent() {
        // `exit 142` in stage 0 is forked, terminates only that subshell;
        // the parent's script keeps running and PIPESTATUS records 142.
        assertEquals(
            "after: 1 -- 142 1\ndone\n",
            out(
                """
                exit 142 | false
                echo "after: ${'$'}? -- ${'$'}{PIPESTATUS[@]}"
                echo done
                """.trimIndent(),
            ),
        )
    }

    @Test fun lastpipeWithLoopAccumulates() {
        assertEquals(
            "sum=10\n",
            out(
                """
                shopt -s lastpipe
                sum=0
                printf '1\n2\n3\n4\n' | while read n; do sum=${'$'}((sum + n)); done
                echo "sum=${'$'}sum"
                """.trimIndent(),
            ),
        )
    }
}
