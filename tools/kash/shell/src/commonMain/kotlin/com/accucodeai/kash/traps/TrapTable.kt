package com.accucodeai.kash.traps

import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.api.signal.SigDebug
import com.accucodeai.kash.api.signal.SigErr
import com.accucodeai.kash.api.signal.SigReturn

/**
 * Per-interpreter trap registry. The interpreter consults this table at
 * three points:
 *
 *  - Top-level `run()` finally block — fires the `EXIT` handler.
 *  - Signal delivery (future) — fires the matching handler.
 *  - On `forkSubshell()` — POSIX says non-ignored traps reset to default
 *    in subshells; ignored traps stay ignored. See [forSubshell].
 *
 * Forms in the table:
 *  - absent key → default (no handler)
 *  - [TrapAction.Ignore] → swallow the signal silently
 *  - [TrapAction.Handler] → run the stored script when the signal fires
 */
internal class TrapTable {
    private val table: MutableMap<KashSignal, TrapAction> = mutableMapOf()

    /**
     * Signals that were [TrapAction.Ignore] when this table was sealed
     * (i.e. when a child shell started up). POSIX: signals ignored at
     * shell startup cannot be re-trapped or reset; the child silently
     * refuses any attempt to change their disposition. Populated by
     * [sealStartupIgnored], typically right after [inheritFrom] in the
     * exec'd-shell setup path.
     */
    private var startupIgnored: Set<KashSignal> = emptySet()

    fun isStartupIgnored(sig: KashSignal): Boolean = sig in startupIgnored

    fun sealStartupIgnored() {
        startupIgnored =
            table.entries
                .asSequence()
                .filter { it.value is TrapAction.Ignore }
                .map { it.key }
                .toSet()
    }

    /** Returns null when the signal has the default action (no trap set). */
    fun get(sig: KashSignal): TrapAction? = table[sig]

    fun set(
        sig: KashSignal,
        action: TrapAction,
    ) {
        table[sig] = action
    }

    fun reset(sig: KashSignal) {
        table.remove(sig)
    }

    /** Snapshot for `trap` (no-arg) listing. Iteration order is insertion order. */
    fun entries(): List<Pair<KashSignal, TrapAction>> = table.entries.map { it.key to it.value }

    /**
     * Copy subshell-inheritable entries from [parent] into this table.
     *
     * Default (POSIX) rule: only [TrapAction.Ignore] entries carry
     * through; non-ignored handlers (including EXIT) reset to default
     * action — matches POSIX §2.12 "Other traps shall be reset to the
     * default action".
     *
     * Bash extensions, gated by the caller:
     *  - [errtrace] (`set -E`) — the ERR trap handler is also inherited.
     *  - [functrace] (`set -T`) — DEBUG and RETURN trap handlers are
     *    also inherited.
     *
     * Both gates take Boolean (not nullable) so callers commit at
     * call time. EXIT handlers are NEVER inherited regardless of
     * flags — otherwise `(...)` would fire the parent's EXIT trap
     * on group close.
     */
    fun inheritFrom(
        parent: TrapTable,
        errtrace: Boolean = false,
        functrace: Boolean = false,
    ) {
        for ((sig, action) in parent.table) {
            val carry =
                action is TrapAction.Ignore ||
                    (errtrace && sig === SigErr && action is TrapAction.Handler) ||
                    (functrace && (sig === SigDebug || sig === SigReturn) && action is TrapAction.Handler)
            if (carry) table[sig] = action
        }
    }
}

internal sealed interface TrapAction {
    data object Ignore : TrapAction

    data class Handler(
        val script: String,
    ) : TrapAction
}
