package com.accucodeai.kash.snapshot

import com.accucodeai.kash.ast.FunctionDef
import kotlinx.serialization.Serializable

/**
 * Per-process shell state — the "slot" a [com.accucodeai.kash.Kash.Session]
 * publishes into a [com.accucodeai.kash.snapshot.MachineSnapshot]'s
 * `snapshotSlots`, and the unit [com.accucodeai.kash.Kash.Companion.attachSession]
 * grafts onto a VM that already has a filesystem.
 *
 * Captures everything the interpreter needs to resume between top-level
 * commands: env, cwd, function definitions, positional params, last exit,
 * the local-scope stack, readonly variable names, and the
 * POSIX-special-builtin abort flags.
 *
 * Deliberately does **NOT** carry the filesystem. The FS lives in exactly
 * one place per persistable form: at the machine level in a FULL
 * [com.accucodeai.kash.snapshot.MachineSnapshot], or as the
 * [com.accucodeai.kash.fs.MountedFsSnapshot] itself in an FS_ONLY snapshot.
 * A bare [InterpreterSnapshot] is never standalone-bootable — it only makes
 * sense attached to a VM that already supplies the FS.
 *
 * Pure code (the `CommandRegistry`, custom commands, the `interactive`
 * flag) is intentionally NOT serialized — supply it again at restore time.
 *
 * Format is whatever the caller chooses via `kotlinx.serialization`; see
 * [SnapshotJson] (in `:corevm`) for the canonical JSON instance.
 */
@Serializable
public data class InterpreterSnapshot(
    val env: Map<String, String>,
    val cwd: String,
    val functions: Map<String, FunctionDef>,
    val positional: List<String>,
    val dollarZero: String = "kash",
    val lastExit: Int,
    val localScopes: List<Map<String, String?>>,
    val readonlyVars: Set<String>,
    val pendingAbort: Boolean,
    val pendingAbortCode: Int,
    /** Runtime alias table (`alias name=value`). Insertion-ordered. */
    val aliases: Map<String, String> = emptyMap(),
)
