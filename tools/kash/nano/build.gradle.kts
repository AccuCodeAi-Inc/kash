plugins {
    id("kash.kmp")
}

description =
    "kash `nano` — a multiplatform pure-Kotlin terminal text editor (nano-style keybindings) for the kash shell."

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
