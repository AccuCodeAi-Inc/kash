plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        jvmMain.dependencies {
            implementation(libs.commonsCompress)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
