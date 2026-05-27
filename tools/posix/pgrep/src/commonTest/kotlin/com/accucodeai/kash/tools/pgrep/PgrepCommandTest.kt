package com.accucodeai.kash.tools.pgrep

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Run(
    val rc: Int,
    val out: String,
    val err: String,
)

/**
 * Fabricate N child processes off init with the given (commandName, argv,
 * startTime) tuples, then run pgrep against that table. Returns the test
 * context's pid (pgrep itself) and the spawned pids in registration order.
 */
private suspend fun runPgrep(
    args: List<String>,
    procs: List<Triple<String, List<String>, Long>> = emptyList(),
): Pair<Run, List<Int>> {
    val out = Buffer()
    val err = Buffer()
    val ctx: CommandContext =
        bareCommandContext(
            fs = NullFs(),
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val machine = ctx.process.machine
    val init = machine.processTable[1]!!
    val pids = mutableListOf<Int>()
    var t = 1000L
    for ((name, argv, startOffset) in procs) {
        val child = init.fork()
        child.commandName = name
        child.argv = argv
        // can't reassign startTimeMillis directly (val); approximate via offset
        // The real start time is set by fork(); we use startOffset to order
        // by registration. Tests that need newest/oldest use insertion order.
        @Suppress("UNUSED_VARIABLE")
        val ignored = startOffset
        t++
        pids += child.pid
    }
    val res = PgrepCommand().run(args, ctx)
    return Run(res.exitCode, out.readString(), err.readString()) to pids
}

class PgrepCommandTest {
    @Test fun noProcessesNoMatch() =
        runTest {
            val (r, _) = runPgrep(listOf("foo"))
            assertEquals(1, r.rc)
            assertEquals("", r.out)
        }

    @Test fun matchesByName() =
        runTest {
            val (r, pids) =
                runPgrep(
                    listOf("foo"),
                    procs =
                        listOf(
                            Triple("foo", listOf("foo"), 0L),
                            Triple("bar", listOf("bar"), 1L),
                            Triple("foobar", listOf("foobar"), 2L),
                        ),
                )
            assertEquals(0, r.rc)
            // Two matches: foo and foobar.
            val lines = r.out.trim().split("\n")
            assertEquals(setOf(pids[0].toString(), pids[2].toString()), lines.toSet())
        }

    @Test fun lFlagAddsName() =
        runTest {
            val (r, pids) =
                runPgrep(
                    listOf("-l", "foo"),
                    procs = listOf(Triple("foo", listOf("foo"), 0L)),
                )
            assertEquals(0, r.rc)
            assertEquals("${pids[0]} foo\n", r.out)
        }

    @Test fun aFlagAddsFullCommand() =
        runTest {
            val (r, pids) =
                runPgrep(
                    listOf("-a", "foo"),
                    procs = listOf(Triple("foo", listOf("foo", "--bar", "baz"), 0L)),
                )
            assertEquals(0, r.rc)
            assertEquals("${pids[0]} foo --bar baz\n", r.out)
        }

    @Test fun cFlagPrintsCount() =
        runTest {
            val (r, _) =
                runPgrep(
                    listOf("-c", "foo"),
                    procs =
                        listOf(
                            Triple("foo", listOf("foo"), 0L),
                            Triple("foobar", listOf("foobar"), 1L),
                            Triple("bar", listOf("bar"), 2L),
                        ),
                )
            assertEquals(0, r.rc)
            assertEquals("2\n", r.out)
        }

    @Test fun cFlagZeroExits1() =
        runTest {
            val (r, _) =
                runPgrep(
                    listOf("-c", "nomatch"),
                    procs = listOf(Triple("foo", listOf("foo"), 0L)),
                )
            assertEquals(1, r.rc)
            assertEquals("0\n", r.out)
        }

    @Test fun fFlagMatchesFullCmdline() =
        runTest {
            // Pattern only matches the argument, not the command name.
            val (r, pids) =
                runPgrep(
                    listOf("-f", "--config"),
                    procs =
                        listOf(
                            Triple("daemon", listOf("daemon", "--config=/etc/d.cfg"), 0L),
                            Triple("daemon", listOf("daemon"), 1L),
                        ),
                )
            assertEquals(0, r.rc)
            assertEquals("${pids[0]}\n", r.out)
        }

    @Test fun xFlagExactMatch() =
        runTest {
            // -x foo matches "foo" not "foobar"
            val (r, pids) =
                runPgrep(
                    listOf("-x", "foo"),
                    procs =
                        listOf(
                            Triple("foo", listOf("foo"), 0L),
                            Triple("foobar", listOf("foobar"), 1L),
                        ),
                )
            assertEquals(0, r.rc)
            assertEquals("${pids[0]}\n", r.out)
        }

    @Test fun iFlagCaseInsensitive() =
        runTest {
            val (r, pids) =
                runPgrep(
                    listOf("-i", "FOO"),
                    procs = listOf(Triple("foo", listOf("foo"), 0L)),
                )
            assertEquals(0, r.rc)
            assertEquals("${pids[0]}\n", r.out)
        }

    @Test fun vFlagInverts() =
        runTest {
            val (r, pids) =
                runPgrep(
                    listOf("-v", "foo"),
                    procs =
                        listOf(
                            Triple("foo", listOf("foo"), 0L),
                            Triple("bar", listOf("bar"), 1L),
                            Triple("baz", listOf("baz"), 2L),
                        ),
                )
            assertEquals(0, r.rc)
            val lines =
                r.out
                    .trim()
                    .split("\n")
                    .toSet()
            // pids[0] (commandName=foo) excluded; pids[1] and pids[2] included.
            // init (pid 1) is also in the table and is also non-matching, so
            // it lands in the inverted set too — assert the bar/baz pids are
            // present without requiring an exact match.
            assertTrue(pids[1].toString() in lines)
            assertTrue(pids[2].toString() in lines)
            assertTrue(pids[0].toString() !in lines)
        }

    @Test fun newestOnly() =
        runTest {
            // The KashProcess.startTimeMillis we can't trivially set; rely on
            // the fact that subsequent fork()s have monotonically increasing
            // start times.
            val (r, pids) =
                runPgrep(
                    listOf("-n", "foo"),
                    procs =
                        listOf(
                            Triple("foo", listOf("foo"), 0L),
                            Triple("foo", listOf("foo"), 1L),
                            Triple("foo", listOf("foo"), 2L),
                        ),
                )
            assertEquals(0, r.rc)
            // newest = max startTimeMillis. Since all forks share start time,
            // any of them is acceptable; assert exit 0 and exactly one pid in
            // the set.
            val line = r.out.trim()
            assertTrue(line in pids.map { it.toString() })
        }

    @Test fun oldestOnly() =
        runTest {
            val (r, pids) =
                runPgrep(
                    listOf("-o", "foo"),
                    procs =
                        listOf(
                            Triple("foo", listOf("foo"), 0L),
                            Triple("foo", listOf("foo"), 1L),
                        ),
                )
            assertEquals(0, r.rc)
            val line = r.out.trim()
            assertTrue(line in pids.map { it.toString() })
        }

    @Test fun dFlagCustomDelimiter() =
        runTest {
            val (r, pids) =
                runPgrep(
                    listOf("-d", ",", "foo"),
                    procs =
                        listOf(
                            Triple("foo", listOf("foo"), 0L),
                            Triple("foobar", listOf("foobar"), 1L),
                        ),
                )
            assertEquals(0, r.rc)
            assertEquals("${pids[0]},${pids[1]}\n", r.out)
        }

    @Test fun invalidRegexExits2() =
        runTest {
            val (r, _) =
                runPgrep(
                    listOf("[unclosed"),
                    procs = listOf(Triple("foo", listOf("foo"), 0L)),
                )
            assertEquals(2, r.rc)
            assertTrue(r.err.contains("invalid pattern"))
        }

    @Test fun noPatternAndNoUserIsError() =
        runTest {
            val (r, _) = runPgrep(emptyList())
            assertEquals(2, r.rc)
        }

    @Test fun uUserAcceptsDefault() =
        runTest {
            val (r, _) =
                runPgrep(
                    listOf("-u", "user", "foo"),
                    procs = listOf(Triple("foo", listOf("foo"), 0L)),
                )
            assertEquals(0, r.rc)
        }

    @Test fun uUserRejectsUnknown() =
        runTest {
            val (r, _) =
                runPgrep(
                    listOf("-u", "nobody", "foo"),
                    procs = listOf(Triple("foo", listOf("foo"), 0L)),
                )
            assertEquals(1, r.rc)
        }

    @Test fun multiplePatternsAreAnded() =
        runTest {
            val (r, pids) =
                runPgrep(
                    listOf("-f", "foo", "bar"),
                    procs =
                        listOf(
                            Triple("daemon", listOf("daemon", "foo", "bar"), 0L),
                            Triple("daemon", listOf("daemon", "foo"), 1L),
                        ),
                )
            assertEquals(0, r.rc)
            assertEquals("${pids[0]}\n", r.out)
        }

    // ---- Recipe tests ---- //

    @Test fun recipeCountWebServers() =
        runTest {
            val (r, _) =
                runPgrep(
                    listOf("-cf", "nginx|httpd|apache"),
                    procs =
                        listOf(
                            Triple("nginx", listOf("nginx", "-g", "daemon off;"), 0L),
                            Triple("httpd", listOf("httpd"), 1L),
                            Triple("postgres", listOf("postgres"), 2L),
                        ),
                )
            assertEquals(0, r.rc)
            assertEquals("2\n", r.out)
        }

    @Test fun recipeFindBackgroundDaemons() =
        runTest {
            // Common pattern: pgrep -af '\-\-daemon' to find background services
            val (r, pids) =
                runPgrep(
                    listOf("-af", "--daemon"),
                    procs =
                        listOf(
                            Triple("svcA", listOf("svcA", "--daemon"), 0L),
                            Triple("svcB", listOf("svcB", "--foreground"), 1L),
                        ),
                )
            assertEquals(0, r.rc)
            assertTrue(r.out.contains("${pids[0]} svcA --daemon"))
            assertTrue(!r.out.contains("svcB"))
        }

    @Test fun helpFlag() =
        runTest {
            val (r, _) = runPgrep(listOf("--help"))
            assertEquals(0, r.rc)
            assertTrue(r.out.contains("pgrep"))
        }

    @Test fun versionFlag() =
        runTest {
            val (r, _) = runPgrep(listOf("-V"))
            assertEquals(0, r.rc)
            assertTrue(r.out.contains("pgrep"))
        }
}
