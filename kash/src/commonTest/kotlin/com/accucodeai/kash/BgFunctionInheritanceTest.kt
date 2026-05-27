package com.accucodeai.kash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * POSIX: a backgrounded command runs in a subshell environment that
 * inherits shell functions from the parent shell. From XCU §2.12
 * (Shell Execution Environment) and §2.9.3.1 (Asynchronous Lists):
 *
 *   "Each command in an asynchronous list shall be executed in a
 *    subshell environment."
 *
 * §2.12 defines the subshell environment as a *duplicate* of the shell
 * environment, "and includes ... shell functions". Bash matches this:
 * `f(){ echo hi; }; f & wait` prints `hi`.
 *
 * Today kash diverges — the bg subshell loses the function table, so
 * recursive bg-call patterns (e.g. the classic `:(){ :|:& };:` fork
 * bomb) silently no-op. These tests pin the gap so a future fix in
 * `dispatchBackground` / `forkSubshell` can flip them green.
 *
 * Sibling spec text confirming POSIX intent:
 *   - XCU §2.9.5 (Function Definition Command): functions defined in the
 *     current shell environment "shall be inherited" by all subshells.
 *   - bash(1) — "Shell functions ... are inherited by child shells".
 */
class BgFunctionInheritanceTest {
    @Test
    fun subshellSeesFunction_sanity() =
        runTest {
            // Confirms the baseline: synchronous `(f)` subshell sees the
            // function. This already passes today — the gap is only on
            // the bg path.
            val r = Kash().exec($$"f(){ echo SYNC; }; (f)")
            assertEquals("SYNC\n", r.stdout)
        }

    @Test
    fun backgroundedFunctionCall_inheritsParentFunctions() =
        runTest {
            // This is the POSIX-required behavior that kash currently
            // gets wrong. `f & wait` should print SYNC, since the
            // backgrounded execution runs in a subshell that inherits
            // the parent's function table.
            val s = Kash(registry = standardRegistry(), parentContext = Dispatchers.Default).newSession()
            try {
                val r = s.exec($$"f(){ echo BG; }; f & wait")
                assertEquals("BG\n", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test
    fun recursiveBackgroundCall_seesItselfInChild() =
        runTest {
            // Recursion-via-bg: the function calls itself in the
            // background. Bounded by a file-existence check so the test
            // can't actually fork-bomb. Bash prints "L1" then "L2" —
            // the first bg call finds `f` again and recurses.
            //
            //   $ bash -c '
            //     mkdir -p /tmp/kt; rm -f /tmp/kt/stop
            //     f(){ echo L >&2; [ -e /tmp/kt/stop ] && return; touch /tmp/kt/stop; f& wait; }
            //     f' 2>&1
            //   L
            //   L
            //
            // kash diverges: the bg-forked `f` cannot find the function
            // and silently no-ops, so only the first "L" prints.
            val s = Kash(registry = standardRegistry(), parentContext = Dispatchers.Default).newSession()
            try {
                val r =
                    s.exec(
                        $$"""
                        f(){ echo L >&2; [ -e /tmp/kt-bgrec ] && return; touch /tmp/kt-bgrec; f& wait; }
                        rm -f /tmp/kt-bgrec
                        f
                        rm -f /tmp/kt-bgrec
                        """.trimIndent(),
                    )
                val count = r.stderr.lines().count { it == "L" }
                kotlin.test.assertTrue(
                    count >= 2,
                    "expected bg-fork to find the function and produce >=2 L lines; got $count:\n${r.stderr}",
                )
            } finally {
                s.close()
            }
        }
}
