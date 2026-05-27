plugins {
    id("kash.kmp")
}

description = "kash `uuidgen` — generate and print random (v4) or time-based (v1) UUIDs, one per line."

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
