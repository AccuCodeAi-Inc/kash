plugins {
    id("kash.kmp")
}

description =
    "kash `binwalk` — scan binary files for embedded file signatures at arbitrary offsets and optionally carve them out, with Shannon-entropy mapping for compressed/encrypted region detection."

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
