plugins {
    id("kash.kmp")
}

// Shared wasmJs interop for the `fflate` npm package (MIT) plus the
// byte<->JS marshaling helpers its callers need. Centralizes what was
// duplicated across :tools:ext:{gzip,tar,zip}. `api(npm(...))` so the
// fflate dependency reaches consumers' webpack bundle transitively.
//
// git keeps its own pako interop (it needs strm.total_in, which fflate
// doesn't expose) and does not depend on this module.
kotlin {
    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                api(npm("fflate", "0.8.2"))
            }
        }
    }
}
