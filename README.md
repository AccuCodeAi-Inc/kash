# Kash

Bash on Kotlin

## What is Kash?

Kash is POSIX userland environment with over 170 tools directly implemented in Kotlin. It runs
on the JVM and WASM.

Is it a VM? An emulator? Not exactly. All of Kash runs directly on the machine with 0(ish) virtualization.

## Where's my REPL?

Run `kash-app` direct in terminal. Do not use jvmRun task.
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
