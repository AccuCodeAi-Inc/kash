package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DebugReturnTrapTest {
    private suspend fun out(script: String): String = Kash().exec(script).stdout

    @Test fun debugFiresBeforeEachSimpleCommand() =
        runTest {
            // The DEBUG trap runs before every simple command in the script
            // body (including the trap-installer itself doesn't recurse).
            val script =
                """
                trap 'echo D' DEBUG
                echo a
                echo b
                """.trimIndent()
            // Each `echo a` / `echo b` is preceded by `echo D`. The `trap`
            // command itself fires DEBUG too, but the trap isn't installed
            // until *after* `trap` runs, so it's not preceded.
            assertEquals("D\na\nD\nb\n", out(script))
        }

    @Test fun debugSeesLineno() =
        runTest {
            val script =
                """
                trap 'echo line=$\LINENO' DEBUG
                echo first
                echo second
                """.trimIndent().replace("\$\\", "\$")
            // LINENO inside the handler reports the line of the command
            // *about to run* — the line we just updated `currentLine` to.
            assertEquals("line=2\nfirst\nline=3\nsecond\n", out(script))
        }

    @Test fun debugDoesNotRecurseIntoHandler() =
        runTest {
            // If DEBUG fired inside its own handler we'd loop forever or
            // print "Dhandler" many times. Guard ensures it fires once per
            // outer simple command.
            val script =
                """
                trap 'echo H' DEBUG
                echo x
                """.trimIndent()
            assertEquals("H\nx\n", out(script))
        }

    @Test fun returnDoesNotFireForUntracedFunction() =
        runTest {
            // Bash semantics: without `declare -ft` (or `set -T`), a top-
            // level RETURN trap is NOT inherited into function bodies, so
            // `f` returning does not fire the handler.
            val script =
                """
                trap 'echo R' RETURN
                f() {
                  echo in-f
                }
                f
                echo done
                """.trimIndent()
            assertEquals("in-f\ndone\n", out(script))
        }

    @Test fun returnFiresForTracedFunction() =
        runTest {
            val script =
                """
                trap 'echo R' RETURN
                f() {
                  echo in-f
                }
                declare -ft f
                f
                echo done
                """.trimIndent()
            assertEquals("in-f\nR\ndone\n", out(script))
        }

    @Test fun declarePlusFtClearsTrace() =
        runTest {
            val script =
                """
                trap 'echo R' RETURN
                f() { echo in-f; }
                declare -ft f
                f
                declare +ft f
                f
                echo done
                """.trimIndent()
            // First call: traced → R fires. Second call: untraced → R does not.
            assertEquals("in-f\nR\nin-f\ndone\n", out(script))
        }

    @Test fun debugDoesNotFireInUntracedFunction() =
        runTest {
            val script =
                """
                trap 'echo D' DEBUG
                f() { echo inside; }
                f
                """.trimIndent()
            // DEBUG fires before `f` (the call site is a simple command) but
            // not for `echo inside` (untraced function body).
            assertEquals("D\ninside\n", out(script))
        }

    @Test fun debugFiresInTracedFunction() =
        runTest {
            val script =
                """
                trap 'echo D' DEBUG
                f() { echo inside; }
                declare -ft f
                f
                """.trimIndent()
            // DEBUG fires before `declare`, before `f` call, before
            // the function-body Group's `{...}` (bash fires DEBUG for
            // compound commands too, not just simple), and before
            // `echo inside`.
            assertEquals("D\nD\nD\nD\ninside\n", out(script))
        }

    @Test fun returnDoesNotFireForNonFunctionCommands() =
        runTest {
            val script =
                """
                trap 'echo R' RETURN
                echo a
                echo b
                """.trimIndent()
            assertEquals("a\nb\n", out(script))
        }
}
