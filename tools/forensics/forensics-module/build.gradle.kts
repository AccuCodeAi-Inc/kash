plugins {
    id("kash.kmp")
}

description =
    "kash forensics aggregator — Koin registration module that bundles `binwalk`, `openssl`, `strings`, and `xxd` into the default kash command catalog."

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
