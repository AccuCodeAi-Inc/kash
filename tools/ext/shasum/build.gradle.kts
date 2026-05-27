plugins {
    id("kash.kmp")
}

description =
    "kash `shasum` — compute and verify SHA-1/224/256/384/512 digests (and MD5/SHA-*sum coreutils variants) for files or stdin."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:hash"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
