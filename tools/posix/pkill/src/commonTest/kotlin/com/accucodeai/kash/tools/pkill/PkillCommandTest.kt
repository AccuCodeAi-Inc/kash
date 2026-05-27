package com.accucodeai.kash.tools.pkill

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private data class Run(
    val rc: Int,
    val out: String,
    val err: String,
    val ctx: CommandContext,
    val pids: List<Int>,
)

private suspend fun runPkill(
    args: List<String>,
    procs: List<Pair<String, List<String>>> = emptyList(),
): Run {
    val out = Buffer()
    val err = Buffer()
    val ctx: CommandContext =
        bareCommandContext(
            fs = NullFs(),
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val init = ctx.process.machine.processTable[1]!!
    val pids = mutableListOf<Int>()
    for ((name, argv) in procs) {
        val child = init.fork()
        child.commandName = name
        child.argv = argv
        pids += child.pid
    }
    val res = PkillCommand().run(args, ctx)
    return Run(res.exitCode, out.readString(), err.readString(), ctx, pids)
}

class PkillCommandTest {
    @Test fun noMatchExits1() =
        runTest {
            val r = runPkill(listOf("foo"))
            assertEquals(1, r.rc)
        }

    @Test fun killsMatchingProcess() =
        runTest {
            val r =
                runPkill(
                    listOf("foo"),
                    listOf(
                        "foo" to listOf("foo"),
                        "bar" to listOf("bar"),
                    ),
                )
            assertEquals(0, r.rc)
            // foo's pid should be removed from the table.
            assertFalse(r.pids[0] in r.ctx.process.machine.processTable.keys)
            // bar still there.
            assertTrue(r.pids[1] in r.ctx.process.machine.processTable.keys)
        }

    @Test fun killsAllMatches() =
        runTest {
            val r =
                runPkill(
                    listOf("foo"),
                    listOf(
                        "foo" to listOf("foo"),
                        "foobar" to listOf("foobar"),
                        "bar" to listOf("bar"),
                    ),
                )
            assertEquals(0, r.rc)
            assertFalse(r.pids[0] in r.ctx.process.machine.processTable.keys)
            assertFalse(r.pids[1] in r.ctx.process.machine.processTable.keys)
            assertTrue(r.pids[2] in r.ctx.process.machine.processTable.keys)
        }

    @Test fun signalNumberWorks() =
        runTest {
            val r =
                runPkill(
                    listOf("-9", "foo"),
                    listOf("foo" to listOf("foo")),
                )
            assertEquals(0, r.rc)
            assertFalse(r.pids[0] in r.ctx.process.machine.processTable.keys)
        }

    @Test fun signalNameWorks() =
        runTest {
            val r =
                runPkill(
                    listOf("-TERM", "foo"),
                    listOf("foo" to listOf("foo")),
                )
            assertEquals(0, r.rc)
            assertFalse(r.pids[0] in r.ctx.process.machine.processTable.keys)
        }

    @Test fun signalWithSigPrefix() =
        runTest {
            val r =
                runPkill(
                    listOf("-SIGKILL", "foo"),
                    listOf("foo" to listOf("foo")),
                )
            assertEquals(0, r.rc)
            assertFalse(r.pids[0] in r.ctx.process.machine.processTable.keys)
        }

    @Test fun longSignalFlag() =
        runTest {
            val r =
                runPkill(
                    listOf("--signal=HUP", "foo"),
                    listOf("foo" to listOf("foo")),
                )
            assertEquals(0, r.rc)
        }

    @Test fun sSignalFlag() =
        runTest {
            val r =
                runPkill(
                    listOf("-s", "HUP", "foo"),
                    listOf("foo" to listOf("foo")),
                )
            assertEquals(0, r.rc)
        }

    @Test fun invalidSignal() =
        runTest {
            val r =
                runPkill(
                    listOf("-NOPE", "foo"),
                    listOf("foo" to listOf("foo")),
                )
            assertEquals(2, r.rc)
            assertTrue(r.err.contains("invalid signal"))
        }

    @Test fun eFlagEchoesPids() =
        runTest {
            val r =
                runPkill(
                    listOf("-e", "foo"),
                    listOf(
                        "foo" to listOf("foo"),
                        "foobar" to listOf("foobar"),
                    ),
                )
            assertEquals(0, r.rc)
            val printed =
                r.out
                    .trim()
                    .split("\n")
                    .toSet()
            assertEquals(setOf(r.pids[0].toString(), r.pids[1].toString()), printed)
        }

    @Test fun fFlagMatchesFullCmd() =
        runTest {
            val r =
                runPkill(
                    listOf("-f", "--", "--daemon"),
                    listOf(
                        "svcA" to listOf("svcA", "--daemon"),
                        "svcB" to listOf("svcB"),
                    ),
                )
            assertEquals(0, r.rc)
            assertFalse(r.pids[0] in r.ctx.process.machine.processTable.keys)
            assertTrue(r.pids[1] in r.ctx.process.machine.processTable.keys)
        }

    @Test fun xFlagExactMatch() =
        runTest {
            val r =
                runPkill(
                    listOf("-x", "foo"),
                    listOf(
                        "foo" to listOf("foo"),
                        "foobar" to listOf("foobar"),
                    ),
                )
            assertEquals(0, r.rc)
            assertFalse(r.pids[0] in r.ctx.process.machine.processTable.keys)
            assertTrue(r.pids[1] in r.ctx.process.machine.processTable.keys)
        }

    @Test fun iFlagCaseInsensitive() =
        runTest {
            val r =
                runPkill(
                    listOf("-i", "FOO"),
                    listOf("foo" to listOf("foo")),
                )
            assertEquals(0, r.rc)
            assertFalse(r.pids[0] in r.ctx.process.machine.processTable.keys)
        }

    @Test fun nFlagNewestOnly() =
        runTest {
            val r =
                runPkill(
                    listOf("-n", "foo"),
                    listOf(
                        "foo" to listOf("foo"),
                        "foo" to listOf("foo"),
                    ),
                )
            assertEquals(0, r.rc)
            // Exactly one of them removed.
            val survivors = r.pids.count { it in r.ctx.process.machine.processTable.keys }
            assertEquals(1, survivors)
        }

    @Test fun noPatternIsError() =
        runTest {
            val r = runPkill(emptyList())
            assertEquals(2, r.rc)
        }

    @Test fun invalidPatternExits2() =
        runTest {
            val r = runPkill(listOf("[unclosed"), listOf("foo" to listOf("foo")))
            assertEquals(2, r.rc)
        }

    @Test fun recipeKillNginxAndWorkers() =
        runTest {
            val r =
                runPkill(
                    listOf("-f", "nginx"),
                    listOf(
                        "nginx" to listOf("nginx", "master"),
                        "nginx" to listOf("nginx", "worker"),
                        "postgres" to listOf("postgres"),
                    ),
                )
            assertEquals(0, r.rc)
            assertFalse(r.pids[0] in r.ctx.process.machine.processTable.keys)
            assertFalse(r.pids[1] in r.ctx.process.machine.processTable.keys)
            assertTrue(r.pids[2] in r.ctx.process.machine.processTable.keys)
        }

    @Test fun helpFlag() =
        runTest {
            val r = runPkill(listOf("--help"))
            assertEquals(0, r.rc)
            assertTrue(r.out.contains("pkill"))
        }
}
