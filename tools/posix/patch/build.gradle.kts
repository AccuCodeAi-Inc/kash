plugins {
    id("kash.kmp")
}

description =
    "kash `patch` — apply unified, context, or normal diff hunks to files, with strip-level control, reverse mode, and dry-run support."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(project(":corevm"))
            implementation(project(":tools:posix:diff"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
