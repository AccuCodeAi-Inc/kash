plugins {
    id("kash.kmp")
}

description =
    "kash `sort` — sort text lines with numeric (`-n`), reverse (`-r`), unique (`-u`), multi-key (`-k`), and custom-separator (`-t`) options."

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
