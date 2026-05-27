plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":corevm"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
