plugins {
    id("kash.kmp")
}

description =
    "kash `diff` — compare files line-by-line, producing normal, unified, context, or ed-script output, with whitespace-folding options and recursive directory comparison."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:difflib"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(project(":corevm"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
