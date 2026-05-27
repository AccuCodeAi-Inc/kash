plugins {
    id("kash.kmp")
}

description =
    "kash `reset` — return the terminal to a sane state by emitting RIS, DECSTR, DECAWM, DECTCEM, SGR-reset, and screen-clear escape sequences."

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
