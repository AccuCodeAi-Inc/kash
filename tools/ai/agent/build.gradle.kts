plugins {
    id("kash.kmp")
}

description =
    "kash `agent` — interactive LLM agent with shell tool access, streaming directly against any OpenAI-compatible endpoint (LM Studio, Ollama, OpenRouter) via our own thin Ktor client."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            // Persistent non-interactive shell for the agent's shell_exec tool —
            // we hold a long-lived Interpreter so cwd/env/functions/aliases
            // survive across tool calls.
            implementation(project(":tools:kash:shell"))
            // Content-based file-type identification (magic-number signatures)
            // so read_file can tell an image from text/other-binary and route
            // images into the model's vision input.
            implementation(project(":shared:kmagic"))
            // For probing `/v1/models` (a simple JSON GET). Our OpenAI chat
            // client uses Ktor directly (see jvmMain/wasmJsMain) because it
            // needs streaming SSE consumption, which KashKtorClient buffers.
            implementation(project(":shared:net"))
            // Ktor — multiplatform HTTP client we drive directly to talk to
            // OpenAI-compatible endpoints. The engine is platform-specific
            // (CIO on JVM, JS on wasmJs), wired up in each platform's
            // OpenAIChatClient.kt actual.
            implementation(libs.ktorClientCore)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
        jvmMain.dependencies {
            implementation(libs.ktorClientCio)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktorClientJs)
        }
    }
}
