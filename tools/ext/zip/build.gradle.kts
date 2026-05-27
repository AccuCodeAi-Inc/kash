plugins {
    id("kash.kmp")
}

description =
    "kash `zip` and `unzip` — create and extract ZIP archives with deflate compression (fflate on Wasm, pure-Kotlin on JVM)."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(project(":shared:fflate"))
            }
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
