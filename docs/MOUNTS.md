# Mounts & filesystem labels

kash's [`FileSystem`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/FileSystem.kt)
abstracts where data lives so commands stay portable. For sessions that
need more than a single in-memory FS — a host cache, a scratch tmpfs, a
borrow of a host directory — use
[`MountedFileSystem`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/MountedFileSystem.kt),
a router over a list of labeled
[`Mount`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/Mount.kt)s.

## What a mount is

```kotlin
public data class Mount(
    val mountPoint: String,    // absolute path, e.g. "/", "/tmp", "/.cache/graalpy"
    val fs: FileSystem,        // any FileSystem — InMemoryFs, HostFs, custom
    val label: FsLabel,        // persistence category — see below
    val readOnly: Boolean = false,
)
```

A `MountedFileSystem` is constructed from a list of `Mount`s. Every kash
operation against it routes by **longest-prefix match** to the owning
mount, then translates the path to the mount-local form and delegates.

## Labels — the persistence axis

[`FsLabel`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/FsLabel.kt)
tells the future snapshot/restore layer (and policy filters like
`hermetic`) what to do with a mount:

| Label | What it means | Snapshotted? |
|---|---|---|
| `USER` | Script-owned state. The default for the root mount. | Yes |
| `ENGINE_CACHE` | Derivable from engine state (e.g. extracted Python stdlib). | No — regenerated on first use after restore. |
| `HOST_BORROW` | Opt-in passthrough to the real host filesystem. | No — out of scope. |
| `EPHEMERAL` | Memory-only scratch. | No — evaporates at session end. |

This split mirrors Docker's volume / bind / tmpfs categories — same axis,
explicit labels.

## Routing semantics (audited against Linux VFS, Plan 9, WASI preopens)

1. **Longest-prefix wins.** Mounts `/`, `/a`, `/a/b` all present →
   `/a/b/c/file` routes to `/a/b` with relative path `c/file`.
2. **Path canonicalization happens at the router**, before lookup.
   `/a/../b/c` normalizes to `/b/c` before the prefix match — Plan 9's
   "Getting Dot-Dot Right" applied at our layer.
3. **Mount points are virtual directories.** With a `/.cache/graalpy`
   mount and no `/.cache` in the parent mount, `exists("/.cache")` is
   true and `list("/.cache")` returns `["graalpy"]`.
4. **Mount shadows underlying content.** If the root mount has
   `/tmp/leaked.txt` and `/tmp` is also mounted, reads through the router
   see only the mount's view — `leaked.txt` is masked.
5. **Read-only enforcement** is on `Mount`, not on the underlying
   `FileSystem`. The same `HostFs` can be mounted writable at one path
   and read-only at another (Docker's `:ro` flag pattern). Writes to a
   read-only mount throw
   [`ReadOnlyMountException`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/Mount.kt).
6. **The mount table is immutable per `MountedFileSystem` instance.** No
   `addMount` / `removeMount` at runtime. Sessions construct their table
   up front; multi-coroutine pipelines see the same view.

Cross-mount rename/move is not modeled by the `FileSystem` interface
(there's no rename op) — tools that need to move files across mount
boundaries (`mv`) catch
[`CrossMountException`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/Mount.kt)
and fall back to copy + delete. Analogous to POSIX `EXDEV`.

## Snapshot & restore

[`MountedFileSystem.snapshot()`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/MountedFileSystem.kt)
captures a label-aware
[`MountedFsSnapshot`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/MountedFsSnapshot.kt):

- **`USER` mounts** backed by `InMemoryFs` → contents serialized into
  `userMounts: Map<mountPoint, FsSnapshot>`.
- **`USER` mounts** with non-`InMemoryFs` backing → appear in the manifest
  but content is not serialized (caller can walk them externally).
- **`ENGINE_CACHE` / `HOST_BORROW` / `EPHEMERAL`** → manifest entry only.
  Engines regenerate caches on next use, host borrows are out of scope,
  ephemeral evaporates by definition.

The result is a `@Serializable` data class — encode with any
kotlinx.serialization format (JSON, CBOR, ProtoBuf). For example:

```kotlin
val snap = session.fs.snapshot()
val bytes = Json.encodeToString(snap).encodeToByteArray()
// ... later, in a new process:
val restored: MountedFsSnapshot = Json.decodeFromString(bytes.decodeToString())
val newSession = KashSession(/* mounts re-declared by engines/library */)
(newSession.fs as MountedFileSystem).restore(restored)
```

Restore is **in place** against an existing `MountedFileSystem` whose mount
table matches the snapshot's manifest. We do NOT rebuild the table from a
snapshot — engines re-register their cache mounts at session construction,
user code re-declares borrows, and only the USER `InMemoryFs` content gets
rehydrated. A mismatched manifest throws
[`MountManifestMismatchException`](../api/src/commonMain/kotlin/com/accucodeai/kash/fs/MountedFsSnapshot.kt)
so the caller can either reconstruct the table or surface a clear error.

This split — declarative table at construction, content snapshot at
rest — keeps snapshots stable across engine upgrades (a new GraalPy
version still works; its cache mount declaration may change, but USER
content is unaffected) and across kash version bumps (the table is
expressed in user code; the snapshot just carries content + metadata).

## How a tool declares a cache mount

The `:tools:python3-graalpy` engine is the reference. It needs a place on
real disk for GraalPy's stdlib cache (Truffle's `InternalResource`
machinery extracts there). The engine builds a per-invocation
`MountedFileSystem` itself:

```kotlin
val composedKashFs =
    if (fs is MountedFileSystem &&
        fs.mounts().any { it.label == FsLabel.ENGINE_CACHE &&
                          it.mountPoint == CACHE_MOUNT_POINT }) {
        fs
    } else {
        MountedFileSystem(
            listOf(
                Mount("/", fs, FsLabel.USER),
                Mount(CACHE_MOUNT_POINT, HostFs(cacheHostDir), FsLabel.ENGINE_CACHE),
            ),
        )
    }
```

The mount point is exposed as
[`GraalPyEngine.CACHE_MOUNT_POINT`](../tools/python3-graalpy/src/jvmMain/kotlin/com/accucodeai/kash/tools/python3/graalpy/GraalPyEngine.kt)
(currently `/.cache/graalpy`). A future snapshot tool will:

1. Call `fs.mounts()` on the session's `FileSystem`.
2. Serialize every `USER` mount.
3. Record `ENGINE_CACHE` mount points (label + path) but skip contents.
4. Ignore `HOST_BORROW` and `EPHEMERAL` mounts entirely.
5. On restore: re-create the mount table; engines repopulate their
   `ENGINE_CACHE` mounts lazily on first use.

## The polyglot bridge

`:tools:python3-graalpy` also wraps `ctx.fs` in a
[`KashPolyglotFileSystem`](../tools/python3-graalpy/src/jvmMain/kotlin/com/accucodeai/kash/tools/python3/graalpy/KashPolyglotFileSystem.kt),
which implements GraalVM's `org.graalvm.polyglot.io.FileSystem` against
kash's `FileSystem` — Python's `open()` / `os.stat` etc. route through
the kash mount table.

A small **host passthrough region** is allowed for the GraalPy cache
prefix only (so Truffle's stdlib extraction works); all other Python file
access goes to kash. The `IOAccess` builder gets only that adapter — no
`allowHostFileAccess`, so paths like `/etc/passwd` raise
`FileNotFoundError` in Python.

This is the model future Python/Lua/etc. engines should follow:
implement the host polyglot FS as a kash-FS adapter, allow only the
narrowest host passthrough the engine bootstrap needs, label it
`ENGINE_CACHE`.

## What this enables / doesn't enable yet

**Enabled today:**
- Routing across mounts with real semantics (shadow, virtual dirs,
  dot-dot normalization).
- `HostFs` for engine caches and (future) host borrows.
- GraalPy's Python file I/O genuinely going through kash's VFS, not the
  host disk.

**Deferred:**
- A `KashSession(mounts = ...)` parameter to express the mount table at
  session construction is part of the Phase C1 follow-up.
- Engine-discoverable mount contributions via a `MountContribution`
  interface — for now engines build mount tables themselves per execution.
- Quotas on `EPHEMERAL` mounts (Docker `tmpfs size=64m` analog).
- Symlinks — `InMemoryFs` doesn't model them yet; cross-mount symlink
  semantics are a follow-up when they land.

## Tests

The contract is covered by:

- `api/src/commonTest/.../fs/MountedFileSystemTest.kt` — 23
  cases: longest-prefix, virtual mount-point dirs, shadow rule, dot-dot
  normalization, read-only enforcement, label query, more.
- `api/src/commonTest/.../fs/MountedFileSystemSnapshotTest.kt`
  — 7 cases: USER-only capture, manifest of all mounts with labels,
  in-place restore, non-USER mounts left untouched, manifest mismatch
  throws, mode/mtime round-trip, read-only flag in manifest.
- `api/src/jvmTest/.../fs/HostFsTest.kt` — 13 cases: round-trip,
  recursive remove, dot-dot sanitized to root, same `HostFs` mountable
  writable + read-only at different paths.
- `tools/python3-graalpy/src/jvmTest/.../graalpy/GraalPyEngineTest.kt`
  — covers the end-to-end: Python writes land in kash FS,
  `open('/etc/passwd')` is blocked, mount-point constant matches the
  documented one.

## Sources

The audit that informed these semantics:

- [Linux Overlay Filesystem](https://docs.kernel.org/filesystems/overlayfs.html) — shadow / whiteout / readdir caching
- [Plan 9: Use of Name Spaces](https://9p.io/sys/doc/names.html) — per-process namespaces, union directories
- [Plan 9: Getting Dot-Dot Right](https://9p.io/sys/doc/lexnames.html) — why path canonicalization must happen at the namespace layer
- [Linux VFS overview](https://www.kernel.org/doc/html/next/filesystems/vfs.html) — cross-FS hardlink/rename (`EXDEV`)
- [WASI preopens (wasmtime)](https://github.com/bytecodealliance/wasmtime/blob/main/docs/WASI-tutorial.md) — capability model, guest/host path mapping
- [Docker storage: bind / volumes / tmpfs](https://docs.docker.com/engine/storage/) — persistence categories
- [Btrfs subvolumes & snapshots (LWN)](https://lwn.net/Articles/579009/) — COW snapshot semantics
