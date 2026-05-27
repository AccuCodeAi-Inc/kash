package com.accucodeai.kash.api

import com.accucodeai.kash.api.binfmt.BinfmtRegistry
import com.accucodeai.kash.api.binfmt.ExecOutcome
import com.accucodeai.kash.api.binfmt.ExecRequest
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.fs.FileSystem
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonElement

/**
 * The "VM" / kernel that a kash session runs on. Exactly one [KashMachine]
 * per session; many [KashProcess]es execute on it.
 *
 * Holds *system-wide* state — what a real kernel owns and every process
 * shares a single view of:
 *
 *   - [fs] — the mount table. Per-process cwd/root are views into this.
 *   - [processTable] — pid → process. A process survives in the table
 *     after exit (zombie) until its parent calls `wait`.
 *   - [processGroups] — pgid → set of pids. Fast `kill -<pgid>`.
 *   - [sessions] — sid → session leader / controlling tty.
 *   - [allocatePid] — monotonic pid allocator.
 *
 * Tools see [KashMachine] as an interface; the concrete impl lives in
 * `:corevm` (`DefaultKashMachine`). Construct via the top-level
 * [KashMachine] factory function exported from `:corevm`.
 *
 * **Wiring status.** [fs], [processTable], [sessions], [snapshotSlots],
 * and the pid allocator ([nextPid] / [allocatePid]) are all load-bearing.
 * [processGroups] is shaped but unmutated — `setpgid(2)` isn't wired yet,
 * so every process inherits the init pgid through `fork()`.
 */
public interface KashMachine {
    /** Shared, kernel-wide filesystem. Per-process cwd/root view it. */
    public val fs: FileSystem

    /**
     * Pluggable foreground-signal receiver. The host (kash-app's `Main`)
     * catches asynchronous signals (SIGINT, SIGTSTP, SIGTERM) from the JVM
     * and forwards them through this slot. Whichever process is currently
     * "in the foreground" — typically the interactive `kash` shell —
     * installs its own delivery callback here and clears it on exit.
     *
     * Conceptually mirrors a kernel routing the terminal-generated signal
     * to its current foreground process group. Null when no one is
     * listening; callers MUST null-check before invoking.
     *
     * Mutable because the foreground process changes over a session
     * (recursive `kash` invocations, `fg`/`bg` swapping the foreground
     * job).
     */
    public var foregroundSignalReceiver: ((KashSignal) -> Unit)?

    /**
     * Per-session foreground signal receivers, keyed by `sid`. The global
     * [foregroundSignalReceiver] slot above only fits one listener at a
     * time, so when multiple interactive shells run on the same machine
     * — e.g. two web-app tabs, each its own session — the more recently
     * started shell overwrites the older one, and a terminal-generated
     * signal (browser Ctrl-C) gets routed to whichever shell happened to
     * start last instead of the one whose tab the user is focused on.
     *
     * Hosts that drive multiple sessions per machine should both:
     *  - register here under the session's `sid` when the shell starts,
     *  - look up by `sid` on the way in (using the controlling tty's
     *    session as the routing key) rather than using
     *    [foregroundSignalReceiver].
     *
     * The global slot is retained for the JVM single-tty path and for
     * recursive-shell save/restore semantics.
     */
    public val sessionSignalReceivers: MutableMap<Int, (KashSignal) -> Unit>

    /**
     * The command catalog visible to every process running on this machine.
     * Owned by the VM (one registry per session), not by individual processes
     * or interpreters — natural place because "what commands exist" is a
     * kernel-equivalent property in our model. Tools that need to dispatch
     * by name (recursive `kash -c '...'`, `xargs UTIL`, `find -exec`) read
     * `ctx.process.machine.registry` rather than capturing a registry
     * reference at construction time, which would otherwise create
     * chicken-and-egg cycles (the registry contains the command that
     * needs the registry).
     */
    public val registry: CommandRegistry

    /**
     * Live process table — pid → process. [KashProcess.fork] registers
     * the child here automatically; [spawn] reaps via [wait]; subshells
     * and pipeline stages register on fork and unregister via
     * [unregisterProcess] when their interpreter scope completes. Read
     * by `ps`, `/proc`, `kill <pid>`, and the `wait <pid>` builtin.
     */
    public val processTable: MutableMap<Int, KashProcess>

    /**
     * Process groups — pgid → set of member pids. Lets `kill -<pgid>` and
     * `jobs` work without scanning the whole process table. The group
     * leader's pid equals the pgid.
     *
     * Populated by the shell's pipeline dispatch — each pipeline gets a
     * fresh pgid (its leader stage's pid) and every stage process's
     * `pgid` field plus its membership in this map is stamped at dispatch
     * time. A future `setpgid(2)` wiring will reuse the same path.
     */
    public val processGroups: MutableMap<Int, MutableSet<Int>>

    /**
     * Synthetic analogue of `tcgetpgrp(SHTTY)` — the pgid of the process
     * group that currently "owns" the controlling tty in kash's
     * in-process model. Set by the shell dispatcher on foreground
     * statement entry, cleared on exit; backgrounded pipelines and
     * subshells do not touch it. `null` means "nothing in the
     * foreground" (e.g. between commands at the REPL prompt).
     *
     * Read via [tcgetpgrp] / written via [tcsetpgrp]. A future external-
     * exec path will additionally issue the real `tcsetpgrp(2)` syscall
     * when handing the tty to a spawned OS process.
     */
    public var foregroundPgrp: Int?

    /**
     * Sessions — sid → [Session]. Session leader's pid equals the sid;
     * the session optionally owns a controlling tty (used by DevFs to
     * resolve `/dev/tty` per process). The host populates session 1 at
     * boot with the controlling-tty bundle; `setsid(2)` will populate
     * additional sessions when implemented.
     */
    public val sessions: MutableMap<Int, Session>

    /**
     * Wall-clock epoch seconds at which this machine "booted." Embedders
     * stamp at construction (e.g. `Instant.now().epochSecond` on JVM).
     * Default 0 means "boot time unknown"; consumers
     * (`/proc/stat`'s `btime`, `/proc/uptime`) treat 0 as "decline to
     * compute" rather than "1970-01-01." Same intent as the
     * "production embedders inject a real clock" comment on
     * [com.accucodeai.kash.fs.FileStat].
     */
    public val bootEpochSeconds: Long get() = 0L

    /**
     * Wall-clock-now supplier in epoch seconds. Defaults to
     * `{ bootEpochSeconds }` so uptime computes as 0 in headless/test
     * setups (also the value the snapshot serializer would round-trip
     * to). Production embedders inject a real clock.
     */
    public val nowEpochSeconds: () -> Long get() = { bootEpochSeconds }

    /**
     * Unified shell clock — backs `$EPOCHSECONDS`, `$EPOCHREALTIME`,
     * `$SECONDS`, `times` user/sys, and the `date` tool. Defaults to
     * [com.accucodeai.kash.api.clock.SystemShellClock]; conformance tests
     * inject a virtual variant that follows `kotlinx.coroutines.test`
     * scheduler time.
     */
    public val clock: com.accucodeai.kash.api.clock.ShellClock get() = com.accucodeai.kash.api.clock.DefaultShellClock

    /**
     * Machine-wide outbound-network capability. Consulted by network-aware
     * tools (today: `curl` via `KashKtorClient`) when they aren't given an
     * explicit per-tool policy at construction time. Defaults to
     * [NetworkPolicy.None] — i.e. allow everything — to keep test fixtures
     * and headless setups working without ceremony. Production embedders
     * that need to restrict outbound traffic override this when building
     * the machine.
     *
     * Like [com.accucodeai.kash.api.sandbox.SandboxPolicy], not snapshot-
     * persisted — must be re-supplied at session restore.
     */
    public val networkPolicy: NetworkPolicy get() = NetworkPolicy.None

    /**
     * Linux-binfmt-style handler chain consulted by [execFile] to dispatch
     * a path to its loader (kash-tool, shebang, native-code-reject, shell
     * script, or any host-registered userspace handler). Mirrors Linux's
     * `formats` linked list in `fs/exec.c` walked by `search_binary_handler`.
     *
     * Like [networkPolicy] and [sandbox][com.accucodeai.kash.api.sandbox],
     * NOT snapshot-persisted: handlers are code, not data. Hosts re-register
     * built-ins and any userspace handlers at boot.
     */
    public val binfmt: BinfmtRegistry

    /** Allocate a fresh, monotonically-increasing pid. */
    public fun allocatePid(): Int

    /**
     * Next pid that [allocatePid] will hand out. Exposed read-write so the
     * snapshot/restore plumbing in `:corevm` can persist the allocator
     * state across machine reincarnations without re-allocating every
     * captured pid from scratch. Implementers must keep allocation
     * monotonic — setting [nextPid] to a value <= an in-use pid is
     * caller error.
     */
    public var nextPid: Int

    /**
     * Per-pid command snapshot slots. A [Snapshottable] command writes its
     * captured-state [JsonElement] here in its `run()` `finally`, so the
     * machine-level snapshot writer can collect it even after the process
     * has been auto-reaped (init's children are reaped immediately on
     * exit; the slot survives).
     *
     * Slots are also populated by the restore path. A freshly-spawned
     * command whose pid matches a slot entry reads + restores at the top
     * of its `run()`, then overwrites at exit.
     *
     * Indexed by pid for now; pids are stable across a single machine
     * lifetime (init is always 1, first spawn is always 2, etc.) so the
     * shell's slot consistently lands at pid 2 across invocations.
     */
    public val snapshotSlots: MutableMap<Int, JsonElement>

    /**
     * Per-pid "publish-my-state-now" callbacks for live (non-quiescent)
     * snapshot capture. A long-running [Snapshottable] command (notably
     * interactive `kash`) registers a callback whose body writes its
     * current state into [snapshotSlots]. The machine-level snapshot
     * writer invokes every registered callback before its quiescence
     * check, so a snapshot taken while the shell is at its prompt sees
     * the live interpreter's vars / aliases / cwd / history.
     *
     * Without this, the shell only writes its slot on exit, and
     * mid-session snapshots refuse with `NonQuiescentException`.
     */
    public val liveSnapshotProviders: MutableMap<Int, () -> Unit>

    /**
     * POSIX-equivalent `init` bootstrap. Ensures the machine has a process
     * at pid 1 with `commandName == "init"` and registers it in
     * [processTable] if not already present. All other processes are
     * descendants of init: orphan reparenting in [unregisterProcess] /
     * [wait] rewrites surviving children's `ppid` to 1, and zombies whose
     * `ppid == 1` are auto-reaped (init "calls `waitpid()`").
     *
     * Idempotent — repeated calls return the same init process. Entry-
     * points ([com.accucodeai.kash.Kash], `Kash.Session`, `kash-app/Main`,
     * test fixtures) call this once during machine setup, then spawn
     * shells / sessions as init's children.
     */
    public fun ensureInit(
        cwd: String = "/",
        env: MutableMap<String, String> = mutableMapOf(),
    ): KashProcess

    /**
     * Remove [pid] from [processTable] and unlink it from its parent's
     * [KashProcess.children] list. Also releases any fdTable entries
     * the process is still holding so OFD refcounts settle correctly.
     *
     * Use case: callers that build a process *outside* the [spawn] +
     * [wait] reap loop — most importantly [com.accucodeai.kash.interpreter
     * .Interpreter.forkSubshell], which forks for `(...)` / pipeline
     * stages / command substitution but isn't a normal reap target.
     * Without this, transient subshell pids accumulate in `/proc` for
     * the lifetime of the session.
     *
     * [spawn]'s reap path is unchanged — it has its own teardown via
     * [wait]. No-op if [pid] is not registered.
     */
    public fun unregisterProcess(pid: Int)

    /**
     * Synthetic POSIX `tcgetpgrp(SHTTY)` — returns the pgid currently
     * owning the controlling tty in kash's in-process model, or null if
     * the foreground slot is empty (REPL idle, no command in flight).
     * Default impl reads [foregroundPgrp]; a future host with a real
     * tty can override to query `tcgetpgrp(2)`.
     */
    public fun tcgetpgrp(): Int? = foregroundPgrp

    /**
     * Synthetic POSIX `tcsetpgrp(SHTTY, pgid)` — make [pgid] the
     * foreground pgrp. Default impl writes [foregroundPgrp]; future
     * external-exec hosts add the real `tcsetpgrp(2)` syscall before
     * handing the tty to a spawned OS process.
     */
    public fun tcsetpgrp(pgid: Int?) {
        foregroundPgrp = pgid
    }

    // -------- VM primitives: spawn / wait --------
    //
    // These are the kernel-level entry points the shell uses to run
    // non-intrinsic commands as separate processes. Intrinsic builtins
    // (`cd`, `export`, `umask`, `exec`, `set`, `trap`, …) DO NOT route
    // through [spawn]; they run on the shell's process directly so their
    // mutations stick — that's the same POSIX split between "special
    // builtins" (in-process) and "external utilities" (forked).

    /**
     * Fork [parent] (which auto-allocates a pid and registers the child
     * in [processTable] + on `parent.children`), run [block] on the
     * child, then reap to [ProcessState.ZOMBIE] with [exitStatus] set.
     *
     * The child stays in [processTable] (state ZOMBIE) until [wait] reaps
     * it — POSIX semantics. Foreground callers chain spawn + wait
     * immediately; backgrounds (`cmd &`) launch spawn inside a session-
     * scope coroutine and let `wait`/`jobs` reap later.
     *
     * The child's [KashProcess.fdTable] is a copy of the parent's at fork
     * time (entries point at the same OFDs; refcounts bumped). The block
     * sees the child's env/cwd/fdTable; mutations are local to the child
     * and discarded when the child is reaped.
     *
     * @param parent the process to fork from. Seeds the child's env/cwd/
     *   fdTable/credentials/signal-mask/etc. per [KashProcess.fork].
     * @param block runs on the child process; returns the child's exit
     *   code. Any thrown exception is converted to a non-zero exit status.
     */
    public suspend fun spawn(
        parent: KashProcess,
        block: suspend (child: KashProcess) -> Int,
    ): SpawnResult

    /**
     * POSIX `wait(2)` for a specific pid. Suspends until the process with
     * [pid] reaches [ProcessState.ZOMBIE], then reaps it: removes it from
     * [processTable] and the parent's [KashProcess.children], returns its
     * [ExitStatus].
     *
     * Returns null if no such pid is registered (already reaped, or never
     * spawned). POSIX-wise, only the parent should call wait on a child;
     * we don't enforce that today.
     */
    public suspend fun wait(pid: Int): ExitStatus?

    /**
     * Dispatch [req] through the [binfmt] handler chain.
     *
     * Walks handlers in priority order; the first one to return a non-
     * [ExecOutcome.NotMine] verdict wins. [ExecOutcome.Reexec] restarts the
     * walk with a new path (capped at 4 levels — `BINPRM_MAX_RECURSION` in
     * Linux). [ExecOutcome.Refused] writes the message to
     * [ExecRequest.stderr] and returns the supplied exit code; the chain
     * stops. If no handler claims the file, returns 126 with an ENOEXEC
     * diagnostic — in practice the built-in `BinfmtShellScript` is the
     * universal terminator so this branch is defensive.
     *
     * Default implementation is the kernel-equivalent walk; concrete
     * machines normally don't override.
     */
    public suspend fun execFile(req: ExecRequest): Int {
        var current = req
        while (true) {
            if (current.recursionDepth > MAX_BINFMT_RECURSION) {
                current.stderr.writeUtf8("${current.path}: too many levels of binfmt recursion\n")
                return 126
            }
            var matched = false
            var nextRequest: ExecRequest? = null
            for (h in binfmt.handlers()) {
                when (val out = h.tryExec(current)) {
                    is ExecOutcome.NotMine -> {
                        continue
                    }

                    is ExecOutcome.Ran -> {
                        return out.exitCode
                    }

                    is ExecOutcome.Refused -> {
                        current.stderr.writeUtf8(out.message + "\n")
                        return out.exitCode
                    }

                    is ExecOutcome.Reexec -> {
                        val peek = peekHead(fs, out.newPath)
                        nextRequest =
                            current.copy(
                                path = out.newPath,
                                argv = out.newArgv,
                                headPeek = peek,
                                recursionDepth = current.recursionDepth + 1,
                            )
                        matched = true
                        break
                    }
                }
            }
            if (nextRequest != null) {
                current = nextRequest
                continue
            }
            if (!matched) {
                current.stderr.writeUtf8(
                    "${current.path}: ENOEXEC: no binfmt handler claimed this file\n",
                )
                return 126
            }
        }
    }
}

/** BINPRM_MAX_RECURSION in `linux/binfmts.h`. Cap on shebang / reexec chains. */
public const val MAX_BINFMT_RECURSION: Int = 4

/**
 * Read up to 128 bytes from [path] for binfmt magic-byte sniffing. Returns
 * an empty array if the file is unreadable — handlers then treat it as
 * "no magic" and fall through. Lives at top-level so [KashMachine.execFile]
 * (a default-method) can call it without an instance reference.
 */
internal suspend fun peekHead(
    fs: FileSystem,
    path: String,
): ByteArray =
    try {
        val all = fs.readBytes(path)
        if (all.size <= 128) all else all.copyOfRange(0, 128)
    } catch (_: Throwable) {
        ByteArray(0)
    }

/**
 * Outcome of [KashMachine.spawn] — the child's pid plus a handle that
 * completes with its [ExitStatus]. The deferred fires when the child
 * transitions to [ProcessState.ZOMBIE] (just before [KashMachine.spawn]
 * returns, for the synchronous case; on coroutine completion for
 * background spawns wrapped in `launch` / `async`).
 *
 * Use [exit] when you need to await without reaping (the wait builtin's
 * `wait $pid` uses [KashMachine.wait], which reaps).
 */
public data class SpawnResult(
    public val pid: Int,
    public val exit: Deferred<ExitStatus>,
)

/**
 * POSIX session — group of process groups under one session leader, with
 * an optional controlling terminal. Leader is identified by `leaderPid ==
 * sid`. The host installs session 1 at boot with the controlling-tty
 * bundle (used by DevFs to vend `/dev/tty` per process); additional
 * sessions land when `setsid(2)` is implemented.
 */
public data class Session(
    val sid: Int,
    val leaderPid: Int,
    /**
     * Bundle backing `open("/dev/tty")` for any process in this session.
     * Set by the host at session start; cleared by `setsid()` and by any
     * future "lose controlling tty" path. Null → `open("/dev/tty")`
     * surfaces as `FileNotFound` (kash's stand-in for POSIX `ENXIO`).
     */
    var controllingTty: com.accucodeai.kash.api.terminal.ControllingTty? = null,
)
