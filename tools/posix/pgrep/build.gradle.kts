plugins {
    id("kash.kmp")
}

description =
    "kash `pgrep` — look up processes in the kash process table by ERE name pattern and print matching PIDs, mirroring the procps-ng interface."

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
