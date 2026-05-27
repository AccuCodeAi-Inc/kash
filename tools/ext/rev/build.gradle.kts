plugins {
    id("kash.kmp")
}

description = "kash `rev` — reverse the Unicode codepoints of every input line, reading from files or stdin."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
        }
    }
}
