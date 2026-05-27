package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `exit [N]` is a POSIX special builtin that terminates the shell. It funnels
 * into [Interpreter.ScriptAbortException], already caught at the top-level
 * `run()`.
 */
class ExitIntrinsicTest {
    @Test
    fun exitWithNoArgReturnsLastExit() =
        runTest {
            // `false` sets $? = 1, then exit (no arg) propagates it.
            val r = Kash().exec("false\nexit")
            assertEquals(1, r.exitCode)
        }

    @Test
    fun exitWithExplicitCode() =
        runTest {
            val r = Kash().exec("exit 42")
            assertEquals(42, r.exitCode)
        }

    @Test
    fun exitStopsSubsequentStatements() =
        runTest {
            val r = Kash().exec("echo before\nexit 3\necho after")
            assertEquals("before\n", r.stdout)
            assertEquals(3, r.exitCode)
        }

    @Test
    fun exitFiresExitTrap() =
        runTest {
            val r = Kash().exec("trap 'echo bye' EXIT\nexit 0")
            assertEquals("bye\n", r.stdout)
            assertEquals(0, r.exitCode)
        }
}
