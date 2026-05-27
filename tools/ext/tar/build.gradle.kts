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
            implementation(libs.tukaaniXz)
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(project(":shared:fflate"))
            }
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
