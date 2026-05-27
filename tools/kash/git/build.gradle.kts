plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            implementation(project(":shared:difflib"))
            implementation(project(":shared:hash"))
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(project(":corevm"))
        }
        // wasmJs zlib is provided by `pako` (MIT + Zlib licensed). The JS
        // side is bundled by webpack from the npm package; the Kotlin
        // side calls into it via `js("…")` helpers in Zlib.wasmJs.kt.
        //
        // pako is retained here (not migrated to fflate alongside the rest
        // of kash's compression tools) because git's pack-file parser
        // requires `strm.total_in` after each inflate to advance the read
        // cursor — pako exposes that on its `Inflate` class, and fflate's
        // maintainer closed the equivalent request as not-planned
        // (fflate#221). See Zlib.wasmJs.kt for the consuming-inflate path.
        //
        // See NOTICE for attribution.
        val wasmJsMain by getting {
            dependencies {
                implementation(npm("pako", "2.1.0"))
            }
        }
    }
}
