plugins {
    id("kash.kmp")
    id("kash.conformance")
}

description =
    "kash — the embeddable bash-compatible shell VM: `Kash` host, `Session`, interpreter, parser, POSIX/ext/kash tool catalog, and the bash conformance test harness."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(project(":corevm"))
            api(project(":tools:kash:kash-module"))
            api(project(":tools:posix:posix-module"))
            api(project(":tools:ext:ext-module"))
            api(project(":tools:forensics:forensics-module"))
            implementation(libs.kotlinxCoroutinesCore)
            api(libs.kotlinxSerializationJson)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}
