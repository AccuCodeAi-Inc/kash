plugins {
    id("kash.kmp")
}

description =
    "kash `find` — recursive filesystem walker with name/type/size/mtime predicates, boolean operators, `-prune`, and `-exec`/`-exec ... +` actions."

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
