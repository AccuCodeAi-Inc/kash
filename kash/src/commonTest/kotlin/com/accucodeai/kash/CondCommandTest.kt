package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CondCommandTest {
    private suspend fun out(script: String) = Kash().exec(script).stdout

    private suspend fun exit(script: String) = Kash().exec(script).exitCode

    // -------- basic string/test ops --------

    @Test fun stringEqualMatches() =
        runTest {
            assertEquals(0, exit("[[ hello == hello ]]"))
            assertEquals(1, exit("[[ hello == world ]]"))
        }

    @Test fun stringNotEqual() =
        runTest {
            assertEquals(0, exit("[[ a != b ]]"))
            assertEquals(1, exit("[[ a != a ]]"))
        }

    @Test fun zeroLengthString() =
        runTest {
            assertEquals(0, exit("[[ -z \"\" ]]"))
            assertEquals(1, exit("[[ -z x ]]"))
        }

    @Test fun nonEmptyString() =
        runTest {
            assertEquals(0, exit("[[ -n x ]]"))
            assertEquals(1, exit("[[ -n \"\" ]]"))
        }

    @Test fun loneWordIsNonemptyTest() =
        runTest {
            assertEquals(0, exit("[[ hello ]]"))
            assertEquals(1, exit("[[ \"\" ]]"))
        }

    // -------- glob pattern matching on rhs --------

    @Test fun equalDoesGlobMatch() =
        runTest {
            assertEquals(0, exit("x=hello.txt\n[[ \$x == *.txt ]]"))
            assertEquals(1, exit("x=hello.bak\n[[ \$x == *.txt ]]"))
        }

    @Test fun notEqualWithGlob() =
        runTest {
            assertEquals(0, exit("[[ foo != b* ]]"))
            assertEquals(1, exit("[[ foo != f* ]]"))
        }

    // -------- integer ops --------

    @Test fun integerCompare() =
        runTest {
            assertEquals(0, exit("[[ 5 -eq 5 ]]"))
            assertEquals(0, exit("[[ 3 -lt 5 ]]"))
            assertEquals(1, exit("[[ 5 -lt 3 ]]"))
            assertEquals(0, exit("[[ 7 -ge 7 ]]"))
        }

    // -------- file tests (in-memory FS) --------

    @Test fun fileExistenceTests() =
        runTest {
            val script =
                """
                echo data > /tmp/x.txt
                [[ -e /tmp/x.txt ]] && echo e
                [[ -f /tmp/x.txt ]] && echo f
                [[ -s /tmp/x.txt ]] && echo s
                [[ -d /tmp ]] && echo d
                [[ -e /nope ]] || echo no-e
                """.trimIndent()
            // Test via in-memory FS
            val fs =
                com.accucodeai.kash.fs
                    .InMemoryFs()
            fs.mkdirs("/tmp")
            val r = Kash(fs = fs).exec(script)
            assertEquals("e\nf\ns\nd\nno-e\n", r.stdout)
        }

    // -------- logical combinators --------

    @Test fun lazyAnd() =
        runTest {
            assertEquals(0, exit("[[ -n x && -n y ]]"))
            assertEquals(1, exit("[[ -n \"\" && -n y ]]"))
        }

    @Test fun lazyOr() =
        runTest {
            assertEquals(0, exit("[[ -z \"\" || -z x ]]"))
            assertEquals(1, exit("[[ -z x || -z y ]]"))
        }

    @Test fun negation() =
        runTest {
            assertEquals(0, exit("[[ ! -z \"\" ]] && echo never; [[ ! -n \"\" ]]"))
        }

    @Test fun parenGrouping() =
        runTest {
            assertEquals(0, exit("[[ ( -n a || -n b ) && -n c ]]"))
            assertEquals(1, exit("[[ ( -z x ) || -n \"\" ]]"))
        }

    // -------- regex (=~) --------

    @Test fun regexMatch() =
        runTest {
            assertEquals(0, exit("[[ hello123 =~ ^hello[0-9]+\$ ]]"))
            assertEquals(1, exit("[[ hello =~ ^[0-9]+\$ ]]"))
        }

    // -------- string < > --------

    @Test fun stringLessThanAndGreater() =
        runTest {
            assertEquals(0, exit("[[ apple < banana ]]"))
            assertEquals(0, exit("[[ banana > apple ]]"))
            assertEquals(1, exit("[[ apple > banana ]]"))
        }

    // -------- integration with if --------

    @Test fun usedInsideIf() =
        runTest {
            assertEquals("yes\n", out("if [[ 5 -gt 3 ]]; then echo yes; else echo no; fi"))
        }

    // -------- extglob patterns --------
    // These verify the lexer keeps `?(...)`, `+(...)`, `@(...)`, `!(...)`, `*(...)` as a
    // single word inside `[[...]]` instead of splitting on `(`. Pattern-matching semantics
    // (whether the extglob actually matches) are out of scope for these tests.

    @Test fun extglobPlusGroupParsesAsOnePattern() =
        runTest {
            // Must not throw a parse error.
            Kash().exec("[[ x == +([a-z]) ]]; echo done")
        }

    @Test fun extglobAtGroupParsesAsOnePattern() =
        runTest {
            // Paren-balanced with `|` inside.
            Kash().exec("[[ ./foo == @(./*|../*|/*) ]]; echo done")
        }

    @Test fun extglobBangParsesAsOnePattern() =
        runTest {
            Kash().exec("[[ ab/../ == +([!/])/..?(/) ]]; echo done")
        }
}
