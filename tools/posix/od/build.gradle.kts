plugins {
    id("kash.kmp")
}

description =
    "kash `od` — dump file or stdin bytes in octal, hex, decimal, ASCII, or char format, with configurable address radix, skip, byte-count limit, and line width."

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
