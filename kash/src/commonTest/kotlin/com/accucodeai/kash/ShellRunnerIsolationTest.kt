package com.accucodeai.kash

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `${THIS_SH} ./script.sh` invokes a fresh shell process — bash does not
 * propagate shell-only attributes (readonly, integer, case bits) across
 * such an invocation; only exported env variables cross. Without isolation
 * the parent's `readonly x` would falsely block `x=…` inside the child
 * script.
 *
 * Repro for the appendop conformance regression that emitted a duplicate
 * `x: readonly variable` from `./appendop2.sub` after the parent set x
 * readonly.
 */
class ShellRunnerIsolationTest {
    @Test
    fun readonlyDoesNotLeakIntoShChildScript() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/child.sh", "x=99\necho child=\$x".encodeToByteArray())
            val script =
                """
                readonly x=1
                sh /child.sh
                echo parent=${'$'}x
                """.trimIndent()
            val r = Kash(fs = fs).exec(script)
            assertEquals("child=99\nparent=1\n", r.stdout)
            assertEquals("", r.stderr)
        }

    @Test
    fun integerAttributeDoesNotLeakIntoShChildScript() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/child.sh", "y=2+3\necho child=\$y".encodeToByteArray())
            val script =
                """
                typeset -i y
                sh /child.sh
                """.trimIndent()
            // Without isolation the child's `y=2+3` would arithmetic-evaluate
            // to "5"; bash treats it as the literal string "2+3".
            val r = Kash(fs = fs).exec(script)
            assertEquals("child=2+3\n", r.stdout)
        }
}
