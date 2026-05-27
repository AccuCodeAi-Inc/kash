package com.accucodeai.kash.conformance

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Runs GNU bash's upstream test corpus (external/bash/tests/) through Kash.
 *
 * Each `<name>.tests` script is executed and its captured stdout+stderr
 * is diffed against the sibling `<name>.right` fixture.
 *
 * Known failures are listed in `bash-tests-xfail.txt` on the test classpath.
 * An xfail entry that suddenly starts passing fails loudly so we notice progress.
 *
 * Tagged `conformance` — excluded from the default `jvmTest` run and gated
 * behind `./gradlew conformanceTest`.
 */
@Tag("conformance")
class BashTestPairRunner {
    @TestFactory
    fun bashTests(): List<DynamicTest> {
        val dir =
            System.getProperty("kash.bashTestsDir")?.let(::File)
                ?: error("kash.bashTestsDir system property not set; run via Gradle.")
        val pairs = ScriptPairRunner.discoverPairs(dir, ".tests", ".right")
        val xfail = ScriptPairRunner.loadXfail("bash-tests-xfail.txt")
        val normalize = ScriptPairRunner.loadNormalizations("bash-tests-normalize.txt")
        return ScriptPairRunner.buildDynamicTests(
            labelPrefix = "bash",
            pairs = pairs,
            xfail = xfail,
            scriptDir = dir,
            normalizations = normalize,
        )
    }
}
