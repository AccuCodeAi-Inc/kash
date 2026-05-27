plugins {
    id("kash.kmp")
}

description =
    "kash `which` — locate commands by walking \$PATH, with -a to show all matches and -s for silent exit-status-only mode."

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
