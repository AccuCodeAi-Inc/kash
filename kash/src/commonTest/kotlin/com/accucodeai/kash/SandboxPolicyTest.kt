package com.accucodeai.kash

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.sandbox.SandboxPolicy
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [SandboxPolicy] is correctly threaded through to
 * [com.accucodeai.kash.api.CommandContext], inherited by subshells, and
 * defaults to [SandboxPolicy.TRUSTED]. Doesn't exercise GraalPy — that's
 * runtime-dependent and lives in `:tools:python3-graalpy:jvmTest`.
 */
class SandboxPolicyTest {
    /**
     * Test-only command that just dumps `ctx.sandbox` to stdout. Lets us
     * assert what each invocation sees without a real sandboxed tool.
     */
    private class WhichSandbox :
        Command,
        CommandSpec {
        override val name: String = "whichsandbox"
        override val kind: CommandKind = CommandKind.TOOL
        override val tags: Set<CommandTag> = emptySet()
        override val command: Command get() = this

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
        ): CommandResult {
            ctx.stdout.writeUtf8("${ctx.sandbox.name}\n")
            return CommandResult()
        }
    }

    @Test fun defaultIsTrusted() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    fs = InMemoryFs(),
                    customCommands = listOf(WhichSandbox()),
                ).newSession()
            assertEquals("TRUSTED\n", s.exec("whichsandbox").stdout)
        }

    @Test fun explicitConstrainedFlowsToTools() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    fs = InMemoryFs(),
                    customCommands = listOf(WhichSandbox()),
                    sandbox = SandboxPolicy.CONSTRAINED,
                ).newSession()
            assertEquals("CONSTRAINED\n", s.exec("whichsandbox").stdout)
        }

    @Test fun explicitUntrustedFlowsToTools() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    fs = InMemoryFs(),
                    customCommands = listOf(WhichSandbox()),
                    sandbox = SandboxPolicy.UNTRUSTED,
                ).newSession()
            assertEquals("UNTRUSTED\n", s.exec("whichsandbox").stdout)
        }

    @Test fun subshellInheritsPolicy() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    fs = InMemoryFs(),
                    customCommands = listOf(WhichSandbox()),
                    sandbox = SandboxPolicy.UNTRUSTED,
                ).newSession()
            val r = s.exec("whichsandbox; (whichsandbox)")
            assertEquals("UNTRUSTED\nUNTRUSTED\n", r.stdout)
        }

    @Test fun backgroundJobInheritsPolicy() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    fs = InMemoryFs(),
                    customCommands = listOf(WhichSandbox()),
                    sandbox = SandboxPolicy.CONSTRAINED,
                    parentContext = coroutineContext,
                ).newSession()
            val r = s.exec("whichsandbox &\nwait")
            assertTrue(r.stdout.contains("CONSTRAINED"), "stdout=${r.stdout}")
        }

    @Test fun atLeastAsStrictAs() =
        runTest {
            assertTrue(SandboxPolicy.UNTRUSTED.atLeastAsStrictAs(SandboxPolicy.CONSTRAINED))
            assertTrue(SandboxPolicy.CONSTRAINED.atLeastAsStrictAs(SandboxPolicy.TRUSTED))
            assertTrue(SandboxPolicy.TRUSTED.atLeastAsStrictAs(SandboxPolicy.TRUSTED))
            // Reverse direction: a stricter policy is NOT looser.
            assertEquals(false, SandboxPolicy.TRUSTED.atLeastAsStrictAs(SandboxPolicy.UNTRUSTED))
            assertEquals(false, SandboxPolicy.CONSTRAINED.atLeastAsStrictAs(SandboxPolicy.UNTRUSTED))
        }
}
