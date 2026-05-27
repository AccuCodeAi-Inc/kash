package com.accucodeai.kash

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** `${!array[@]}` / `${!array[*]}` — indirect keys/indices. */
class AssocKeysTest {
    private fun out(script: String): String = runBlocking { Kash().exec(script).stdout }

    @Test fun assocKeysInsertionOrder() {
        assertEquals(
            "foo bar baz\n",
            out(
                """
                declare -A a
                a[foo]=1; a[bar]=2; a[baz]=3
                echo ${'$'}{!a[@]}
                """.trimIndent(),
            ),
        )
    }

    @Test fun indexedKeysAscending() {
        assertEquals(
            "0 3 5\n",
            out(
                """
                declare -a b
                b[0]=x; b[5]=y; b[3]=z
                echo ${'$'}{!b[@]}
                """.trimIndent(),
            ),
        )
    }

    @Test fun starJoinsWithIfs() {
        assertEquals(
            "foo|bar\n",
            out(
                """
                declare -A a
                a[foo]=1; a[bar]=2
                IFS='|'
                echo "${'$'}{!a[*]}"
                """.trimIndent(),
            ),
        )
    }

    @Test fun quotedAtSpreadsAsSeparateFields() {
        // Each key is its own argv element; printf %s\n prints one per line.
        assertEquals(
            "foo\nbar\n",
            out(
                """
                declare -A a
                a[foo]=1; a[bar]=2
                for k in "${'$'}{!a[@]}"; do echo "${'$'}k"; done
                """.trimIndent(),
            ),
        )
    }

    @Test fun emptyArrayReturnsEmpty() {
        assertEquals(
            "[]\n",
            out(
                """
                declare -A a
                echo "[${'$'}{!a[@]}]"
                """.trimIndent(),
            ),
        )
    }
}
