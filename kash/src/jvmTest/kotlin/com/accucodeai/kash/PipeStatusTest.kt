package com.accucodeai.kash

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** `PIPESTATUS[@]` per-stage exits + `set -o pipefail` overall exit. */
class PipeStatusTest {
    private fun out(script: String): String = runBlocking { Kash().exec(script).stdout }

    private fun code(script: String): Int = runBlocking { Kash().exec(script).exitCode }

    @Test fun pipeStatusReflectsEachStage() {
        // `true | false | true` — exits 0, 1, 0.
        assertEquals(
            "0 1 0\n",
            out("true | false | true; echo \${PIPESTATUS[@]}"),
        )
    }

    @Test fun singleCommandPipeStatusHasOneEntry() {
        assertEquals(
            "5\n",
            out("(exit 5); echo \${PIPESTATUS[0]}"),
        )
    }

    @Test fun pipefailReportsRightmostNonzero() {
        // Default behavior: pipeline exit = last command's exit.
        assertEquals(0, code("true | false | true"))
        // With pipefail: pipeline exit = rightmost non-zero = the `false` stage.
        assertEquals(1, code("set -o pipefail\ntrue | false | true"))
    }

    @Test fun pipefailAllSuccessIsZero() {
        assertEquals(0, code("set -o pipefail\ntrue | true | true"))
    }

    @Test fun pipefailToggleOff() {
        assertEquals(
            0,
            code(
                """
                set -o pipefail
                set +o pipefail
                true | false | true
                """.trimIndent(),
            ),
        )
    }
}
