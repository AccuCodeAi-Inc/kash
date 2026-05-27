plugins {
    id("kash.kmp")
    id("kash.antlr")
}

description =
    "kash `make` — POSIX Makefile interpreter with ANTLR-parsed Makefile grammar, macro expansion, dependency-graph build, and dry-run/question-mode support."

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
