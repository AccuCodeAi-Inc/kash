plugins {
    id("kash.kmp")
}

description =
    "kash `vi` — a multiplatform pure-Kotlin modal terminal text editor (vi/ex keybindings) for the kash shell."

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
