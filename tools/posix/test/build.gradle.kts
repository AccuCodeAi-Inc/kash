plugins {
    id("kash.kmp")
}

description =
    "kash `test` / `[` — POSIX conditional expression evaluator for file attributes, string comparisons, and integer relations, used as a shell builtin."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
