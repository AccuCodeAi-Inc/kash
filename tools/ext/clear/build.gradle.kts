plugins {
    id("kash.kmp")
}

description =
    "kash `clear` — clear the terminal screen by emitting ANSI/xterm escape sequences (cursor home, erase screen, optional scrollback erase)."

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
