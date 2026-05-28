package com.accucodeai.kash

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Pins the file-access metric's coverage boundary so blind spots are
 * reviewed, not discovered in the field.
 *
 * The metric is complete only where file I/O flows through the per-process
 * facade (`ctx.process.fs` / `ctx.fs`). Any production code that reaches the
 * raw machine filesystem via `process.machine.fs` bypasses recording. Two
 * call sites do so *legitimately* — `df` and `mount` introspect the
 * `MountedFileSystem` itself (not file contents) and need the concrete type,
 * which the facade isn't. This test fails if a NEW bypass appears, forcing a
 * conscious decision: route it through the facade, or add it here with a
 * reason.
 *
 * Known out-of-scope bypass NOT detectable here: `tools/kash/git-jgit`
 * (JGit) hits disk via `java.nio` directly — git file I/O is invisible to
 * the metric by design.
 */
class FileAccessCoverageBoundaryTest {
    private val allowed =
        setOf(
            // df: `ctx.process.machine.fs as? MountedFileSystem` for df stats.
            "DfCommand.kt",
            // mount: same cast to enumerate the mount table.
            "MountCommand.kt",
        )

    @Test
    fun noNewFacadeBypasses() {
        val root = repoRoot()
        val offenders =
            root
                .walkTopDown()
                // Prune heavy / irrelevant trees for speed.
                .onEnter { it.name !in setOf("build", ".git", ".gradle", "external", "node_modules") }
                .filter { it.isFile && it.extension == "kt" }
                .filter { f ->
                    val p = f.path.replace('\\', '/')
                    "/build/" !in p &&
                        "/jvmTest/" !in p &&
                        "/commonTest/" !in p &&
                        "/wasmJsTest/" !in p
                }.filter { it.readText().contains("process.machine.fs") }
                .map { it.name }
                .toSet()

        val unexpected = offenders - allowed
        if (unexpected.isNotEmpty()) {
            fail(
                "New `process.machine.fs` facade-bypass(es) found in $unexpected. " +
                    "File access there is NOT recorded. Route content I/O through " +
                    "`ctx.process.fs`/`ctx.fs`, or — if it's MountedFileSystem " +
                    "introspection like df/mount — add the file to the allowlist with a reason.",
            )
        }
    }

    /**
     * Portable tools (`tools/posix`, `tools/ext`) must reach the filesystem
     * only through the kash [com.accucodeai.kash.fs.FileSystem] abstraction
     * (`ctx.fs` / `ctx.process.fs`) — never the raw host FS. A direct
     * `java.io`/`java.nio`/`okio` import there both breaks multiplatform and
     * silently bypasses file-access recording. (Engine/host bridges under
     * `tools/kash` — git-jgit, graalpy's polyglot FS — are the deliberate
     * exceptions and are not scanned here.)
     */
    @Test
    fun portableToolsDoNotTouchRawHostFs() {
        val root = repoRoot()
        val banned =
            listOf(
                "import java.io.File",
                "import java.nio.file",
                "import okio.",
                "import kotlinx.io.files",
            )
        val offenders =
            sequenceOf("tools/posix", "tools/ext")
                .map { File(root, it) }
                .filter { it.isDirectory }
                .flatMap { it.walkTopDown() }
                .onEach { } // (walk is small here; no pruning needed)
                .filter { it.isFile && it.extension == "kt" }
                .filter { f ->
                    val p = f.path.replace('\\', '/')
                    "/build/" !in p && "/jvmTest/" !in p && "/commonTest/" !in p && "/wasmJsTest/" !in p
                }.filter { f -> f.readLines().any { line -> banned.any { line.trimStart().startsWith(it) } } }
                .map { it.name }
                .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "Portable tool(s) $offenders import a raw host filesystem API. " +
                    "Route file I/O through `ctx.fs` / `ctx.process.fs` so it's " +
                    "recorded and stays multiplatform.",
            )
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("could not locate repo root (no settings.gradle.kts above ${System.getProperty("user.dir")})")
    }
}
