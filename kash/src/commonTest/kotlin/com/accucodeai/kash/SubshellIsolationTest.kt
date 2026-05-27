package com.accucodeai.kash

import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * POSIX subshell isolation: a `(...)` group and a backgrounded pipeline
 * (`cmd &`) execute in a forked execution environment whose mutations to
 * env, cwd, functions, positional params, and locals do not leak back to
 * the parent.
 *
 * Before forking was wired up, `runSubshell` saved/restored only env / cwd /
 * positional — so `( foo() { …; } )` and similar would corrupt the parent's
 * function table. These tests pin the new behavior.
 */
class SubshellIsolationTest {
    // ---- (...) sequential subshell ----

    @Test fun parenSubshellEnvMutationDoesNotLeak() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("FOO=outer\n(FOO=inner)\necho \$FOO")
                assertEquals("outer\n", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test fun parenSubshellFunctionDefDoesNotLeak() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // Define `foo` only inside the subshell. The parent should
                // get "command not found" when it tries to call it.
                val r = s.exec("(foo() { echo inner; }; foo)\nfoo")
                assertEquals("inner\n", r.stdout)
                // Non-zero exit because `foo` isn't found in the parent.
                assertEquals(true, r.exitCode != 0)
            } finally {
                s.close()
            }
        }

    @Test fun parenSubshellCwdDoesNotLeak() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                s.exec("cd /tmp")
                val r = s.exec("(cd /home)\npwd")
                assertEquals("/tmp\n", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test fun parenSubshellPositionalDoesNotLeak() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("set -- a b c\n(set -- x y)\necho \$1 \$2 \$3")
                assertEquals("a b c\n", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test fun parenSubshellReadonlyDoesNotLeak() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // A readonly declared only inside the subshell must not bind
                // in the parent — otherwise the parent can't reassign the var.
                val r = s.exec("(readonly X=1)\nX=2\necho \$X")
                assertEquals("2\n", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test fun parenSubshellSeesParentEnv() =
        runTest {
            // Isolation is one-way: child sees parent state at fork time.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("FOO=hi\n(echo \$FOO)")
                assertEquals("hi\n", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test fun parenSubshellSeesParentFunctions() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("greet() { echo hi; }\n(greet)")
                assertEquals("hi\n", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test fun parenSubshellExitCodeFlows() =
        runTest {
            // `exit` builtin isn't implemented yet (TOOLS.md P0 backlog), so
            // exercise exit-code propagation via `false` instead. The point
            // is that the subshell's status reaches the parent's `$?`.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("(false)\necho \$?")
                assertEquals("1\n", r.stdout)
            } finally {
                s.close()
            }
        }

    // ---- cmd & background ----

    @Test fun backgroundEnvMutationDoesNotLeak() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("FOO=outer\nFOO=inner true &\nwait\necho \$FOO")
                assertEquals("outer\n", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test fun backgroundCwdDoesNotLeak() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                s.exec("cd /tmp")
                val r = s.exec("cd /home &\nwait\npwd")
                assertEquals("/tmp\n", r.stdout)
            } finally {
                s.close()
            }
        }

    @Test fun backgroundSeesParentEnv() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("FOO=hi\necho \$FOO &\nwait")
                assertEquals("hi\n", r.stdout)
            } finally {
                s.close()
            }
        }
}
