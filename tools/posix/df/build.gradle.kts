plugins {
    id("kash.kmp")
}

description =
    "kash `df` — report disk-space usage for mounted filesystems, querying the kash virtual FS layer, with human-readable, inode, POSIX, and grand-total options."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":corevm"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
