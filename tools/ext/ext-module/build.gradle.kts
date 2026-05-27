plugins {
    id("kash.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(project(":tools:ext:base64"))
            api(project(":tools:ext:bzip2"))
            api(project(":tools:ext:clear"))
            api(project(":tools:ext:column"))
            api(project(":tools:ext:cpio"))
            api(project(":tools:ext:curl"))
            api(project(":tools:ext:gzip"))
            api(project(":tools:ext:hexdump"))
            api(project(":tools:ext:lz4"))
            api(project(":tools:ext:pax"))
            api(project(":tools:ext:printenv"))
            api(project(":tools:ext:reset"))
            api(project(":tools:ext:rev"))
            api(project(":tools:ext:seq"))
            api(project(":tools:ext:shasum"))
            api(project(":tools:ext:tac"))
            api(project(":tools:ext:uuidgen"))
            api(project(":tools:ext:which"))
            api(project(":tools:ext:whoami"))
            api(project(":tools:ext:tar"))
            api(project(":tools:ext:xz"))
            api(project(":tools:ext:yes"))
            api(project(":tools:ext:zip"))
            api(project(":tools:ext:zstd"))
        }
    }
}
