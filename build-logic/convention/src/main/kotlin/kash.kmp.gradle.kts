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
    //
    // commonTest *compiles* against wasmJs by default — the legacy
    // `kotlinx.coroutines.runBlocking` pattern (which doesn't exist on
    // wasmJs; it's single-threaded — no blocking primitive) has been
    // migrated to `kotlinx.coroutines.test.runTest`. Browser test
    // EXECUTION (`wasmJsBrowserTest` via Karma) is still opt-in per
    // module: spinning up headless Chrome on every `:check` is
    // heavyweight, and most modules don't have wasmJs-specific tests
    // worth the cost yet. Modules that want browser tests re-enable
    // with `tasks.named("wasmJsBrowserTest") { enabled = true }`
    // and add wasmJsTest sources (see :tools:kash:python3-pyodide).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }

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
