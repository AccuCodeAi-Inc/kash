package com.accucodeai.kash.api

import com.accucodeai.kash.api.signal.KashSignal

/**
 * Per-process state in the POSIX sense — the bundle the kernel duplicates
 * on `fork(2)` and partially preserves across `execve(2)`. Many processes
 * run on a single [KashMachine]; the machine is the "kernel", a
 * [KashProcess] is "what `ps` would show".
 *
 * The interpreter executes *on* a process — reads/writes its cwd, env,
 * umask, fd table, etc. Subshells (`(...)`, `$(...)`, `cmd &`) get a
 * forked process; mutations in the fork do not leak.
 *
 * Tools see [KashProcess] as an interface; the concrete impl lives in
 * `:corevm` (`DefaultKashProcess`). Construct via the top-level
 * [KashProcess] factory function exported from `:corevm`.
 *
 * **Scaffolding status:** `umask`, `cwd`, `env`, `fdTable` (all fds,
 * 0/1/2 included), `pid`/`ppid`/`pgid`/`sid`, `argv`, `commandName`,
 * `state`, `exitStatus`, and the fs facade are load-bearing on the hot
 * path. `rlimits` is enforced for `NPROC` only. The snapshot serializer
 * captures every public field, so credentials / signal mask /
 * dispositions / niceValue / CPU accumulators round-trip through
 * persistence even though no in-shell feature reads them yet — they
 * wire up at runtime as setuid/sigaction/getrlimit/etc. land, without
 * a schema break.
 */
public interface KashProcess {
    /** The VM this process runs on. Shared by every sibling process. */
    public val machine: KashMachine

    /**
     * The filesystem view this process sees — an **opener-bound facade**
     * over [KashMachine.fs] that auto-passes `this` as the opener to
     * every read-surface method. So `process.fs.stat(p)` resolves
     * per-process state (`/proc/self`, `/dev/tty`) correctly without
     * the caller ever threading opener explicitly.
     *
     * Backed by `OpenerBoundFs` in `:corevm`. The default impl below
     * builds one each call; concrete `KashProcess` implementations
     * should override to cache a single facade per process for
     * identity stability and to avoid per-access allocation.
     */
    public val fs: com.accucodeai.kash.fs.FileSystem get() = machine.fs

    // -------- POSIX per-process filesystem view --------

    /** POSIX file-creation mask. Newly-created files get `mode & ~umask`. */
    public var umask: Int

    /** Current working directory — a view into [KashMachine.fs]. */
    public var cwd: String

    /** chroot root. Almost always "/"; interpose-able for sandboxes. */
    public var rootDir: String

    /**
     * Exported environment — the strings that cross the `execve` boundary.
     * Distinct from shell-only variables (which live on the interpreter
     * and are mirrored here only when `export`-flagged).
     */
    public val env: MutableMap<String, String>

    /**
     * Open file descriptor table — integer fd → [FdTableEntry]. fds 0/1/2
     * are not magic to the kernel; they're just conventional indices.
     * Helpers below expose them by name.
     */
    public val fdTable: MutableMap<Int, FdTableEntry>

    // -------- POSIX identity --------

    public var pid: Int
    public var ppid: Int?

    /** Process group id. Group leader has `pid == pgid`. */
    public var pgid: Int

    /** Session id. Session leader has `pid == sid`. */
    public var sid: Int

    // -------- POSIX credentials (credentials(7)) --------
    //
    // kash has no setuid/setgid model today; effective/saved variants are
    // included for shape only. `id`, `whoami`, `~user` consult these.

    public var realUid: Int
    public var effectiveUid: Int
    public var savedUid: Int
    public var realGid: Int
    public var effectiveGid: Int
    public var savedGid: Int
    public val supplementaryGids: MutableSet<Int>

    // -------- POSIX signals (signal(7)) --------

    /**
     * Signals currently blocked from delivery. `sigprocmask(SIG_BLOCK,...)`
     * adds; `SIG_UNBLOCK` removes. Inherited by fork AND exec.
     */
    public val signalMask: MutableSet<KashSignal>

    /**
     * Signals that arrived while masked and have not yet been delivered.
     * Emptied on fork; inherited on exec (POSIX asymmetry).
     */
    public val pendingSignals: MutableSet<KashSignal>

    /**
     * Per-signal disposition: default, ignore, or a kash script handler
     * (`trap '<script>' <SIG>`). Inherited on fork; on exec, Handler →
     * Default, Ignore stays Ignore.
     */
    public val dispositions: MutableMap<KashSignal, Disposition>

    // -------- Resource limits / scheduling --------

    public val rlimits: MutableMap<RLimit, RLimitPair>

    // -------- Host-UI affordances --------

    /**
     * Sink for files the host UI drops while this process is foreground.
     * A process that wants drag-and-drop semantics (`agent`) implements
     * [AttachmentSink] and assigns `this` on entry / clears on exit.
     *
     * The host's drop handler walks the foreground pgrp looking for a
     * process with a non-null sink; finding one routes binary drops to
     * `sink.add(...)`. Finding none falls back to the generic "write to
     * /tmp/drops + paste path" path.
     *
     * Defaults to null — most processes don't care about UI attachments.
     */
    public var attachmentSink: AttachmentSink?
        get() = null
        set(_) {
            // Backing storage lives on impls that actually want it
            // (DefaultKashProcess); the interface default is a no-op so
            // test fakes and minimal impls don't have to declare it.
        }

    /** Nice value, -20..19. `nice`/`renice` adjust this. */
    public var niceValue: Int

    // -------- Terminal & job control --------

    /** Controlling tty fd, if any. Inherited via session leadership. */
    public var controllingTty: OpenFileDescription?

    // -------- Lifecycle --------

    public var state: ProcessState

    /** Set when [state] == [ProcessState.ZOMBIE]; cleared on `wait` reap. */
    public var exitStatus: ExitStatus?

    // -------- Introspection --------

    /** argv passed at exec (or shell invocation). $0 = `argv[0]`-ish. */
    public var argv: List<String>

    /** Pretty name for `ps`/`jobs`. */
    public var commandName: String

    /** Process start time, ms since epoch (or session start — TBD). */
    public val startTimeMillis: Long

    /** Accumulated user-CPU microseconds. Reset to 0 on fork. */
    public var userCpuMicros: Long

    /** Accumulated system-CPU microseconds. Reset to 0 on fork. */
    public var sysCpuMicros: Long

    // -------- Parent/child wiring --------

    /**
     * Live children. `wait(-1)` iterates this; reaping a zombie removes
     * the entry. Redundant with [KashMachine.processTable] filtered by
     * ppid, but kept for O(children) iteration (Linux does the same).
     */
    public val children: MutableList<KashProcess>

    // -------- POSIX primitives --------

    /**
     * `fork(2)` — duplicate per-process state. The [machine] is shared
     * by reference; everything else is copied per the per-field rules:
     *
     *   - cwd/rootDir/umask/env/credentials/rlimits/niceValue — copied
     *   - fdTable — copied (entries point at same OFDs, refcounts bumped;
     *     closeOnExec preserved)
     *   - signalMask, dispositions — copied
     *   - pendingSignals — **emptied** (POSIX asymmetry vs. exec)
     *   - children — **emptied** (fork has no children yet)
     *   - pid — caller allocates from machine
     *   - ppid — set to parent.pid
     *   - pgid, sid — inherited
     *   - state — RUNNING; exitStatus — null
     *   - startTimeMillis — fresh
     *   - CPU accumulators — zeroed
     *
     * Throws [com.accucodeai.kash.api.ForkException] when `RLIMIT_NPROC`
     * (read from this process's `rlimits[NPROC].soft`) would be
     * exceeded — POSIX `EAGAIN`. Callers translate to
     * `kash: fork: retry: Resource temporarily unavailable\n` and
     * exit 1 for the offending statement.
     */
    public fun fork(): KashProcess

    /**
     * `execve(2)` — reset per-process state that doesn't survive exec:
     *
     *   - fdTable — drop entries with closeOnExec=true
     *   - dispositions — Handler → Default; Ignore stays Ignore
     *   - argv/commandName/env — caller replaces (this method clears)
     *   - signalMask — preserved
     *   - pendingSignals — **preserved** (POSIX asymmetry vs. fork)
     *   - alarms / POSIX timers — reset (when scaffolded)
     *   - everything else (pid/ppid/cwd/umask/credentials/rlimits) — kept
     */
    public fun execReset()

    /** Convenience: stdin fd's OFD. */
    public fun stdin(): OpenFileDescription? = fdTable[0]?.ofd

    /** Convenience: stdout fd's OFD. */
    public fun stdout(): OpenFileDescription? = fdTable[1]?.ofd

    /** Convenience: stderr fd's OFD. */
    public fun stderr(): OpenFileDescription? = fdTable[2]?.ofd

    /**
     * POSIX `isatty(N)` — true iff fd [fd] is connected to a terminal.
     * Backs the shell test `[ -t N ]` and tool-level "should I drop into
     * an interactive REPL" decisions. Unopened or non-tty fds return false.
     */
    public fun isTty(fd: Int): Boolean = fdTable[fd]?.ofd?.isTty == true
}

/**
 * Signal disposition — directly maps to bash `trap`. Handler.script is the
 * shell snippet to run on delivery.
 */
public sealed interface Disposition {
    public data object Default : Disposition

    public data object Ignore : Disposition

    public data class Handler(
        val script: String,
    ) : Disposition
}

public enum class ProcessState { RUNNING, STOPPED, ZOMBIE }

/** POSIX resource limits. Only the commonly-tunable ones are enumerated. */
public enum class RLimit { CPU, FSIZE, DATA, STACK, CORE, RSS, NOFILE, NPROC, MEMLOCK, AS }

/** Soft/hard rlimit pair. */
public data class RLimitPair(
    val soft: Long,
    val hard: Long,
)

/** How a process terminated. Mirrors `WIFEXITED`/`WIFSIGNALED`/`WIFSTOPPED`. */
public sealed interface ExitStatus {
    public data class Exited(
        val code: Int,
    ) : ExitStatus

    public data class Signaled(
        val signal: KashSignal,
        val coreDumped: Boolean,
    ) : ExitStatus

    public data class Stopped(
        val signal: KashSignal,
    ) : ExitStatus
}
