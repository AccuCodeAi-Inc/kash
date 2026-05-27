plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}

// Python3Command depends on PythonEngine, which is supplied by a separate
// per-target module (`:tools:python3-graalpy` on JVM,
// `:tools:python3-pyodide` on wasmJs). The app-level entry point
// constructs the engine and passes it to `python3Commands(engine)`.
