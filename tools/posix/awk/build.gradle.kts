plugins {
    id("kash.kmp")
    id("kash.antlr")
    id("kash.conformance")
}

kashAntlr {
    packageName.set("com.accucodeai.kash.tools.awk.parser.antlr")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:regex"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
