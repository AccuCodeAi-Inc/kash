plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(project(":tools:forensics:binwalk"))
            api(project(":tools:forensics:openssl"))
            api(project(":tools:forensics:strings"))
            api(project(":tools:forensics:xxd"))
        }
    }
}
