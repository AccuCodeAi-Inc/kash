plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:hash"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
