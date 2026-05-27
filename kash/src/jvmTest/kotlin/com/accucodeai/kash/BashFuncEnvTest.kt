package com.accucodeai.kash

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BASH_FUNC_<name>%%=() { body; } env-var convention. On entering a
 * sub-shell via `sh -c`, kash parses such entries and caches them as
 * FunctionDef so the named function resolves without re-parsing on call.
 */
class BashFuncEnvTest {
    private fun runWith(
        env: Map<String, String>,
        script: String,
    ): Pair<String, Int> =
        runBlocking {
            val r =
                Kash().exec(
                    script,
                    ExecOptions(env = env, replaceEnv = false),
                )
            r.stdout to r.exitCode
        }

    @Test fun bashFuncEnvVarDefinesFunctionInSubshell() {
        val (out, code) =
            runWith(
                env = mapOf("BASH_FUNC_greet%%" to "() { echo hello; }"),
                script = "sh -c 'greet'",
            )
        assertEquals("hello\n", out)
        assertEquals(0, code)
    }

    @Test fun malformedBashFuncEntryIsSilentlyDropped() {
        // Adversarial-shaped value (CVE-2014-6271): the trailing `; echo BAD`
        // outside the `{ ... }` is *not* part of the function body — bash's
        // post-fix parse rejects entries whose value doesn't cleanly equal a
        // function definition. We do the same: foo is undefined, sh errors out.
        val (out, _) =
            runWith(
                env = mapOf("BASH_FUNC_foo%%" to "() { echo good; }; echo BAD"),
                script = "sh -c 'foo' 2>/dev/null",
            )
        assertEquals("", out)
    }

    @Test fun nonFunctionShapeIsDropped() {
        val (out, _) =
            runWith(
                env = mapOf("BASH_FUNC_x%%" to "not a function"),
                script = "sh -c 'x' 2>/dev/null",
            )
        assertEquals("", out)
    }

    @Test fun bashFuncEntryStrippedFromSubshellEnv() {
        // The receiving sub-shell parses BASH_FUNC_x%% into a function and
        // removes the raw env entry so a deeper `env` listing doesn't show
        // it. `env | grep BASH_FUNC` should print nothing inside the sub-shell.
        val (out, _) =
            runWith(
                env = mapOf("BASH_FUNC_inner%%" to "() { echo from-env; }"),
                script = "sh -c 'env' 2>/dev/null",
            )
        assertEquals(false, out.contains("BASH_FUNC_inner"))
    }
}
