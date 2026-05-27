package com.accucodeai.kash.tools.zip

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound

/**
 * `unzip` — list / extract a ZIP archive.
 *
 * Synopsis: unzip {-l|-p|-o|-q|-d DIR} ARCHIVE FILES...
 *
 * Modes:
 *  - `-l`  list table-of-contents (no extraction).
 *  - `-p`  pipe extracted contents to stdout (no filesystem writes).
 *  - default: extract to current directory (or `-d DIR`).
 *
 * Positional args after the archive name filter which members are acted on
 * (literal name match; no glob in v1).
 *
 * `-o` overwrites existing files without prompting; without it, an existing
 * destination is skipped with a stderr note (no interactive prompt — kash
 * isn't running with a TTY model for this tool yet).
 */
public class UnzipCommand :
    Command,
    CommandSpec {
    override val name: String = "unzip"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.FS_WRITE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var list = false
        var toStdout = false
        var overwrite = false
        var quiet = false
        var destDir: String? = null
        val positional = mutableListOf<String>()

        var i = 0
        var endOfOpts = false
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                positional += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-l" -> {
                    list = true
                }

                a == "-p" -> {
                    toStdout = true
                }

                a == "-o" -> {
                    overwrite = true
                }

                a == "-q" || a == "--quiet" -> {
                    quiet = true
                }

                a == "-d" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("unzip: option requires an argument -- d\n")
                        return CommandResult(exitCode = 2)
                    }
                    destDir = args[i + 1]
                    i += 2
                    continue
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("unzip: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    positional += a
                }
            }
            i++
        }

        if (positional.isEmpty()) {
            ctx.stderr.writeUtf8("unzip: missing archive operand\n")
            return CommandResult(exitCode = 2)
        }

        val archive = positional[0]
        val filterRaw = positional.drop(1)
        // Trailing `-d DIR` form is sometimes written *after* the archive +
        // file list: `unzip foo.zip a b -d /out`. Re-scan filterRaw for it.
        val filter = mutableListOf<String>()
        var j = 0
        while (j < filterRaw.size) {
            val tok = filterRaw[j]
            if (tok == "-d" && destDir == null && j + 1 < filterRaw.size) {
                destDir = filterRaw[j + 1]
                j += 2
                continue
            }
            filter += tok
            j++
        }

        val source: SuspendSource =
            if (archive == "-") {
                ctx.stdin
            } else {
                val path = resolvePath(ctx.cwd, archive)
                try {
                    ctx.fs.source(path)
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("unzip: cannot find or open $archive\n")
                    return CommandResult(exitCode = 9)
                } catch (e: Exception) {
                    ctx.stderr.writeUtf8("unzip: cannot open $archive: ${e.message ?: "I/O error"}\n")
                    return CommandResult(exitCode = 9)
                }
            }

        val reader = ZipReader(source)
        try {
            return when {
                list -> doList(reader, archive, filter, ctx)
                toStdout -> doToStdout(reader, filter, ctx)
                else -> doExtract(reader, archive, filter, destDir, overwrite, quiet, ctx)
            }
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("unzip: ${e.message ?: "archive error"}\n")
            return CommandResult(exitCode = 1)
        } finally {
            reader.close()
            if (archive != "-") source.close()
        }
    }

    private suspend fun doList(
        reader: ZipReader,
        archive: String,
        filter: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        ctx.stdout.writeUtf8("Archive:  $archive\n")
        ctx.stdout.writeUtf8("  Length      Date    Time    Name\n")
        ctx.stdout.writeUtf8("---------  ---------- -----   ----\n")
        var totalSize = 0L
        var count = 0
        while (true) {
            val e = reader.readNextEntry() ?: break
            if (!matchesFilter(e.name, filter)) {
                reader.skipEntry()
                continue
            }
            val sz = if (e.uncompressedSize < 0) 0L else e.uncompressedSize
            totalSize += sz
            count++
            ctx.stdout.writeUtf8("${sz.toString().padStart(9)}  ${formatDateTime(e.mtimeEpochSeconds)}   ${e.name}\n")
            reader.skipEntry()
        }
        ctx.stdout.writeUtf8("---------                     -------\n")
        ctx.stdout.writeUtf8(
            "${totalSize.toString().padStart(9)}                     $count file${if (count == 1) "" else "s"}\n",
        )
        return CommandResult()
    }

    private suspend fun doToStdout(
        reader: ZipReader,
        filter: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        while (true) {
            val e = reader.readNextEntry() ?: break
            if (e.isDirectory) {
                reader.skipEntry()
                continue
            }
            if (!matchesFilter(e.name, filter)) {
                reader.skipEntry()
                continue
            }
            reader.copyEntryTo(ctx.stdout)
        }
        return CommandResult()
    }

    private suspend fun doExtract(
        reader: ZipReader,
        archive: String,
        filter: List<String>,
        destDir: String?,
        overwrite: Boolean,
        quiet: Boolean,
        ctx: CommandContext,
    ): CommandResult {
        if (!quiet) ctx.stdout.writeUtf8("Archive:  $archive\n")
        val base = destDir?.let { resolvePath(ctx.cwd, it) } ?: ctx.cwd
        // Create dest dir if missing.
        try {
            if (!ctx.fs.exists(base)) ctx.fs.mkdirs(base)
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("unzip: cannot create $base: ${e.message ?: "I/O error"}\n")
            return CommandResult(exitCode = 1)
        }
        var hadError = false
        while (true) {
            val e = reader.readNextEntry() ?: break
            if (!matchesFilter(e.name, filter)) {
                reader.skipEntry()
                continue
            }
            val safeName = sanitizeEntryName(e.name)
            if (safeName == null) {
                ctx.stderr.writeUtf8("unzip: skipping unsafe entry name: ${e.name}\n")
                reader.skipEntry()
                continue
            }
            val outPath = joinPath(base, safeName)
            if (e.isDirectory) {
                try {
                    ctx.fs.mkdirs(outPath)
                    if (!quiet) ctx.stdout.writeUtf8("   creating: $safeName\n")
                } catch (ex: Exception) {
                    ctx.stderr.writeUtf8("unzip: cannot create $safeName: ${ex.message ?: "I/O error"}\n")
                    hadError = true
                }
                reader.skipEntry()
                continue
            }
            // Ensure parent dir exists.
            val parent = outPath.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isNotEmpty() && !ctx.fs.exists(parent)) {
                try {
                    ctx.fs.mkdirs(parent)
                } catch (ex: Exception) {
                    ctx.stderr.writeUtf8("unzip: cannot create parent for $safeName: ${ex.message ?: "I/O error"}\n")
                    hadError = true
                    reader.skipEntry()
                    continue
                }
            }
            if (ctx.fs.exists(outPath) && !overwrite) {
                if (!quiet) ctx.stderr.writeUtf8("unzip: $safeName already exists; use -o to overwrite\n")
                reader.skipEntry()
                continue
            }
            try {
                val sink = ctx.fs.sink(outPath, append = false)
                try {
                    reader.copyEntryTo(sink)
                } finally {
                    sink.close()
                }
                if (!quiet) ctx.stdout.writeUtf8("  inflating: $safeName\n")
            } catch (ex: Exception) {
                ctx.stderr.writeUtf8("unzip: cannot write $safeName: ${ex.message ?: "I/O error"}\n")
                hadError = true
                reader.skipEntry()
            }
        }
        return CommandResult(exitCode = if (hadError) 1 else 0)
    }

    private fun matchesFilter(
        name: String,
        filter: List<String>,
    ): Boolean {
        if (filter.isEmpty()) return true
        // POSIX unzip uses shell-style globs; v1 does literal match plus
        // an "endsWith /name" trailing-component match. Glob support is P1.
        for (f in filter) {
            if (name == f || name == "$f/" || name.startsWith("$f/")) return true
        }
        return false
    }

    /**
     * Reject path-traversal entries (`..`, absolute paths). Returns the
     * normalized POSIX-style name on success, or null to refuse.
     */
    private fun sanitizeEntryName(raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw.startsWith("/")) return null
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        for (p in parts) if (p == "..") return null
        if (parts.isEmpty()) return null
        val joined = parts.joinToString("/")
        return if (raw.endsWith("/")) "$joined/" else joined
    }

    private fun joinPath(
        base: String,
        rel: String,
    ): String {
        val baseTrim = if (base.endsWith("/")) base.dropLast(1) else base
        return "$baseTrim/$rel".trimEnd('/')
    }

    /** Format mtime as `YYYY-MM-DD HH:MM` UTC, mirroring real `unzip -l`. */
    private fun formatDateTime(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "                "
        // Manual UTC breakdown — small helper to avoid pulling in a tz lib
        // for an audit display field.
        return formatEpochUtc(epochSeconds)
    }
}

/**
 * Format [epochSeconds] as `YYYY-MM-DD HH:MM` in UTC. Pure-Kotlin —
 * common-source-set safe.
 */
internal fun formatEpochUtc(epochSeconds: Long): String {
    val daysSinceEpoch = epochSeconds.floorDiv(86_400L)
    val secOfDay = epochSeconds - daysSinceEpoch * 86_400L
    val hh = (secOfDay / 3600).toInt()
    val mm = ((secOfDay % 3600) / 60).toInt()

    // Convert days since 1970-01-01 to civil date (Howard Hinnant's algorithm).
    val z = daysSinceEpoch + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val doe = (z - era * 146_097L) // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365 // [0, 399]
    val y = yoe + era * 400L
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
    val mp = (5 * doy + 2) / 153 // [0, 11]
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt() // [1, 31]
    val mRaw = (mp + if (mp < 10) 3 else -9).toInt() // [1, 12]
    val year = (y + if (mRaw <= 2) 1 else 0).toInt()
    return "${year.toString().padStart(
        4,
        '0',
    )}-${mRaw.toString().padStart(
        2,
        '0',
    )}-${d.toString().padStart(2, '0')} ${hh.toString().padStart(2, '0')}:${mm.toString().padStart(2, '0')}"
}
