plugins {
    id("kash.kmp")
}

description =
    "kash `pkill` — signal (terminate) processes matched by ERE name pattern, modeled as removal from the kash machine's process table."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":tools:posix:pgrep"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
