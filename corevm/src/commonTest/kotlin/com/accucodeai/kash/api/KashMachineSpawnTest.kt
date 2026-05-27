package com.accucodeai.kash.api

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KashMachineSpawnTest {
    // bootMachine returns the machine plus a "shell" process that's a
    // child of init. Tests spawn from this shell, not from init directly,
    // because children of init auto-reap (matching real Linux PID 1
    // semantics) — the zombie-until-wait window only exists when the
    // parent is some other process. The shell stand-in models bash's
    // role: a normal process that forks utilities and reaps them.
    private fun bootMachine(): Pair<KashMachine, KashProcess> {
        val machine = KashMachine(fs = InMemoryFs())
        machine.ensureInit()
        val shell =
            (machine.processTable[1]!!).fork().apply {
                commandName = "kash"
                argv = listOf("kash")
            }
        return machine to shell
    }

    @Test fun spawnAssignsFreshPidAndRunsBlock() =
        runTest {
            val (machine, shell) = bootMachine()
            val r =
                machine.spawn(shell) { child ->
                    assertEquals(shell.pid, child.ppid)
                    assertNotEquals(shell.pid, child.pid)
                    42
                }
            assertEquals(42, (r.exit.await() as ExitStatus.Exited).code)
        }

    @Test fun spawnedChildRegistersInProcessTableAsZombieUntilWait() =
        runTest {
            val (machine, shell) = bootMachine()
            val r = machine.spawn(shell) { 0 }
            // Child is still in the table as a zombie.
            val child = machine.processTable[r.pid]
            assertTrue(child != null)
            assertEquals(ProcessState.ZOMBIE, child.state)
            assertEquals(ExitStatus.Exited(0), child.exitStatus)
            // wait reaps: child leaves the table, parent's children list shrinks.
            val status = machine.wait(r.pid)
            assertEquals(ExitStatus.Exited(0), status)
            assertNull(machine.processTable[r.pid])
            assertTrue(shell.children.none { it.pid == r.pid })
        }

    @Test fun childInheritsEnvAndCwdButMutationsDoNotLeak() =
        runTest {
            val (machine, shell) = bootMachine()
            shell.env["FOO"] = "parent"
            shell.cwd = "/work"
            machine.spawn(shell) { child ->
                assertEquals("parent", child.env["FOO"])
                assertEquals("/work", child.cwd)
                // POSIX: child mutations are local; parent must not see them.
                child.env["FOO"] = "child"
                child.cwd = "/elsewhere"
                0
            }
            assertEquals("parent", shell.env["FOO"])
            assertEquals("/work", shell.cwd)
        }

    @Test fun spawnHandlesUncaughtThrowAsNonZeroExit() =
        runTest {
            val (machine, shell) = bootMachine()
            val r = machine.spawn(shell) { error("boom") }
            assertEquals(ExitStatus.Exited(1), r.exit.await())
        }

    @Test fun waitOnUnknownPidReturnsNull() =
        runTest {
            val (machine, _) = bootMachine()
            assertNull(machine.wait(9999))
        }

    @Test fun backgroundSpawnLetsForegroundContinueThenWait() =
        runTest {
            val (machine, shell) = bootMachine()
            coroutineScope {
                val bg =
                    async {
                        machine.spawn(shell) { 7 }
                    }
                // Foreground work continues; wait reaps once bg completes.
                val r = bg.await()
                assertEquals(ExitStatus.Exited(7), machine.wait(r.pid))
            }
        }
}
