plugins {
    id("kash.kmp")
}

description =
    "kash `tac` — concatenate inputs and print records in reverse order, with optional literal or regex separator."

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
