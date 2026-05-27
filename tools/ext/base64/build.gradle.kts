plugins {
    id("kash.kmp")
}

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
