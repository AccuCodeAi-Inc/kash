package com.accucodeai.kash

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.Session
import com.accucodeai.kash.api.installStdio
import com.accucodeai.kash.api.io.asSuspend
import com.accucodeai.kash.api.signal.SigInt
import com.accucodeai.kash.api.signal.SigTstp
import com.accucodeai.kash.api.terminal.ControllingTty
import com.accucodeai.kash.api.user.UserDatabase
import com.accucodeai.kash.app.KashSnapshotStore
import com.accucodeai.kash.app.appStandardRegistry
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.fs.MountedFileSystem
import com.accucodeai.kash.fs.installSystemBin
import com.accucodeai.kash.snapshot.MachineSnapshot
import com.accucodeai.kash.snapshot.SnapshotPayload
import com.accucodeai.kash.snapshot.restoreFsAndSlots
import com.accucodeai.kash.terminal.posix.Libc
import com.accucodeai.kash.terminal.posix.PosixTerminalControl
import com.accucodeai.kash.terminal.posix.ShutdownRestorer
import com.accucodeai.kash.terminal.posix.SignalBridge
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import kotlin.system.exitProcess
import kotlin.time.Clock
import com.accucodeai.kash.snapshot.snapshot as machineSnapshot

/**
 * Kash entry point — the "kernel boot loader".
 *
 * Real bash is itself a process the OS exec's; nothing wraps `main()` to
 * do shell things on its behalf. Kash mirrors that: this `main()` is the
 * VM/kernel bootstrap (build the machine, install the terminal, materialize
 * `~/.kashrc` on the VFS, route signals), then it [KashMachine.spawn]s the
 * `kash` command — registered like any other tool — as PID 1 and waits.
 *
 * The `kash` command itself owns:
 *   - argv parsing (`-c`, script-file, stdin-script, interactive)
 *   - sourcing `$HOME/.kashrc` when interactive
 *   - the REPL loop (`KashShellCommand.runInteractive`)
 *
 * Platform note: macOS and Linux only. On Windows, the interactive REPL
 * requires raw-mode termios which we obtain via libc through Panama —
 * there's no Windows equivalent yet. Non-interactive modes (`-c`, file,
 * stdin pipe) work everywhere because they don't touch the terminal.
 */
public fun main(args: Array<String>) {
    val argList = args.toList()
    val inheritEnv = "--inherit-env" in argList

    // --- kash-app-level flags (consumed before forwarding to the kash
    // command). These are NOT bash flags and must be stripped so the kash
    // argv parser doesn't see them.
    //   --snapshot <path>   persistent state file (default `.kash/state.json`)
    //   --resume [pid]      apply slot at <pid> from the snapshot onto the
    //                       new shell (default pid=2); fatal if explicit
    //                       and the slot is missing
    //   --no-resume         FS-only restore; don't apply any slot
    //   --no-lock           skip the PID-file lock (concurrent invocations race)
    //   --force-unlock      take over a lock held by another live PID
    //
    // Default (no flag, no path): use `.kash/state.json` in cwd, restore
    // FS, apply slot 2 to the new shell (silent if missing — first run).
    val flags = consumeAppFlags(argList)
    val takeForceUnlock = flags.forceUnlock
    val takeNoLock = flags.noLock
    val forwarded = flags.forwarded

    // We need to know up front whether to enter raw mode, because raw-mode
    // setup touches the controlling terminal — wrong for non-interactive
    // runs. The interactive/non-interactive split is decided based on
    // isatty + argv, BEFORE eval. The kash command's parseArgs makes the
    // same decision again; cheap duplication, single source of truth in
    // kash itself.
    val stdinIsTty = Libc.isatty(0) != 0
    val wantInteractive = stdinIsTty && !forwarded.any { it == "-c" || !it.startsWith("--") }

    // Forward refs so the lazy JGit adapter sees the live VFS + $HOME
    // when it eventually fires. Built later in main(); the lambdas
    // resolve at lazy-init time, after the assignments below.
    var effectiveFsRef: com.accucodeai.kash.fs.FileSystem? = null
    var homeDirRef: String = "/root"
    val appReg =
        appStandardRegistry(
            fsProvider = { effectiveFsRef },
            homeProvider = { homeDirRef },
        )
    val registry = appReg.registry

    // PosixTerminalControl owns the single host-fd-0 reader, regardless of
    // interactivity. wantInteractive only governs whether the prompt loop
    // and raw-mode line editor run — the byte pump exists either way so
    // that piped/non-tty kash still gets a consistent stdin path through
    // the OFD model. Construction is cheap; tcgetattr failure is non-fatal.
    val terminal: PosixTerminalControl = PosixTerminalControl().also { it.start() }
    ShutdownRestorer.install(terminal)

    // Stdio for the spawned kash. The stdin source is owned by the
    // PosixTerminalControl byte pump — every read goes through its single
    // host-fd-0 reader, so embedded interpreters (GraalPy) that pull
    // through ctx.stdin can't race against the line editor's raw-mode
    // reads. Stdout/stderr are direct adapters; there's no analog parallel
    // reader on the output side.
    val stdinSource = terminal.cookedByteSource()
    val stdoutSink = SystemOutSink(System.out).asSuspend()
    val stderrSink = SystemOutSink(System.err).asSuspend()

    // /dev/tty bundle: when the host has a real terminal, expose it under
    // /dev/tty so tools can `open("/dev/tty")` for keyboard access even
    // when fd 0 is piped — the POSIX pattern for password prompts. Only
    // built when stdin was actually a tty (raw-mode use is meaningless
    // otherwise); the pump still runs for the cooked-bytes path either
    // way.
    val controllingTtyBundle: ControllingTty? =
        if (stdinIsTty) {
            ControllingTty(source = stdinSource, sink = stdoutSink, terminalControl = terminal)
        } else {
            null
        }

    val machine =
        KashMachine(
            fs = InMemoryFs(clock = { Clock.System.now().epochSeconds }),
            registry = registry,
        )
    // The mountedMachine is built AFTER the FS that wraps it, so ProcFs
    // can't take a direct reference to processTable at FS-construction
    // time. The `mountedMachineHolder` var is the seam: ProcFs's
    // `processes` supplier returns the current machine's processTable
    // once it exists, empty map before.
    var mountedMachineHolder: KashMachine? = null
    val effectiveFs =
        installSystemBin(
            machine.fs,
            registry = { registry },
            processes = { mountedMachineHolder?.processTable ?: emptyMap() },
        )
    val mountedMachine =
        KashMachine(
            fs = effectiveFs,
            registry = registry,
        )
    mountedMachineHolder = mountedMachine
    // Publish the live VFS to the lazy JGit adapter. $HOME ref is
    // updated below once buildInitialEnv has resolved it.
    effectiveFsRef = effectiveFs

    // Snapshot persistence: load BEFORE seeding rc / spawning kash so the
    // shell starts against restored state. We always have a store unless
    // the user gave us no path AND `--no-lock` — the latter signals
    // "absolutely no on-disk state, please."
    val store: KashSnapshotStore? =
        flags.resolveStorePath()?.let { KashSnapshotStore(it) }
    if (store != null && !takeNoLock) {
        val holder = store.acquire(force = takeForceUnlock)
        if (holder != null) {
            System.err.println(
                "kash: snapshot ${store.snapshotPath} still locked by pid $holder " +
                    "after wait timeout (use --force-unlock to override, or --no-lock to skip)",
            )
            exitProcess(1)
        }
    }
    val loadedPayload = store?.loadOrNull()
    // A FULL envelope carries the machine state we resume slots from; an
    // FS_ONLY envelope only rehydrates the filesystem (no slot to resume).
    val loaded: MachineSnapshot? =
        when (loadedPayload) {
            is SnapshotPayload.Full -> {
                loadedPayload.snapshot
            }

            is SnapshotPayload.FsOnly -> {
                (mountedMachine.fs as? MountedFileSystem)?.restore(loadedPayload.snapshot)
                null
            }

            null -> {
                null
            }
        }
    if (loaded != null) {
        // FS always comes back; per-pid slots are dropped at this point —
        // we re-apply at most one slot below, AFTER ensureInit, so the
        // slot lands at the pid `fork()` will actually allocate for the
        // shell (init burns pid 1 first, so the shell spawn lands at 2).
        mountedMachine.restoreFsAndSlots(loaded)
        mountedMachine.snapshotSlots.clear()
    }
    if (flags.resumeEnabled && flags.resumeExplicit && loadedPayload == null) {
        // User explicitly typed `--resume` but there's no snapshot file on
        // disk. Fatal — explicit --resume is an assertion. Silent fresh
        // boot is reserved for the implicit-default case.
        System.err.println(
            "kash: --resume ${flags.resumePid}: no snapshot file at ${store?.snapshotPath}",
        )
        exitProcess(1)
    }
    // Register the init session (sid = 1; init process gets sid=1 by
    // default — see DefaultKashProcess.sid default). Carries the host
    // terminal bundle so any process in this session sees /dev/tty.
    // Non-interactive runs leave controllingTty null → open("/dev/tty")
    // surfaces FileNotFound, matching POSIX ENXIO on a detached session.
    mountedMachine.sessions[1] =
        Session(sid = 1, leaderPid = 1, controllingTty = controllingTtyBundle)

    val userDb = UserDatabase.Default
    val initialEnv = buildInitialEnv(userDb, inheritEnv).toMutableMap()
    val homeDir = initialEnv["HOME"] ?: userDb.current().home
    homeDirRef = homeDir

    // Seed the rc file on the VFS BEFORE spawning kash. The kernel
    // bootstrap owns rc materialization; kash itself just sources the
    // file if it exists. Wrapper writes once; user edits with `nano
    // ~/.kashrc` and the next session picks up the change.
    if (wantInteractive) {
        runBlocking { seedDefaultStartupFilesIfMissing(effectiveFs, homeDir) }
    }

    // The JGit adapter (appReg.jgitAdapter) wraps a session-local
    // InMemoryRepository; nothing to bootstrap from disk. The LLM
    // populates it via `git init` / `git clone` / `git fetch` once
    // the shell is up — operations from kash's git tool land in JGit,
    // push hands JGit the LLM's commits, fetch reads JGit's refs.

    // Init process — PID 1, the parent of every other process on this
    // machine. `ensureInit` registers it in `processTable[1]` and labels
    // it `init` for `ps`/`/proc/1/cmdline`. The shell is spawned as
    // init's child below.
    val init = mountedMachine.ensureInit(cwd = homeDir, env = initialEnv)

    // Slot pick. ensureInit has now allocated pid 1, so `nextPid` points
    // at 2 — the pid `spawn()` will hand the shell. We copy the source
    // slot from the snapshot to that pid so the shell's `run()`
    // top-of-frame `restoreSlotIfPresent` finds its state.
    if (loaded != null && flags.resumeEnabled) {
        val sourceSlot = loaded.snapshotSlots[flags.resumePid]
        if (sourceSlot == null) {
            if (flags.resumeExplicit) {
                System.err.println(
                    "kash: --resume ${flags.resumePid}: no slot at pid ${flags.resumePid} in ${store?.snapshotPath}",
                )
                exitProcess(1)
            }
            // implicit default + missing slot → silent fresh boot
        } else {
            mountedMachine.snapshotSlots[mountedMachine.nextPid] = sourceSlot
        }
    }

    if (wantInteractive) {
        wireTerminalSignals(mountedMachine, terminal, initialEnv)
        println("kash — a bash simulator in Kotlin. Type 'exit' or Ctrl-D to quit.")
        System.out.flush()
    }

    val kashCommand =
        registry["kash"]?.command
            ?: run {
                System.err.println("kash: internal: 'kash' command missing from registry")
                exitProcess(1)
            }

    val exit =
        runBlocking {
            val result =
                mountedMachine.spawn(init) { child ->
                    child.commandName = "kash"
                    child.argv = listOf("kash") + forwarded
                    // If all three stdio fds are tty AND we have a
                    // controlling-tty bundle, use the bidirectional
                    // install path — mirrors Linux's RDWR-tty + dup
                    // semantics so `cat /dev/stdout` and `echo > /dev/stdin`
                    // work. Mixed cases (e.g. piped stdout) fall back to
                    // the one-direction overload.
                    val stdoutIsTty = Libc.isatty(1) != 0
                    val stderrIsTty = Libc.isatty(2) != 0
                    if (stdinIsTty && stdoutIsTty && stderrIsTty && controllingTtyBundle != null) {
                        child.installStdio(tty = controllingTtyBundle, stderr = stderrSink)
                    } else {
                        child.installStdio(
                            stdin = stdinSource,
                            stdout = stdoutSink,
                            stderr = stderrSink,
                            stdinIsTty = stdinIsTty,
                            stdoutIsTty = stdoutIsTty,
                            stderrIsTty = stderrIsTty,
                            terminalControl = if (stdinIsTty) terminal else null,
                        )
                    }
                    val ctx =
                        CommandContext(
                            process = child,
                            isInteractive = wantInteractive,
                            userDb = userDb,
                        )
                    kashCommand.run(forwarded, ctx).exitCode
                }
            result.exit.await().let { if (it is com.accucodeai.kash.api.ExitStatus.Exited) it.code else 1 }
        }

    // Persist before halt(). Save BEFORE releasing the lock so a concurrent
    // invocation that's been waiting can't observe a half-written file.
    // (save itself is atomic via tmp+rename — the lock-release boundary is
    // just the natural sync point.)
    if (store != null) {
        try {
            val snap = mountedMachine.machineSnapshot()
            store.save(snap)
        } catch (t: Throwable) {
            System.err.println("kash: snapshot save failed: ${t.message ?: t::class.simpleName}")
        }
        if (!takeNoLock) store.release()
    }

    System.out.flush()
    System.err.flush()
    terminal.stop()
    if (wantInteractive) println("bye.")
    // halt() rather than exitProcess(): we already called terminal.stop()
    // above (which restores termios), so the ShutdownRestorer hook is a
    // crash-path safety net we don't need on the happy exit. Bypassing
    // hooks also avoids a macOS quirk where the hook chain interacts
    // poorly with the daemon stdin pump thread that's still parked in
    // libc read(0, …) — see PosixTerminalControl.startPumpThread for the
    // writeup.
    Runtime.getRuntime().halt(exit)
}

/**
 * Strip kash-app-level flags from [argList] and return them with the
 * residual argv (which gets forwarded to the `kash` command verbatim).
 *
 * Recognized:
 *   --snapshot <path>   custom on-disk path for the snapshot file
 *                       (default `<cwd>/.kash/state.json`)
 *   --resume [pid]      apply the slot at <pid> from the snapshot onto
 *                       the new shell (default pid=2). Implicit default
 *                       if neither --resume nor --no-resume is given.
 *                       Fatal exit if explicitly typed and the snapshot
 *                       has no slot at that pid.
 *   --no-resume         FS-only restore; don't apply any slot
 *   --force-unlock      take over a lock held by another live PID
 *   --no-lock           skip locking entirely (no persistence either)
 *   --inherit-env       (handled separately in [main] — just drop here)
 *
 * Anything else is passed through to the kash command unchanged.
 */
private fun consumeAppFlags(argList: List<String>): AppFlags {
    var snapshotPath: java.nio.file.Path? = null
    var resumeEnabled = true
    var resumeExplicit = false
    var resumePid = 2
    var forceUnlock = false
    var noLock = false
    val rest = mutableListOf<String>()
    var i = 0
    while (i < argList.size) {
        val a = argList[i]
        when {
            a == "--inherit-env" -> {
                i++
            }

            a == "--force-unlock" -> {
                forceUnlock = true
                i++
            }

            a == "--no-lock" -> {
                noLock = true
                i++
            }

            a == "--no-resume" -> {
                resumeEnabled = false
                resumeExplicit = true
                i++
            }

            a == "--resume" -> {
                resumeExplicit = true
                resumeEnabled = true
                // Optional pid follows. Accept only if the next arg parses
                // as an int; otherwise treat as the start of the forwarded
                // argv (`--resume -c '…'` is "resume with default pid").
                val next = argList.getOrNull(i + 1)
                val parsed = next?.toIntOrNull()
                if (parsed != null) {
                    resumePid = parsed
                    i += 2
                } else {
                    i++
                }
            }

            a.startsWith("--resume=") -> {
                resumeExplicit = true
                resumeEnabled = true
                val v = a.removePrefix("--resume=")
                val parsed =
                    v.toIntOrNull() ?: run {
                        System.err.println("kash: --resume=$v: not an integer pid")
                        exitProcess(2)
                    }
                resumePid = parsed
                i++
            }

            a == "--snapshot" -> {
                if (i + 1 >= argList.size) {
                    System.err.println("kash: --snapshot requires a path argument")
                    exitProcess(2)
                }
                snapshotPath =
                    java.nio.file.Paths
                        .get(argList[i + 1])
                        .toAbsolutePath()
                i += 2
            }

            a.startsWith("--snapshot=") -> {
                snapshotPath =
                    java.nio.file.Paths
                        .get(a.removePrefix("--snapshot="))
                        .toAbsolutePath()
                i++
            }

            else -> {
                rest.add(a)
                i++
            }
        }
    }
    return AppFlags(
        snapshotPath = snapshotPath,
        resumeEnabled = resumeEnabled,
        resumeExplicit = resumeExplicit,
        resumePid = resumePid,
        forceUnlock = forceUnlock,
        noLock = noLock,
        forwarded = rest,
    )
}

private data class AppFlags(
    val snapshotPath: java.nio.file.Path?,
    /** Whether to apply a slot from the snapshot at all. False ⇒ FS-only restore. */
    val resumeEnabled: Boolean,
    /** True iff the user explicitly typed `--resume` or `--no-resume`. */
    val resumeExplicit: Boolean,
    /** Slot pid to apply when [resumeEnabled]. Default 2 (first non-init spawn). */
    val resumePid: Int,
    val forceUnlock: Boolean,
    val noLock: Boolean,
    val forwarded: List<String>,
) {
    /**
     * Resolve the on-disk path the [KashSnapshotStore] should use, or null
     * if no on-disk state should be touched at all.
     *
     * - `--snapshot <path>` always wins.
     * - `--no-lock` without an explicit path → null (caller skips both
     *   load and save; matches the "I just want fast ephemeral kash"
     *   intent).
     * - Otherwise default to `<cwd>/.kash/state.json`.
     */
    fun resolveStorePath(): java.nio.file.Path? {
        snapshotPath?.let { return it }
        if (noLock) return null
        return java.nio.file.Paths
            .get(System.getProperty("user.dir"), ".kash", "state.json")
    }
}

/**
 * Color/UX env vars the REPL inherits from the host shell even without
 * `--inherit-env`. Each var is one a color- or terminal-aware tool (`ls`,
 * `grep`, `Ansi.stylerFor`) actually consults. Anything else stays out so
 * kash's default env stays the small, predictable map below.
 */
private val INHERITED_COLOR_ENV_KEYS =
    listOf(
        "TERM",
        "COLORTERM",
        "NO_COLOR",
        "CLICOLOR",
        "CLICOLOR_FORCE",
        "LS_COLORS",
        "LSCOLORS",
        "GREP_COLORS",
    )

/**
 * Vars kash always owns — even with `--inherit-env`, the host value is
 * dropped so a hostile or odd parent can't redirect the embedded shell.
 * See dwheeler.com/secure-programs/Secure-Programs-HOWTO/environment-variables.html.
 */
private val KASH_OWNED_ENV_KEYS = setOf("PATH", "IFS", "TZ")

private fun buildInitialEnv(
    userDb: UserDatabase,
    inheritAll: Boolean,
): Map<String, String> {
    val u = userDb.current()
    val base =
        mutableMapOf(
            "HOME" to u.home,
            "PATH" to "/usr/bin:/bin",
            "PWD" to u.home,
            "LOGNAME" to u.name,
            "USER" to u.name,
            "OPTIND" to "1",
        )
    if (inheritAll) {
        for ((k, v) in System.getenv()) {
            if (k in KASH_OWNED_ENV_KEYS) continue
            if (v.isNullOrEmpty()) continue
            base[k] = v
        }
    } else {
        for (k in INHERITED_COLOR_ENV_KEYS) {
            val v = System.getenv(k)
            if (!v.isNullOrEmpty()) base[k] = v
        }
    }
    return base
}

/**
 * Materialize the default `~/.kashrc` and `~/.kash_profile` stubs on
 * the VFS if they're missing. Existing files are never overwritten —
 * user edits survive across sessions. Best-effort: a read-only VFS
 * silently swallows the write and the shell just starts without a
 * seeded rc / profile.
 *
 * `~/.kashrc` is the interactive non-login rc; `~/.kash_profile` is
 * the login-shell entry that re-sources `~/.kashrc` so a single edit
 * covers both modes. POSIX-shared files (`/etc/profile`, `~/.profile`)
 * and the logout hook (`~/.kash_logout`) are NOT seeded — those are
 * opt-in.
 */
private suspend fun seedDefaultStartupFilesIfMissing(
    fs: com.accucodeai.kash.fs.FileSystem,
    homeDir: String,
) {
    seedIfMissing(fs, homeDir, ".kashrc", Kash.DEFAULT_KASHRC)
    seedIfMissing(fs, homeDir, ".kash_profile", Kash.DEFAULT_KASH_PROFILE)
}

private suspend fun seedIfMissing(
    fs: com.accucodeai.kash.fs.FileSystem,
    homeDir: String,
    fileName: String,
    contents: String,
) {
    val path = if (homeDir.endsWith("/")) "$homeDir$fileName" else "$homeDir/$fileName"
    if (fs.exists(path)) return
    try {
        val parent = path.substringBeforeLast('/', "/")
        if (parent.isNotEmpty() && !fs.exists(parent)) fs.mkdirs(parent)
        fs.writeBytes(path, contents.encodeToByteArray())
    } catch (_: Throwable) {
        // Non-fatal — shell just starts without the seeded file.
    }
}

/**
 * Plumb async signals from the JVM through the VM's foreground-receiver
 * slot, and re-publish COLUMNS/LINES from SIGWINCH so width-aware tools
 * (`ls`, `column`, `tput cols`) see the right terminal dimensions.
 */
private fun wireTerminalSignals(
    machine: KashMachine,
    terminal: PosixTerminalControl,
    sharedEnv: MutableMap<String, String>,
) {
    fun publishSize() {
        val size = terminal.size()
        if (size.cols > 0) sharedEnv["COLUMNS"] = size.cols.toString()
        if (size.rows > 0) sharedEnv["LINES"] = size.rows.toString()
    }
    publishSize()
    SignalBridge.onWinch {
        publishSize()
        // Kick the line editor into an immediate redraw at the new width.
        // Skipped automatically when a full-screen tool owns raw mode.
        terminal.notifyResizeRedraw()
    }
    SignalBridge.onInt { machine.foregroundSignalReceiver?.invoke(SigInt) }
    SignalBridge.onTstp { machine.foregroundSignalReceiver?.invoke(SigTstp) }
}

/**
 * Bridges kotlinx-io [RawSink] to a JVM [java.io.PrintStream]. Each write
 * decodes the chunk and writes through — used by the spawned kash so tools
 * that paint mid-execution (nano, slow pipelines) flush progressively.
 */
private class SystemOutSink(
    private val out: java.io.PrintStream,
) : RawSink {
    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        if (byteCount <= 0) return
        val bytes = source.readByteArray(byteCount.toInt())
        out.write(bytes)
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        flush()
    }
}
