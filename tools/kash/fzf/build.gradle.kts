plugins {
    id("kash.kmp")
}

description = "kash `fzf` — a multiplatform pure-Kotlin fuzzy-finder TUI command for the kash shell."

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
