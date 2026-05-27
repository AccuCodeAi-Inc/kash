plugins {
    id("kash.kmp")
}

description =
    "kash `seq` — print an arithmetic sequence of integers or decimals with optional separator and zero-padding."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
