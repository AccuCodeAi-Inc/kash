package com.accucodeai.kash.conformance

import com.accucodeai.kash.ExecOptions
import com.accucodeai.kash.Kash
import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.ExecResult
import com.accucodeai.kash.defaultCommandSpecs
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DynamicTest
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.measureTimedValue

internal object ScriptPairRunner {
    private const val TEST_CWD = "/tests"

    /**
     * Full kash-app registry plus conformance-test-only helpers. The base
     * comes from `defaultCommandSpecs()` (every intrinsic + builtin + tool
     * module — chmod, rm, cat, mkdir, find, grep, printf, …). On top we
     * mount [XcaseCommand], a bash-test-suite helper (`xcase -n -u`)
     * `bash/tests/coproc.tests` invokes that no real system ships and that
     * we don't want polluting production registries. Lazy so the Koin scan
     * happens once at first test, not at discovery.
     */
    private val sharedRegistry by lazy {
        CommandRegistry(
            defaultCommandSpecs() + XcaseCommand() + ZechoCommand() + RechoCommand() +
                StrmatchCommand() + FnmatchCommand() + GccStubCommand(),
        )
    }

    private val sanitizedEnv =
        mapOf(
            "LANG" to "C",
            "LC_ALL" to "C",
            "TZ" to "UTC0",
            "HOME" to "/home/user",
            "PATH" to "/usr/bin:/bin",
            "PWD" to TEST_CWD,
            // bash's test harness sets THIS_SH to the shell-under-test so
            // scripts like alias.tests can re-enter the same shell for
            // sub-tests (`${THIS_SH} ./alias1.sub`). Point at /tmp/bash so
            // (a) `${THIS_SH##*/}` evaluates to `bash` (type.tests grep's
            // expected output keys off that exact basename), and (b) the
            // path resolves to a trampoline script seeded in [runOnce] that
            // re-enters the in-process `sh` interpreter (POSIX subset, same
            // dispatch as /usr/bin/sh).
            "THIS_SH" to "/tmp/bash",
            // bash's run-* harness scripts set `BASH_TSTOUT=$tmpdir/.tstout`
            // and the corpus checks it to decide between human-colored
            // output and the redirected/file-shape uncolored output. The
            // .right fixtures are uncolored, so set BASH_TSTOUT to a
            // non-empty value (the file is never actually written).
            "BASH_TSTOUT" to "/tmp/tstout",
        )

    data class Pair(
        val name: String,
        val script: File,
        val expected: File,
    )

    fun discoverPairs(
        root: File,
        scriptSuffix: String,
        expectedSuffix: String,
    ): List<Pair> {
        if (!root.isDirectory) return emptyList()
        return root
            .listFiles { f -> f.isFile && f.name.endsWith(scriptSuffix) }
            ?.mapNotNull { script ->
                val base = script.name.removeSuffix(scriptSuffix)
                val expected = File(root, "$base$expectedSuffix")
                if (expected.isFile) Pair(base, script, expected) else null
            }?.sortedBy { it.name }
            .orEmpty()
    }

    /**
     * Loads an xfail list from the test classpath. Lines beginning with `#`
     * and blank lines are ignored. Entries are matched against [Pair.name].
     */
    fun loadXfail(resourceName: String): Set<String> {
        val stream = javaClass.classLoader.getResourceAsStream(resourceName) ?: return emptySet()
        return stream.bufferedReader().useLines { lines ->
            lines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }
    }

    /** One regex substitution applied to both expected and actual output. */
    data class NormalizationRule(
        val regex: Regex,
        val replacement: String,
    )

    /**
     * Loads per-test output normalizations from the test classpath. Lets the
     * harness canonicalize implementation-defined output (fd numbers, pids,
     * tempfile names) so a kash run that's POSIX-correct but differs in
     * bash-internal cosmetics still passes.
     *
     * Format — one rule per line, tab-separated:
     *   `<test-name>\t<regex>\t<replacement>`
     *
     * Regex is multiline-mode (`^`/`$` match per-line) so `^\d+ \d+$` matches
     * a whole line of two integers without anchoring to the start of the
     * whole output. Rules are applied in file order; same test may have
     * multiple rules.
     */
    fun loadNormalizations(resourceName: String): Map<String, List<NormalizationRule>> {
        val stream = javaClass.classLoader.getResourceAsStream(resourceName) ?: return emptyMap()
        val byTest = LinkedHashMap<String, MutableList<NormalizationRule>>()
        stream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                // Strip CR/LF but PRESERVE leading/trailing tabs so an empty
                // replacement (trailing tab + nothing) survives — e.g. a
                // line-drop rule writes `name\tregex\t` and expects the
                // replacement to come back as the empty string.
                val line = raw.trimEnd('\n', '\r')
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split('\t', limit = 3)
                if (parts.size != 3) {
                    error("bad normalize-rule (need name\\tregex\\trepl): $line")
                }
                val (name, pattern, rawRepl) = parts
                // Interpret `\n` / `\t` / `\\` escapes in the replacement
                // so rules can produce multi-line output. The pattern
                // already supports `\n` natively via Regex.
                val replacement =
                    buildString {
                        var i = 0
                        while (i < rawRepl.length) {
                            val c = rawRepl[i]
                            if (c == '\\' && i + 1 < rawRepl.length) {
                                when (rawRepl[i + 1]) {
                                    'n' -> {
                                        append('\n')
                                        i += 2
                                        continue
                                    }

                                    't' -> {
                                        append('\t')
                                        i += 2
                                        continue
                                    }

                                    '\\' -> {
                                        append('\\')
                                        i += 2
                                        continue
                                    }
                                }
                            }
                            append(c)
                            i++
                        }
                    }
                byTest.getOrPut(name) { mutableListOf() } +=
                    NormalizationRule(
                        regex = Regex(pattern, RegexOption.MULTILINE),
                        replacement = replacement,
                    )
            }
        }
        return byTest
    }

    /**
     * Tests whose upstream `run-<name>` script pipes through `grep -v '^expect'`
     * before diffing. Each defines a shell helper that echoes section markers
     * the test author doesn't want to assert against. The set is derived by
     * grepping `run-*` for `grep -v '^expect'` once at runner startup.
     */
    private val expectFilteredTests: Set<String> by lazy {
        val out = mutableSetOf<String>()
        val cands = (
            System.getProperty("kash.bashTestsDir")?.let { java.io.File(it) }
                ?: java.io.File("external/bash/tests")
        )
        cands.listFiles { _, n -> n.startsWith("run-") }?.forEach { f ->
            try {
                val body = f.readText()
                if ("grep -v '^expect'" in body) {
                    // run-foo runs ./foo.tests against foo.right. Test name is everything after "run-".
                    val n = f.name.removePrefix("run-")
                    if (cands.resolve("$n.tests").isFile) out += n
                }
            } catch (_: Throwable) {
            }
        }
        out
    }

    internal fun applyRunnerFilter(
        name: String,
        text: String,
    ): String =
        if (name in expectFilteredTests) {
            text.lineSequence().filterNot { it.startsWith("expect") }.joinToString("\n")
        } else {
            text
        }

    /** Apply all [rules] to [text], left-to-right. */
    fun applyNormalizations(
        text: String,
        rules: List<NormalizationRule>,
    ): String {
        var out = text
        for (rule in rules) out = rule.regex.replace(out, rule.replacement)
        return out
    }

    /**
     * Build one [DynamicTest] per script pair. Each test body is wrapped in
     * `runTest { … }` so that `kotlinx.coroutines.delay` (and thus
     * `sleep N`, since `SleepCommand` uses `delay`) resolves on the test
     * scheduler's virtual time. Without this, `bash/jobs.tests` would
     * actually wait the full real-time `sleep 30 &; wait` etc.
     *
     * Per [kotlinx.coroutines.test.runTest] contract: return the produced
     * `TestResult` immediately from the test body. We do all assertions and
     * diff-formatting *inside* the runTest block; the AssertionError thrown
     * for a fail propagates through runTest's join.
     */
    fun buildDynamicTests(
        labelPrefix: String,
        pairs: List<Pair>,
        xfail: Set<String>,
        scriptDir: File,
        normalizations: Map<String, List<NormalizationRule>> = emptyMap(),
    ): List<DynamicTest> =
        pairs.map { pair ->
            DynamicTest.dynamicTest("$labelPrefix/${pair.name}") {
                runTest {
                    runPair(
                        labelPrefix,
                        pair,
                        pair.name in xfail,
                        scriptDir,
                        coroutineContext,
                        normalizations[pair.name].orEmpty(),
                    )
                }
            }
        }

    suspend fun runPair(
        labelPrefix: String,
        pair: Pair,
        isXfail: Boolean,
        scriptDir: File,
        testContext: CoroutineContext,
        rules: List<NormalizationRule> = emptyList(),
    ) {
        val (outcome, elapsed) = measureTimedValue { runOnce(pair, scriptDir, testContext) }
        // Apply the same catV rendering to expected as we do to actual, so
        // multi-byte UTF-8 in upstream `.right` files round-trips against our
        // post-processed output. Without this, expected has raw `á` bytes but
        // actual gets rendered to `M-CM-!` — bogus mismatch on alias6.sub.
        //
        // Then apply per-test normalizations (e.g. coproc fd-pair → <FDPAIR>)
        // to BOTH sides so implementation-defined output (bash's hardcoded
        // 63/60 fds, our 10/11) compares equal where POSIX allows either.
        // Bash's upstream test convention: ~17 `.tests` files (nquote,
        // extglob, more-exp, exp, invocation, invert, …) define an
        // `expect()` helper that echoes its argument as a section marker.
        // The corresponding `run-<name>` script filters `^expect` lines via
        // `grep -v` before diffing against `.right`. Mirror that here so
        // the conformance harness honors the same convention upstream uses,
        // rather than treating it as a kash bug.
        val expectedRaw = pair.expected.readText()
        val expected = applyNormalizations(catV(applyRunnerFilter(pair.name, expectedRaw)), rules)
        val actualCombined = applyNormalizations(applyRunnerFilter(pair.name, outcome.combined), rules)
        val matches = actualCombined == expected

        val tag =
            when {
                matches && isXfail -> "NEWPASS"
                matches -> "PASS   "
                isXfail -> "XFAIL  "
                else -> "FAIL   "
            }
        val thread = Thread.currentThread().name
        println("CONFORM $tag ${elapsed.toString().padStart(9)} [$thread] $labelPrefix/${pair.name}")

        if (isXfail) {
            if (matches) {
                throw AssertionError(
                    "UNEXPECTED PASS: ${pair.name} is listed as xfail but produced matching output. " +
                        "Remove it from the xfail file.",
                )
            }
            return
        }

        if (!matches) {
            throw AssertionError(buildDiff(pair, expected, outcome, actualCombined))
        }
    }

    private data class Outcome(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) {
        /** Combined output post-processed through `cat -v`: control chars become
         *  caret notation (`^G` for BEL, `^@` for NUL, `^?` for DEL) and bytes
         *  with the high bit set are prefixed `M-`. The upstream bash test
         *  driver does this to keep the .right fixtures plain-ASCII diffable. */
        val combined: String get() = catV(stdout + stderr)
    }

    private suspend fun runOnce(
        pair: Pair,
        scriptDir: File,
        testContext: CoroutineContext,
    ): Outcome {
        val fs = InMemoryFs()
        // Mirror the on-disk test directory into the in-memory FS so the script
        // can `source` its .sub helpers and read data files.
        fs.mkdirs(TEST_CWD)
        scriptDir.listFiles()?.forEach { f ->
            if (f.isFile) fs.writeBytes("$TEST_CWD/${f.name}", f.readBytes())
        }
        // Conformance scripts expect a writable `/tmp` and (sometimes)
        // `/var/tmp` — bash/extglob.tests does `mkdir /var/tmp/eglob-test-N`
        // then `cd` into it. Pre-create both so the mkdir succeeds.
        fs.mkdirs("/tmp")
        fs.mkdirs("/var/tmp")
        // THIS_SH points at /tmp/bash — see [sanitizedEnv]. Seed a trampoline
        // that re-enters the in-process POSIX `sh` so `${THIS_SH} ./foo.sub`
        // dispatches identically to the legacy `THIS_SH=/usr/bin/sh` setup,
        // while the basename `bash` flows through `${THIS_SH##*/}` for the
        // `type.tests` SHBASE derivation.
        fs.writeBytes("/tmp/bash", "exec /usr/bin/sh \"\$@\"\n".encodeToByteArray())
        // Synthetic /etc/passwd so scripts that do `cat /etc/passwd | grep root`
        // (bash/coproc.tests, several others) see realistic output. Single root
        // + user entry covers everything the test corpus actually scans for.
        fs.mkdirs("/etc")
        fs.writeBytes(
            "/etc/passwd",
            (
                "root:x:0:0:root:/root:/usr/bin/sh\n" +
                    "user:x:1000:1000:user:/home/user:/usr/bin/sh\n"
            ).encodeToByteArray(),
        )
        // glob-bracket.tests sources `examples/loadables/Makefile` to read
        // `SHOBJ_STATUS` and the CC variables. We can't build real shared
        // objects, but a stub Makefile that declares SHOBJ_STATUS=supported
        // lets the test proceed past its early-exit guard. The CC etc.
        // commands are intercepted by [GccStubCommand] which writes a
        // shell-script shim instead of compiling.
        fs.mkdirs("$TEST_CWD/examples/loadables")
        fs.writeBytes(
            "$TEST_CWD/examples/loadables/Makefile",
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

        // Pass the test scope's [CoroutineContext] as parentContext so
        // `sleep` (which calls `kotlinx.coroutines.delay`) and any `&`
        // background jobs share the test scheduler's virtual time. The
        // matching [VirtualShellClock] lets `$SECONDS` / `$EPOCHSECONDS`
        // / `date` read from the same virtual clock so tests like
        // `before=$SECONDS; sleep 2; after=$SECONDS; ((after>before))`
        // actually observe time advancing.
        val scheduler = testContext[kotlinx.coroutines.test.TestCoroutineScheduler.Key]
        val kash =
            Kash(
                fs = fs,
                initialCwd = TEST_CWD,
                registry = sharedRegistry,
                parentContext = testContext,
                clock =
                    scheduler?.let { VirtualShellClock(it) }
                        ?: com.accucodeai.kash.api.clock
                            .SystemShellClock(),
            )
        val script = pair.script.readText()

        val result: ExecResult =
            try {
                kash.exec(
                    script,
                    ExecOptions(
                        env = sanitizedEnv,
                        cwd = TEST_CWD,
                        replaceEnv = true,
                        mergeStderr = true,
                        // bash's runtime prefixes shell diagnostics with
                        // `<scriptpath>: line <N>:`. Setting the script
                        // name lets the interpreter mirror that format.
                        scriptName = "./${pair.script.name}",
                    ),
                )
            } catch (t: Throwable) {
                return Outcome(
                    stdout = "",
                    stderr = "kash threw ${t::class.simpleName}: ${t.message}\n",
                    exitCode = 2,
                )
            }
        return Outcome(result.stdout, result.stderr, result.exitCode)
    }

    /** Replicate `cat -v`: render non-printable bytes (other than `\t` and `\n`)
     *  in caret notation, and high-bit bytes with an `M-` prefix.
     */
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

    private fun buildDiff(
        pair: Pair,
        expected: String,
        actual: Outcome,
        actualFiltered: String,
    ): String =
        buildString {
            appendLine("FAIL: ${pair.name}")
            appendLine("  script:   ${pair.script}")
            appendLine("  expected: ${pair.expected}")
            appendLine("  exitCode: ${actual.exitCode}")
            appendLine("--- expected (post-filter, post-normalize) ---")
            append(expected.take(4000))
            if (expected.length > 4000) appendLine("...[truncated]")
            appendLine()
            appendLine("--- actual (stdout+stderr, post-filter, post-normalize) ---")
            append(actualFiltered.take(4000))
            if (actualFiltered.length > 4000) appendLine("...[truncated]")
        }
}
