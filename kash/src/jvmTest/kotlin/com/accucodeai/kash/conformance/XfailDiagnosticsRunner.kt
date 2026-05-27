package com.accucodeai.kash.conformance

import com.accucodeai.kash.ExecOptions
import com.accucodeai.kash.Kash
import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.ExecResult
import com.accucodeai.kash.defaultCommandSpecs
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Diagnostic-only runner: walks every xfail entry, runs the script, and
 * reports the *byte distance* between actual and expected output. Helps
 * pick the next features worth implementing.
 *
 * Emitted as one [DynamicTest] per xfail script via [TestFactory] so each
 * iteration runs in its own `runTest{…}` scope with its own JUnit lifecycle
 * — the InMemoryFs, Kash session, JobControl, interpreter state, and all
 * the test scripts' transient buffers are GC-eligible the moment the
 * dynamic test returns. The previous single-`@Test` form held every
 * iteration's allocations live until the entire 50+-script walk completed,
 * which forced a 2g heap budget. With per-script teardown 512m is enough.
 *
 * The aggregate `XFAIL-DIFF` report (sorted by line-distance) is produced
 * by a final dynamic test that drains the rows collected per-iteration via
 * the shared [rows] map; that map only holds three ints per script
 * (distance, first-diff line, exit) so it doesn't materially pin memory.
 *
 * Run with: ./gradlew :kash-app:conformanceTest --tests "*XfailDiagnostics*" -i
 */
@Tag("conformance")
class XfailDiagnosticsRunner {
    private val rows = java.util.concurrent.ConcurrentHashMap<String, Triple<Int, Int, Int>>()

    @TestFactory
    fun xfailDiagnostics(): List<DynamicTest> {
        val dir =
            System.getProperty("kash.bashTestsDir")?.let(::File)
                ?: error("kash.bashTestsDir not set")
        val xfail = ScriptPairRunner.loadXfail("bash-tests-xfail.txt")
        val normalize = ScriptPairRunner.loadNormalizations("bash-tests-normalize.txt")
        val pairs =
            ScriptPairRunner
                .discoverPairs(dir, ".tests", ".right")
                .filter { it.name in xfail }
        val focus =
            System
                .getProperty("kash.diag.focus")
                ?.split(",")
                ?.map { it.trim() }
                ?.toSet()
                .orEmpty()

        val perScript: List<DynamicTest> =
            pairs.map { p ->
                DynamicTest.dynamicTest("xfail-diag/${p.name}") {
                    runTest {
                        val outcome = runOne(p, dir, coroutineContext)
                        val rules = normalize[p.name].orEmpty()
                        val actual =
                            ScriptPairRunner.applyNormalizations(
                                ScriptPairRunner.applyRunnerFilter(
                                    p.name,
                                    catV(outcome.first + outcome.second),
                                ),
                                rules,
                            )
                        val expected =
                            ScriptPairRunner.applyNormalizations(
                                ScriptPairRunner.applyRunnerFilter(
                                    p.name,
                                    catV(p.expected.readText()),
                                ),
                                rules,
                            )
                        val firstDiff = firstDiffLine(expected, actual)
                        val dist = levenshteinLineDistance(expected, actual)
                        rows[p.name] = Triple(dist, firstDiff, outcome.third)
                        // Per-script summary line. With parallel test
                        // execution the aggregate report below can race the
                        // per-script tests, so each test prints its own
                        // row too — `grep XFAIL-DIFF` picks them all up
                        // regardless of execution order.
                        println("XFAIL-DIFF %4d  first-diff-line=%4d  %s".format(dist, firstDiff, p.name))
                        if (p.name in focus) {
                            println("================================================")
                            println("FOCUS: ${p.name}  exit=${outcome.third}")
                            println("--- EXPECTED ---")
                            println(expected)
                            println("--- ACTUAL ---")
                            println(actual)
                            println("================================================")
                        }
                    }
                }
            }

        // Final aggregate report. Runs after every per-script test because
        // JUnit executes a DynamicTest list in order, and the per-script
        // tests above write into [rows] before this one reads it. If any
        // per-script test fails the report still prints what it has —
        // useful when triaging a regression.
        val report =
            DynamicTest.dynamicTest("xfail-diag/zz-report") {
                val sorted = rows.entries.sortedBy { it.value.first }
                println("=== XFAIL DIFF REPORT (line-distance, first-mismatch-line) ===")
                for ((name, triple) in sorted) {
                    val (dist, line, _) = triple
                    println("XFAIL-DIFF %4d  first-diff-line=%4d  %s".format(dist, line, name))
                }
            }
        return perScript + report
    }

    private suspend fun runOne(
        pair: ScriptPairRunner.Pair,
        dir: File,
        testContext: kotlin.coroutines.CoroutineContext,
    ): Triple<String, String, Int> {
        val fs = InMemoryFs()
        fs.mkdirs("/tests")
        // Mirror the BashTestPairRunner.runOnce setup so scripts that
        // `mkdir /tmp/foo` or `mkdir /var/tmp/foo` have a parent.
        fs.mkdirs("/tmp")
        fs.mkdirs("/var/tmp")
        dir.listFiles()?.forEach { f -> if (f.isFile) fs.writeBytes("/tests/${f.name}", f.readBytes()) }
        // Mirror the trampoline setup from ScriptPairRunner so `${THIS_SH}`
        // re-enters the in-process POSIX sh rather than failing on a missing
        // /usr/bin/bash binary in the in-memory FS.
        fs.writeBytes("/tmp/bash", "exec /usr/bin/sh \"\$@\"\n".encodeToByteArray())
        fs.mkdirs("/etc")
        fs.writeBytes(
            "/etc/passwd",
            (
                "root:x:0:0:root:/root:/usr/bin/sh\n" +
                    "user:x:1000:1000:user:/home/user:/usr/bin/sh\n"
            ).encodeToByteArray(),
        )
        // Stub `examples/loadables/Makefile` so glob-bracket.tests'
        // `eval $(grep -E '^(CC |SHOBJ_)…' …Makefile)` lands SHOBJ_STATUS=
        // supported. The actual compile is intercepted by the gcc stub
        // command in the registry. Mirrors ScriptPairRunner.runOnce.
        fs.mkdirs("/tests/examples/loadables")
        fs.writeBytes(
            "/tests/examples/loadables/Makefile",
            (
                "SHOBJ_STATUS = supported\n" +
                    "CC = gcc\n" +
                    "SHOBJ_CC = gcc\n" +
                    "SHOBJ_CFLAGS =\n" +
                    "SHOBJ_LD = gcc\n" +
                    "SHOBJ_LDFLAGS =\n" +
                    "SHOBJ_XLDFLAGS =\n" +
                    "SHOBJ_LIBS =\n"
            ).encodeToByteArray(),
        )
        val scheduler = testContext[kotlinx.coroutines.test.TestCoroutineScheduler.Key]
        val kash =
            Kash(
                fs = fs,
                initialCwd = "/tests",
                registry = registry,
                parentContext = testContext,
                clock =
                    scheduler?.let { VirtualShellClock(it) }
                        ?: com.accucodeai.kash.api.clock
                            .SystemShellClock(),
            )
        val r: ExecResult =
            try {
                kash.exec(
                    pair.script.readText(),
                    ExecOptions(
                        env =
                            mapOf(
                                "LANG" to "C",
                                "LC_ALL" to "C",
                                "TZ" to "UTC0",
                                "HOME" to "/home/user",
                                "PATH" to "/usr/bin:/bin",
                                "PWD" to "/tests",
                                "THIS_SH" to "/tmp/bash",
                                "BASH_TSTOUT" to "/tmp/tstout",
                            ),
                        cwd = "/tests",
                        replaceEnv = true,
                        mergeStderr = true,
                        scriptName = "./${pair.script.name}",
                    ),
                )
            } catch (t: Throwable) {
                return Triple("", "kash threw ${t::class.simpleName}: ${t.message}\n", 2)
            }
        return Triple(r.stdout, r.stderr, r.exitCode)
    }

    // Mirrors ScriptPairRunner's registry: the standard catalog plus the
    // bash-test-suite-only helpers (recho/zecho/xcase) that live in
    // commonTest so they don't pollute production registries.
    private val registry by lazy {
        CommandRegistry(
            defaultCommandSpecs() + XcaseCommand() + ZechoCommand() + RechoCommand() +
                StrmatchCommand() + FnmatchCommand() + GccStubCommand(),
        )
    }

    private fun firstDiffLine(
        a: String,
        b: String,
    ): Int {
        val la = a.lines()
        val lb = b.lines()
        val n = maxOf(la.size, lb.size)
        for (i in 0 until n) {
            val ai = la.getOrNull(i) ?: return i + 1
            val bi = lb.getOrNull(i) ?: return i + 1
            if (ai != bi) return i + 1
        }
        return 0
    }

    /**
     * Cheap line-distance proxy: count positions at which the two outputs
     * differ. NOT a true edit distance — a one-line insertion in the
     * middle shows as "everything after differs" instead of 1 — but it's
     * monotonic enough to order xfails by closeness, and it allocates
     * no quadratic matrix.
     */
    private fun levenshteinLineDistance(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        var diff = 0
        var i = 0
        var j = 0
        val n = a.length
        val m = b.length
        while (i < n || j < m) {
            val aLineEnd = if (i < n) a.indexOf('\n', i).let { if (it < 0) n else it } else i
            val bLineEnd = if (j < m) b.indexOf('\n', j).let { if (it < 0) m else it } else j
            val aLineLen = aLineEnd - i
            val bLineLen = bLineEnd - j
            val equal =
                aLineLen == bLineLen &&
                    (aLineLen == 0 || a.regionMatches(i, b, j, aLineLen))
            if (!equal) diff++
            i = if (aLineEnd < n) aLineEnd + 1 else aLineEnd
            j = if (bLineEnd < m) bLineEnd + 1 else bLineEnd
        }
        return if (diff == 0) 1 else diff
    }

    private fun catV(s: String): String {
        val sb = StringBuilder(s.length)
        val bytes = s.encodeToByteArray()
        for (b in bytes) {
            val v = b.toInt() and 0xff
            when {
                v == 0x09 || v == 0x0A -> {
                    sb.append(v.toChar())
                }

                v < 0x20 -> {
                    sb.append('^').append((v + 0x40).toChar())
                }

                v == 0x7F -> {
                    sb.append("^?")
                }

                v >= 0x80 -> {
                    sb.append("M-")
                    val low = v and 0x7F
                    when {
                        low < 0x20 -> sb.append('^').append((low + 0x40).toChar())
                        low == 0x7F -> sb.append("^?")
                        else -> sb.append(low.toChar())
                    }
                }

                else -> {
                    sb.append(v.toChar())
                }
            }
        }
        return sb.toString()
    }
}
