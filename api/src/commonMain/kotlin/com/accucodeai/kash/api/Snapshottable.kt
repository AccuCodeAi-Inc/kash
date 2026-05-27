package com.accucodeai.kash.api

import kotlinx.serialization.json.JsonElement

/**
 * Marker for commands whose per-process state survives a machine snapshot.
 *
 * The machine-level snapshot writer uses this marker to enforce
 * quiescence: any non-init non-zombie process whose command is NOT
 * [Snapshottable] is treated as a "mid-execution transient" and the
 * snapshot is refused (`NonQuiescentException`).
 *
 * **Contract.** A [Snapshottable] command's `run()` must:
 *
 *   1. Read [KashMachine.snapshotSlots]`[process.pid]` at entry; if
 *      present, restore from it (typically [JsonElement] decoded via
 *      `kotlinx-serialization-json`) and use that to seed any
 *      per-process state it constructs.
 *   2. In a `finally` block (covering both normal exit and exception),
 *      capture its current state as a [JsonElement] and write it back
 *      to [KashMachine.snapshotSlots]`[process.pid]`. The slot survives
 *      past the process being reaped — init auto-reaps its children, so
 *      the kash shell's slot persists exactly that way.
 *
 * The interface itself is a marker (no methods) because the capture
 * mechanism is the slot map, not a callback-into-the-command pattern.
 * If mid-execution capture is needed later, an extension with
 * `snapshot()`/`restore()` methods can be added without breaking
 * existing implementers.
 */
public interface Snapshottable
