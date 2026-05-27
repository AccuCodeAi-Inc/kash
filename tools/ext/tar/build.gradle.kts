plugins {
    id("kash.kmp")
}

description =
    "kash `tar` — create, extract, and list ustar archives with gzip/bzip2/xz filter support (Commons Compress on JVM, fflate on Wasm)."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        jvmMain.dependencies {
            implementation(libs.commonsCompress)
            implementation(libs.tukaaniXz)
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
