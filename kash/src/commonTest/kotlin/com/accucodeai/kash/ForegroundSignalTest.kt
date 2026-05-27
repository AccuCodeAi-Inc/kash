package com.accucodeai.kash

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.signal.SigInt
import com.accucodeai.kash.standardRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Foreground async-signal delivery and the `trap INT` machinery. These
 * tests rely on the session's background scope inheriting the `runTest`
 * scheduler (virtual time) — passed in via `parentContext = coroutineContext`
 * on construction. Without that the `delay(...)` inside [LongWaitCommand]
 * would block real wall-clock time on `Dispatchers.Default`.
 */
class ForegroundSignalTest {
    /**
     * A test-only command that simulates a long-running, cancellable tool
     * (think `sleep 1000` or `tail -f`). Suspends on `delay`, so coroutine
     * cancellation aborts it at the next dispatcher tick — exactly the
     * cooperative-cancellation path real tools take.
     */
    private class LongWaitCommand :
        Command,
        CommandSpec {
        override val name = "longwait"
        override val kind = CommandKind.TOOL
        override val tags = setOf(CommandTag.POSIX)
        override val command: Command get() = this

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
        ): CommandResult {
            // Big number, virtual-time delay. The whole point is that the
            // suspending call is the cooperation point with cancellation.
            delay(60_000)
            return CommandResult()
        }
    }

    @Test fun intCancelsRunningCommandAndContinuesScript() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(LongWaitCommand()),
                    parentContext = coroutineContext,
                ).newSession()
            try {
                val deferred =
                    async {
                        s.exec("longwait\necho after")
                    }
                // Yield once so `exec` reaches the launched statement and
                // suspends inside LongWaitCommand.delay.
                delay(1)
                s.deliverSignal(SigInt)
                val r = deferred.await()
                // INT cancelled the longwait, set $? to 130, then the next
                // statement (echo) ran normally.
                assertTrue(r.stdout.contains("after"), "expected 'after' in stdout, got: ${r.stdout}")
                assertEquals(0, r.exitCode)
            } finally {
                s.close()
            }
        }

    @Test fun intFiresTrapHandlerThenContinues() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(LongWaitCommand()),
                    parentContext = coroutineContext,
                ).newSession()
            try {
                val deferred =
                    async {
                        s.exec("trap 'echo got-int' INT\nlongwait\necho after")
                    }
                delay(1)
                s.deliverSignal(SigInt)
                val r = deferred.await()
                assertTrue(r.stdout.contains("got-int"), "trap handler should have run, got: ${r.stdout}")
                assertTrue(r.stdout.contains("after"), "script should continue after INT, got: ${r.stdout}")
            } finally {
                s.close()
            }
        }

    @Test fun intWithoutTrapJustSetsExitCode130() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(LongWaitCommand()),
                    parentContext = coroutineContext,
                ).newSession()
            try {
                val deferred =
                    async {
                        s.exec("longwait\necho \$?\necho done")
                    }
                delay(1)
                s.deliverSignal(SigInt)
                val r = deferred.await()
                assertTrue(r.stdout.contains("130"), "expected \$? = 130, got: ${r.stdout}")
                assertTrue(r.stdout.contains("done"))
            } finally {
                s.close()
            }
        }

    @Test fun isExecutingForegroundFlagFlipsAroundStatement() =
        runTest {
            val s =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(LongWaitCommand()),
                    parentContext = coroutineContext,
                ).newSession()
            try {
                // Before exec: not executing.
                assertEquals(false, s.isExecutingForeground)
                val deferred =
                    async {
                        s.exec("longwait")
                    }
                delay(1)
                // While longwait is suspended on delay: flag is true.
                assertEquals(true, s.isExecutingForeground)
                s.deliverSignal(SigInt)
                deferred.await()
                // After: flag is back to false.
                assertEquals(false, s.isExecutingForeground)
            } finally {
                s.close()
            }
        }

    @Test fun backgroundJobsAlsoInheritTestScheduler() =
        runTest {
            // Sanity check that the parentContext plumbing works for `&`
            // too: a backgrounded longwait completes in virtual time, not
            // real time. If parentContext were ignored we'd hang forever.
            val s =
                Kash(
                    registry = standardRegistry(),
                    customCommands = listOf(LongWaitCommand()),
                    parentContext = coroutineContext,
                ).newSession()
            try {
                val r = s.exec("longwait &\nwait")
                assertEquals(0, r.exitCode)
            } finally {
                s.close()
            }
        }
}
