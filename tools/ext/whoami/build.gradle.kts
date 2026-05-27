plugins {
    id("kash.kmp")
}

description = "kash `whoami` — print the effective user's login name (equivalent to `id -un`)."

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
