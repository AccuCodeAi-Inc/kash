plugins {
    id("kash.kmp")
}

description =
    "kash `lz4` — compress and decompress LZ4 frame-format streams (including `unlz4` and `lz4cat` entry points) backed by Apache Commons Compress on JVM."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        jvmMain.dependencies {
            implementation(libs.commonsCompress)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
