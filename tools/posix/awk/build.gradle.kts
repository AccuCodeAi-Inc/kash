plugins {
    id("kash.kmp")
    id("kash.antlr")
    id("kash.conformance")
}

description =
    "kash `awk` — ANTLR-parsed, POSIX-conformant awk interpreter (91 % one-true-awk corpus pass rate) with full pattern-action, arrays, user functions, getline, and output redirection."

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
