plugins {
    id("kash.kmp")
}

description =
    "kash `ps` — report status of processes registered in the kash machine's process table, displaying PID, TTY, CPU time, and command."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
