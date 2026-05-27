package com.accucodeai.kash.commands

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FsLabel
import com.accucodeai.kash.fs.MountedFileSystem

/**
 * Read-only introspection of the session's [MountedFileSystem].
 *
 * Non-POSIX (POSIX `mount(8)` requires privileges kash doesn't model), but
 * mirrors the `mount` you'd find on Linux when run without arguments:
 * one line per mount, sorted by mount point depth. Designed to let
 * humans and agents discover what each FS path means — is `/var/cache/foo`
 * an engine cache, user data, or a host borrow? — without bespoke tooling.
 *
 * No mutation. Mounts are immutable per session by design
 * ([MountedFileSystem] doc, point 6).
 *
 * Output format (one line per mount):
 *
 * ```
 * <mountPoint>  on <label>  (<rw|ro>, <backing-fs-class>)
 * ```
 *
 * Flags:
 *  - `-t LABEL` filter to mounts of the given [FsLabel] (case-insensitive).
 *  - `-l` long form: include the backing-FS `toString` if it's informative
 *    (e.g. `HostFs[/var/cache/kash/graalpy]`).
 *  - `--`, `-h`/`--help`.
 */
public class MountCommand :
    Command,
    CommandSpec {
    override val name: String = "mount"
    override val kind: CommandKind = CommandKind.BUILTIN
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var filter: FsLabel? = null
        var long = false
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--" -> {
                    i++
                    break
                }

                "-h", "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                "-l", "--long" -> {
                    long = true
                }

                "-t" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("mount: option requires an argument -- 't'\n")
                        return CommandResult(exitCode = 2)
                    }
                    val v = args[i + 1]
                    filter =
                        FsLabel.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
                            ?: run {
                                ctx.stderr.writeUtf8("mount: unknown label: $v\n")
                                return CommandResult(exitCode = 2)
                            }
                    i++
                }

                else -> {
                    if (a.startsWith("-")) {
                        ctx.stderr.writeUtf8("mount: unknown option: $a\n")
                        return CommandResult(exitCode = 2)
                    }
                    // Trailing positional args (mount source/target) — we don't
                    // mount things, so emit a hint and exit non-zero.
                    ctx.stderr.writeUtf8("mount: mounts are immutable in this session\n")
                    return CommandResult(exitCode = 2)
                }
            }
            i++
        }

        // `mount` reports machine-wide mount state. Bypass the per-process
        // [OpenerBoundFs] facade exposed by `ctx.process.fs` and read the
        // raw machine FS, which is where mounts actually live.
        val mfs =
            ctx.process.machine.fs as? MountedFileSystem
                ?: run {
                    ctx.stderr.writeUtf8("mount: filesystem is not mountable\n")
                    return CommandResult(exitCode = 1)
                }

        val rows =
            mfs
                .mounts()
                .asSequence()
                .filter { filter == null || it.label == filter }
                // Sort by mount-point depth so `/` appears first, deeper mounts after.
                .sortedBy { it.mountPoint.count { ch -> ch == '/' } }
                .toList()
        if (rows.isEmpty() && filter != null) {
            return CommandResult(exitCode = 0)
        }

        val mpWidth = rows.maxOfOrNull { it.mountPoint.length } ?: 0
        val labelWidth = rows.maxOfOrNull { it.label.label.length } ?: 0
        for (m in rows) {
            val mp = m.mountPoint.padEnd(mpWidth)
            val label = m.label.label.padEnd(labelWidth)
            val ro = if (m.readOnly) "ro" else "rw"
            val backing = m.fs::class.simpleName ?: m.fs::class.toString()
            val backingDetail = if (long) " ${m.fs}" else ""
            ctx.stdout.writeLine("$mp on $label ($ro, $backing)$backingDetail")
        }
        return CommandResult()
    }

    private companion object {
        const val HELP: String =
            """Usage: mount [-l] [-t LABEL]

List the session's filesystem mounts. No-args lists all mounts;
-t filters by FsLabel (USER, ENGINE_CACHE, HOST_BORROW, EPHEMERAL, SYSTEM_BIN);
-l adds the backing FS's verbose form.
"""
    }
}

/** Human-readable form of an [FsLabel]. Lowercased and hyphenated for column display. */
private val FsLabel.label: String
    get() =
        when (this) {
            FsLabel.USER -> "user"
            FsLabel.ENGINE_CACHE -> "engine-cache"
            FsLabel.HOST_BORROW -> "host-borrow"
            FsLabel.EPHEMERAL -> "ephemeral"
            FsLabel.SYSTEM_BIN -> "system-bin"
        }
