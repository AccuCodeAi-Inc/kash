plugins {
    id("kash.kmp")
}

description = "kash `less` — a multiplatform pure-Kotlin terminal pager with search and scrolling for the kash shell."

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
