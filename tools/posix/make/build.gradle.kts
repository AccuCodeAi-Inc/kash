plugins {
    id("kash.kmp")
    id("kash.antlr")
}

kashAntlr {
    packageName.set("com.accucodeai.kash.tools.make.parser.antlr")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(project(":corevm"))
        }
    }
}
