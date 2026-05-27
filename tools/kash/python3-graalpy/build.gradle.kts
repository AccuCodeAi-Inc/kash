plugins {
    id("kash.kmp")
}

description =
    "kash `python3` GraalPy engine — JVM backend for kash python3 that runs Python via GraalVM Polyglot/GraalPy with a virtual filesystem adapter."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(project(":tools:kash:python3"))
        }
        jvmMain.dependencies {
            implementation(project(":coretest"))
            implementation(libs.graalvmPolyglot)
            implementation(libs.graalvmPolyglotPython)
            implementation(libs.graalvmPythonEmbedding)
        }
    }
}

// GraalPy on stock OpenJDK runs in interpreter-only mode and prints a noisy
// warning at first Context creation. We accept the perf hit (kash isn't a
// hot loop for Python) and silence the warning during tests.
tasks.withType<Test>().configureEach {
    jvmArgs("-Dpolyglot.engine.WarnInterpreterOnly=false")
}
