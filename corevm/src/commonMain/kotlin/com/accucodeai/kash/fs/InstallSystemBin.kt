package com.accucodeai.kash.fs

import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.KashProcess

/**
 * Build a [MountedFileSystem] that has `/usr/bin` (and `/bin`) populated
 * by a [ToolsFs] driven off [registry]. The registry is referenced lazily
 * (via the `() -> CommandRegistry` provider) so [ToolsFs] can be
 * constructed before the registry field is populated.
 *
 * If [caller] is already a [MountedFileSystem] with `/usr/bin` mounted,
 * leave it alone; otherwise wrap.
 *
 * Lives in `:corevm` so every layer that boots a [com.accucodeai.kash.api.KashMachine]
 * — `Kash`, `Kash.Session`, and the `kash-app` Main — uses the same wrapper.
 */
public fun installSystemBin(
    caller: FileSystem,
    registry: () -> CommandRegistry,
    processes: () -> Map<Int, KashProcess> = { emptyMap() },
): FileSystem {
    val toolsFs =
        ToolsFs(
            lookup = { name -> registry()[name]?.takeIf { it.isUtility() } },
            names = {
                registry()
                    .specs
                    .asSequence()
                    .filter { it.isUtility() }
                    .map { it.name }
                    .toSet()
            },
        )
    val devFs = DevFs()
    val procFs = ProcFs(processes = processes)
    val usrBin = Mount("/usr/bin", toolsFs, FsLabel.SYSTEM_BIN, readOnly = true)
    val bin = Mount("/bin", toolsFs, FsLabel.SYSTEM_BIN, readOnly = true)
    // /dev is NOT mount-level readOnly: /dev/null must accept writes (DiscardSink).
    // DevFs enforces its own per-path policy — mkdirs/remove throw
    // ReadOnlyMountException from inside the FS, and sink() only accepts /null.
    val dev = Mount("/dev", devFs, FsLabel.SYSTEM_BIN, readOnly = false)
    val proc = Mount("/proc", procFs, FsLabel.SYSTEM_BIN, readOnly = true)
    if (caller is MountedFileSystem) {
        val existing = caller.mounts().map { it.mountPoint }.toSet()
        val toAdd = listOf(usrBin, bin, dev, proc).filter { it.mountPoint !in existing }
        return if (toAdd.isEmpty()) caller else MountedFileSystem(caller.mounts() + toAdd)
    }
    return MountedFileSystem(
        listOf(
            Mount("/", caller, FsLabel.USER, readOnly = false),
            usrBin,
            bin,
            dev,
            proc,
        ),
    )
}

/**
 * Anything reachable via `$PATH`. Per POSIX [XCU §2.9.1.1.e](
 * https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_09_01_01),
 * regular built-ins must "appear" in PATH; only **special** built-ins
 * (§2.14) bypass it.
 */
internal fun CommandSpec.isUtility(): Boolean = !isSpecial
