plugins {
    id("kash.kmp")
}

description =
    "kash shared test scaffolding — `bareCommandContext`, `NullFs`, `FakeFs`, and re-exported `kotlinx-coroutines-test` for use across all tool unit tests."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(project(":corevm"))
            // Re-exported so tool tests can write `runTest { ... }` without
            // each module declaring the dep — replaces the legacy
            // `runBlocking { ... }` pattern (which doesn't exist on wasmJs).
            api(libs.kotlinxCoroutinesTest)
        }
    }
}

// :coretest — shared test scaffolding. NOT a testFixtures jar (KMP support is
// rough); just a plain module whose `main` source set holds test-only helpers
// (`bareCommandContext`, `NullFs`, `FakeFs`). Tools depend on this via
// `testImplementation(project(":coretest"))` and import these utilities
// directly instead of redeclaring private copies per test file.
