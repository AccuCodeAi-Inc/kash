plugins {
    id("kash.kmp")
}

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
