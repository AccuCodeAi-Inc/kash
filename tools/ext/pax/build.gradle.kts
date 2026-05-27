plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(project(":corevm"))
        }
    }
}
