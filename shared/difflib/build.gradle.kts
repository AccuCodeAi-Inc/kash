plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
