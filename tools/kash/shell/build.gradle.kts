plugins {
    id("kash.kmp")
    id("kash.antlr")
}

description =
    "kash `shell` — the multiplatform bash-compatible shell interpreter (ANTLR parser, coroutine-based executor, pipelines, redirections, arithmetic, and POSIX builtins)."

kashAntlr {
    packageName.set("com.accucodeai.kash.parser.antlr")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(project(":corevm"))
            api(project(":shared:regex"))
            implementation(libs.kotlinxCoroutinesCore)
            api(libs.kotlinxSerializationJson)
            implementation(libs.kotlinxDatetime)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
