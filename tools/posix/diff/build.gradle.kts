plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:difflib"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(project(":corevm"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
