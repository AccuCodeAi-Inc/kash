import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
    id("kash.publishing")
}

kotlin {
    explicitApi()
    jvmToolchain(25)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
    }

    // Browser-only Wasm target. Picked over `js(IR)` because Compose
    // Multiplatform for Web targets wasmJs specifically (see :kash-app-web,
    // Phase 3). Modules that have JVM-only `actual`s must supply a wasmJsMain
    // counterpart (see shared/regex, tools/ext/{base64,shasum,uuidgen}).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            // Tests in commonTest use `kotlinx.coroutines.runBlocking`, which
            // does not exist on wasmJs (single-threaded; blocking isn't a
            // thing). Rewriting those tests to `kotlinx.coroutines.test.runTest`
            // is its own project — for now we compile production code for
            // wasmJs but skip test compilation. Phase 2 (Pyodide) and Phase 3
            // (Compose-for-Web) will introduce wasmJs-specific tests in
            // wasmJsTest only.
            testTask { enabled = false }
        }
    }

    // Disable the wasmJs test-compile task itself so `check` doesn't drag
    // commonTest sources through a wasmJs compile that will never succeed
    // until the runBlocking -> runTest migration happens.
    tasks
        .matching {
            it.name == "compileTestKotlinWasmJs" ||
                it.name == "wasmJsTest" ||
                it.name == "wasmJsBrowserTest" ||
                it.name == "wasmJsNodeTest"
        }.configureEach { enabled = false }

    sourceSets {
        all {
            languageSettings {
                progressiveMode = true
            }
        }
    }

    // expect/actual classes are Beta in Kotlin 2.x; kash uses them across
    // shared/regex, shared/net, tools/ext/{tar,zip}, etc. Suppress the Beta
    // warning on every compilation (jvm + wasmJs already had the flag; this
    // also covers the commonMain metadata compile where the warning fired).
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
}

// JUnit 5 is the default test runtime for every module's jvmTest source set.
// Modules that don't have any jvmTest classes simply won't load these jars.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    "jvmTestImplementation"(libs.findLibrary("junitJupiterApi").get())
    "jvmTestImplementation"(libs.findLibrary("junitJupiterParams").get())
    "jvmTestRuntimeOnly"(libs.findLibrary("junitJupiterEngine").get())
    "commonTestImplementation"(libs.findLibrary("kotlinTest").get())
}

ktlint {
    version.set("1.8.0")
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
    }
}
