package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the `complete` and `compopt` builtins driven
 * through a real interpreter. Behavior should round-trip: register a
 * spec, print it back with `complete -p`, get bash-compatible output.
 */
class CompleteIntrinsicTest {
    @Test fun complete_p_emptyRegistry_printsNothing() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("complete -p")
            assertEquals(0, r.exitCode)
            assertEquals("", r.stdout)
        }
    }

    @Test fun complete_bare_printsAllSpecs() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    complete -c type
                    complete -a unalias
                    complete
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines =
                r.stdout
                    .trim()
                    .lines()
                    .toSet()
            assertTrue("complete -c type" in lines, "saw: $lines")
            assertTrue("complete -a unalias" in lines)
        }
    }

    @Test fun complete_p_specificName() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    complete -W 'one two three' foo
                    complete -p foo
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode)
            assertEquals("complete -W 'one two three' foo", r.stdout.trim())
        }
    }

    @Test fun complete_p_missingName_errorsExit1() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("complete -p notthere")
            assertEquals(1, r.exitCode)
            assertTrue("no completion specification" in r.stderr, "stderr=${r.stderr}")
        }
    }

    @Test fun complete_r_removesSpec() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    complete -c type
                    complete -r type
                    complete -p type
                    """.trimIndent(),
                )
            assertEquals(1, r.exitCode)
            assertTrue("no completion specification" in r.stderr)
        }
    }

    @Test fun complete_r_noName_clearsAll() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    complete -c a
                    complete -c b
                    complete -r
                    complete
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("", r.stdout)
        }
    }

    @Test fun complete_invalidOption_errorsExit2() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("complete -z")
            assertEquals(2, r.exitCode)
            assertTrue("-z" in r.stderr, "stderr=${r.stderr}")
            assertTrue("usage" in r.stderr)
        }
    }

    @Test fun complete_dashV_invalidForComplete() {
        runTest {
            // -V is a compgen-only flag. complete should reject it.
            val r = Kash(registry = standardRegistry()).exec("complete -V name")
            assertEquals(2, r.exitCode)
            assertTrue("-V" in r.stderr, "stderr=${r.stderr}")
        }
    }

    @Test fun complete_registerOptions_roundTripsInOrder() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    complete -o nospace -o filenames -o bashdefault -F _cb cd
                    complete -p cd
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            // Options are printed alphabetically; -F comes after.
            assertEquals(
                "complete -o bashdefault -o filenames -o nospace -F _cb cd",
                r.stdout.trim(),
            )
        }
    }

    @Test fun complete_multipleNames_oneSpec() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    complete -c nohup exec nice eval
                    complete -p nohup
                    complete -p exec
                    complete -p nice
                    complete -p eval
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines =
                r.stdout
                    .trim()
                    .lines()
                    .toSet()
            assertTrue("complete -c nohup" in lines)
            assertTrue("complete -c exec" in lines)
            assertTrue("complete -c nice" in lines)
            assertTrue("complete -c eval" in lines)
        }
    }

    @Test fun compopt_addsAndRemovesOptions() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    complete -c -o nospace foo
                    compopt -o filenames foo
                    compopt +o nospace foo
                    complete -p foo
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("complete -o filenames -c foo", r.stdout.trim())
        }
    }

    @Test fun compopt_invalidOption_errorsExit2() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    complete -c foo
                    compopt -o nooption foo
                    """.trimIndent(),
                )
            assertEquals(2, r.exitCode)
            assertTrue("nooption" in r.stderr, "stderr=${r.stderr}")
        }
    }

    @Test fun compopt_missingName_outsideCompletion_errors() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("compopt -o nospace")
            assertEquals(1, r.exitCode)
            assertTrue("not currently executing" in r.stderr, "stderr=${r.stderr}")
        }
    }

    @Test fun compgen_X_filter_excludesMatches() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "compgen -W 'foo bar baz quux' -X 'b*'",
                )
            assertEquals(0, r.exitCode)
            // bar, baz excluded; foo, quux kept.
            val lines = r.stdout.trim().lines()
            assertEquals(setOf("foo", "quux"), lines.toSet())
        }
    }

    @Test fun compgen_X_negated_keepsMatches() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "compgen -W 'foo bar baz quux' -X '!b*'",
                )
            assertEquals(0, r.exitCode)
            val lines = r.stdout.trim().lines()
            assertEquals(setOf("bar", "baz"), lines.toSet())
        }
    }

    @Test fun compgen_invalidOption_errorsExit2() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("compgen -r")
            assertEquals(2, r.exitCode)
            assertTrue("-r" in r.stderr, "stderr=${r.stderr}")
        }
    }

    @Test fun compgen_V_invalidIdentifier_errors() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("compgen -V invalid-name -b")
            assertEquals(2, r.exitCode)
            assertTrue("not a valid identifier" in r.stderr, "stderr=${r.stderr}")
        }
    }
}
