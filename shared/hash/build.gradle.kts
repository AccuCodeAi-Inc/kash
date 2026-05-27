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
                implementation(libs.kotlincryptoHashSha1)
                implementation(libs.kotlincryptoHashSha2)
                implementation(libs.kotlincryptoHashMd)
            }
        }
    }
}
