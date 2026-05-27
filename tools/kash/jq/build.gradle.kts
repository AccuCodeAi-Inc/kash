plugins {
    id("kash.kmp")
    id("kash.antlr")
    alias(libs.plugins.kotlinSerialization)
}

description =
    "kash `jq` — a multiplatform pure-Kotlin jq engine (ANTLR parser, pull-based evaluator, ~60 builtins, RE2-safe regex) for the kash shell."

kashAntlr {
    packageName.set("com.accucodeai.kash.tools.jq.parser.antlr")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:regex"))
            implementation(libs.kotlinxSerializationJson)
        }
    }
}
