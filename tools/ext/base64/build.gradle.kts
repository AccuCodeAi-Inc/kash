plugins {
    id("kash.kmp")
}

description =
    "kash `base64` — encode and decode base64 streams with configurable line-wrap and optional garbage-ignoring strict/lenient decode."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            // bareCommandContext + InMemoryFs live in :corevm
            implementation(project(":coretest"))
        }
    }
}
