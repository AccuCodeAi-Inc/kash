package com.accucodeai.kash.api.terminal

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

/**
 * The triple needed to vend `/dev/tty`: a byte source + sink connected to
 * the terminal device, plus a [TerminalControl] handle for raw-mode-capable
 * tools. The host (kash-app's Main on JVM, a future JS shim in the browser)
 * constructs one of these and hands it to the VM as
 * [com.accucodeai.kash.api.Session.controllingTty].
 *
 * Suspending I/O — see [SuspendSink] / [SuspendSource]. JVM hosts wrap their
 * blocking [System.in]/[System.out] via `.asSuspend()` once at bootstrap.
 */
public data class ControllingTty(
    val source: SuspendSource,
    val sink: SuspendSink,
    val terminalControl: TerminalControl,
)
