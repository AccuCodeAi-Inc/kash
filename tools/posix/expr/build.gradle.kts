plugins {
    id("kash.kmp")
}

description =
    "kash `expr` — evaluate arithmetic, string, and regex expressions passed as argv tokens and print the result, per POSIX."

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
