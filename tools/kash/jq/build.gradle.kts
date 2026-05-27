plugins {
    id("kash.kmp")
    id("kash.antlr")
    alias(libs.plugins.kotlinSerialization)
}

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
