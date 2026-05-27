plugins {
    id("kash.kmp")
}

description =
    "kash shared net — multiplatform HTTP client abstraction (`KashKtorClient`) backed by Ktor-CIO on JVM and the browser `fetch` API on Kotlin/Wasm."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        // Ktor (and the CIO engine) live in jvmMain only. The commonMain
        // surface is a plain-Kotlin `expect class KashKtorClient` that does
        // not leak any ktor type, so wasmJs builds compile against the
        // wasmJs `actual` (which throws) without pulling ktor in.
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktorClientCore)
                implementation(libs.ktorClientCio)
            }
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
