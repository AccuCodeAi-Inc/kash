plugins {
    id("kash.kmp")
}

description =
    "kash `grep` — search input for lines matching a pattern, supporting BRE/ERE/fixed-string modes, context lines, recursive directory search, and color highlighting via the RE2 regex engine."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:regex"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
