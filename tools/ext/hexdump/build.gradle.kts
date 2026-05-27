plugins {
    id("kash.kmp")
}

description =
    "kash `hexdump` — display binary input in canonical hex+ASCII (-C), two-byte hex/octal/decimal, per-byte octal, and per-byte character display modes."

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
