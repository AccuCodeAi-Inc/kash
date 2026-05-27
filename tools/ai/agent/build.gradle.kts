plugins {
    id("kash.kmp")
}

description =
    "kash `agent` — interactive LLM agent with shell tool access, backed by the JetBrains Koog framework and any OpenAI-compatible endpoint (LM Studio, Ollama, OpenRouter)."

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
            // Used to probe `/v1/models` directly — Koog's strict deserializer
            // rejects the LM Studio response (missing `created` field; see
            // https://github.com/JetBrains/koog/issues/139). We parse it
            // ourselves with a tolerant Json.
            implementation(project(":shared:net"))
            // Koog — multiplatform agent framework (JVM, wasmJs, JS, iOS).
            // Brings the OpenAI-compatible client (with custom baseUrl
            // support for LM Studio / Ollama), tool registry, and the
            // streaming event-handler feature used here for token-level
            // rendering.
            implementation(libs.koogAgents)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
