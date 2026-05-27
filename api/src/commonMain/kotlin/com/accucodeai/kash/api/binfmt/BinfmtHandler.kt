package com.accucodeai.kash.api.binfmt

import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.ShellRunner
import com.accucodeai.kash.api.UtilityRunner
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

/**
 * A binfmt handler: "given a file path, do you claim this format, and if so,
 * how do I run it?" Mirrors Linux's `struct linux_binfmt` walked by
 * `search_binary_handler` in `fs/exec.c`.
 *
 * Handlers are registered on a [BinfmtRegistry] (one per [KashMachine]) and
 * consulted in priority order by [KashMachine.execFile]. A handler returns
 * [ExecOutcome.NotMine] to defer to the next handler (Linux's `-ENOEXEC`),
 * [ExecOutcome.Ran] when it executed the file, [ExecOutcome.Refused] when
 * it claims the format but refuses to run it (kash's "this would have been
 * native code" path), or [ExecOutcome.Reexec] to restart the chain with a
 * different file (the shebang path: `#!/usr/bin/env python3` reexecs with
 * `python3` as the new target).
 */
public interface BinfmtHandler {
    /**
     * Stable identifier, e.g. `"elf-reject"`, `"script"`, `"kash-tool"`,
     * `"python-misc"`. Shown via `/proc/sys/fs/binfmt_misc/` and used by
     * [BinfmtRegistry.unregister].
     */
    public val name: String

    /**
     * Lower priorities run earlier. Mirrors Linux's ordered linux_binfmt
     * list (which is insertion-order; here we make ordering explicit so
     * handler registration from multiple sources is deterministic).
     *
     * Conventional ranges:
     *  - 0..99    built-in fast paths (kash-tool, shebang, native-reject)
     *  - 100..999 binfmt_misc-style userspace handlers (e.g. `python3` for `.py`)
     *  - 1000+    fallbacks (the universal shell-script handler)
     */
    public val priority: Int

    public suspend fun tryExec(req: ExecRequest): ExecOutcome
}

/**
 * One exec attempt. Carries everything the handler needs to sniff the file
 * and (if it claims it) actually run it: the file path, full argv (argv[0]
 * conventionally equals [path]), environment, stdio sinks, the parent
 * process, and the live [KashMachine] so handlers can recurse via
 * [KashMachine.execFile] for reexec.
 *
 * [headPeek] is the first ~128 bytes of the file, populated once by the
 * caller; handlers that need only magic-byte sniffing avoid an extra
 * [com.accucodeai.kash.fs.FileSystem.readBytes] call. Handlers that need
 * the full file (script bodies, etc.) read through `machine.fs` directly.
 *
 * [recursionDepth] starts at 0 and bumps on each [ExecOutcome.Reexec].
 * [KashMachine.execFile] enforces a hard cap (BINPRM_MAX_RECURSION = 4 in
 * Linux; we mirror) so shebang chains can't loop.
 */
public data class ExecRequest(
    public val path: String,
    public val argv: List<String>,
    public val env: Map<String, String>,
    public val inlineEnv: Map<String, String>,
    public val stdin: SuspendSource,
    public val stdout: SuspendSink,
    public val stderr: SuspendSink,
    public val parent: KashProcess,
    public val machine: KashMachine,
    public val headPeek: ByteArray,
    public val recursionDepth: Int = 0,
    /**
     * Optional shell-runner for handlers that need to execute the file's
     * bytes as a kash shell script (the BinfmtShellScript fallback path —
     * POSIX §2.9.1.1 step 1.e). When null, those handlers must return
     * [ExecOutcome.NotMine] so the chain continues. Per-call so subshells
     * get their own correctly-scoped runner.
     */
    public val shellRunner: ShellRunner? = null,
    /**
     * Optional utility-runner for handlers that resolve shebangs by basename
     * + utility-registry dispatch (kash's convention; differs from Linux's
     * full reexec). When null, the shebang handler returns
     * [ExecOutcome.NotMine] / [ExecOutcome.Reexec] depending on mode.
     */
    public val utilityRunner: UtilityRunner? = null,
) {
    // ByteArray equality is reference-based; override so tests / debug
    // logging compare structurally. Hash is consistent with equals.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExecRequest) return false
        return path == other.path &&
            argv == other.argv &&
            env == other.env &&
            inlineEnv == other.inlineEnv &&
            stdin === other.stdin &&
            stdout === other.stdout &&
            stderr === other.stderr &&
            parent === other.parent &&
            machine === other.machine &&
            headPeek.contentEquals(other.headPeek) &&
            recursionDepth == other.recursionDepth
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + argv.hashCode()
        result = 31 * result + env.hashCode()
        result = 31 * result + inlineEnv.hashCode()
        result = 31 * result + headPeek.contentHashCode()
        result = 31 * result + recursionDepth
        return result
    }
}

public sealed class ExecOutcome {
    /** This handler does not claim this format. Try the next. = Linux `-ENOEXEC`. */
    public object NotMine : ExecOutcome()

    /** Handler ran the file. [exitCode] is the child's exit status. */
    public data class Ran(
        val exitCode: Int,
    ) : ExecOutcome()

    /**
     * Handler claims this format but refuses to execute it. [message] is
     * printed to stderr by [KashMachine.execFile]; the chain stops here.
     * Used by the native-code-rejection handler.
     */
    public data class Refused(
        val exitCode: Int,
        val message: String,
    ) : ExecOutcome()

    /**
     * Replace the exec target and restart the handler chain. The shebang
     * handler returns this to swap `path = /tmp/script.py` for
     * `path = /usr/bin/python3` (argv rewritten to put `python3` first,
     * script path second, then the original argv tail).
     *
     * [KashMachine.execFile] bumps `recursionDepth` and re-peeks the file
     * before consulting handlers again.
     */
    public data class Reexec(
        val newPath: String,
        val newArgv: List<String>,
    ) : ExecOutcome()
}

/**
 * Mutable, priority-ordered collection of [BinfmtHandler]s on a single
 * [KashMachine]. Equivalent to Linux's `formats` linked list in
 * `fs/exec.c`, accessible via `register_binfmt()` / `unregister_binfmt()`.
 *
 * Snapshot policy: handlers are code, not data — not persisted across
 * [KashMachine] restore. Hosts re-register at boot, same convention as
 * [KashMachine.networkPolicy].
 */
public interface BinfmtRegistry {
    /** Snapshot of currently-registered handlers, sorted by ascending priority. */
    public fun handlers(): List<BinfmtHandler>

    /**
     * Register [handler]. If a handler with the same [BinfmtHandler.name] is
     * already registered, it is replaced (matching the "re-register your
     * own" pattern used by `binfmt_misc` userspace tooling).
     */
    public fun register(handler: BinfmtHandler)

    /** Remove the handler with the given [name]. No-op if not registered. */
    public fun unregister(name: String)
}

/**
 * Optional contribution from a [com.accucodeai.kash.api.CommandSpec] to the
 * machine's [BinfmtRegistry]. When a spec carries one, the machine
 * synthesizes a [BinfmtHandler] that:
 *  1. Matches files by [magic] prefix and/or [extensions].
 *  2. On match, invokes the spec's `command.run(...)` with `path` as
 *     argv[1] (and any [interpreterArgs] inserted before it).
 *
 * Conceptually equivalent to a `binfmt_misc` entry registered against a
 * userspace interpreter. The spec author declares "I handle `.py` files"
 * next to the `python3` implementation, and the binding into the registry
 * is automatic at machine boot.
 *
 * Exactly one of [magic] or [extensions] (or both) should be non-empty.
 * Empty entries are silently skipped at registration.
 */
public data class BinfmtEntry(
    /**
     * Byte-prefix match. Inspected against [ExecRequest.headPeek]; matches
     * if `headPeek` starts with this exact byte sequence.
     */
    public val magic: ByteArray? = null,
    /**
     * Filename suffix match (e.g. `listOf("py", "pyc")`). Case-sensitive;
     * the leading dot is implicit (`.py`, not `py`).
     */
    public val extensions: List<String> = emptyList(),
    /**
     * Extra args inserted between the interpreter and the script path.
     * E.g. for `python -B`, set `interpreterArgs = listOf("-B")` so the
     * final argv is `[python, -B, script, ...origArgs]`.
     */
    public val interpreterArgs: List<String> = emptyList(),
    /**
     * Where the synthesized handler slots into the priority order. Default
     * `100` puts CommandSpec contributions after the built-in fast paths
     * (kash-tool, shebang, native-reject) and before the shell-script
     * fallback (1000).
     */
    public val priority: Int = 100,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BinfmtEntry) return false
        return (magic?.contentEquals(other.magic) ?: (other.magic == null)) &&
            extensions == other.extensions &&
            interpreterArgs == other.interpreterArgs &&
            priority == other.priority
    }

    override fun hashCode(): Int {
        var result = magic?.contentHashCode() ?: 0
        result = 31 * result + extensions.hashCode()
        result = 31 * result + interpreterArgs.hashCode()
        result = 31 * result + priority
        return result
    }
}
