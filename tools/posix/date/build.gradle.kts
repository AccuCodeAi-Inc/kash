plugins {
    id("kash.kmp")
}

description =
    "kash `date` — display the current date and time using POSIX `+format` strings and common GNU extensions (`%s`, `%F`, `-d`, `-R`, `-I`), via kotlinx-datetime."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(libs.kotlinxDatetime)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
