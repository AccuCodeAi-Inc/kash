package com.accucodeai.kash

import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.ShellInvocation
import com.accucodeai.kash.api.defineCommand
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies the tool-facing [com.accucodeai.kash.api.ShellRunner] hook the
 * interpreter wires into every [com.accucodeai.kash.api.CommandContext].
 * Tests run via a tiny `probe` builtin that exercises `ctx.shellRunner` and
 * `ctx.utilityRunner` and reports back through stdout.
 */
class ShellRunnerTest {
    /** `probe <kash-script>` — runs the script through ctx.shellRunner. */
    private val shellProbe =
        defineCommand("probe") { args, ctx ->
            val script = args.joinToString(" ")
            val out = Buffer()
            val rc =
                ctx.shellRunner?.run(
                    ShellInvocation(script = script, stdout = out.asSuspendSink()),
                ) ?: run {
                    ctx.stderr.writeUtf8("probe: no shellRunner\n")
                    return@defineCommand CommandResult(2)
                }
            ctx.stdout.writeUtf8(out.readUtf8Text())
            CommandResult(exitCode = rc)
        }

    /** `inspect VAR` — emits the parent shell's current value of VAR. */
    private val inspect =
        defineCommand("inspect") { args, ctx ->
            ctx.stdout.writeUtf8(ctx.process.env[args[0]] ?: "<unset>")
            CommandResult()
        }

    private fun kash() = Kash(customCommands = listOf(shellProbe, inspect))

    @Test fun runs_simple_script_and_captures_stdout() =
        runTest {
            val r = kash().exec("probe 'echo hello'")
            assertEquals("hello\n", r.stdout)
            assertEquals(0, r.exitCode)
        }

    @Test fun exit_code_of_subscript_propagates() =
        runTest {
            // `false` should give exit 1; probe surfaces it.
            val r = kash().exec($$"probe false; echo $?")
            assertEquals("1\n", r.stdout)
        }

    @Test fun env_mutation_inside_subshell_does_not_leak() =
        runTest {
            val r =
                kash().exec(
                    $$"""
                    export FOO=parent
                    probe 'FOO=child; echo inside=$FOO'
                    inspect FOO
                    """.trimIndent(),
                )
            // The sub-script sees FOO=child; the parent still sees FOO=parent.
            // FOO must be `export`ed for `inspect` (a printenv-style builtin
            // reading ctx.process.env) to observe it — bash semantics for
            // unexported shell variables are that they don't appear in any
            // child / builtin env view.
            assertEquals("inside=child\nparent", r.stdout)
        }

    @Test fun cd_inside_subshell_does_not_leak() =
        runTest {
            val k = kash()
            // Pre-create /tmp inside the in-memory FS so `cd /tmp` succeeds.
            k.fs.mkdirs("/tmp")
            val r =
                k.exec(
                    """
                    probe 'cd /tmp; pwd'
                    pwd
                    """.trimIndent(),
                )
            // Sub-script's pwd is /tmp; parent's pwd is unchanged from default.
            val lines = r.stdout.lines()
            assertEquals("/tmp", lines[0])
            assertNotEquals("/tmp", lines[1])
        }

    @Test fun parse_failure_returns_exit_2() =
        runTest {
            val r = kash().exec($$"probe 'echo (unbalanced'; echo $?")
            assertEquals(
                "2",
                r.stdout
                    .trim()
                    .lines()
                    .last(),
            )
        }

    @Test fun nested_shell_runner_invocations_work() =
        runTest {
            // probe runs `probe 'echo nested'`, which itself runs `echo nested`.
            val r = kash().exec("probe \"probe 'echo nested'\"")
            assertEquals("nested\n", r.stdout)
        }

    @Test fun recursion_limit_is_enforced() =
        runTest {
            // A recursive function that calls probe with itself.
            val r =
                kash().exec(
                    $$"""
                    rec() { probe "rec"; }
                    rec
                    echo done=$?
                    """.trimIndent(),
                )
            // We expect the recursion-limit message to land on stderr eventually
            // and exit non-zero (recursion via probe → shellRunner → probe → …).
            // The exact propagated exit isn't pinned here; just that it didn't
            // explode the JVM and we got back.
            assertEquals(true, r.stdout.contains("done="))
        }
}
