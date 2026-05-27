plugins {
    id("kash.kmp")
}

description =
    "kash `timeout` — run a command and cancel it via coroutine cancellation if it exceeds a GNU-style duration."

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
