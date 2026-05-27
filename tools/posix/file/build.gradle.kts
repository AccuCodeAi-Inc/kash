plugins {
    id("kash.kmp")
}

description =
    "kash `file` — determine file type by content magic inspection via `:shared:kmagic`, with MIME-type output and brief/symlink-follow options."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:kmagic"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
