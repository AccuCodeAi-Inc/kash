plugins {
    id("kash.kmp")
}

description =
    "kash `bc` — arbitrary-precision calculator language interpreter with POSIX math library (`-l`), user-defined functions, and script-file or stdin input."

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
