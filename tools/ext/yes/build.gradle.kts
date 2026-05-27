plugins {
    id("kash.kmp")
}

description = "kash `yes` — repeatedly emit a line (default \"y\") until a broken-pipe closes the stream."

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
