plugins {
    id("kash.kmp")
}

description =
    "kash `bzip2` — compress and decompress bzip2 streams (including `bunzip2` and `bzcat` entry points) backed by Apache Commons Compress on JVM."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        jvmMain.dependencies {
            // Pure-Java bzip2 streaming codec.
            implementation(libs.commonsCompress)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
