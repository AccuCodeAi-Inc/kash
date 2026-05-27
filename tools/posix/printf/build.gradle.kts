plugins {
    id("kash.kmp")
}

description =
    "kash `printf` — format and print data per a C-style format string, with bash-compatible `-v VAR` capture and format recycling across argument lists."

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
