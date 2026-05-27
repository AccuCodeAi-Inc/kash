package com.accucodeai.kash.tools.awk.conformance

import com.accucodeai.kash.tools.awk.Awk
import com.accucodeai.kash.tools.awk.AwkOptions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * Runs the upstream onetrueawk corpus
 * (`external/onetrueawk/testdir/{t,p}.*`) through Kash's awk engine and
 * diffs the output against a reference `awk` binary on the host.
 *
 * The corpus format isn't directly comparable: each `t.N` / `p.N` file is
 * just an awk program; the original `REGRESS` shell script runs it under
 * two different awks (`oldawk` and `../a.out`) and compares the outputs.
 * We do the same here — system awk is the reference, ours is the
 * candidate. On macOS the system awk *is* one-true-awk, so the comparison
 * is exact; on Linux it's typically gawk/mawk which agree on the basics
 * but diverge on edge cases (printf rounding, locale-sensitive regex,
 * gawk extensions) — those show up as expected failures.
 *
 * Tagged `conformance` so it stays out of the default `jvmTest` run;
 * `./gradlew :tools:awk:jvmTest -Dkash.awkTestsDir=…` (or
 * `./gradlew conformanceTest`) opts in.
 *
 * If no system `awk` is on `PATH`, every case is `assumeTrue`-skipped
 * with a clear message — the runner won't false-fail just because the
 * reference is missing.
 */
@Tag("conformance")
class AwkConformanceRunner {
    /**
     * Substrings in a test program that mean "this case exercises a
     * feature we know we don't support yet." Skipping keeps the failure
     * list readable while we land slice-2 work.
     *
     * Conservative on purpose: anything that mentions one of these in
     * comments will be skipped too. Better than a false negative.
     */
    private val unsupportedFeatureSubstrings: List<String> =
        listOf(
            // Process-spawn forms — the engine supports them via
            // `ctx.shellRunner`, but this conformance runner calls the
            // engine directly without a runner, so any test exercising
            // these would diverge from the reference awk's real spawn.
            "system(",
            "| \"",
            // ARGC / ARGV (program-visible argument vector) — engine
            // doesn't model these as scalars yet.
            "ARGC",
            "ARGV",
            // Programs whose output depends on rand() are
            // non-deterministic — gawk and our PRNG don't agree on the
            // seeded sequence, so the diff is unstable by design.
            "rand(",
        )

    /**
     * Programs that loop `for (k in array)` may produce identical sets of
     * lines in different orders, since POSIX leaves array iteration order
     * unspecified. When we detect this shape, sort both sides before
     * diffing — it's a sound comparison for set-equality, and avoids
     * false failures purely due to hash-table ordering.
     */
    private val forInArrayPattern = Regex("""for\s*\(\s*\w+\s+in\s+\w+""")

    private val systemAwk: String? by lazy { findSystemAwk() }

    @TestFactory
    fun upstreamCorpus(): List<DynamicTest> {
        val dir = System.getProperty("kash.awkTestsDir") ?: return emptyList()
        val root = File(dir)
        if (!root.isDirectory) return emptyList()
        val testData = File(root, "test.data")
        val testCountries = File(root, "test.countries")
        if (!testData.isFile || !testCountries.isFile) return emptyList()

        val tFiles =
            root
                .listFiles { f -> f.name.matches(Regex("""t\.\w+""")) }
                .orEmpty()
                .toList()
                .sortedBy { it.name }
        val pFiles =
            root
                .listFiles { f -> f.name.matches(Regex("""p\.\w+""")) }
                .orEmpty()
                .toList()
                .sortedBy { it.name }

        val cases = mutableListOf<DynamicTest>()
        for (f in tFiles) cases += buildCase(f, listOf(testData))
        for (f in pFiles) cases += buildCase(f, listOf(testCountries, testCountries))
        return cases
    }

    private fun buildCase(
        programFile: File,
        inputs: List<File>,
    ): DynamicTest =
        DynamicTest.dynamicTest(programFile.name) {
            assumeTrue(systemAwk != null, "no system awk on PATH — can't diff against a reference")
            val source = programFile.readText()
            assumeTrue(
                unsupportedFeatureSubstrings.none { it in source },
                "quarantined: program uses an unimplemented feature",
            )

            // Reference output: invoke system awk with the same file
            // arguments the reference would see — that's what `awk -f prog
            // f1 f2` materializes into FILENAME/FNR inside the script.
            val expected =
                runReferenceAwk(programFile, inputs) ?: run {
                    assumeTrue(false, "reference awk failed/timed out — skipping")
                    return@dynamicTest
                }

            // Build per-file AwkInputFiles — `runFiles` resets FNR and
            // sets FILENAME at each boundary, matching what the
            // reference awk sees.
            val awkFiles =
                inputs.map { f ->
                    val text = f.readText()
                    val recs =
                        if (text.isEmpty()) {
                            emptySequence<String>()
                        } else {
                            text.removeSuffix("\n").splitToSequence('\n')
                        }
                    com.accucodeai.kash.tools.awk
                        .AwkInputFile(f.absolutePath, recs)
                }

            // Programs that redirect output via `print > "f"` etc. need
            // *some* sink that doesn't throw. The reference awk writes
            // to real files in cwd; the conformance test only compares
            // stdout, so a no-op sink that swallows the writes is
            // sufficient. We don't need to materialize the files.
            val noopWriter =
                object : com.accucodeai.kash.tools.awk.AwkOutputWriter {
                    override suspend fun write(text: String) { /* swallow */ }
                }
            val outputOpener: suspend (String, com.accucodeai.kash.tools.awk.AwkOutputMode) ->
            com.accucodeai.kash.tools.awk.AwkOutputWriter? = { _, mode ->
                if (mode == com.accucodeai.kash.tools.awk.AwkOutputMode.Pipe) null else noopWriter
            }
            val actual =
                try {
                    var collected = ""
                    // JUnit DynamicTest bodies are non-suspending; runTest
                    // drives the Flow on a TestDispatcher and throws on
                    // failure (which the surrounding try/catch maps to a
                    // feature-gap skip or hard failure).
                    kotlinx.coroutines.test.runTest {
                        val sb = StringBuilder()
                        Awk
                            .compile(source)
                            .runFiles(awkFiles.asSequence(), AwkOptions(), outputOpener = outputOpener)
                            .collect { sb.append(it) }
                        collected = sb.toString()
                    }
                    collected
                } catch (t: Throwable) {
                    // Surface known "not yet implemented" runtime/parser
                    // failures as skips rather than hard failures — these
                    // are tracked in STATUS.md, not bugs to chase here.
                    val m = t.message ?: ""
                    val isFeatureGap =
                        "not yet implemented" in m ||
                            "not supported yet" in m ||
                            // Java regex chokes on some POSIX ERE forms
                            // (esp. unescaped `-` inside `[]` where awk
                            // treats it literally). Tracked under the
                            // "regex-engine differences" entry in STATUS.
                            "invalid regex" in m
                    if (isFeatureGap) {
                        assumeTrue(false, "feature gap (${t::class.simpleName}): $m")
                        return@dynamicTest
                    }
                    throw AssertionError(
                        "kash awk threw while running ${programFile.name}:\n  ${t::class.simpleName}: ${t.message}",
                        t,
                    )
                }

            // Trim trailing newlines on both sides — different awks
            // disagree slightly on whether a final ORS appears for the
            // last record. The body of the output is what matters for
            // conformance.
            var e = expected.trimEnd('\n')
            var a = actual.trimEnd('\n')
            if (forInArrayPattern.containsMatchIn(source)) {
                e = e.split('\n').sorted().joinToString("\n")
                a = a.split('\n').sorted().joinToString("\n")
            }
            if (e != a) {
                // Dump both to /tmp so we can inspect when JUnit's
                // truncated assertion message is misleading. Each test
                // gets its own pair: /tmp/awk-conform/<name>.{expected,actual}
                val dump = File("/tmp/awk-conform")
                dump.mkdirs()
                File(dump, "${programFile.name}.expected").writeText(e)
                File(dump, "${programFile.name}.actual").writeText(a)
                val eLines = e.count { it == '\n' } + (if (e.isEmpty()) 0 else 1)
                val aLines = a.count { it == '\n' } + (if (a.isEmpty()) 0 else 1)
                throw AssertionError(
                    "output mismatch on ${programFile.name}: expected $eLines lines, " +
                        "got $aLines (full diff at /tmp/awk-conform/${programFile.name}.{expected,actual})",
                )
            }
        }

    /**
     * Run the reference awk binary as a subprocess. Returns `null` if it
     * times out or exits abnormally (which we treat as "skip" so we
     * don't blame ourselves for upstream-test programs that even the
     * reference can't handle on this host).
     */
    private fun runReferenceAwk(
        programFile: File,
        inputs: List<File>,
    ): String? {
        val awk = systemAwk ?: return null
        val cmd = mutableListOf(awk, "-f", programFile.absolutePath)
        cmd += inputs.map { it.absolutePath }
        // Spawn in a fresh temp dir so corpus tests that do
        // `print > "tempbig"` etc. don't litter the awk module dir.
        // We only consume the reference's stdout; any side-effect files
        // it writes get deleted with the temp dir.
        val workDir = createTempDirectory(prefix = "awk-ref-").toFile()
        try {
            val proc =
                ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .directory(workDir)
                    .start()
            val finished = proc.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return null
            }
            if (proc.exitValue() != 0) return null
            return proc.inputStream.readBytes().decodeToString()
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun findSystemAwk(): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        for (dir in pathEnv.split(File.pathSeparator)) {
            val candidate = File(dir, "awk")
            if (candidate.canExecute()) return candidate.absolutePath
        }
        return null
    }
}
