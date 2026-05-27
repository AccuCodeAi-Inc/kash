plugins {
    id("kash.kmp")
}

// Marker plugin for modules that ship a JVM executable. The actual
// `binaries { executable { mainClass.set(...) } }` block stays in each
// module's own build.gradle.kts.
