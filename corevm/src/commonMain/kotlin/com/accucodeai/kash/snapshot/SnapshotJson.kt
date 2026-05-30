package com.accucodeai.kash.snapshot

import kotlinx.serialization.json.Json

/**
 * The single canonical JSON instance for **all** kash snapshots — the full
 * [MachineSnapshot] (VM), the [com.accucodeai.kash.fs.MountedFsSnapshot]
 * (FS-only payload), and the per-process interpreter slots. Every layer
 * (web store, JVM store, the shell's slot read/write, and the round-trip
 * tests) serializes through this so encode and decode can never drift apart.
 *
 * Config:
 *  - `encodeDefaults = false` — save space
 */
public val SnapshotJson: Json =
    Json {
        encodeDefaults = false
        explicitNulls = true
    }
