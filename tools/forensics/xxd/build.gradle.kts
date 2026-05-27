plugins {
    id("kash.kmp")
}

description =
    "kash `xxd` — hex dump utility and its reverse (hex-to-binary), supporting plain, little-endian, and autoskip formats with configurable column width, grouping, offsets, and length limits."

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
