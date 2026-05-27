import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("kash.kmp-jvm-app")
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    jvm {
        binaries {
            executable {
                mainClass.set("com.accucodeai.kash.MainKt")
            }
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":kash"))
            implementation(project(":tools:kash:python3-graalpy"))
            // JGit-backed git host adapter — automatically used when the
            // host CWD sits inside a real git repo. See
            // `:tools:kash:git-jgit` and `app/KashAppModule.kt`.
            implementation(project(":tools:kash:git-jgit"))
            // AI-tier tools — registers `agent`. Opt-in at the app layer so
            // bare-Kash embedders and conformance fixtures stay LLM-free.
            implementation(project(":tools:ai:ai-module"))
            implementation(libs.kotlinxCoroutinesCore)
            implementation(libs.kotlinxSerializationJson)
            // JGit transitively pulls slf4j-api 2.x. Without a provider on the
            // runtime classpath, SLF4J prints "No SLF4J providers were found"
            // to stderr on every CLI start (it's emitted directly to
            // System.err before any config can intercept it). Ship the NOP
            // provider so ServiceLoader is satisfied and the warning is gone.
            runtimeOnly(libs.slf4jNop)
        }
        jvmTest.dependencies {
            implementation(project(":kash"))
            implementation(libs.kotlinxCoroutinesCore)
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}

// PosixTerminalControl uses Project Panama (java.lang.foreign.*) to call
// libc — that triggers JDK 22+'s "restricted method" check unless we
// explicitly enable native access. We pass the flag to every JavaExec /
// Test / CreateStartScripts task so the warning never reaches end users
// (and tests don't print noise).
private val nativeAccessJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")

tasks.named<JavaExec>("runJvm") {
    standardInput = System.`in`
    jvmArgs(nativeAccessJvmArgs)
}

tasks.withType<CreateStartScripts>().configureEach {
    defaultJvmOpts = (defaultJvmOpts ?: emptyList()) + nativeAccessJvmArgs
}

tasks.named<Test>("jvmTest") {
    systemProperty("kash.repoRoot", rootProject.projectDir.absolutePath)
    jvmArgs(nativeAccessJvmArgs)
}
