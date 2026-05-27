plugins {
    id("kash.kmp")
}

description =
    "kash `gzip` — compress and decompress gzip streams (including `gunzip` and `zcat` entry points), backed by fflate on Wasm and java.util.zip on JVM."

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
