package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * POSIX `getopts` — walks positional parameters and updates `OPTIND`,
 * `OPTARG`, and the named variable for each option character. Tests cover
 * the posix2 conformance script's getopts invocation
 * (`getopts a: store -a aoptval` expects OPTIND=3 store=a OPTARG=aoptval).
 */
class GetoptsTest {
    @Test
    fun simpleFlagWithRequiredArg() =
        runTest {
            val script =
                """
                set -- -a aoptval
                getopts a: store
                echo OPTIND=${'$'}OPTIND store=${'$'}store OPTARG=${'$'}OPTARG
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("OPTIND=3 store=a OPTARG=aoptval\n", r.stdout)
        }

    @Test
    fun clusteredShortOptionsWithoutArg() =
        runTest {
            // -ab -c → three rounds: a, b, c.
            val script =
                """
                set -- -ab -c
                while getopts abc opt; do
                  echo "${'$'}opt"
                done
                echo OPTIND=${'$'}OPTIND
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("a\nb\nc\nOPTIND=3\n", r.stdout)
        }

    @Test
    fun unknownOptionSilentMode() =
        runTest {
            // Leading `:` enables silent mode: unknown opts → `?` + OPTARG=<char>.
            val script =
                """
                set -- -z
                getopts :a opt
                echo "opt=${'$'}opt OPTARG=${'$'}OPTARG"
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("opt=? OPTARG=z\n", r.stdout)
        }

    @Test
    fun missingRequiredArgSilentMode() =
        runTest {
            val script =
                """
                set -- -a
                getopts :a: opt
                echo "opt=${'$'}opt OPTARG=${'$'}OPTARG"
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("opt=: OPTARG=a\n", r.stdout)
        }

    @Test
    fun optindInitialValueIsOne() =
        runTest {
            // posix2 conformance checks `test "$OPTIND" = 1` at the top.
            val r = Kash().exec("echo \$OPTIND")
            assertEquals("1\n", r.stdout)
        }
}
