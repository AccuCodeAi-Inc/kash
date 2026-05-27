plugins {
    id("kash.kmp")
}

description =
    "kash `cpio` — create, extract, and list cpio archives in newc and odc formats (-o copy-out, -i copy-in, -t table-of-contents)."

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
