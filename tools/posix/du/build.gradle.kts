plugins {
    id("kash.kmp")
}

description =
    "kash `du` — estimate file and directory disk usage with block-size, human-readable, summary, max-depth, and glob-exclude options."

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
