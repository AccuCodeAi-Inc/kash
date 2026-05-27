plugins {
    id("kash.kmp")
}

description =
    "kash `sh` — POSIX shell front-end that runs scripts and `-c` commands via the kash interpreter in an isolated subshell."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
