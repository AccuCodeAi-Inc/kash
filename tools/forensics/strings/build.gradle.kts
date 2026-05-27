plugins {
    id("kash.kmp")
}

description =
    "kash `strings` — extract printable character sequences from binary files, with GNU/BSD-compatible flags for minimum run length, byte-offset radix, file-name prefix, and multi-byte encoding modes."

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
