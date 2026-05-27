package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the `compgen` builtin — the user-facing surface of
 * tab completion. Drives compgen through a real [Kash] interpreter so we
 * exercise registry / intrinsic / function / alias / env enumeration end
 * to end.
 */
class CompgenIntrinsicTest {
    @Test fun compgen_b_listsBuiltins() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("compgen -b")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val names =
                r.stdout
                    .trim()
                    .lines()
                    .toSet()
            // Spot-check a few intrinsics that should always be present.
            assertTrue("set" in names, "expected 'set' in builtins: $names")
            assertTrue("export" in names)
            assertTrue("compgen" in names)
        }
    }

    @Test fun compgen_b_withPrefix_filters() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("compgen -b ex")
            assertEquals(0, r.exitCode)
            val names = r.stdout.trim().lines()
            for (n in names) {
                assertTrue(n.startsWith("ex"), "non-matching: $n")
            }
            assertTrue("export" in names)
            assertTrue("exit" in names)
        }
    }

    @Test fun compgen_c_includesIntrinsicsAndRegistry() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("compgen -c")
            assertEquals(0, r.exitCode)
            val names =
                r.stdout
                    .trim()
                    .lines()
                    .toSet()
            // Intrinsic.
            assertTrue("export" in names)
            // Registry tool (echo is a POSIX util).
            assertTrue("echo" in names)
        }
    }

    @Test fun compgen_v_listsEnvVars() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "FOO=bar compgen -v F",
                    options = ExecOptions(env = mapOf("FOOBAR" to "1", "FIZZ" to "2")),
                )
            assertEquals(0, r.exitCode)
            val names =
                r.stdout
                    .trim()
                    .lines()
                    .toSet()
            assertTrue("FOOBAR" in names)
            assertTrue("FIZZ" in names)
            for (n in names) assertTrue(n.startsWith("F"), "non-matching: $n")
        }
    }

    @Test fun compgen_a_listsAliases() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "alias ll='ls -l'; alias gst='git status'; compgen -a",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val names =
                r.stdout
                    .trim()
                    .lines()
                    .toSet()
            assertTrue("ll" in names)
            assertTrue("gst" in names)
        }
    }

    @Test fun compgen_A_function_listsFunctions() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "greet() { echo hi; }; bye() { echo bye; }; compgen -A function",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val names =
                r.stdout
                    .trim()
                    .lines()
                    .toSet()
            assertTrue("greet" in names)
            assertTrue("bye" in names)
        }
    }

    @Test fun compgen_k_listsKeywords() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("compgen -k")
            assertEquals(0, r.exitCode)
            val names =
                r.stdout
                    .trim()
                    .lines()
                    .toSet()
            assertTrue("if" in names)
            assertTrue("then" in names)
            assertTrue("for" in names)
            assertTrue("done" in names)
        }
    }

    @Test fun compgen_W_explicitWordList() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("compgen -W 'one two three' t")
            assertEquals(0, r.exitCode)
            assertEquals("two\nthree", r.stdout.trim())
        }
    }

    @Test fun compgen_noMatches_returnsExit1() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("compgen -b zzzzzzzz")
            assertEquals(1, r.exitCode)
            assertEquals("", r.stdout)
        }
    }

    @Test fun compgen_prefixAndSuffix_decorateMatches() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("compgen -W 'a b c' -P '<' -S '>'")
            assertEquals(0, r.exitCode)
            assertEquals("<a>\n<b>\n<c>", r.stdout.trim())
        }
    }

    @Test fun compgen_d_listsDirectoriesOnly() {
        runTest {
            val k = Kash(registry = standardRegistry())
            k.fs.mkdirs("/work/sub")
            k.fs.writeBytes("/work/file.txt", ByteArray(0))
            val r = k.exec("cd /work; compgen -d")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val names =
                r.stdout
                    .trim()
                    .lines()
                    .toSet()
            assertTrue("sub" in names)
            assertTrue("file.txt" !in names, "regular file leaked: $names")
        }
    }
}
