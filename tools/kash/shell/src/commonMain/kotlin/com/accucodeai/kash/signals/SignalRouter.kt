package com.accucodeai.kash.signals

import com.accucodeai.kash.api.signal.KashSignal

/**
 * Session-scoped pid → in-shell signal-deliverer registry.
 *
 * Bash gets this for free from the kernel: `kill $$` from inside a
 * subshell sends to the parent shell's real OS process and that
 * process's signal handler fires. Kash runs N "shells" (Interpreter
 * forks) inside one JVM process, so a raw `kill(pid)` against a
 * synthetic shell-pid has nowhere to land unless we route it.
 *
 * The root Interpreter registers under its `shellPid` (i.e. `$$`)
 * at startup and unregisters on close. The `kill` intrinsic checks
 * this map first: if the target pid is registered the signal is
 * delivered in-shell (fires the trap dispatcher on that interpreter);
 * otherwise the path falls through to jobspec/OS lookup.
 *
 * Forks share a single instance via [Interpreter.SharedSession].
 * Adding more registrations later (e.g. `$BASHPID` per fork) is a
 * matter of calling [register] from the fork — the API surface
 * stays the same. The router does no fan-out or fallback; callers
 * own that.
 *
 * Thread-safety: registration happens from the interpreter coroutine
 * (single-threaded by construction). Delivery happens from the same
 * thread that invoked the `kill` builtin, also the interpreter's.
 * No JVM signal-handler thread reads this map. A plain MutableMap
 * is sufficient.
 */
public class SignalRouter internal constructor() {
    private val registry: MutableMap<Int, (KashSignal) -> Unit> = mutableMapOf()

    /**
     * Bind [pid] to [deliver]. Subsequent [deliver] calls overwrite,
     * matching "this pid is now owned by this interpreter".
     */
    internal fun register(
        pid: Int,
        deliver: (KashSignal) -> Unit,
    ) {
        registry[pid] = deliver
    }

    /** Drop [pid] from the registry. Idempotent. */
    internal fun unregister(pid: Int) {
        registry.remove(pid)
    }

    /**
     * Try to deliver [sig] to whatever interpreter owns [pid].
     * Returns true iff a registered deliverer was invoked (the caller
     * should NOT then fall through to jobspec/OS handling). Returns
     * false when [pid] is not a kash shell, leaving the caller to
     * resolve via the job table or `kill(2)`.
     */
    public fun deliver(
        pid: Int,
        sig: KashSignal,
    ): Boolean {
        val fn = registry[pid] ?: return false
        fn(sig)
        return true
    }
}
