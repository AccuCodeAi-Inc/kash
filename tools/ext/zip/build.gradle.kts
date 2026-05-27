plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
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
