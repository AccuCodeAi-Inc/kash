package com.accucodeai.kash.api

import com.accucodeai.kash.api.io.PipeEnd
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.terminal.ControllingTty
import com.accucodeai.kash.api.terminal.TerminalControl

/**
 * Install fds 0/1/2 as non-owning OFDs wrapping the given streams. The
 * single chokepoint both the interpreter's dispatch path and
 * `bareCommandContext` (test scaffolding) call to wire stdio into a
 * process before invoking a command. `owning = false`: release won't
 * close the underlying source/sink — the shell still owns them.
 *
 * Lives in `:corevm` rather than on the `KashProcess` interface in `:api`
 * because the [OpenFileDescription] factory is itself a `:corevm` symbol.
 *
 * [terminalControl], when non-null, is attached to whichever of fd 0/1/2
 * is marked `isTty=true`. Tools that need raw mode read it via
 * `process.fdTable[N]?.ofd?.terminalControl`. Redirection rebuilds the
 * affected fd with a fresh non-tty OFD (terminalControl=null), which is
 * how `tool </dev/null` correctly hides the terminal from the tool.
 */
public fun KashProcess.installStdio(
    stdin: SuspendSource,
    stdout: SuspendSink,
    stderr: SuspendSink,
    stdinIsTty: Boolean = false,
    stdoutIsTty: Boolean = false,
    stderrIsTty: Boolean = false,
    terminalControl: TerminalControl? = null,
    stdinPath: String? = null,
    stdoutPath: String? = null,
    stderrPath: String? = null,
) {
    // Per-fd `path`: stamp "/dev/tty" on tty-flagged fds so `tty(1)`,
    // `readlink /proc/self/fd/N`, and any tool that reports a fd's
    // backing path (`ls -l /proc/<pid>/fd/`) all surface the same string
    // a real Linux process would see for inherited stdio. Non-tty fds
    // keep path=null — installStdio doesn't know what file (if any) the
    // stream backed, and the redirection code stamps the real path when
    // it does know.
    val ttyPath = "/dev/tty".takeIf { terminalControl != null }
    // Path priority: caller-supplied redirection path beats /dev/tty (which
    // would only apply if the fd is still bound to the terminal). For a
    // non-tty file redirect, the explicit path is the only useful answer.
    installFd(
        0,
        OpenFileDescription(
            source = stdin,
            accessMode = AccessMode.RDONLY,
            isTty = stdinIsTty,
            owning = false,
            terminalControl = if (stdinIsTty) terminalControl else null,
            path = stdinPath ?: ttyPath.takeIf { stdinIsTty },
            pipeInode = (stdin as? PipeEnd)?.pipeInode,
        ),
    )
    installFd(
        1,
        OpenFileDescription(
            sink = stdout,
            accessMode = AccessMode.WRONLY,
            isTty = stdoutIsTty,
            owning = false,
            terminalControl = if (stdoutIsTty) terminalControl else null,
            path = stdoutPath ?: ttyPath.takeIf { stdoutIsTty },
            pipeInode = (stdout as? PipeEnd)?.pipeInode,
        ),
    )
    installFd(
        2,
        OpenFileDescription(
            sink = stderr,
            accessMode = AccessMode.WRONLY,
            isTty = stderrIsTty,
            owning = false,
            terminalControl = if (stderrIsTty) terminalControl else null,
            path = stderrPath ?: ttyPath.takeIf { stderrIsTty },
            pipeInode = (stderr as? PipeEnd)?.pipeInode,
        ),
    )
}

/**
 * Bidirectional-terminal variant: install fds 0/1/2 onto a single
 * [ControllingTty] bundle as `RDWR` OFDs (source + sink + terminal
 * control), mirroring Linux's `open("/dev/tty", O_RDWR)` followed by
 * `dup2` to fds 0/1/2 in an interactive shell.
 *
 * Use this when stdin AND stdout AND stderr are connected to the same
 * controlling terminal — i.e. the typical interactive case. The one-
 * direction overload above remains the correct shape for non-tty stdio
 * (piped stdin, redirected stdout). With this overload, `cat /dev/stdout`
 * can read keyboard input (because fd 1's OFD has a source — same
 * underlying tty source as fd 0), and `echo > /dev/stdin` writes to the
 * terminal (because fd 0's OFD has a sink). Errors with the one-
 * direction overload that have no counterpart on Linux — `cat /dev/stdout`
 * returning `No such file or directory`, `echo > /dev/stdin` failing
 * with confused messaging — disappear under this install path.
 *
 * Each fd gets its own OFD (independent file-status flags / offset,
 * matching Linux's "separate `struct file` per `open`") pointing at the
 * same underlying source/sink/terminalControl. All three carry
 * `path = "/dev/tty"`, `isTty = true`, `owning = false`.
 *
 * [stderr], when non-null, overrides fd 2's sink. Pass a host-supplied
 * `SuspendSink` (e.g. JVM's `System.err`) to keep error output on a
 * separate underlying stream from stdout while still inheriting the
 * RDWR + tty traits. Null (the default) sends fd 2 through the same
 * `tty.sink` as fd 1.
 */
public fun KashProcess.installStdio(
    tty: ControllingTty,
    stderr: SuspendSink? = null,
) {
    fun ttyOfd(sink: SuspendSink) =
        OpenFileDescription(
            source = tty.source,
            sink = sink,
            accessMode = AccessMode.RDWR,
            isTty = true,
            owning = false,
            terminalControl = tty.terminalControl,
            path = "/dev/tty",
        )
    installFd(0, ttyOfd(tty.sink))
    installFd(1, ttyOfd(tty.sink))
    installFd(2, ttyOfd(stderr ?: tty.sink))
}
