plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":tools:kash:git"))
            implementation(project(":shared:hash"))
        }
        // JGit is JVM-only. We bind it in jvmMain so wasmJs builds don't drag
        // it in (and so the surface here stays a JVM-only host driver).
        val jvmMain by getting {
            dependencies {
                api(libs.jgit)
            }
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(project(":corevm"))
        }
    }
}
