plugins {
    id("kash.kmp")
}

description =
    "kash `printenv` — print environment variables: all NAME=value pairs when given no arguments, or the value of each named variable with non-zero exit for any unset name."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
