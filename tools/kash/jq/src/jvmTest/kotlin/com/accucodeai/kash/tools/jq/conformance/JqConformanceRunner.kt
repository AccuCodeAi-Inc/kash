package com.accucodeai.kash.tools.jq.conformance

import com.accucodeai.kash.json.KashJson
import com.accucodeai.kash.tools.jq.Jq
import com.accucodeai.kash.tools.jq.JqCorpusParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Runs the upstream jqlang/jq corpus (`external/jq/tests/jq.test`).
 *
 * Tagged `conformance` so it's excluded from the default `jvmTest` run;
 * `./gradlew conformanceTest` opts in. The path is supplied by the build
 * via `-Dkash.jqTestsDir=...`.
 *
 * This is informational on v1 — many cases exercise features deferred to
 * v1.5/v2 (assignment, `def`, regex, modules). The quarantine list below
 * skips known-unsupported feature buckets.
 */
@Tag("conformance")
class JqConformanceRunner {
    private val quarantineSubstrings: List<String> =
        listOf(
            // regex — `splits` (stream-form of split) still missing
            "splits(",
            // modules / imports — deferred
            "import ",
            "include ",
            "modulemeta",
            // sql-ish — deferred
            " INDEX ",
            " IN(",
            "GROUP_BY",
            "UNIQUE_BY",
            // jq-specific I/O / format builtins not in v1
            "inputs",
            "@base64",
            "@uri",
            "@csv",
            "@tsv",
            "@sh",
            "@html",
            "@json",
            "@text",
            "\$__loc__",
            "\$ENV",
            "env",
            "halt",
            "debug",
            "stderr",
        )

    @TestFactory
    fun upstreamCorpus(): List<DynamicTest> {
        val dir =
            System.getProperty("kash.jqTestsDir")
                ?: return emptyList()
        val file = File(dir, "jq.test")
        if (!file.isFile) return emptyList()
        val cases = JqCorpusParser.parse("jq.test", file.readText())
        return cases.mapIndexed { idx, c ->
            DynamicTest.dynamicTest("[$idx] L${c.lineNumber}: ${c.filter.take(50)}") {
                assumeTrue(
                    quarantineSubstrings.none { it in c.filter },
                    "quarantined: filter mentions unsupported v1 feature",
                )
                runCase(c)
            }
        }
    }

    private fun runCase(c: com.accucodeai.kash.tools.jq.JqCorpusCase) {
        val input =
            try {
                KashJson.parse(c.input)
            } catch (_: Throwable) {
                assumeTrue(false, "input is not parseable as a single JSON value")
                return
            }
        if (c.expectError) {
            assertFails { Jq.run(c.filter, input).toList() }
            return
        }
        val actual = Jq.run(c.filter, input).toList().map { KashJson.encode(it) }
        val expected = c.expected.map { KashJson.encode(KashJson.parse(it)) }
        assertEquals(expected, actual)
    }
}
