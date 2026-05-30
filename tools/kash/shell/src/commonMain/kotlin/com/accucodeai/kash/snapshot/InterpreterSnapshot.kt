package com.accucodeai.kash.snapshot

import com.accucodeai.kash.ast.FunctionDef
import com.accucodeai.kash.fs.FsSnapshot
import kotlinx.serialization.Serializable

/**
 * Quiescent snapshot of a [com.accucodeai.kash.Kash.Session]'s mutable state.
 *
 * Captures everything the interpreter needs to resume between top-level
 * commands: env, cwd, function definitions, positional params, last exit,
 * the local-scope stack, readonly variable names, the in-memory filesystem,
 * and the POSIX-special-builtin abort flags.
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
    val fs: FsSnapshot,
    /** Runtime alias table (`alias name=value`). Insertion-ordered. */
    val aliases: Map<String, String> = emptyMap(),
)
