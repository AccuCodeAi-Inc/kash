package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof that `python3` is reachable from a kash script and that
 * heredoc redirection feeds the program into the interpreter via stdin.
 * Mirrors the typical shell idiom:
 *
 *   python3 <<EOF
 *   print("hello")
 *   EOF
 *
 * The kash parser captures the heredoc body, the interpreter binds it as
 * stdin for the command, and `Python3Command` with no operands routes
 * `PythonSource.Stdin` into the GraalPy engine which reads the source from
 * the bound RawSource.
 */
class Python3IntegrationTest {
    @Test fun typeReportsPython3() {
        runTest {
            val r = Kash(registry = standardRegistry()).exec("type python3")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertTrue(r.stdout.contains("python3"), "stdout=${r.stdout}")
        }
    }

    @Test fun dashCRunsInline() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "python3 -c 'print(1 + 1)'",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("2\n", r.stdout)
        }
    }

    @Test fun heredocFeedsProgramToStdin() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """
                    python3 <<EOF
                    print("hello from heredoc")
                    EOF
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("hello from heredoc\n", r.stdout)
        }
    }

    @Test fun heredocStripTabsVariant() {
        runTest {
            // `<<-EOF` strips leading tabs from each body line (and the delimiter).
            // The body lines below begin with literal tab characters.
            val script = "python3 <<-EOF\n\tprint('tabs stripped')\n\tEOF\n"
            val r = Kash(registry = standardRegistry()).exec(script)
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("tabs stripped\n", r.stdout)
        }
    }

    @Test fun quotedHeredocSuppressesExpansion() {
        runTest {
            // Quoting the delimiter (`'EOF'`) makes the body literal — kash must
            // not interpret `$NAME` as a shell var inside the Python source.
            val script =
                """
                NAME=ignored
                python3 <<'EOF'
                print("literal: ${"$"}NAME")
                EOF
                """.trimIndent()
            val r = Kash(registry = standardRegistry()).exec(script)
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("literal: \$NAME\n", r.stdout)
        }
    }

    @Test fun unquotedHeredocExpandsShellVars() {
        runTest {
            // Without quotes the heredoc body undergoes parameter expansion
            // before the bytes reach python3's stdin.
            val script =
                """
                GREETING=hi
                python3 <<EOF
                print("${"$"}GREETING from kash")
                EOF
                """.trimIndent()
            val r = Kash(registry = standardRegistry()).exec(script)
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("hi from kash\n", r.stdout)
        }
    }

    @Test fun hereStringFeedsProgramToStdin() {
        runTest {
            // `<<<` herestring: the word becomes stdin with a trailing newline.
            val r =
                Kash(registry = standardRegistry()).exec(
                    "python3 <<< 'print(2 * 3)'",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("6\n", r.stdout)
        }
    }

    @Test fun pipeFromEchoFeedsProgramToStdin() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    """echo 'print(7 * 6)' | python3""",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("42\n", r.stdout)
        }
    }
}
