package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.terminal.TerminalControl
import kotlinx.io.Buffer

/**
 * Shell I/O cluster — current stdout/stderr sinks, their tty status,
 * the inherited stdin's tty status, and the (process-wide) host
 * terminal handle. Mutated by every dispatch boundary (`runStatements`
 * saves and restores; `evalCommandSubstitution` swaps `outSink` for a
 * capture `Buffer`; pipelines re-thread the per-fd ttyness).
 *
 * Interpreter exposes each field as a forwarding property so call
 * sites stay flat; this cluster only matters for `forkSubshell` and
 * future per-cluster snapshot/restore.
 */
internal class ShellIo {
    var outSink: SuspendSink = Buffer().asSuspendSink()
    var errSink: SuspendSink = Buffer().asSuspendSink()
    var outSinkIsTty: Boolean = false
    var errSinkIsTty: Boolean = false
    var currentStdinIsTty: Boolean = false
    var hostTerminal: TerminalControl? = null

    /**
     * Sticky flag: an `exec >file` (no command, just redirection)
     * persisted the parent shell's stdio. Suppresses the cleanup
     * unwind that would otherwise close the redirection's sink at
     * statement end.
     */
    var execPersistedRedirections: Boolean = false

    fun copyFrom(other: ShellIo) {
        outSink = other.outSink
        errSink = other.errSink
        outSinkIsTty = other.outSinkIsTty
        errSinkIsTty = other.errSinkIsTty
        currentStdinIsTty = other.currentStdinIsTty
        hostTerminal = other.hostTerminal
        execPersistedRedirections = other.execPersistedRedirections
    }
}
