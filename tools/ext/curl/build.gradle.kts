plugins {
    id("kash.kmp")
}

description =
    "kash `curl` — in-process HTTP(S) client supporting GET/POST/HEAD, custom headers, basic auth, redirects, streaming body output, and sandbox network policy enforcement."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:net"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
