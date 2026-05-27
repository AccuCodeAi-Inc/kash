package com.accucodeai.kash

import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TestBuiltinTest {
    @Test fun stringEquality() =
        runTest {
            assertEquals(0, Kash().exec("[ foo = foo ]").exitCode)
            assertEquals(1, Kash().exec("[ foo = bar ]").exitCode)
        }

    @Test fun stringInequality() =
        runTest {
            assertEquals(0, Kash().exec("[ foo != bar ]").exitCode)
            assertEquals(1, Kash().exec("[ foo != foo ]").exitCode)
        }

    @Test fun emptyAndNonEmpty() =
        runTest {
            assertEquals(0, Kash().exec("[ -z \"\" ]").exitCode)
            assertEquals(1, Kash().exec("[ -z hi ]").exitCode)
            assertEquals(0, Kash().exec("[ -n hi ]").exitCode)
            assertEquals(1, Kash().exec("[ -n \"\" ]").exitCode)
        }

    @Test fun integerCompare() =
        runTest {
            assertEquals(0, Kash().exec("[ 5 -eq 5 ]").exitCode)
            assertEquals(1, Kash().exec("[ 5 -eq 6 ]").exitCode)
            assertEquals(0, Kash().exec("[ 3 -lt 4 ]").exitCode)
            assertEquals(0, Kash().exec("[ 7 -ge 7 ]").exitCode)
            assertEquals(1, Kash().exec("[ 7 -gt 7 ]").exitCode)
        }

    @Test fun fileTests() =
        runTest {
            val k = Kash()
            k.fs.writeBytes("/tmp/exists.txt", "hi".encodeToByteArray())
            assertEquals(0, k.exec("[ -e /tmp/exists.txt ]").exitCode)
            assertEquals(1, k.exec("[ -e /tmp/missing.txt ]").exitCode)
            assertEquals(0, k.exec("[ -f /tmp/exists.txt ]").exitCode)
            assertEquals(0, k.exec("[ -d /tmp ]").exitCode)
            assertEquals(1, k.exec("[ -d /tmp/exists.txt ]").exitCode)
            assertEquals(0, k.exec("[ -s /tmp/exists.txt ]").exitCode)
        }

    @Test fun permBitsRWX() =
        runTest {
            val k = Kash()
            k.fs.writeBytes("/tmp/file", "x".encodeToByteArray())
            // 0o400 — owner-readable only.
            k.fs.chmod("/tmp/file", 0b100_000_000)
            assertEquals(0, k.exec("[ -r /tmp/file ]").exitCode)
            assertEquals(1, k.exec("[ -w /tmp/file ]").exitCode)
            assertEquals(1, k.exec("[ -x /tmp/file ]").exitCode)
            // 0o755 — full owner perms.
            k.fs.chmod("/tmp/file", 0b111_101_101)
            assertEquals(0, k.exec("[ -r /tmp/file ]").exitCode)
            assertEquals(0, k.exec("[ -w /tmp/file ]").exitCode)
            assertEquals(0, k.exec("[ -x /tmp/file ]").exitCode)
            // Missing → false (not error).
            assertEquals(1, k.exec("[ -r /tmp/missing ]").exitCode)
        }

    @Test fun symlinkTests() =
        runTest {
            val k = Kash()
            k.fs.writeBytes("/tmp/real.txt", "x".encodeToByteArray())
            k.fs.createSymlink("/tmp/good", "/tmp/real.txt")
            k.fs.createSymlink("/tmp/dangle", "/tmp/missing")

            // -L / -h identify symlinks (dangling or not).
            assertEquals(0, k.exec("[ -L /tmp/good ]").exitCode)
            assertEquals(0, k.exec("[ -h /tmp/dangle ]").exitCode)
            assertEquals(1, k.exec("[ -L /tmp/real.txt ]").exitCode)

            // POSIX -e/-f follow links: dangling → false.
            assertEquals(0, k.exec("[ -e /tmp/good ]").exitCode)
            assertEquals(1, k.exec("[ -e /tmp/dangle ]").exitCode)
            assertEquals(0, k.exec("[ -f /tmp/good ]").exitCode)
            assertEquals(1, k.exec("[ -f /tmp/dangle ]").exitCode)
        }

    @Test fun negation() =
        runTest {
            assertEquals(0, Kash().exec("[ ! foo = bar ]").exitCode)
            assertEquals(1, Kash().exec("[ ! foo = foo ]").exitCode)
        }

    @Test fun andOr() =
        runTest {
            assertEquals(0, Kash().exec("[ foo = foo -a bar = bar ]").exitCode)
            assertEquals(1, Kash().exec("[ foo = foo -a bar = baz ]").exitCode)
            assertEquals(0, Kash().exec("[ foo = bar -o baz = baz ]").exitCode)
        }

    @Test fun testCommandSameAsBracket() =
        runTest {
            assertEquals(0, Kash().exec("test foo = foo").exitCode)
            assertEquals(1, Kash().exec("test foo = bar").exitCode)
        }

    @Test fun ttyTestFollowsSessionInteractivity() =
        runTest {
            // Non-interactive (default): no fd is a tty.
            val nonInteractive = Kash(registry = standardRegistry(), isInteractive = false).newSession()
            try {
                assertEquals(1, nonInteractive.exec("[ -t 0 ]").exitCode)
                assertEquals(1, nonInteractive.exec("[ -t 1 ]").exitCode)
                assertEquals(1, nonInteractive.exec("[ -t 2 ]").exitCode)
                assertEquals(1, nonInteractive.exec("[ -t 99 ]").exitCode)
            } finally {
                nonInteractive.close()
            }

            // Interactive: stdin/stdout/stderr report tty; other fds do not.
            val interactive = Kash(registry = standardRegistry(), isInteractive = true).newSession()
            try {
                assertEquals(0, interactive.exec("[ -t 0 ]").exitCode)
                assertEquals(0, interactive.exec("[ -t 1 ]").exitCode)
                assertEquals(0, interactive.exec("[ -t 2 ]").exitCode)
                assertEquals(1, interactive.exec("[ -t 3 ]").exitCode)
                assertEquals(1, interactive.exec("[ -t notanumber ]").exitCode)
            } finally {
                interactive.close()
            }
        }

    @Test fun missingClosingBracket() =
        runTest {
            val r = Kash().exec("[ foo = foo")
            assertEquals(2, r.exitCode)
        }

    @Test fun integrationWithIf() =
        runTest {
            val script =
                $$"""
            for n in 1 2 3 4 5; do
              if [ $n -lt 3 ]; then echo "small $n"
              elif [ $n -eq 3 ]; then echo "three"
              else echo "big $n"
              fi
            done
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("small 1\nsmall 2\nthree\nbig 4\nbig 5\n", r.stdout)
        }

    @Test fun integrationWithWhile() =
        runTest {
            // While-loop with counter — but we have no arithmetic yet, so iterate over a fixed list.
            val script =
                $$"""
            COUNT=0
            for x in a b c d; do
              if [ -n "$x" ]; then
                echo "got: $x"
              fi
            done
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("got: a\ngot: b\ngot: c\ngot: d\n", r.stdout)
        }
}
