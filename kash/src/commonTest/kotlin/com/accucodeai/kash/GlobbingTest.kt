package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobbingTest {
    private suspend fun setupFs(): Kash {
        val k = Kash()
        k.fs.writeBytes("/work/a.txt", "A\n".encodeToByteArray())
        k.fs.writeBytes("/work/b.txt", "B\n".encodeToByteArray())
        k.fs.writeBytes("/work/c.md", "C\n".encodeToByteArray())
        k.fs.writeBytes("/work/sub/d.txt", "D\n".encodeToByteArray())
        return k
    }

    @Test fun starMatchesAllNonHidden() =
        runTest {
            val k = setupFs()
            val r = k.exec("echo /work/*.txt")
            assertEquals("/work/a.txt /work/b.txt\n", r.stdout)
        }

    @Test fun questionMatchesSingleChar() =
        runTest {
            val k = setupFs()
            val r = k.exec("echo /work/?.txt")
            assertEquals("/work/a.txt /work/b.txt\n", r.stdout)
        }

    @Test fun bracketMatchesClass() =
        runTest {
            val k = setupFs()
            val r = k.exec("echo /work/[ab].txt")
            assertEquals("/work/a.txt /work/b.txt\n", r.stdout)
        }

    @Test fun nonMatchingGlobReturnsLiteral() =
        runTest {
            val k = setupFs()
            val r = k.exec("echo /work/*.png")
            assertEquals("/work/*.png\n", r.stdout)
        }

    @Test fun globOnCatReadsMatchingFiles() =
        runTest {
            val k = setupFs()
            val r = k.exec("cat /work/*.txt")
            assertEquals("A\nB\n", r.stdout)
        }

    @Test fun quotedStarIsLiteral() =
        runTest {
            val k = setupFs()
            val r = k.exec("""echo "/work/*.txt"""")
            assertEquals("/work/*.txt\n", r.stdout)
        }

    @Test fun wordSplittingOnUnquotedExpansion() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            FILES="a b c"
            for f in $FILES; do echo "[$f]"; done
                    """.trimIndent(),
                )
            assertEquals("[a]\n[b]\n[c]\n", r.stdout)
        }

    @Test fun quotedExpansionPreventsSplitting() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            FILES="a b c"
            for f in "$FILES"; do echo "[$f]"; done
                    """.trimIndent(),
                )
            assertEquals("[a b c]\n", r.stdout)
        }
}
