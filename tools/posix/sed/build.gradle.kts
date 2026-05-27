plugins {
    id("kash.kmp")
}

description =
    "kash `sed` — stream editor supporting substitution, addresses, hold-space, branching, in-place edit, and multi-script `-e`/`-f`, backed by the RE2 regex engine."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:regex"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
