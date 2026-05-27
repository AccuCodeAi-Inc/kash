plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        jvmMain.dependencies {
            implementation(libs.tukaaniXz)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
