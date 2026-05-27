package com.accucodeai.kash.tools.tar

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileType

/**
 * `tar` — create, extract, and list ustar archives.
 *
 * Modes (one required): `-c` create, `-x` extract, `-t` list. Common flags:
 *  `-f FILE` archive path (`-` for stdin/stdout).
 *  `-v` verbose.
 *  `-z` gzip filter (`-j`/`-J` reserved, return diagnostic).
 *  `-C DIR` chdir before reading/writing.
 *  `--exclude PAT` glob exclusion.
 *  `--strip-components N` peel N leading path segments on extract.
 *  `-p` preserve permissions on extract (best-effort).
 *
 * Argument parsing is single-pass and accepts short-bundled forms (`-czvf`),
 * GNU long forms (`--create`, `--file=`, `--exclude=PAT`, `--strip-components=N`),
 * and the legacy "no-dash first argument" form (`tar cf out.tar dir`).
 *
 * Streaming end-to-end: create walks the FS yielding 8 KiB chunks per file;
 * extract emits files to disk as headers arrive; list never buffers content.
 */
public class TarCommand :
    Command,
    CommandSpec {
    override val name: String = "tar"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.FS_WRITE)
    override val command: Command get() = this

    private enum class Mode { CREATE, EXTRACT, LIST }

    private data class Opts(
        var mode: Mode? = null,
        var file: String? = null,
        var verbose: Boolean = false,
        var gzip: Boolean = false,
        var bzip2: Boolean = false,
        var xz: Boolean = false,
        var chdir: String? = null,
        var stripComponents: Int = 0,
        var preservePerms: Boolean = false,
        var excludes: MutableList<String> = mutableListOf(),
        var paths: MutableList<String> = mutableListOf(),
    )

    private class CompressionCodec(
        val name: String,
        val flag: String,
        val decompress: (com.accucodeai.kash.api.io.SuspendSource) -> com.accucodeai.kash.api.io.SuspendSource?,
        val compress: (com.accucodeai.kash.api.io.SuspendSink) -> com.accucodeai.kash.api.io.SuspendSink?,
    )

    private fun compressionFlag(opts: Opts): CompressionCodec? =
        when {
            opts.gzip -> CompressionCodec("gzip", "-z", GzipFilter::decompress, GzipFilter::compress)
            opts.bzip2 -> CompressionCodec("bzip2", "-j", Bzip2Filter::decompress, Bzip2Filter::compress)
            opts.xz -> CompressionCodec("xz", "-J", XzFilter::decompress, XzFilter::compress)
            else -> null
        }

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = Opts()
        val parseResult = parseArgs(args, opts, ctx)
        if (parseResult != 0) return CommandResult(exitCode = parseResult)
        val mode = opts.mode
        if (mode == null) {
            ctx.stderr.writeUtf8("tar: You must specify one of the '-cxt' options\n")
            return CommandResult(exitCode = 2)
        }
        if (listOf(opts.gzip, opts.bzip2, opts.xz).count { it } > 1) {
            ctx.stderr.writeUtf8("tar: -z, -j, and -J are mutually exclusive\n")
            return CommandResult(exitCode = 2)
        }
        return when (mode) {
            Mode.CREATE -> doCreate(opts, ctx)
            Mode.EXTRACT -> doExtract(opts, ctx)
            Mode.LIST -> doList(opts, ctx)
        }
    }

    // ------------------------------------------------------------------
    // Argument parsing
    // ------------------------------------------------------------------

    private suspend fun parseArgs(
        args: List<String>,
        opts: Opts,
        ctx: CommandContext,
    ): Int {
        var i = 0
        var endOfOpts = false
        // Legacy form: first arg has no leading dash and is a cluster of mode/flag letters.
        if (args.isNotEmpty() && !args[0].startsWith("-") && args[0].isNotEmpty() &&
            args[0].all { it in "cxtvfzjJpC" }
        ) {
            val cluster = args[0]
            val rc = handleShortCluster(cluster, args, startIndex = 1, opts = opts, ctx = ctx)
            if (rc.first != 0) return rc.first
            i = rc.second
        }
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                opts.paths += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                    i++
                }

                a == "--create" -> {
                    setMode(opts, Mode.CREATE)
                    i++
                }

                a == "--extract" || a == "--get" -> {
                    setMode(opts, Mode.EXTRACT)
                    i++
                }

                a == "--list" -> {
                    setMode(opts, Mode.LIST)
                    i++
                }

                a == "--verbose" -> {
                    opts.verbose = true
                    i++
                }

                a == "--gzip" || a == "--gunzip" || a == "--ungzip" -> {
                    opts.gzip = true
                    i++
                }

                a == "--bzip2" -> {
                    opts.bzip2 = true
                    i++
                }

                a == "--xz" -> {
                    opts.xz = true
                    i++
                }

                a == "--preserve-permissions" || a == "--same-permissions" -> {
                    opts.preservePerms = true
                    i++
                }

                a == "--file" -> {
                    if (i + 1 >=
                        args.size
                    ) {
                        ctx.stderr.writeUtf8("tar: option '--file' requires an argument\n")
                        return 2
                    }
                    opts.file = args[i + 1]
                    i += 2
                }

                a.startsWith("--file=") -> {
                    opts.file = a.substring("--file=".length)
                    i++
                }

                a == "--directory" -> {
                    if (i + 1 >=
                        args.size
                    ) {
                        ctx.stderr.writeUtf8("tar: option '--directory' requires an argument\n")
                        return 2
                    }
                    opts.chdir = args[i + 1]
                    i += 2
                }

                a.startsWith("--directory=") -> {
                    opts.chdir = a.substring("--directory=".length)
                    i++
                }

                a == "--exclude" -> {
                    if (i + 1 >=
                        args.size
                    ) {
                        ctx.stderr.writeUtf8("tar: option '--exclude' requires an argument\n")
                        return 2
                    }
                    opts.excludes += args[i + 1]
                    i += 2
                }

                a.startsWith("--exclude=") -> {
                    opts.excludes += a.substring("--exclude=".length)
                    i++
                }

                a == "--strip-components" -> {
                    if (i + 1 >=
                        args.size
                    ) {
                        ctx.stderr.writeUtf8("tar: option '--strip-components' requires an argument\n")
                        return 2
                    }
                    opts.stripComponents = args[i + 1].toIntOrNull() ?: run {
                        ctx.stderr.writeUtf8("tar: invalid --strip-components: '${args[i + 1]}'\n")
                        return 2
                    }
                    i += 2
                }

                a.startsWith("--strip-components=") -> {
                    val v =
                        a.substring("--strip-components=".length).toIntOrNull() ?: run {
                            ctx.stderr.writeUtf8(
                                "tar: invalid --strip-components: '${a.substring("--strip-components=".length)}'\n",
                            )
                            return 2
                        }
                    opts.stripComponents = v
                    i++
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("tar: unknown option: $a\n")
                    return 2
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Short cluster, possibly with attached operand (e.g. `-fFOO`, `-C/tmp`).
                    val rc = handleShortCluster(a.substring(1), args, startIndex = i + 1, opts = opts, ctx = ctx)
                    if (rc.first != 0) return rc.first
                    i = rc.second
                }

                else -> {
                    opts.paths += a
                    i++
                }
            }
        }
        return 0
    }

    /**
     * Parse a bundled short-option cluster (no leading dash). Returns (exit, nextArgIndex).
     * Options that take an operand consume from [args] starting at [startIndex];
     * unconsumed args resume from the returned index.
     */
    private suspend fun handleShortCluster(
        cluster: String,
        args: List<String>,
        startIndex: Int,
        opts: Opts,
        ctx: CommandContext,
    ): Pair<Int, Int> {
        var consumeFrom = startIndex
        var k = 0
        while (k < cluster.length) {
            when (val ch = cluster[k]) {
                'c' -> {
                    setMode(opts, Mode.CREATE)
                }

                'x' -> {
                    setMode(opts, Mode.EXTRACT)
                }

                't' -> {
                    setMode(opts, Mode.LIST)
                }

                'v' -> {
                    opts.verbose = true
                }

                'z' -> {
                    opts.gzip = true
                }

                'j' -> {
                    opts.bzip2 = true
                }

                'J' -> {
                    opts.xz = true
                }

                'p' -> {
                    opts.preservePerms = true
                }

                'f' -> {
                    // Remainder of cluster, or next arg.
                    val rest = cluster.substring(k + 1)
                    if (rest.isNotEmpty()) {
                        opts.file = rest
                    } else {
                        if (consumeFrom >= args.size) {
                            ctx.stderr.writeUtf8("tar: option requires an argument -- 'f'\n")
                            return 2 to consumeFrom
                        }
                        opts.file = args[consumeFrom++]
                    }
                    return 0 to consumeFrom
                }

                'C' -> {
                    val rest = cluster.substring(k + 1)
                    if (rest.isNotEmpty()) {
                        opts.chdir = rest
                    } else {
                        if (consumeFrom >= args.size) {
                            ctx.stderr.writeUtf8("tar: option requires an argument -- 'C'\n")
                            return 2 to consumeFrom
                        }
                        opts.chdir = args[consumeFrom++]
                    }
                    return 0 to consumeFrom
                }

                else -> {
                    ctx.stderr.writeUtf8("tar: unknown option: -$ch\n")
                    return 2 to consumeFrom
                }
            }
            k++
        }
        return 0 to consumeFrom
    }

    private fun setMode(
        opts: Opts,
        m: Mode,
    ) {
        opts.mode = m
    }

    // ------------------------------------------------------------------
    // CREATE
    // ------------------------------------------------------------------

    private suspend fun doCreate(
        opts: Opts,
        ctx: CommandContext,
    ): CommandResult {
        if (opts.paths.isEmpty()) {
            ctx.stderr.writeUtf8("tar: Cowardly refusing to create an empty archive\n")
            return CommandResult(exitCode = 2)
        }
        val baseDir = opts.chdir ?: ctx.cwd
        val rawSink: SuspendSink =
            when (val f = opts.file) {
                null, "-" -> {
                    ctx.stdout
                }

                else -> {
                    try {
                        ctx.fs.sink(resolvePath(ctx.cwd, f), append = false)
                    } catch (e: Throwable) {
                        ctx.stderr.writeUtf8("tar: $f: Cannot open: ${e.message ?: "error"}\n")
                        return CommandResult(exitCode = 2)
                    }
                }
            }
        val (sink, finishable) =
            when (val codec = compressionFlag(opts)) {
                null -> {
                    rawSink to null
                }

                else -> {
                    val wrapped = codec.compress(rawSink)
                    if (wrapped == null) {
                        ctx.stderr.writeUtf8("tar: ${codec.name} (${codec.flag}) is not yet supported in this build\n")
                        return CommandResult(exitCode = 2)
                    }
                    wrapped to (wrapped as? FinishableSink)
                }
            }
        val writer = TarWriter(sink)
        var exit = 0
        try {
            for (p in opts.paths) {
                val abs = resolvePath(baseDir, p)
                val rc = createWalk(abs, p, opts, writer, ctx, baseDir)
                if (rc != 0) exit = rc
            }
            writer.finish()
            finishable?.finishCompression()
        } finally {
            if (opts.file != null && opts.file != "-") {
                sink.close()
            }
        }
        return CommandResult(exitCode = exit)
    }

    private suspend fun createWalk(
        abs: String,
        archiveName: String,
        opts: Opts,
        writer: TarWriter,
        ctx: CommandContext,
        baseDir: String,
    ): Int {
        if (matchesExclude(archiveName, opts.excludes)) return 0
        if (!ctx.fs.exists(abs)) {
            ctx.stderr.writeUtf8("tar: $archiveName: Cannot stat: No such file or directory\n")
            return 2
        }
        val stat =
            try {
                ctx.fs.statLink(abs)
            } catch (_: Throwable) {
                null
            }
        when {
            stat?.type == FileType.SYMLINK -> {
                val target =
                    try {
                        ctx.fs.readSymlink(abs)
                    } catch (_: Throwable) {
                        ""
                    }
                if (opts.verbose) ctx.stderr.writeUtf8("$archiveName\n")
                writer.writeSymlink(archiveName, target, mode = stat.mode and 0xfff, mtime = stat.mtimeEpochSeconds)
            }

            ctx.fs.isDirectory(abs) -> {
                if (opts.verbose) ctx.stderr.writeUtf8("$archiveName/\n")
                writer.writeDirectory(
                    archiveName,
                    mode = (stat?.mode ?: 0b111_101_101) and 0xfff,
                    mtime = stat?.mtimeEpochSeconds ?: 0L,
                )
                val entries =
                    try {
                        ctx.fs.list(abs).sorted()
                    } catch (_: Throwable) {
                        emptyList()
                    }
                for (entry in entries) {
                    val childAbs = if (abs.endsWith("/")) "$abs$entry" else "$abs/$entry"
                    val childName = if (archiveName.endsWith("/")) "$archiveName$entry" else "$archiveName/$entry"
                    val rc = createWalk(childAbs, childName, opts, writer, ctx, baseDir)
                    if (rc != 0) return rc
                }
            }

            else -> {
                if (opts.verbose) ctx.stderr.writeUtf8("$archiveName\n")
                val source =
                    try {
                        ctx.fs.source(abs)
                    } catch (e: Throwable) {
                        ctx.stderr.writeUtf8("tar: $archiveName: Cannot open: ${e.message ?: "error"}\n")
                        return 2
                    }
                try {
                    val size = stat?.size ?: 0L
                    writer.writeRegularFile(
                        archiveName,
                        size = size,
                        content = source,
                        mode = (stat?.mode ?: 0b110_100_100) and 0xfff,
                        mtime = stat?.mtimeEpochSeconds ?: 0L,
                    )
                } finally {
                    source.close()
                }
            }
        }
        return 0
    }

    // ------------------------------------------------------------------
    // EXTRACT
    // ------------------------------------------------------------------

    private suspend fun doExtract(
        opts: Opts,
        ctx: CommandContext,
    ): CommandResult {
        val rawSource: SuspendSource =
            when (val f = opts.file) {
                null, "-" -> {
                    ctx.stdin
                }

                else -> {
                    try {
                        ctx.fs.source(resolvePath(ctx.cwd, f))
                    } catch (_: FileNotFound) {
                        ctx.stderr.writeUtf8("tar: $f: Cannot open: No such file or directory\n")
                        return CommandResult(exitCode = 2)
                    }
                }
            }
        val source =
            when (val codec = compressionFlag(opts)) {
                null -> {
                    rawSource
                }

                else -> {
                    codec.decompress(rawSource) ?: run {
                        ctx.stderr.writeUtf8("tar: ${codec.name} (${codec.flag}) is not yet supported in this build\n")
                        return CommandResult(exitCode = 2)
                    }
                }
            }
        val reader = TarReader(source)
        val baseDir = opts.chdir ?: ctx.cwd
        val pathFilter = opts.paths.toSet()
        try {
            while (true) {
                val h = reader.nextEntry() ?: break
                val name = stripComponents(h.path, opts.stripComponents) ?: continue
                if (pathFilter.isNotEmpty() && !pathFilter.any { p -> name == p || name.startsWith("$p/") }) {
                    continue
                }
                if (matchesExclude(name, opts.excludes)) continue
                val dest = resolvePath(baseDir, name)
                when {
                    h.isDirectory -> {
                        ctx.fs.mkdirs(dest, mode = if (opts.preservePerms) h.mode and 0xfff else 0b111_101_101)
                        if (opts.verbose) ctx.stderr.writeUtf8("$name/\n")
                    }

                    h.isSymlink -> {
                        // Ensure parent.
                        parentOf(dest)?.let { if (!ctx.fs.exists(it)) ctx.fs.mkdirs(it) }
                        try {
                            ctx.fs.createSymlink(dest, h.linkname)
                        } catch (e: UnsupportedOperationException) {
                            ctx.stderr.writeUtf8("tar: $name: cannot create symlink: not supported\n")
                        }
                        if (opts.verbose) ctx.stderr.writeUtf8("$name\n")
                    }

                    h.isRegularFile -> {
                        parentOf(dest)?.let { if (!ctx.fs.exists(it)) ctx.fs.mkdirs(it) }
                        val createMode = if (opts.preservePerms) h.mode and 0xfff else 0b110_100_100
                        val sink = ctx.fs.sink(dest, append = false, mode = createMode)
                        try {
                            val buf = kotlinx.io.Buffer()
                            while (true) {
                                val n = reader.readEntryBytes(buf, 8 * 1024L)
                                if (n == -1L) break
                                sink.write(buf, buf.size)
                            }
                            sink.flush()
                        } finally {
                            sink.close()
                        }
                        if (opts.preservePerms) {
                            try {
                                ctx.fs.chmod(dest, h.mode and 0xfff)
                            } catch (_: Throwable) {
                            }
                        }
                        if (h.mtime > 0) {
                            try {
                                ctx.fs.setMtime(dest, h.mtime)
                            } catch (_: Throwable) {
                            }
                        }
                        if (opts.verbose) ctx.stderr.writeUtf8("$name\n")
                    }

                    else -> {
                        // Skip hardlinks / devices / fifos — log if verbose.
                        if (opts.verbose) ctx.stderr.writeUtf8("tar: $name: unsupported entry type, skipping\n")
                    }
                }
            }
        } finally {
            source.close()
        }
        return CommandResult()
    }

    // ------------------------------------------------------------------
    // LIST
    // ------------------------------------------------------------------

    private suspend fun doList(
        opts: Opts,
        ctx: CommandContext,
    ): CommandResult {
        val rawSource: SuspendSource =
            when (val f = opts.file) {
                null, "-" -> {
                    ctx.stdin
                }

                else -> {
                    try {
                        ctx.fs.source(resolvePath(ctx.cwd, f))
                    } catch (_: FileNotFound) {
                        ctx.stderr.writeUtf8("tar: $f: Cannot open: No such file or directory\n")
                        return CommandResult(exitCode = 2)
                    }
                }
            }
        val source =
            when (val codec = compressionFlag(opts)) {
                null -> {
                    rawSource
                }

                else -> {
                    codec.decompress(rawSource) ?: run {
                        ctx.stderr.writeUtf8("tar: ${codec.name} (${codec.flag}) is not yet supported in this build\n")
                        return CommandResult(exitCode = 2)
                    }
                }
            }
        val reader = TarReader(source)
        try {
            while (true) {
                val h = reader.nextEntry() ?: break
                val display = if (h.isDirectory) "${h.path.trimEnd('/')}/" else h.path
                if (opts.verbose) {
                    // Minimal `tar -tv` line: mode size name. Mtime/uid:gid skipped to stay portable.
                    ctx.stdout.writeUtf8("${formatModeShort(h.mode, h.typeflag)} ${h.size} $display\n")
                } else {
                    ctx.stdout.writeUtf8("$display\n")
                }
            }
        } finally {
            source.close()
        }
        return CommandResult()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun resolvePath(
        cwd: String,
        path: String,
    ): String {
        if (path.startsWith("/")) return path
        val base = if (cwd.endsWith("/")) cwd else "$cwd/"
        return "$base$path"
    }

    private fun parentOf(path: String): String? {
        val idx = path.lastIndexOf('/')
        if (idx <= 0) return null
        return path.substring(0, idx)
    }

    /** Drop the first [n] path segments; return null if [n] consumes the whole name. */
    private fun stripComponents(
        path: String,
        n: Int,
    ): String? {
        if (n <= 0) return path
        val parts = path.trim('/').split('/')
        if (parts.size <= n) return null
        return parts.drop(n).joinToString("/")
    }

    private fun matchesExclude(
        name: String,
        patterns: List<String>,
    ): Boolean {
        if (patterns.isEmpty()) return false
        for (p in patterns) {
            if (globMatch(p, name)) return true
            // GNU tar's --exclude also matches any path component.
            for (segment in name.split('/')) {
                if (globMatch(p, segment)) return true
            }
        }
        return false
    }

    /** Tiny glob matcher: `*`, `?`, character classes `[...]`. */
    private fun globMatch(
        pat: String,
        text: String,
    ): Boolean = matchGlob(pat, 0, text, 0)

    private fun matchGlob(
        pat: String,
        pi0: Int,
        text: String,
        ti0: Int,
    ): Boolean {
        var pi = pi0
        var ti = ti0
        while (pi < pat.length) {
            when (val pc = pat[pi]) {
                '*' -> {
                    // Collapse runs of '*'.
                    while (pi < pat.length && pat[pi] == '*') pi++
                    if (pi == pat.length) return true
                    for (k in ti..text.length) {
                        if (matchGlob(pat, pi, text, k)) return true
                    }
                    return false
                }

                '?' -> {
                    if (ti >= text.length) return false
                    pi++
                    ti++
                }

                '[' -> {
                    if (ti >= text.length) return false
                    val close = pat.indexOf(']', pi + 1)
                    if (close < 0) {
                        // Literal '['
                        if (text[ti] != '[') return false
                        pi++
                        ti++
                    } else {
                        var negate = false
                        var k = pi + 1
                        if (k < close && (pat[k] == '!' || pat[k] == '^')) {
                            negate = true
                            k++
                        }
                        var matched = false
                        while (k < close) {
                            if (k + 2 < close && pat[k + 1] == '-') {
                                if (text[ti] in pat[k]..pat[k + 2]) matched = true
                                k += 3
                            } else {
                                if (text[ti] == pat[k]) matched = true
                                k++
                            }
                        }
                        if (matched == negate) return false
                        pi = close + 1
                        ti++
                    }
                }

                else -> {
                    if (ti >= text.length || text[ti] != pc) return false
                    pi++
                    ti++
                }
            }
        }
        return ti == text.length
    }

    private fun formatModeShort(
        mode: Int,
        typeflag: Byte,
    ): String {
        val t =
            when (typeflag) {
                TarFormat.TF_DIR -> 'd'
                TarFormat.TF_SYMLINK -> 'l'
                TarFormat.TF_FIFO -> 'p'
                TarFormat.TF_CHAR -> 'c'
                TarFormat.TF_BLOCK -> 'b'
                else -> '-'
            }

        fun rwx(bits: Int): String =
            buildString {
                append(if (bits and 4 != 0) 'r' else '-')
                append(if (bits and 2 != 0) 'w' else '-')
                append(if (bits and 1 != 0) 'x' else '-')
            }
        return "$t${rwx((mode shr 6) and 7)}${rwx((mode shr 3) and 7)}${rwx(mode and 7)}"
    }
}
