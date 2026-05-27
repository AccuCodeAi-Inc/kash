@file:Suppress("removal", "DEPRECATION")

package com.accucodeai.kash.terminal.posix

import sun.misc.Signal
import sun.misc.SignalHandler

/**
 * Thin wrapper over `sun.misc.Signal` for the three signals kash-app cares
 * about: WINCH (resize), INT (Ctrl-C), TSTP (Ctrl-Z). Replaces
 * `org.jline.terminal.Terminal.handle(Signal.*, ...)` post-JLine.
 *
 * `sun.misc.Signal` is part of `jdk.unsupported` and has been deprecated-
 * for-removal for years without actually being removed (still present in
 * JDK 25, no JEP scheduled to remove it). If/when it goes, replace each
 * `Signal.handle` call with a Panama bridge to libc `sigaction(2)` — about
 * 100 LOC of FFI per signal. Not v1's problem.
 */
internal object SignalBridge {
    /**
     * Install [handler] for [name] (e.g. "WINCH", "INT", "TSTP"). Replaces
     * any prior handler for that signal. Idempotent w.r.t. the same handler.
     *
     * Handlers run on a dedicated dispatcher thread, NOT the kash REPL
     * coroutine. Don't do blocking work; just push events to a channel.
     */
    fun install(
        name: String,
        handler: () -> Unit,
    ) {
        Signal.handle(Signal(name), SignalHandler { handler() })
    }

    fun onWinch(handler: () -> Unit): Unit = install("WINCH", handler)

    fun onInt(handler: () -> Unit): Unit = install("INT", handler)

    fun onTstp(handler: () -> Unit): Unit = install("TSTP", handler)
}
