import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

// Wires the bash/modernish/jq upstream test corpora (git submodules under
// external/) into the consuming module's jvmTest. The submodule paths are
// always relative to the repo root, never the module root.
//
// Apply this only on modules that actually run the conformance suites
// (currently :kash-app).

plugins {
    id("kash.kmp")
}

val bashTestsDir =
    rootProject.layout.projectDirectory
        .dir("external/bash/tests")
        .asFile
val modernishTestsDir =
    rootProject.layout.projectDirectory
        .dir("external/modernish/lib/modernish/tst")
        .asFile
val jqTestsDir =
    rootProject.layout.projectDirectory
        .dir("external/jq/tests")
        .asFile
val awkTestsDir =
    rootProject.layout.projectDirectory
        .dir("external/onetrueawk/testdir")
        .asFile

val checkSubmodules by tasks.registering {
    description =
        "Verifies external/bash, external/modernish, external/jq, and external/onetrueawk submodules are initialized."
    group = "verification"
    val bashDir = bashTestsDir
    val modernishDir = modernishTestsDir
    val jqDir = jqTestsDir
    val awkDir = awkTestsDir
    val rootPath = rootDir
    doLast {
        val missing = listOf(bashDir, modernishDir, jqDir, awkDir).filterNot { it.isDirectory }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Submodule(s) not initialized: ${missing.joinToString { it.relativeTo(rootPath).path }}\n" +
                    "Run: git submodule update --init --recursive",
            )
        }
    }
}

tasks.named<Test>("jvmTest") {
    dependsOn(checkSubmodules)
    systemProperty("kash.bashTestsDir", bashTestsDir.absolutePath)
    systemProperty("kash.modernishTestsDir", modernishTestsDir.absolutePath)
    systemProperty("kash.jqTestsDir", jqTestsDir.absolutePath)
    systemProperty("kash.awkTestsDir", awkTestsDir.absolutePath)
    useJUnitPlatform {
        // Conformance suites are opt-in. `./gradlew jvmTest` runs only the
        // hand-written Kotlin tests; `./gradlew conformanceTest` runs the corpus.
        excludeTags("conformance")
    }
}

tasks.register<Test>("conformanceTest") {
    description = "Runs the upstream bash + modernish + jq + awk conformance corpora through Kash."
    group = "verification"
    dependsOn(checkSubmodules, tasks.named("jvmTestClasses"))
    val jvmTestTask = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTestTask.testClassesDirs
    classpath = jvmTestTask.classpath
    systemProperty("kash.bashTestsDir", bashTestsDir.absolutePath)
    systemProperty("kash.modernishTestsDir", modernishTestsDir.absolutePath)
    systemProperty("kash.jqTestsDir", jqTestsDir.absolutePath)
    systemProperty("kash.awkTestsDir", awkTestsDir.absolutePath)
    System.getProperty("kash.diag.focus")?.let { systemProperty("kash.diag.focus", it) }
    // XfailDiagnosticsRunner emits one DynamicTest per xfailed script via
    // @TestFactory, so each iteration's InMemoryFs / Kash session / interp
    // state is released between tests. But JUnit runs DynamicTests in
    // parallel (junit-platform.properties: factor 1.0 = one worker per
    // CPU), so peak heap is per-test footprint × CPU count. 512m
    // occasionally trips a transient OOM when several heavy scripts
    // overlap with an AST-heavy one; 1g gives headroom without paying
    // the 2g price of the old single-`@Test` form.
    maxHeapSize = "1g"
    useJUnitPlatform {
        includeTags("conformance")
    }
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}
