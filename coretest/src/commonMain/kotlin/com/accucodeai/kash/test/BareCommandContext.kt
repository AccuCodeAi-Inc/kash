package com.accucodeai.kash.test

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.ShellRunner
import com.accucodeai.kash.api.UtilityRunner
import com.accucodeai.kash.api.installStdio
import com.accucodeai.kash.api.io.EmptySuspendSource
import com.accucodeai.kash.api.io.NullSuspendSink
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.api.sandbox.SandboxPolicy
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.api.user.UserDatabase
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.InMemoryFs

/**
 * Test / convenience constructor for [CommandContext]: takes the pre-
 * `KashProcess`-refactor field shape (fs / env / cwd / umask / 3×tty),
 * builds a fresh single-process [KashMachine] + [KashProcess] under the
 * hood, and wires the supplied stdio into the process's fd table with the
 * given tty flags.
 *
 * Real interpreter dispatch never goes through this — it constructs a
 * full process via [KashMachine.spawn] and threads stdio in via the
 * interpreter's own `installStdioFds`. This is just for tools that build
 * a `CommandContext` by hand in tests, and for `AnsiTest`-style fixtures
 * that don't need the rest of the VM.
 */
public fun bareCommandContext(
    fs: FileSystem = InMemoryFs(),
    env: MutableMap<String, String> = mutableMapOf(),
    cwd: String = "/",
    stdin: SuspendSource = EmptySuspendSource,
    stdout: SuspendSink = NullSuspendSink,
    stderr: SuspendSink = NullSuspendSink,
    shellRunner: ShellRunner? = null,
    utilityRunner: UtilityRunner? = null,
    isInteractive: Boolean = false,
    stdinIsTty: Boolean = false,
    stdoutIsTty: Boolean = false,
    stderrIsTty: Boolean = false,
    userDb: UserDatabase = UserDatabase.Default,
    sandbox: SandboxPolicy = SandboxPolicy.TRUSTED,
    terminal: TerminalControl? = null,
    umask: Int = 0b000_010_010,
    // Pinned to UTC by default so tests that format mtimes / commit
    // dates produce host-independent output. Production binds
    // [com.accucodeai.kash.api.clock.SystemShellClock] which reads
    // [TimeZone.currentSystemDefault]; for that, callers can pass
    // `DefaultShellClock` explicitly.
    clock: com.accucodeai.kash.api.clock.ShellClock = TestShellClockUtc,
    networkPolicy: NetworkPolicy = NetworkPolicy.None,
): CommandContext {
    val machine = KashMachine(fs = fs, clock = clock, networkPolicy = networkPolicy)
    // POSIX-faithful: pid 1 is init; the test fixture's process is a
    // child of init. fork() allocates pid 2 and wires ppid = 1 so tools
    // that read $$ / $PPID / /proc/self see realistic values.
    val init = machine.ensureInit()
    val process =
        init.fork().apply {
            this.umask = umask
            this.cwd = cwd
            this.env.clear()
            this.env.putAll(env)
        }
    process.installStdio(stdin, stdout, stderr, stdinIsTty, stdoutIsTty, stderrIsTty, terminal)
    return CommandContext(
        process = process,
        shellRunner = shellRunner,
        utilityRunner = utilityRunner,
        isInteractive = isInteractive,
        userDb = userDb,
        sandbox = sandbox,
    )
}

/**
 * Test-only [com.accucodeai.kash.api.clock.ShellClock] that pins
 * [localTimeZone] to UTC. Wall-clock `now()` still reads
 * [kotlin.time.Clock.System] so tests can compare against `clock()`
 * outputs, but anything that renders local time gets a deterministic
 * `+0000` regardless of host.
 *
 * Tests that pin `now()` too should construct their own [ShellClock]
 * — this only fixes the tz seam.
 */
private val TestShellClockUtc: com.accucodeai.kash.api.clock.ShellClock =
    object : com.accucodeai.kash.api.clock.SystemShellClock() {
        override fun localTimeZone(): kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.UTC
    }
