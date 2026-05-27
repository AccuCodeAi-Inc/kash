plugins {
    id("kash.kmp")
}

description =
    "kash shared regex — multiplatform `LinearRegex` abstraction backed by RE2/J on JVM (guaranteed linear-time, ReDoS-safe) and the JS `RegExp` engine on Kotlin/Wasm."

kotlin {
    sourceSets {
        jvmMain.dependencies {
            // RE2/J: linear-time regex engine. We deliberately don't use
            // java.util.regex for jq's test/match/sub/gsub because its
            // backtracking can blow up on adversarial patterns (catastrophic
            // backtracking, ReDoS). RE2/J guarantees time linear in input
            // length regardless of pattern.
            implementation(libs.re2j)
        }
    }
}
