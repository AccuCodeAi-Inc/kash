plugins {
    id("kash.kmp")
}

description =
    "kash `xz` — compress and decompress XZ/LZMA streams and files (`xz`, `unxz`, `xzcat`, `lzma`, `unlzma`, `lzcat`; Tukaani XZ on JVM)."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        jvmMain.dependencies {
            implementation(libs.tukaaniXz)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
