package com.accucodeai.kash

import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.defineCommand
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the tool-facing [com.accucodeai.kash.api.UtilityRunner] hook —
 * POSIX `execvp`-style direct utility invocation. The `invoke` probe pushes a
 * (name, args) call through `ctx.utilityRunner` and reports the exit + the
 * stdout it captured back to the parent script.
 */
class UtilityRunnerTest {
    private val invokeProbe =
        defineCommand("invoke") { args, ctx ->
            val name = args.first()
            val rest = args.drop(1)
            val out = Buffer()
            val err = Buffer()
            val rc =
                ctx.utilityRunner?.run(
                    name = name,
                    args = rest,
                    stdin = Buffer().asSuspendSource(),
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                ) ?: run {
                    ctx.stderr.writeUtf8("invoke: no utilityRunner\n")
                    return@defineCommand CommandResult(2)
                }
            ctx.stdout.writeUtf8("rc=$rc;out=${out.readString()};err=${err.readString()}")
            CommandResult()
        }

    /** Test-only utility that mutates ctx.process.env to prove it doesn't leak. */
    private val mutateEnv =
        defineCommand("mutate-env") { _, ctx ->
            ctx.process.env["LEAKED"] = "1"
            ctx.stdout.writeUtf8("mutated")
            CommandResult()
        }

    private fun kash() = Kash(customCommands = listOf(invokeProbe, mutateEnv))

    @Test fun invokes_a_registered_utility() =
        runTest {
            val r = kash().exec("invoke echo hello world")
            // `echo` writes "hello world\n" → captured by the probe.
            assertEquals("rc=0;out=hello world\n;err=", r.stdout)
        }

    @Test fun missing_utility_returns_127_and_writes_to_stderr() =
        runTest {
            val r = kash().exec("invoke does-not-exist")
            // Probe captured: rc=127, empty out, stderr contains "command not found".
            assertEquals(true, r.stdout.startsWith("rc=127;"))
            assertEquals(true, r.stdout.contains("command not found"))
        }

    @Test fun intrinsic_name_returns_126() =
        runTest {
            // `set` is an INTRINSIC (interpreter-owned, no command instance).
            // Per execvp semantics it's not callable as a utility.
            val r = kash().exec("invoke set -x")
            assertEquals(true, r.stdout.startsWith("rc=126;"))
            assertEquals(true, r.stdout.contains("not executable"))
        }

    @Test fun env_mutation_by_utility_is_visible_to_parent() =
        runTest {
            // Note: this is the *expected* leak — UtilityRunner shares the
            // parent's env map (matches "in-process" semantics — utilities are
            // not subshells). Tools that want isolation use ShellRunner.
            val r =
                kash().exec(
                    """
                    invoke mutate-env
                    echo leaked=${'$'}LEAKED
                    """.trimIndent(),
                )
            assertEquals(true, r.stdout.contains("leaked=1"))
        }
}
