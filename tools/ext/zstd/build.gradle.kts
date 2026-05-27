plugins {
    id("kash.kmp")
}

description =
    "kash `zstd` — compress and decompress Zstandard streams and files (`zstd`, `unzstd`, `zstdcat`; aircompressor on JVM)."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        jvmMain.dependencies {
            implementation(libs.aircompressor)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
