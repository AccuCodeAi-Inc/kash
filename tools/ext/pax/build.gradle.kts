plugins {
    id("kash.kmp")
}

description =
    "kash `pax` — POSIX Portable Archive Interchange utility supporting ustar and pax extended-header formats for listing, extracting, and creating tar-compatible archives."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(project(":corevm"))
        }
    }
}
