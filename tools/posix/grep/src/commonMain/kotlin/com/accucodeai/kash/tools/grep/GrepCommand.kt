package com.accucodeai.kash.tools.grep

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.ansi.AnsiStyler
import com.accucodeai.kash.api.ansi.ColorMode
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.matchGlob
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.shared.regex.LinearRegex

/**
 * POSIX `grep` (also `egrep`, `fgrep`) as a kash [Command]. Three [Single]
 * specs register the three names, each delegating to a shared engine with a
 * different default regex dialect.
 *
 * Supported flags:
 *
 *  - `-E` ERE; `-F` fixed strings (literal); `-G` BRE (default)
 *  - `-i` case-insensitive
 *  - `-v` invert match
 *  - `-n` prefix each matched line with its 1-based line number
 *  - `-l` print only filenames with at least one match
 *  - `-c` print only a count per file
 *  - `-q` quiet — no output; exit status only
 *  - `-h` suppress filename prefix
 *  - `-H` force filename prefix
 *  - `-e PATTERN` add a pattern (repeatable)
 *  - `-f FILE` read patterns from FILE (one per line, repeatable)
 *  - `-r` / `-R` recurse directories (via `ctx.process.fs`)
 *  - `-s` suppress error messages about missing/unreadable files
 *  - `-x` match only entire lines
 *  - `-w` match must sit on word boundaries
 *  - `-o` print only matching substrings, one per line
 *  - `-L` print files with NO match (inverse of `-l`)
 *  - `-m N` stop after N matches per source (`-A` still drains)
 *  - `-A N` / `-B N` / `-C N` print N lines of after/before/both context
 *  - `-P` accepted for compatibility; RE2 backend (no lookaround/backrefs)
 *  - `-a` / `--text` treat binary files as text
 *  - `-I` skip binary files
 *  - `--binary-files=binary|text|without-match` binary-file disposition
 *  - `--include=GLOB` / `--exclude=GLOB` filter file basenames under `-r`
 *  - `--include-dir=GLOB` / `--exclude-dir=GLOB` filter dir basenames under `-r`
 *  - `--` end of options
 *
 * Exit codes per POSIX:
 *
 *  - 0 — at least one match found across all sources
 *  - 1 — no matches found, no errors
 *  - 2 — usage error or unreadable input
 *
 * Streaming: reads line-by-line from `ctx.stdin` or `ctx.process.fs.source(file)`,
 * writing matches to `ctx.stdout` as they're found. Closing the downstream
 * read end (broken pipe) terminates the scan promptly.
 */
internal class GrepCommandSpec :
    Command,
    CommandSpec {
    override val name: String = "grep"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult = runGrep(name, GrepMode.BRE, args, ctx)
}

internal class EgrepCommandSpec :
    Command,
    CommandSpec {
    override val name: String = "egrep"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult = runGrep(name, GrepMode.ERE, args, ctx)
}

internal class FgrepCommandSpec :
    Command,
    CommandSpec {
    override val name: String = "fgrep"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult = runGrep(name, GrepMode.FIXED, args, ctx)
}

/** Shared entrypoint: parse → resolve `-f` files → run. */
internal suspend fun runGrep(
    toolName: String,
    defaultMode: GrepMode,
    args: List<String>,
    ctx: CommandContext,
): CommandResult {
    val parsed =
        try {
            parseArgs(args, defaultMode, toolName)
        } catch (e: GrepUsageError) {
            ctx.stderr.writeUtf8("$toolName: ${e.message}\n")
            return CommandResult(exitCode = 2)
        }

    // Resolve pattern files from the FS.
    val expandedPatterns = mutableListOf<String>()
    for (p in parsed.opts.patterns) {
        if (p.startsWith(PATTERN_FILE_MARKER)) {
            val path = p.substring(PATTERN_FILE_MARKER.length)
            val abs = Paths.resolve(ctx.process.cwd, path)
            if (!ctx.process.fs.exists(abs)) {
                if (!parsed.suppressFileErrors) {
                    ctx.stderr.writeUtf8("$toolName: $path: No such file or directory\n")
                }
                return CommandResult(exitCode = 2)
            }
            val text =
                ctx.process.fs
                    .readBytes(abs)
                    .decodeToString()
            val lines =
                when {
                    text.isEmpty() -> emptyList()
                    text.endsWith("\n") -> text.substring(0, text.length - 1).split("\n")
                    else -> text.split("\n")
                }
            expandedPatterns.addAll(lines)
        } else {
            expandedPatterns.add(p)
        }
    }

    if (expandedPatterns.isEmpty()) {
        ctx.stderr.writeUtf8("$toolName: no pattern specified\n")
        return CommandResult(exitCode = 2)
    }

    val opts = parsed.opts.copy(patterns = expandedPatterns)

    val regex =
        try {
            compileRegex(opts)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // RE2/J is the only regex backend. PCRE-only constructs (lookaround,
            // backreferences) parse-fail here; give the user a hint instead of
            // a bare "invalid escape sequence: \1".
            val looksPcreOnly =
                "(?=" in msg || "(?!" in msg || "(?<" in msg ||
                    Regex("\\\\\\d").containsMatchIn(msg)
            val hint =
                if (looksPcreOnly) {
                    " (the RE2 regex backend doesn't support lookaround or backreferences;" +
                        " -P is accepted for compatibility but uses RE2 underneath)"
                } else {
                    ""
                }
            ctx.stderr.writeUtf8("$toolName: invalid pattern: $msg$hint\n")
            return CommandResult(exitCode = 2)
        }

    // Build the source list (expanding directories under -r).
    val sources: List<GrepSource> =
        if (parsed.files.isEmpty()) {
            listOf(GrepSource.Stdin)
        } else if (parsed.recursive) {
            val out = mutableListOf<GrepSource>()
            for (f in parsed.files) {
                val abs = Paths.resolve(ctx.process.cwd, f)
                if (!ctx.process.fs.exists(abs)) {
                    if (!parsed.suppressFileErrors) {
                        ctx.stderr.writeUtf8("$toolName: $f: No such file or directory\n")
                    }
                    return CommandResult(exitCode = 2)
                }
                walkRecursive(
                    ctx,
                    f,
                    abs,
                    out,
                    parsed.opts.include,
                    parsed.opts.exclude,
                    parsed.opts.includeDir,
                    parsed.opts.excludeDir,
                )
            }
            out
        } else {
            parsed.files.map { f ->
                if (f == "-") GrepSource.Stdin else GrepSource.File(f, Paths.resolve(ctx.process.cwd, f))
            }
        }

    val showFilename =
        when {
            opts.forceFilename -> {
                true
            }

            opts.suppressFilename -> {
                false
            }

            parsed.recursive -> {
                // Under -r, always prefix filename — matches GNU's behavior of
                // treating recursion as implicitly forcing -H.
                sources.any { it is GrepSource.File }
            }

            else -> {
                sources.count { it is GrepSource.File } > 1 ||
                    (sources.size > 1 && sources.any { it is GrepSource.File })
            }
        }

    val styler = Ansi.stylerFor(ctx, mode = opts.color)
    val palette = GrepColors.parse(ctx.process.env["GREP_COLORS"])

    var anyMatch = false
    var anyError = false

    for (src in sources) {
        if (src is GrepSource.File) {
            if (!ctx.process.fs.exists(src.absPath)) {
                if (!parsed.suppressFileErrors) {
                    ctx.stderr.writeUtf8("$toolName: ${src.displayPath}: No such file or directory\n")
                }
                anyError = true
                continue
            }
            if (ctx.process.fs.isDirectory(src.absPath) && !parsed.recursive) {
                if (!parsed.suppressFileErrors) {
                    ctx.stderr.writeUtf8("$toolName: ${src.displayPath}: Is a directory\n")
                }
                anyError = true
                continue
            }
        }

        val res =
            try {
                grepOneSource(src, regex, opts, showFilename, ctx, styler, palette)
            } catch (e: BrokenPipeMarker) {
                return CommandResult(exitCode = if (anyMatch) 0 else 1)
            }
        if (res.matched) anyMatch = true
        if (opts.quiet && anyMatch) {
            return CommandResult(exitCode = 0)
        }
    }

    val exit =
        when {
            anyError && !anyMatch -> 2
            anyMatch -> 0
            else -> 1
        }
    return CommandResult(exitCode = exit)
}

internal sealed class GrepSource {
    object Stdin : GrepSource()

    data class File(
        // Path as the user supplied it — used for the filename prefix in
        // output (`grep PATTERN foo.txt` → `foo.txt:line`, not the cwd-resolved
        // absolute path).
        val displayPath: String,
        // Absolute path used for every `ctx.process.fs.*` call.
        val absPath: String = displayPath,
    ) : GrepSource()
}

private class BrokenPipeMarker : RuntimeException()

private suspend fun walkRecursive(
    ctx: CommandContext,
    displayPath: String,
    resolvedPath: String,
    out: MutableList<GrepSource>,
    include: List<String>,
    exclude: List<String>,
    includeDir: List<String>,
    excludeDir: List<String>,
) {
    if (!ctx.process.fs.exists(resolvedPath)) return
    if (!ctx.process.fs.isDirectory(resolvedPath)) {
        val base = displayPath.substringAfterLast('/')
        if (exclude.any { matchGlob(it, base) }) return
        if (include.isNotEmpty() && include.none { matchGlob(it, base) }) return
        out.add(GrepSource.File(displayPath, resolvedPath))
        return
    }
    val dispBase = if (displayPath == "/" || displayPath.endsWith("/")) displayPath else "$displayPath/"
    val resBase = if (resolvedPath == "/") "/" else "$resolvedPath/"
    for (entry in ctx.process.fs.list(resolvedPath)) {
        val childRes = "$resBase$entry"
        // --exclude-dir / --include-dir apply to descended directory basenames
        // only; the explicitly-passed root is never filtered.
        if (ctx.process.fs.isDirectory(childRes)) {
            if (excludeDir.any { matchGlob(it, entry) }) continue
            if (includeDir.isNotEmpty() && includeDir.none { matchGlob(it, entry) }) continue
        }
        walkRecursive(ctx, "$dispBase$entry", childRes, out, include, exclude, includeDir, excludeDir)
    }
}

private suspend fun grepOneSource(
    src: GrepSource,
    regex: LinearRegex,
    opts: GrepOptions,
    showFilename: Boolean,
    ctx: CommandContext,
    styler: AnsiStyler,
    palette: GrepColors,
): GrepSourceResult {
    val label =
        when (src) {
            is GrepSource.File -> src.displayPath
            GrepSource.Stdin -> "(standard input)"
        }
    val lines: List<String> =
        run {
            val out = mutableListOf<String>()
            when (src) {
                GrepSource.Stdin -> {
                    while (true) {
                        val line = ctx.stdin.readUtf8LineOrNull() ?: break
                        out += line
                    }
                }

                is GrepSource.File -> {
                    val source = ctx.process.fs.source(src.absPath)
                    try {
                        while (true) {
                            val line = source.readUtf8LineOrNull() ?: break
                            out += line
                        }
                    } finally {
                        try {
                            source.close()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            out
        }

    // Binary detection: a file is "binary" if any line contains a NUL. With
    // --binary-files=without-match (`-I`), skip silently. With
    // =binary (the default), emit a single "Binary file LABEL matches" line
    // instead of the matches (unless -c/-l/-L/-q which have their own
    // output). =text (`-a`) treats it as text — fall through.
    val isBinary = src is GrepSource.File && lines.any { it.indexOf(0.toChar()) >= 0 }
    if (isBinary && opts.binaryMode == BinaryMode.WITHOUT_MATCH) {
        return GrepSourceResult(matched = false, count = 0)
    }
    val emitBinaryMarker =
        isBinary && opts.binaryMode == BinaryMode.BINARY &&
            !opts.countOnly && !opts.filesWithMatches && !opts.filesWithoutMatch &&
            !opts.quiet && !opts.onlyMatching

    // -L special case: scan the whole file, suppress per-line output, print
    // the filename only if no line matched.
    if (opts.filesWithoutMatch) {
        val anyMatch = lines.any { regex.containsMatch(it) xor opts.invert }
        if (!anyMatch) writeLine(ctx, styler.style(label, palette.fn))
        return GrepSourceResult(matched = !anyMatch, count = 0)
    }

    if (emitBinaryMarker) {
        val anyMatch = lines.any { regex.containsMatch(it) xor opts.invert }
        if (anyMatch) writeLine(ctx, "Binary file $label matches")
        return GrepSourceResult(matched = anyMatch, count = if (anyMatch) 1 else 0)
    }

    val before = if (opts.beforeContext > 0) ArrayDeque<Pair<Int, String>>() else null
    val hasContext = opts.beforeContext > 0 || opts.afterContext > 0
    var afterRemaining = 0
    var lastPrintedLine = -1
    var count = 0
    var sourceMatched = false
    val cap = opts.maxCount

    suspend fun emitSeparatorIfNeeded(nextLineNo: Int) {
        if (hasContext && lastPrintedLine >= 0 && nextLineNo - lastPrintedLine > 1) {
            writeLine(ctx, styler.style("--", palette.se))
        }
    }

    suspend fun printLine(
        line: String,
        n: Int,
        isMatch: Boolean,
    ) {
        if (opts.onlyMatching && isMatch) {
            // Each match on its own line. Skip non-match (context) entirely
            // — GNU `-o` doesn't print context.
            for (m in regex.findAll(line)) {
                if (m.length == 0) continue
                val sb = StringBuilder()
                if (showFilename) {
                    sb.append(styler.style(label, palette.fn))
                    sb.append(styler.style(":", palette.se))
                }
                if (opts.lineNumbers) {
                    sb.append(styler.style(n.toString(), palette.ln))
                    sb.append(styler.style(":", palette.se))
                }
                sb.append(styler.style(line.substring(m.offset, m.offset + m.length), palette.ms))
                writeLine(ctx, sb.toString())
            }
            return
        }
        if (opts.onlyMatching) return // context lines under -o → suppressed
        val sep = if (isMatch) ":" else "-"
        val sb = StringBuilder()
        if (showFilename) {
            sb.append(styler.style(label, palette.fn))
            sb.append(styler.style(sep, palette.se))
        }
        if (opts.lineNumbers) {
            sb.append(styler.style(n.toString(), palette.ln))
            sb.append(styler.style(sep, palette.se))
        }
        if (isMatch) {
            sb.append(highlightMatches(line, regex, styler, palette, opts.invert))
        } else {
            // Context line: GNU uses `mc` color for any embedded match within
            // context lines, but since by construction this line did NOT
            // match, we just emit it raw.
            sb.append(line)
        }
        writeLine(ctx, sb.toString())
    }

    for ((idx, line) in lines.withIndex()) {
        val lineNo = idx + 1
        val isMatch = regex.containsMatch(line) xor opts.invert

        if (isMatch) {
            sourceMatched = true
            // -l: short-circuit, print filename once.
            if (opts.filesWithMatches) {
                writeLine(ctx, styler.style(label, palette.fn))
                return GrepSourceResult(true, 1)
            }
            // -q: short-circuit, no output.
            if (opts.quiet) return GrepSourceResult(true, 1)

            if (cap != null && count >= cap) {
                // Past the match cap. Allow after-context to drain.
                if (afterRemaining > 0 && !opts.countOnly) {
                    emitSeparatorIfNeeded(lineNo)
                    printLine(line, lineNo, isMatch = false)
                    lastPrintedLine = lineNo
                    afterRemaining--
                }
                if (afterRemaining <= 0) break
                continue
            }

            count++
            if (!opts.countOnly) {
                // Drain before-context ahead of the match line.
                if (before != null && before.isNotEmpty()) {
                    val firstCtxLine = before.first().first
                    if (lastPrintedLine >= 0 && firstCtxLine - lastPrintedLine > 1) {
                        writeLine(ctx, styler.style("--", palette.se))
                    }
                    for ((bn, bl) in before) {
                        if (bn > lastPrintedLine) {
                            printLine(bl, bn, isMatch = false)
                            lastPrintedLine = bn
                        }
                    }
                    before.clear()
                } else {
                    emitSeparatorIfNeeded(lineNo)
                }
                printLine(line, lineNo, isMatch = true)
                lastPrintedLine = lineNo
            }
            afterRemaining = opts.afterContext
            // If we just hit the cap and no after-context to drain, stop.
            if (cap != null && count >= cap && afterRemaining == 0) break
        } else {
            // Non-match. Possibly draining after-context.
            if (afterRemaining > 0 && !opts.countOnly) {
                printLine(line, lineNo, isMatch = false)
                lastPrintedLine = lineNo
                afterRemaining--
                if (cap != null && count >= cap && afterRemaining == 0) break
            } else if (before != null) {
                if (before.size >= opts.beforeContext) before.removeFirst()
                before.addLast(lineNo to line)
            }
        }
    }

    if (opts.countOnly && !opts.quiet) {
        val sb = StringBuilder()
        if (showFilename) {
            sb.append(styler.style(label, palette.fn))
            sb.append(styler.style(":", palette.se))
        }
        sb.append(count)
        writeLine(ctx, sb.toString())
    }

    return GrepSourceResult(sourceMatched, count)
}

/**
 * Wrap each regex match in [line] with bold-red SGR, leaving non-match runs
 * bare. No-op (returns [line] verbatim) when [styler] is off, when [invert]
 * is set (whole line matched, but the "match" semantics are inverted — GNU
 * grep emits the line uncolored in this case), or when no matches are found.
 */
private fun highlightMatches(
    line: String,
    regex: LinearRegex,
    styler: AnsiStyler,
    palette: GrepColors,
    invert: Boolean,
): String {
    if (!styler.on || invert) return line
    val matches = regex.findAll(line).toList()
    if (matches.isEmpty()) return line
    val sb = StringBuilder(line.length + matches.size * 12)
    var cursor = 0
    for (m in matches) {
        if (m.offset < cursor) continue // overlapping match guard
        if (m.length == 0) continue
        sb.append(line, cursor, m.offset)
        sb.append(styler.style(line.substring(m.offset, m.offset + m.length), palette.ms))
        cursor = m.offset + m.length
    }
    if (cursor < line.length) sb.append(line, cursor, line.length)
    return sb.toString()
}

private suspend fun writeLine(
    ctx: CommandContext,
    s: String,
) {
    try {
        ctx.stdout.writeLine(s)
    } catch (e: kotlinx.io.IOException) {
        throw BrokenPipeMarker()
    }
}

// ---------- Argument parsing ----------

internal const val PATTERN_FILE_MARKER = " PFILE "

internal class GrepUsageError(
    message: String,
) : RuntimeException(message)

internal data class ParsedArgs(
    val opts: GrepOptions,
    val files: List<String>,
    val recursive: Boolean,
    val suppressFileErrors: Boolean,
)

/**
 * Parse [args] for grep. [defaultMode] determines the dialect when the user
 * passes neither `-E`, `-F`, nor `-G`. [toolName] is used for error text only.
 * No FS access — `-f FILE` is encoded as a marker pattern that the caller
 * expands after parsing.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun parseArgs(
    args: List<String>,
    defaultMode: GrepMode,
    @Suppress("UNUSED_PARAMETER") toolName: String,
): ParsedArgs {
    var mode: GrepMode = defaultMode
    val patterns = mutableListOf<String>()
    val patternFiles = mutableListOf<String>()
    val files = mutableListOf<String>()
    var ignoreCase = false
    var invert = false
    var lineNumbers = false
    var filesWithMatches = false
    var countOnly = false
    var quiet = false
    var suppressFilename = false
    var forceFilename = false
    var recursive = false
    var suppressFileErrors = false
    var wholeLine = false
    var wordRegexp = false
    var onlyMatching = false
    var filesWithoutMatch = false
    var maxCount: Int? = null
    var afterContext = 0
    var beforeContext = 0
    val include = mutableListOf<String>()
    val exclude = mutableListOf<String>()
    val includeDir = mutableListOf<String>()
    val excludeDir = mutableListOf<String>()
    var binaryMode = BinaryMode.BINARY
    var color = ColorMode.NEVER

    fun parseNum(
        opt: String,
        raw: String,
    ): Int {
        val n = raw.toIntOrNull() ?: throw GrepUsageError("$opt: invalid number: $raw")
        if (n < 0) throw GrepUsageError("$opt: number must be non-negative: $raw")
        return n
    }

    var i = 0
    var doubleDash = false
    while (i < args.size) {
        val a = args[i]
        if (doubleDash) {
            if (patterns.isEmpty() && patternFiles.isEmpty()) {
                patterns.add(a)
            } else {
                files.add(a)
            }
            i++
            continue
        }
        if (a == "-") {
            files.add(a)
            i++
            continue
        }
        if (a == "--") {
            doubleDash = true
            i++
            continue
        }

        if (a.startsWith("--")) {
            when (a) {
                "--extended-regexp" -> {
                    mode = GrepMode.ERE
                }

                "--basic-regexp" -> {
                    mode = GrepMode.BRE
                }

                "--fixed-strings" -> {
                    mode = GrepMode.FIXED
                }

                "--ignore-case" -> {
                    ignoreCase = true
                }

                "--invert-match" -> {
                    invert = true
                }

                "--line-number" -> {
                    lineNumbers = true
                }

                "--files-with-matches" -> {
                    filesWithMatches = true
                }

                "--count" -> {
                    countOnly = true
                }

                "--quiet", "--silent" -> {
                    quiet = true
                }

                "--no-filename" -> {
                    suppressFilename = true
                }

                "--with-filename" -> {
                    forceFilename = true
                }

                "--recursive", "--dereference-recursive" -> {
                    recursive = true
                }

                "--no-messages" -> {
                    suppressFileErrors = true
                }

                "--line-regexp" -> {
                    wholeLine = true
                }

                "--word-regexp" -> {
                    wordRegexp = true
                }

                "--only-matching" -> {
                    onlyMatching = true
                }

                "--files-without-match" -> {
                    filesWithoutMatch = true
                }

                "--text" -> {
                    binaryMode = BinaryMode.TEXT
                }

                "--perl-regexp" -> {
                    // Accepted for compatibility. The underlying engine is
                    // RE2/J: most PCRE patterns work, but lookaround and
                    // backreferences will be rejected at compile time.
                    mode = GrepMode.ERE
                }

                else -> {
                    when {
                        a.startsWith("--regexp=") -> {
                            patterns.add(a.substring("--regexp=".length))
                        }

                        a.startsWith("--file=") -> {
                            patternFiles.add(a.substring("--file=".length))
                        }

                        a.startsWith("--max-count=") -> {
                            maxCount = parseNum("--max-count", a.substringAfter('='))
                        }

                        a.startsWith("--after-context=") -> {
                            afterContext = parseNum("--after-context", a.substringAfter('='))
                        }

                        a.startsWith("--before-context=") -> {
                            beforeContext = parseNum("--before-context", a.substringAfter('='))
                        }

                        a.startsWith("--context=") -> {
                            val n = parseNum("--context", a.substringAfter('='))
                            afterContext = n
                            beforeContext = n
                        }

                        a.startsWith("--include=") -> {
                            include.add(a.substring("--include=".length))
                        }

                        a.startsWith("--exclude=") -> {
                            exclude.add(a.substring("--exclude=".length))
                        }

                        a.startsWith("--include-dir=") -> {
                            includeDir.add(a.substring("--include-dir=".length))
                        }

                        a.startsWith("--exclude-dir=") -> {
                            excludeDir.add(a.substring("--exclude-dir=".length))
                        }

                        a.startsWith("--binary-files=") -> {
                            binaryMode =
                                when (val v = a.substringAfter('=')) {
                                    "binary" -> BinaryMode.BINARY
                                    "text" -> BinaryMode.TEXT
                                    "without-match" -> BinaryMode.WITHOUT_MATCH
                                    else -> throw GrepUsageError("invalid --binary-files value: $v")
                                }
                        }

                        a == "--color" || a == "--colour" -> {
                            color = ColorMode.AUTO
                        }

                        a.startsWith("--color=") || a.startsWith("--colour=") -> {
                            color = ColorMode.parse(a.substringAfter('='))
                                ?: throw GrepUsageError("invalid --color value: ${a.substringAfter('=')}")
                        }

                        else -> {
                            throw GrepUsageError("unrecognized option: $a")
                        }
                    }
                }
            }
            i++
            continue
        }

        if (a.startsWith("-") && a.length > 1) {
            var j = 1
            while (j < a.length) {
                val c = a[j]
                when (c) {
                    'E' -> {
                        mode = GrepMode.ERE
                    }

                    'F' -> {
                        mode = GrepMode.FIXED
                    }

                    'G' -> {
                        mode = GrepMode.BRE
                    }

                    'i', 'y' -> {
                        ignoreCase = true
                    }

                    'v' -> {
                        invert = true
                    }

                    'n' -> {
                        lineNumbers = true
                    }

                    'l' -> {
                        filesWithMatches = true
                    }

                    'c' -> {
                        countOnly = true
                    }

                    'q' -> {
                        quiet = true
                    }

                    'h' -> {
                        suppressFilename = true
                    }

                    'H' -> {
                        forceFilename = true
                    }

                    'r', 'R' -> {
                        recursive = true
                    }

                    's' -> {
                        suppressFileErrors = true
                    }

                    'x' -> {
                        wholeLine = true
                    }

                    'w' -> {
                        wordRegexp = true
                    }

                    'o' -> {
                        onlyMatching = true
                    }

                    'L' -> {
                        filesWithoutMatch = true
                    }

                    'a' -> {
                        binaryMode = BinaryMode.TEXT
                    }

                    'I' -> {
                        binaryMode = BinaryMode.WITHOUT_MATCH
                    }

                    'P' -> {
                        // Accepted for compatibility. Engine is RE2 — patterns
                        // requiring lookaround/backrefs will fail at compile.
                        mode = GrepMode.ERE
                    }

                    'A', 'B', 'C', 'm' -> {
                        val rest = a.substring(j + 1)
                        val raw =
                            if (rest.isNotEmpty()) {
                                rest
                            } else {
                                if (i + 1 >= args.size) {
                                    throw GrepUsageError("option requires an argument: -$c")
                                }
                                i++
                                args[i]
                            }
                        val n = parseNum("-$c", raw)
                        when (c) {
                            'A' -> {
                                afterContext = n
                            }

                            'B' -> {
                                beforeContext = n
                            }

                            'C' -> {
                                afterContext = n
                                beforeContext = n
                            }

                            'm' -> {
                                maxCount = n
                            }
                        }
                        j = a.length
                    }

                    'e' -> {
                        val rest = a.substring(j + 1)
                        if (rest.isNotEmpty()) {
                            patterns.add(rest)
                        } else {
                            if (i + 1 >= args.size) {
                                throw GrepUsageError("option requires an argument: -e")
                            }
                            patterns.add(args[i + 1])
                            i++
                        }
                        j = a.length
                    }

                    'f' -> {
                        val rest = a.substring(j + 1)
                        if (rest.isNotEmpty()) {
                            patternFiles.add(rest)
                        } else {
                            if (i + 1 >= args.size) {
                                throw GrepUsageError("option requires an argument: -f")
                            }
                            patternFiles.add(args[i + 1])
                            i++
                        }
                        j = a.length
                    }

                    else -> {
                        throw GrepUsageError("unknown option: -$c")
                    }
                }
                j++
            }
            i++
            continue
        }

        if (patterns.isEmpty() && patternFiles.isEmpty()) {
            patterns.add(a)
        } else {
            files.add(a)
        }
        i++
    }

    return ParsedArgs(
        opts =
            GrepOptions(
                mode = mode,
                patterns = patterns + patternFiles.map { PATTERN_FILE_MARKER + it },
                files = files,
                ignoreCase = ignoreCase,
                invert = invert,
                lineNumbers = lineNumbers,
                filesWithMatches = filesWithMatches,
                countOnly = countOnly,
                quiet = quiet,
                suppressFilename = suppressFilename,
                forceFilename = forceFilename,
                recursive = recursive,
                wholeLine = wholeLine,
                wordRegexp = wordRegexp,
                onlyMatching = onlyMatching,
                filesWithoutMatch = filesWithoutMatch,
                maxCount = maxCount,
                afterContext = afterContext,
                beforeContext = beforeContext,
                include = include,
                exclude = exclude,
                includeDir = includeDir,
                excludeDir = excludeDir,
                binaryMode = binaryMode,
                color = color,
            ),
        files = files,
        recursive = recursive,
        suppressFileErrors = suppressFileErrors,
    )
}
