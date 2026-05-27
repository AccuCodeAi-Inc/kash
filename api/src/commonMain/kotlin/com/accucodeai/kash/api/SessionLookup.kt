package com.accucodeai.kash.api

/**
 * Convenience: resolve this process's [Session] via the machine's session
 * table. Returns null if no session is registered for [KashProcess.sid] —
 * happens in tests that build a bare process without a session, and in
 * any code path that hasn't yet been taught to register one.
 *
 * Mirrors how a Linux process reaches its session: via the kernel's
 * pid → task → signal_struct → session pointer chain. Here it's one map
 * lookup, but the call site reads the same way.
 */
public fun KashProcess.session(): Session? = machine.sessions[sid]
