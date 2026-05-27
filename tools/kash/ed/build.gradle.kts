plugins {
    id("kash.kmp")
}

description = "kash `ed` — a multiplatform pure-Kotlin implementation of the POSIX line editor for the kash shell."

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
