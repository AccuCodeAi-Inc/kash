plugins {
    id("kash.kmp")
}

description =
    "kash `dd` — copy and convert a byte stream using `name=value` operands, supporting block sizing, skip/seek, and POSIX conv flags such as lcase/ucase/swab/sync."

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
