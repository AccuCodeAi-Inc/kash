plugins {
    id("kash.kmp")
}

description =
    "kash `git` smart-HTTP transport — multiplatform git-upload-pack/receive-pack over HTTP(S) for the kash git client."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":tools:kash:git"))
            implementation(project(":shared:net"))
            implementation(project(":shared:hash"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
