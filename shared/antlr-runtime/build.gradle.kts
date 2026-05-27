plugins {
    id("kash.kmp")
}

description =
    "kash shared ANTLR runtime — re-exports `antlr-kotlin-runtime` and bundles common parser utilities (`TwoStageParse`, `ThrowingErrorListener`, `RecognitionDiagnostics`) used by all kash grammars."

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
