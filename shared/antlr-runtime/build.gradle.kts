plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Re-exported so any module that depends on :shared:antlr-runtime
            // automatically gets the antlr-kotlin runtime on its classpath —
            // the kash.antlr convention plugin relies on this.
            api(libs.antlrKotlinRuntime)
        }
    }
}
