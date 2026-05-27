plugins {
    id("kash.kmp")
}

description =
    "kash core VM — default implementations of the :api interfaces: `DefaultKashMachine`, `DefaultKashProcess`, `InMemoryFs`, `MountedFileSystem`, `ToolsFs`, and machine snapshot/restore."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(libs.kotlinxSerializationJson)
        }
        commonTest.dependencies {
            implementation(project(":coretest"))
            implementation(libs.kotlinxCoroutinesTest)
        }
    }
}

// :corevm holds the default implementations of the :api interfaces
// (DefaultKashMachine/Process/OFD, InMemoryFs, MountedFileSystem,
// MountedFsSnapshot, ToolsFs, HostFs). Lives between :api (pure interfaces,
// shared with every tool) and :core (the interpreter), so tools depend on
// :api but the interpreter / app pulls in the implementations from :corevm.
