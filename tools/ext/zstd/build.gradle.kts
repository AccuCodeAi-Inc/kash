plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        jvmMain.dependencies {
            implementation(libs.aircompressor)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
