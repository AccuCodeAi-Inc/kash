// Root project — empty aggregator. All build logic lives in `build-logic/`
// (composite build of convention plugins), and all sources live in the
// per-module subprojects: :core, :shared:regex, :tools:jq, :kash-app.
//
// Module graph (compile-time enforced):
//   :kash-app ──▶ :core ──▶ :shared:regex
//          └──▶ :tools:jq ──▶ :shared:regex
//
// To run everything:        ./gradlew check
// To run unit tests only:   ./gradlew jvmTest
// To run conformance:       ./gradlew :kash-app:conformanceTest
// To run the REPL:          ./gradlew :kash-app:runJvm
// To publish locally:       ./gradlew publishToMavenLocal

// Publishing convention (`kash.publishing`) is composed into `kash.kmp`
// (and via that, the app variants), so every kash module that applies
// `kash.kmp` automatically publishes. `./gradlew publishToMavenLocal`
// produces one root + per-target artifact set per module under
// `com.accucodeai.kash:<dotted-path>:<version>`.
