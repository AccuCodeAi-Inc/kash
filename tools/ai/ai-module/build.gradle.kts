plugins {
    id("kash.kmp")
}

description =
    "kash AI tools aggregator — Koin registration module that exposes the `agent` command to kash app entry points without pulling LLM dependencies into the core embedder."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(project(":tools:ai:agent"))
        }
    }
}
