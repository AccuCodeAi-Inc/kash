plugins {
    id("kash.kmp")
}

description =
    "kash core API — `Command`, `CommandSpec`, `CommandRegistry`, `KashMachine`, `KashProcess`, `FileSystem`, and the streaming async-pipe IO that every kash tool builds on."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinxIo)
            api(libs.kotlinxIoBytestring)
            api(libs.kotlinxCoroutinesCore)
            api(libs.kotlinxSerializationJson)
            // ShellClock surfaces a `kotlinx.datetime.TimeZone` so
            // downstream tools (`date`, `ls -l`, `git log`) all reach
            // the same IANA-aware zone source —
            // `TimeZone.currentSystemDefault()` returns
            // `America/Los_Angeles` etc. so `%Z` can show a real zone
            // name and DST transitions are honored.
            api(libs.kotlinxDatetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}

// :api is the smallest possible module — Command, CommandSpec,
// CommandKind, CommandTag, CommandRegistry, FileSystem, AsyncPipe.
// Streaming IO is part of the surface, so kotlinx-io is `api`.
