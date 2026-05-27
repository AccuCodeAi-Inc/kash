plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:hash"))
            implementation(libs.cryptographyCore)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
        jvmMain.dependencies {
            implementation(libs.cryptographyProviderJdk)
        }
        wasmJsMain.dependencies {
            implementation(libs.cryptographyProviderWebcrypto)
        }
    }
}
