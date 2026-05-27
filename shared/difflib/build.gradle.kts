plugins {
    id("kash.kmp")
}

description =
    "kash shared diff library — multiplatform LCS-based line differ that produces unified-diff hunks, used by `diff` and git tooling."

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
