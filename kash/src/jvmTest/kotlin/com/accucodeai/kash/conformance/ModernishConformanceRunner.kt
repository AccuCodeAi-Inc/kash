package com.accucodeai.kash.conformance

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Runs the modernish conformance suite (external/modernish/lib/modernish/tst/)
 * through Kash.
 *
 * Modernish's `.t` files are not standalone scripts — they expect the modernish
 * harness (`run.sh` + `bin/modernish`) to be sourced first. Wiring that harness
 * to drive Kash is a follow-up; until then this factory enumerates the `.t`
 * files and reports each as skipped via JUnit's assumption mechanism, so the
 * suite is visible in CI output without producing false negatives.
 *
 * Once Kash can host modernish, replace the `assumeTrue(false, ...)` body with
 * the same diff-driven check used by [BashTestPairRunner] (likely against a
 * captured run.sh output baseline).
 */
@Tag("conformance")
class ModernishConformanceRunner {
    @TestFactory
    fun modernishTests(): List<DynamicTest> {
        val dir =
            System.getProperty("kash.modernishTestsDir")?.let(::File)
                ?: error("kash.modernishTestsDir system property not set; run via Gradle.")
        if (!dir.isDirectory) return emptyList()
        val xfail = ScriptPairRunner.loadXfail("modernish-xfail.txt")
        return dir
            .listFiles { f -> f.isFile && f.name.endsWith(".t") }
            ?.sortedBy { it.name }
            ?.map { f ->
                val name = f.nameWithoutExtension
                DynamicTest.dynamicTest("modernish/$name") {
                    val reason =
                        if (name in xfail) {
                            "xfail: pending modernish harness"
                        } else {
                            "skipped: modernish harness wiring not implemented yet"
                        }
                    assumeTrue(false, reason)
                }
            }.orEmpty()
    }
}
