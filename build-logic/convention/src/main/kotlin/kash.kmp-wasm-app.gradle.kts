// Convention plugin for modules that ship a wasmJs Compose-for-Web binary.
// Adds the Compose Multiplatform plugin + Kotlin Compose compiler plugin
// and configures the wasmJs target to produce an executable browser binary.

plugins {
    id("kash.kmp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        binaries.executable()
        browser {
            commonWebpackConfig {
                outputFileName = "kash-app-web.js"
            }
        }
    }
}
