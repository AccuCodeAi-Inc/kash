plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        jvmMain.dependencies {
            // Pure-Java bzip2 streaming codec.
            implementation(libs.commonsCompress)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
