# Kash

[![Maven Central Version](https://img.shields.io/maven-central/v/com.accucodeai.kash/kash?label=Kash)](https://central.sonatype.com/artifact/com.accucodeai.kash/kash)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.accucodeai.kash/api?label=API)](https://central.sonatype.com/artifact/com.accucodeai.kash/api)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.accucodeai.kash/corevm?label=CoreVM)](https://central.sonatype.com/artifact/com.accucodeai.kash/corevm)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.accucodeai.kash/tools.posix.posix-module?label=Posix%20Tools)](https://central.sonatype.com/artifact/com.accucodeai.kash/tools.posix.posix-module)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.accucodeai.kash/tools.ext.ext-module?label=Ext%20Tools)](https://central.sonatype.com/artifact/com.accucodeai.kash/tools.ext.ext-module)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.accucodeai.kash/tools.kash.kash-module?label=Kash%20Tools)](https://central.sonatype.com/artifact/com.accucodeai.kash/tools.kash.kash-module)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.accucodeai.kash/tools.forensics.forensics-module?label=Forensics%20Tools)](https://central.sonatype.com/artifact/com.accucodeai.kash/tools.forensics.forensics-module)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.accucodeai.kash/tools.ai.ai-module?label=AI%20Tools)](https://central.sonatype.com/artifact/com.accucodeai.kash/tools.ai.ai-module)
[![Platform](https://img.shields.io/badge/Platform-JVM%20%7C%20WASM-blueviolet?logo=kotlin&logoColor=white)](#)


## What is Kash?

Kash is POSIX userland environment with over 120 tools directly implemented in Kotlin. It runs
on the JVM and WASM.

Is it a VM? An emulator? Not exactly. All of Kash runs directly on the machine with 0(ish) virtualization.

## Play with the REPL

Kash has 2 REPL apps to help test and play with it.

### In the browser

https://accucodeai-inc.github.io/kash/

### In your terminal

Run `kash-app` direct in terminal. Do not use the jvmRun task.
```
./gradlew :kash-app:installJvmDist
./kash-app/build/install/kash-app-jvm/bin/kash-app
```

Optionally use the executable 'kash' shim

```
./gradlew :kash-app:installJvmDist
./bin/kash
```

Better yet, point Claude at it

```
./bin/claude-with-kash
```

Note: By default the REPL maintains a snapshot in `.kash`. We don't currently run a daemon so
this snapshot is simply locked until the previous REPL (and KashMachine) closes. This is a limitation with `kash-app` not
necesarilly a core limitation, as we fully support parallel shells.

## Use it as a library

Kash is published to Maven Central under the `com.accucodeai.kash` group. Add the
repository and pull in the core, then **pick only the commands you need** — every
command is its own module, so you don't drag in the whole userland to get `grep`.

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
}

dependencies {
    // Core: the shell engine + KashMachine (parsing, job control, the VM).
    implementation("com.accucodeai.kash:kash:0.1.0")

    // À la carte commands — add a line per tool you actually use.
    implementation("com.accucodeai.kash:tools.posix.grep:0.1.0")
    implementation("com.accucodeai.kash:tools.posix.sed:0.1.0")
    implementation("com.accucodeai.kash:tools.posix.awk:0.1.0")

    // Or grab a whole suite at once via its aggregate module:
    implementation("com.accucodeai.kash:tools.posix.posix-module:0.1.0")   // all POSIX tools
    // tools.ext.ext-module · tools.kash.kash-module ·
    // tools.forensics.forensics-module · tools.ai.ai-module
}
```

Artifact IDs mirror the module path: `:tools:posix:grep` → `tools.posix.grep`. See
the version badges above for the latest release of each module (use `0.1.0-SNAPSHOT`
from the Central Portal snapshot repo for bleeding-edge builds).

Kash is Kotlin Multiplatform (JVM + WASM); the same coordinates work from a
multiplatform `commonMain` source set.

## License

This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.

For third-party dependencies, attributions, and the test-only status of GPL components vendored under `external/`, see [NOTICE](NOTICE).

## Security

Read about it [here](./docs/SECURITY.md).

## Module Arch

### API

Central 'API' for tools (think like Linux c headers) which are used for Tools

### CoreVM

Fake process virtuailziation, FS, linux stuff

### CoreTest

Useful test utils

### Kash

Typical user entry point to system (exec)

### Kash-App

REPL Implementation of Kash to play with

### Shared

Group of shared utilities that usually require platform specific stuff.

### Tools

Group of Tools, subdivided into their group

#### Ai

Ai utility extensions including a full `agent` (claude-code like) helper tool which
is run entirely in Kash.

#### Kash (shell)

Home of the actual bash (shell) and other related Kash tools like `git`

#### Posix

Posix tools

#### Ext

Extra tools not a core part of Posix, but common Linux defaults.

#### Forensics

Forensics tools like `openssl` that aren't normally installed defaults.

These tools typically have no use case in a AI Workflow except for forensics.
