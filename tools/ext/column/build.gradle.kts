plugins {
    id("kash.kmp")
}

description =
    "kash `column` — format input into a grid or aligned table (BSD/util-linux column, supporting grid mode and `-t` table mode with configurable separators)."

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
